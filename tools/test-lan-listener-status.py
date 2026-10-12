#!/usr/bin/env python3
"""在主机上真实执行 LAN 监听器的状态发布与绑定路径（无需 Android/Gradle/设备）。

用最小 Android 替身（`tools/fixtures/lan-listener-status/`，经 `-sourcepath` 生效、
不进任何 Gradle source set）编译并运行**真实生产** `LanProxyService` 与其状态发布核心
`util/LanListenerStatus.java`：

  * 本轮认证已就绪、绑定未返回 → WAITING，绝不是 BOUND；
  * 3081 等端口真实被占用 → FAILED + 脱敏分类（PORT_IN_USE），而不是「正在等待本轮认证」；
  * 真实绑定成功 → BOUND，停止后 → IDLE；
  * 旧轮次（epoch 不同）的迟到失败被拒绝；按 dsh generation 停止能清掉绑定失败
    （即使 acceptLoop 已退出），别的代次停止不误伤；
  * 无上下文的 start fail-closed；固定端口仍是 3081，没有端口扫描或回退。

探针通过包内注入接缝换到临时端口，生产默认值仍是常量 3081，因此本脚本不占真实 3081、
不操作设备、不写工作树（只在临时目录编译）。真实 API30+ 设备行为仍未验收。
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tools/fixtures/lan-listener-status"
MAIN = ROOT / "app/src/main/java"
PRODUCTION = MAIN / "com/deepseekharness/app/util/LanListenerStatus.java"
SERVICE = MAIN / "com/deepseekharness/app/LanProxyService.java"
PROBE = FIXTURE / "com/deepseekharness/app/LanProxyServiceHostProbe.java"

# 绝不允许出现在探针输出里的内容（状态与日志都不得泄漏这些）。
FORBIDDEN = ("?token", "token=", "Address already in use", "dsha_lan=")


def main():
    if sys.flags.optimize:
        raise SystemExit("TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON")
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK17_REQUIRED")
    for required, label in ((PRODUCTION, "LAN_LISTENER_STATUS_SOURCE_MISSING"),
                            (SERVICE, "LAN_PROXY_SERVICE_SOURCE_MISSING"),
                            (PROBE, "LAN_LISTENER_STATUS_PROBE_MISSING")):
        if not required.is_file():
            raise SystemExit(label + ": " + str(required))

    with tempfile.TemporaryDirectory(prefix="lan-listener-status-") as folder:
        classes = Path(folder) / "classes"
        classes.mkdir()
        subprocess.run(
            [javac, "-encoding", "UTF-8", "-source", "17", "-target", "17",
             "-proc:none", "-nowarn", "-g:source,lines",
             "-sourcepath", str(FIXTURE) + ":" + str(MAIN),
             "-d", str(classes),
             str(SERVICE), str(PROBE)],
            check=True, timeout=120)

        result = subprocess.run(
            [java, "-ea", "-cp", str(classes),
             "com.deepseekharness.app.LanProxyServiceHostProbe"],
            capture_output=True, text=True, timeout=120)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0:
            raise SystemExit("LAN_LISTENER_STATUS_FAILED: exit " + str(result.returncode))

        combined = result.stdout + result.stderr
        if "LAN_LISTENER_STATUS_PROBE_CHECKS" not in combined:
            raise SystemExit("LAN_LISTENER_STATUS_FAILED: missing check count")
        leaked = [needle for needle in FORBIDDEN if needle in combined]
        if leaked:
            raise SystemExit("LAN_LISTENER_STATUS_LEAK: " + repr(leaked))

    print("LAN_LISTENER_STATUS_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
