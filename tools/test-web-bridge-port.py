#!/usr/bin/env python3
"""真实执行生产 WebPageScripts 生成的桥端口接线主机回归（JDK + Android 桩，不跑 Gradle、不碰设备）。

覆盖本轮双实例隔离的浏览器侧：
  * 正式本机可信页面在 compat shim 执行之前注入桥的**实际**端口快照，并显式声明
    __DSHA_BRIDGE_PORT_REQUIRED__=true（端口只能由 provider 每次现取）；
  * 桥未绑定 / 已停止时 activePort()==0，静态快照按 0 显式注入，shim 见 0 即拒绝注入 token；
  * 每次重新生成都重新读取 activePort()，桥换端口后重新生成的脚本立刻反映新值；
  * 应急页只保留语言与语法兼容：不含桥端口字段、不声明 REQUIRED、不加载 compat shim、
    也不读 activePort()；
  * 真实资产模式下把生产生成的两段脚本落盘，核对 startup.js 的 provider 段与 shim 都在，
    应急页一个桥字节都不含。

WebPreviewActivity / GeckoPreviewActivity 的原生接线（WebMessageListener DshaBridgePort、
Gecko message delegate 的 bridge-port-request → activePort() 回包）无法在主机 JVM 里实例化，
这里按源码契约核对：两条通道各自在 sameService / 主框架 / 当前页代次门禁内、只回一个端口数字，
且正式页面没有第二个静态端口注入点。provider / shim / 内容脚本的运行时语义由
tools/test-bridge-token-compat.mjs 用真实字节在 node 里验证。host 证据不等于设备/仪器验收。

刷新限制与设计边界（本轮明确记录，未新增轮询器 / 跨来源 API / 宽泛原生接口）：
  * 静态快照仍只在三处刷新（每次生成、文档起始脚本按语言或端口变化重生成、onResume 对当前
    文档重注入），但它已不是安全依据：正式页 shim 每次带 token 的已知桥请求都先 await
    __DSHA_BRIDGE_PORT_PROVIDER__，拿不到就 fail-closed；
  * provider 的 pending 有界（8）且 1.5s 超时，pagehide 释放；超时 / 0 / 坏值一律拒绝，
    不回退 3090，也不自动换端口或重放；
  * Low / Gecko 正式页的 provider 走 startup-relay.js 内容脚本转发，native 侧同样按
    sameService / 主框架 / 页面代次校验；应急页与外部来源页不装这套能力。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tools/fixtures/web-bridge-port"
SCRIPTS = ROOT / "app/src/main/java/com/deepseekharness/app/ui/WebPageScripts.java"
ACTIVITY = ROOT / "app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java"
GECKO = ROOT / "app/src/low/java/com/deepseekharness/app/ui/GeckoPreviewActivity.java"
RECOVERY = ROOT / "app/src/main/java/com/deepseekharness/app/ui/RecoveryWebSurface.java"
BRIDGE = ROOT / "app/src/main/java/com/deepseekharness/app/HttpShellService.java"
ASSETS = ROOT / "app/src/main/assets"
STARTUP = ASSETS / "web-integration/startup.js"
RELAY = ASSETS / "web-integration/startup-relay.js"
PREPARE = ROOT / "tools/prepare-standard-assets.py"
RECOVERY_EXTENSION = ASSETS / "recovery-web-integration/manifest.json"

REQUIRED_LINE = "window.__DSHA_BRIDGE_PORT_REQUIRED__=true;"
PROVIDER_START = "/*#dsha-bridge-port-provider:start*/"
PROVIDER_END = "/*#dsha-bridge-port-provider:end*/"

# 只做语法核对时，缺 Android/JDK 依赖的符号错误按契约忽略；其它错误一律暴露。
UNRESOLVED = ("does not exist", "cannot find symbol", "cannot access", "cannot inherit")
ERROR_LINE = re.compile(r"^.*?\.java:\d+: error: (.*)$")
BROKEN_SYNTAX = "class BrokenSyntax { void f() { int x = ; } }\n"


def parse_only(javac, work, path):
    """用 -XDshould-stop.at=PARSE 只校验语法，返回未被契约忽略的错误信息。"""
    result = subprocess.run(
        [javac, "-encoding", "UTF-8", "-XDshould-stop.at=PARSE", "-d", str(work), str(path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
    )
    unexpected = []
    for line in result.stderr.splitlines():
        match = ERROR_LINE.match(line)
        if match is None:
            continue
        message = match.group(1)
        if any(token in message for token in UNRESOLVED):
            continue
        unexpected.append(message)
    return unexpected


def source_text(path):
    if not path.is_file():
        raise SystemExit("WEB_BRIDGE_PORT_SOURCE_MISSING: " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")


def contract(text, pattern, path, description):
    if re.search(pattern, text) is None:
        raise SystemExit(
            "WEB_BRIDGE_PORT_CONTRACT_FAILED: "
            + description
            + " ("
            + str(path.relative_to(ROOT))
            + ")"
        )


def require(text, needle, path, description):
    if needle not in text:
        raise SystemExit(
            "WEB_BRIDGE_PORT_CONTRACT_FAILED: "
            + description
            + " ("
            + str(path.relative_to(ROOT))
            + ")"
        )


def check_generated(java, work):
    """真实资产模式：生产 Java 生成器 + 真实 JS 字节，核对正式页有 provider、应急页隔离。"""
    dump = work / "real"
    dump.mkdir()
    result = subprocess.run(
        [
            java,
            "-Dfile.encoding=UTF-8",
            "-Ddsha.web.assets=" + str(ASSETS),
            "-cp",
            str(work / "classes"),
            "fixture.WebBridgePortProbe",
            "--dump",
            str(dump),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=180,
    )
    if result.returncode != 0 or "WEB_BRIDGE_PORT_DUMP_OK" not in result.stdout:
        raise SystemExit(
            "WEB_BRIDGE_PORT_DUMP_FAILED: " + (result.stdout + result.stderr)[-500:]
        )
    formal = (dump / "formal.js").read_text(encoding="utf-8")
    emergency = (dump / "emergency.js").read_text(encoding="utf-8")
    if "window.__DSHA_BRIDGE_PORT__=3093;" not in formal:
        raise SystemExit("WEB_BRIDGE_PORT_GENERATED_FAILED: 正式页静态快照")
    if formal.count(REQUIRED_LINE) != 1:
        raise SystemExit("WEB_BRIDGE_PORT_GENERATED_FAILED: REQUIRED 必须恰好一次")
    for needle, description in (
        ("__DSHA_BRIDGE_PORT_PROVIDER__", "provider 全局"),
        ("window.DshaBridgePort", "Standard 原生读通道对象"),
        ("BRIDGE_ENDPOINT_UNAVAILABLE", "shim 的 fail-closed 错误"),
        ("pagehide", "provider 释放"),
    ):
        if needle not in formal:
            raise SystemExit(
                "WEB_BRIDGE_PORT_GENERATED_FAILED: 正式页缺少 " + description
            )
    if not (formal.index(REQUIRED_LINE) < formal.index(PROVIDER_START) < formal.index(PROVIDER_END)):
        raise SystemExit("WEB_BRIDGE_PORT_GENERATED_FAILED: provider 段顺序")
    for needle in (
        "__DSHA_BRIDGE_PORT",
        "bridge-token-compat",
        "X-Token",
        "DshaBridgePort",
        "__dshaBridgePort",
    ):
        if needle in emergency:
            raise SystemExit("WEB_BRIDGE_PORT_GENERATED_FAILED: 应急页含 " + needle)


def main():
    if sys.flags.optimize:
        raise SystemExit("TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", help="JDK 17 所在目录（默认用 PATH 上的 javac/java）")
    args = parser.parse_args()
    if args.jdk:
        java_root = Path(args.jdk).resolve() / "bin"
        javac = str(java_root / ("javac.exe" if os.name == "nt" else "javac"))
        java = str(java_root / ("java.exe" if os.name == "nt" else "java"))
    else:
        javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java or not Path(javac).is_file() or not Path(java).is_file():
        raise SystemExit("JDK17_REQUIRED")

    with tempfile.TemporaryDirectory(prefix="web-bridge-port-") as folder:
        work = Path(folder)
        syntax = work / "syntax"
        syntax.mkdir()
        # 自检：语法核对必须能抓到真语法错误，否则这里会整体假通过。
        broken = work / "BrokenSyntax.java"
        broken.write_text(BROKEN_SYNTAX, encoding="utf-8")
        if not parse_only(javac, syntax, broken):
            raise SystemExit("WEB_BRIDGE_PORT_PARSE_CHECK_BLIND")
        for path in (SCRIPTS, ACTIVITY, GECKO):
            unexpected = parse_only(javac, syntax, path)
            if unexpected:
                raise SystemExit(
                    "WEB_BRIDGE_PORT_PARSE_FAILED: "
                    + str(path.relative_to(ROOT))
                    + " -> "
                    + " | ".join(unexpected[:3])
                )

        classes = work / "classes"
        classes.mkdir()
        subprocess.run(
            [
                javac,
                "-encoding",
                "UTF-8",
                "--release",
                "17",
                "-d",
                str(classes),
                "-sourcepath",
                str(FIXTURE),
                str(FIXTURE / "fixture/WebBridgePortProbe.java"),
                str(SCRIPTS),
            ],
            check=True,
            timeout=180,
        )
        result = subprocess.run(
            [java, "-Dfile.encoding=UTF-8", "-cp", str(classes), "fixture.WebBridgePortProbe"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=180,
        )
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        if result.returncode != 0:
            raise SystemExit("WEB_BRIDGE_PORT_PROBE_FAILED: exit " + str(result.returncode))
        combined = result.stdout + result.stderr
        if "CHECK_FAILED" in combined:
            raise SystemExit("WEB_BRIDGE_PORT_PROBE_FAILED: check failed")
        match = re.search(r"WEB_BRIDGE_PORT_PROBE_CHECKS=(\d+)", combined)
        if match is None or int(match.group(1)) < 30:
            raise SystemExit("WEB_BRIDGE_PORT_PROBE_FAILED: incomplete checks")
        check_generated(java, work)

    # 桥侧契约：浏览器唯一来源仍是 activePort()（0 = 不可用）。
    contract(
        source_text(BRIDGE),
        r"public\s+static\s+int\s+activePort\s*\(\s*\)",
        BRIDGE,
        "桥仍以 activePort() 发布实际端口",
    )
    scripts = source_text(SCRIPTS)
    contract(
        scripts,
        r"com\.deepseekharness\.app\.HttpShellService\.activePort\(\)",
        SCRIPTS,
        "生成脚本直接读取 activePort() 而不是复制常量",
    )
    contract(
        scripts,
        re.escape(REQUIRED_LINE),
        SCRIPTS,
        "正式页显式声明端口必须现取（fail-closed 不依赖资产加载）",
    )
    recovery = source_text(RECOVERY)
    contract(
        recovery,
        r"WebPageScripts\.emergencyCompatibility\(",
        RECOVERY,
        "应急页只接应急脚本",
    )
    if "WebPageScripts.bridgePort()" in recovery:
        raise SystemExit("WEB_BRIDGE_PORT_CONTRACT_FAILED: 应急页不得注入桥端口")
    if "bridgePortRequired" in recovery:
        raise SystemExit("WEB_BRIDGE_PORT_CONTRACT_FAILED: 应急页不得声明 REQUIRED")

    activity = source_text(ACTIVITY)
    wires = activity.count("WebPageScripts.bridgePort()")
    if wires != 1:
        raise SystemExit("WEB_BRIDGE_PORT_CONTRACT_FAILED: 正式页面只允许一个端口重注入点")
    at = activity.index("WebPageScripts.bridgePort()")
    guard = activity.rfind("WebPreviewPolicy.sameService(baseUrl, webView.getUrl())", 0, at)
    if guard < 0 or at - guard > 600:
        raise SystemExit("WEB_BRIDGE_PORT_CONTRACT_FAILED: 端口重注入必须在 sameService 门禁内")
    contract(
        activity,
        r"scriptBridgePort",
        ACTIVITY,
        "文档起始脚本按桥端口变化重生成",
    )
    contract(
        activity,
        r"addDocumentStartJavaScript\(",
        ACTIVITY,
        "文档起始脚本注册保持",
    )
    contract(
        activity,
        r"Collections\.singleton\(baseUrl\.substring\(0, baseUrl\.length\(\) - 1\)\)",
        ACTIVITY,
        "文档起始脚本仍按正式本机来源限定",
    )
    # 正式页的原生读通道：只回一个端口数字，且必须在来源 / 主框架 / 当前页门禁内。
    contract(
        activity,
        r'removeWebMessageListener\(view, "DshaBridgePort"\)',
        ACTIVITY,
        "重复进入时先移除旧的桥端口监听",
    )
    contract(
        activity,
        r'addWebMessageListener\(\s*view,\s*"DshaBridgePort"',
        ACTIVITY,
        "注册 DshaBridgePort 原生读通道",
    )
    listener = re.search(
        r'addWebMessageListener\(\s*view,\s*"DshaBridgePort"', activity
    )
    listener_text = activity[listener.start() : listener.start() + 1600]
    for needle in (
        "isCurrentPage(source)",
        "!mainFrame",
        "WebPreviewPolicy.sameService(baseUrl, source.getUrl())",
        "retained.pageEvents.owns(source)",
        'put("__dshaBridgePortReply", id)',
        "com.deepseekharness.app.HttpShellService.activePort()",
    ):
        if needle not in listener_text:
            raise SystemExit(
                "WEB_BRIDGE_PORT_CONTRACT_FAILED: 桥端口监听缺少 " + needle
            )

    # Low / Gecko 正式页：内容脚本转发 + native 在同一 MessageDelegate 内现取 activePort()。
    gecko = source_text(GECKO)
    on_message = gecko.index("public void onPortMessage")
    on_disconnect = gecko.index("public void onDisconnect", on_message)
    request_at = gecko.index('"bridge-port-request"')
    if not (on_message < request_at < on_disconnect):
        raise SystemExit(
            "WEB_BRIDGE_PORT_CONTRACT_FAILED: Gecko 端口请求必须与既有页面校验在同一 onPortMessage 内"
        )
    before = gecko[on_message:request_at]
    for needle in ("pageCurrent(current, pageIdentity)", "source != pagePort"):
        if needle not in before:
            raise SystemExit(
                "WEB_BRIDGE_PORT_CONTRACT_FAILED: Gecko 端口请求缺少既有门禁 " + needle
            )
    reply = gecko[request_at : request_at + 1200]
    if re.search(r'put\(\s*"type",\s*"bridge-port-reply"\)', reply) is None:
        raise SystemExit(
            "WEB_BRIDGE_PORT_CONTRACT_FAILED: Gecko 端口回包缺少 bridge-port-reply"
        )
    if re.search(r'put\(\s*"port",', reply) is None:
        raise SystemExit("WEB_BRIDGE_PORT_CONTRACT_FAILED: Gecko 端口回包缺少 port 字段")
    if "com.deepseekharness.app.HttpShellService.activePort()" not in reply:
        raise SystemExit(
            "WEB_BRIDGE_PORT_CONTRACT_FAILED: Gecko 端口回包必须现取 activePort()"
        )

    startup = source_text(STARTUP)
    require(startup, PROVIDER_START, STARTUP, "startup.js 带 provider 段起始标记")
    require(startup, PROVIDER_END, STARTUP, "startup.js 带 provider 段结束标记")
    for pattern, description in (
        (r"window\.__DSHA_BRIDGE_PORT_REQUIRED__\s*=\s*true;", "provider 安装声明 REQUIRED"),
        (r"window\.__DSHA_BRIDGE_PORT_PROVIDER__\s*=\s*request;", "provider 安装函数"),
        (r"window\.DshaBridgePort", "Standard 读通道对象"),
        (r"PENDING_LIMIT\s*=\s*8", "pending 有界"),
        (r"addEventListener\('pagehide',\s*release\)", "pagehide 释放"),
        (r"__dshaBridgePortReply", "Gecko 回包格式"),
    ):
        contract(startup, pattern, STARTUP, description)

    relay = source_text(RELAY)
    for pattern, description in (
        (r"bridge-port-request", "内容脚本向 native 要端口"),
        (r"bridge-port-reply", "内容脚本处理 native 回包"),
        (r"__dshaBridgePortReply", "内容脚本回页面世界"),
        (r"BRIDGE_PORT_LIMIT\s*=\s*8", "内容脚本 pending 有界"),
        (r"BRIDGE_PORT_TIMEOUT\s*=\s*1500", "内容脚本 pending 超时"),
    ):
        contract(relay, pattern, RELAY, description)

    prepare = source_text(PREPARE)
    contract(
        prepare,
        r"source / 'web-integration/startup\.js'",
        PREPARE,
        "Gecko 页面世界 bundle 含 startup.js（provider 来源）",
    )
    contract(
        prepare,
        r"source / 'bridge-token-compat\.cjs'",
        PREPARE,
        "Gecko bundle 含同一份 compat shim",
    )
    recovery_extension = source_text(RECOVERY_EXTENSION)
    for needle in ("startup.js", "startup-relay.js", "bridge-token-compat"):
        if needle in recovery_extension:
            raise SystemExit(
                "WEB_BRIDGE_PORT_CONTRACT_FAILED: 应急扩展不得含 " + needle
            )

    print("WEB_BRIDGE_PORT_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
