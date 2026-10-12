#!/usr/bin/env python3
"""回归：CI 解析 Android sdkmanager 的方式（不能只靠 PATH 上有一个 sdkmanager）。

背景：2026-10-12 的 Source checks 死在裸 `sdkmanager: command not found`（exit 127），
排在其后的全部契约检查与双 flavor 单测被跳过。本测试用假 SDK 目录树核对三件事：
按 cmdline-tools 布局解析、缺 ANDROID_HOME 时能从可执行文件回推根目录、真的找不到时
必须显式失败而不是静默通过。不联网、不安装任何 SDK 组件、不碰设备。
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import os

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ci-android-sdk.sh"
BASH = shutil.which("bash")


class CiAndroidSdkTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(BASH, "需要 bash 才能核对 CI 的 SDK 解析入口")
        self.tmp = Path(tempfile.mkdtemp(prefix="ci-android-sdk-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.log = self.tmp / "sdkmanager.args"

    def fake_sdk(self, layout="cmdline-tools/latest/bin"):
        """建一个只记录参数、不安装任何东西的假 sdkmanager。"""
        root = self.tmp / "sdk"
        binary = root / layout / "sdkmanager"
        binary.parent.mkdir(parents=True, exist_ok=True)
        binary.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            'printf "%s\\n" "$@" > ' + str(self.log) + "\n",
            encoding="utf-8")
        binary.chmod(0o755)
        return root, binary

    def env(self, sdk_root=None, path=None):
        value = {
            "HOME": str(self.tmp),
            "PATH": path if path is not None else "/usr/bin:/bin",
        }
        if sdk_root is not None:
            value["ANDROID_HOME"] = str(sdk_root)
        return value

    def call(self, env, args=("platforms;android-37.0", "build-tools;36.0.0")):
        return subprocess.run([BASH, str(SCRIPT), *args], cwd=str(ROOT), env=env,
                              capture_output=True, text=True, timeout=60)

    def recorded(self):
        self.assertTrue(self.log.is_file(), "假 sdkmanager 一次都没被调用")
        return self.log.read_text(encoding="utf-8").splitlines()

    def test_cmdline_tools_latest_is_resolved_under_android_home(self):
        root, binary = self.fake_sdk()
        done = self.call(self.env(sdk_root=root))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(["platforms;android-37.0", "build-tools;36.0.0"], self.recorded())
        self.assertIn("==> sdkmanager=" + str(binary), done.stdout)

    def test_legacy_tools_layout_is_still_resolved(self):
        root, binary = self.fake_sdk("tools/bin")
        done = self.call(self.env(sdk_root=root), args=("ndk;26.3.11579264",))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(["ndk;26.3.11579264"], self.recorded())

    def test_missing_android_home_derives_root_from_the_resolved_binary(self):
        root, binary = self.fake_sdk()
        done = self.call(self.env(path=str(binary.parent) + ":/usr/bin:/bin"))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("==> ANDROID_HOME=" + str(root), done.stdout)
        self.assertEqual(["platforms;android-37.0", "build-tools;36.0.0"], self.recorded())

    def test_sourcing_keeps_the_resolved_root_in_the_current_shell(self):
        root, binary = self.fake_sdk()
        # workflow 里的用法就是 source：同一 step 后面还要按 ANDROID_HOME 拼 build-tools 路径。
        done = subprocess.run(
            [BASH, "-c",
             'source tools/ci-android-sdk.sh "platforms;android-37.0" && printf "ROOT=%s\\n" "$ANDROID_HOME"'],
            cwd=str(ROOT), env=self.env(path=str(binary.parent) + ":/usr/bin:/bin"),
            capture_output=True, text=True, timeout=60)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("ROOT=" + str(root), done.stdout)

    def test_missing_sdkmanager_fails_loudly_instead_of_silently_passing(self):
        empty = self.tmp / "empty-sdk"
        empty.mkdir()
        done = self.call(self.env(sdk_root=empty))
        self.assertEqual(1, done.returncode)
        self.assertIn("找不到 sdkmanager", done.stderr)
        self.assertFalse(self.log.exists(), "解析失败时不应调用任何 sdkmanager")

    def test_no_package_argument_is_rejected(self):
        root, _ = self.fake_sdk()
        done = self.call(self.env(sdk_root=root), args=())
        self.assertEqual(2, done.returncode)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
