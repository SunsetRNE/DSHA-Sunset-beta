#!/usr/bin/env python3
"""ci-test-package 的主机测试：不跑 Gradle、不装设备、不推送、不碰真实密钥。

夹具 APK 用与 CI 相同的 apksigner 真实签名一张临时测试证书；缺 SDK/JDK 时
明确 SKIP 并说明原因，不用假通过掩盖未执行的门禁。
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


fixture = load("ci_test_package_fixture", "tools/ci_test_package_fixture.py")
tool = load("ci_test_package_tool", "tools/ci-test-package.py")


def local_apksigner():
    return tool.find_apksigner(None, None)


def sign_apk(path, keystore, apksigner):
    command = [str(apksigner), "sign", "--ks", str(keystore), "--ks-pass", "pass:android",
               "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
               "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
               "--v3-signing-enabled", "true", "--out", str(path) + ".signed", str(path)]
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0:
        raise AssertionError(completed.stdout.decode("utf-8", "replace"))
    Path(str(path) + ".signed").replace(path)


def certificate_digest(path, apksigner, min_sdk=30):
    completed = subprocess.run([str(apksigner), "verify", "--min-sdk-version", str(min_sdk),
                                "--print-certs", str(path)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    text = completed.stdout.decode("utf-8", "replace")
    for line in text.splitlines():
        if "certificate SHA-256 digest:" in line:
            return line.rsplit(":", 1)[1].strip()
    raise AssertionError("no certificate in: " + text)


def make_keystore(path):
    command = ["keytool", "-genkeypair", "-keystore", str(path), "-storepass", "android",
               "-keypass", "android", "-alias", "androiddebugkey",
               "-dname", "C=US, O=Android, CN=Android Debug", "-keyalg", "RSA", "-keysize", "2048",
               "-validity", "10000"]
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0:
        raise AssertionError(completed.stdout.decode("utf-8", "replace"))


def workflow_text():
    return (ROOT / ".github/workflows/ci-apk-sunsetrne.yml").read_text(encoding="utf-8")


class ManifestParser(unittest.TestCase):
    def test_fixture_manifest_round_trips(self):
        data = fixture.android_manifest("com.example.test", 190, "1.2.3", 30, 37, 37, True)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            fixture.build_apk(path, "com.example.test", 190, "1.2.3", 30, 37, 37, True, {})
            import zipfile
            attributes = tool.manifest_attributes(zipfile.ZipFile(path))
        self.assertEqual(attributes["package"], "com.example.test")
        self.assertEqual(attributes["versionCode"], 190)
        self.assertEqual(attributes["versionName"], "1.2.3")
        self.assertEqual(attributes["minSdkVersion"], 30)
        self.assertEqual(attributes["targetSdkVersion"], 37)
        self.assertTrue(attributes["debuggable"])
        self.assertGreater(len(data), 100)


class SignedFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.apksigner = local_apksigner()
        if cls.apksigner is None:
            raise unittest.SkipTest("local Android build-tools/apksigner 不可用")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.keystore = Path(cls.temp.name) / "debug.keystore"
        make_keystore(cls.keystore)
        cls.repo = fixture.FixtureRepo(Path(cls.temp.name) / "repo", ROOT).build()
        for flavor in ("standard", "low"):
            sign_apk(cls.repo.apks[flavor], cls.keystore, cls.apksigner)
        cls.certificate = certificate_digest(cls.repo.apks["standard"], cls.apksigner)

    def verify(self, flavors=("standard", "low"), certificate=None, minimum=10, apks=None):
        selected = apks or {flavor: self.repo.apks[flavor] for flavor in flavors}
        return tool.run(self.repo.root, selected, certificate or self.certificate,
                        {"url": "", "sha256": ""}, None,
                        {"GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123", "GITHUB_REPOSITORY": "x/y"},
                        minimum, quiet=True)

    def copy_with(self, name, replace=None, package=None, drop=()):
        """复制真夹具 APK，可改包名或替换某个成员字节，再重新签名。"""
        import zipfile
        source = self.repo.apks["standard"]
        target = Path(self.temp.name) / name
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, "w") as output:
            for item in original.infolist():
                if item.filename in drop:
                    continue
                payload = original.read(item.filename)
                if replace and item.filename in replace:
                    payload = replace[item.filename]
                if package and item.filename == "AndroidManifest.xml":
                    payload = fixture.android_manifest(package, self.repo.version_code, self.repo.version_name,
                                                       30, self.repo.target_sdk, self.repo.target_sdk, True)
                output.writestr(item, payload)
        sign_apk(target, self.keystore, self.apksigner)
        return target

    def test_complete_pair_passes_with_receipt(self):
        receipt = self.verify()
        self.assertEqual(receipt["status"], "PASS_EXPERIMENTAL_DEBUG_PACKAGE")
        self.assertEqual(receipt["package"], "com.dsh.clienu.SunsetRNE")
        self.assertEqual({row["flavor"] for row in receipt["apks"]}, {"standard", "low"})
        for row in receipt["apks"]:
            self.assertTrue(row["debuggable"])
            self.assertEqual(row["certificateSha256"], self.certificate)
            self.assertEqual(row["package"], "com.dsh.clienu.SunsetRNE")
            self.assertEqual(row["nativeAbi"], ["arm64-v8a"])
            self.assertEqual(row["runtimeId"], self.repo.descriptor["runtimeId"])
            self.assertNotEqual(row["sha256"], "")
        self.assertEqual(receipt["source"]["commit"], "a" * 40)
        self.assertEqual(receipt["certificate"]["sha256"], self.certificate)
        self.assertEqual(receipt["certificate"]["schemes"], ["v1", "v2", "v3"])
        self.assertEqual(receipt["apks"][0]["versionCode"], 190)

    def test_low_flavor_keeps_legacy_launcher_and_its_own_scheme_floor(self):
        receipt = self.verify()
        low = next(row for row in receipt["apks"] if row["flavor"] == "low")
        self.assertEqual(low["launcher"], "low-legacy")
        self.assertEqual(low["minSdk"], 23)

    def test_wrong_certificate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "APK_CERTIFICATE_MISMATCH"):
            self.verify(certificate="b" * 64)

    def test_release_certificate_is_refused_as_expectation(self):
        with self.assertRaisesRegex(RuntimeError, "EXPECTED_CERTIFICATE_IS_PUBLISH_KEY"):
            self.verify(certificate=tool.PUBLISH_CERTIFICATE_SHA256)

    def test_unknown_signature_is_rejected(self):
        other = Path(self.temp.name) / "other.keystore"
        make_keystore(other)
        signed = Path(self.temp.name) / "standard-other.apk"
        shutil.copyfile(self.repo.apks["standard"], signed)
        sign_apk(signed, other, self.apksigner)
        with self.assertRaisesRegex(ValueError, "APK_CERTIFICATE_MISMATCH"):
            self.verify(apks={"standard": signed, "low": self.repo.apks["low"]})

    def test_tampered_asset_is_rejected(self):
        tampered = self.copy_with("tampered.apk",
                                  replace={"assets/offline-rootfs.bin": b"tampered payload"})
        with self.assertRaisesRegex(ValueError, "APK_ASSET_DIGEST"):
            self.verify(apks={"standard": tampered, "low": self.repo.apks["low"]})

    def test_package_name_is_enforced(self):
        renamed = self.copy_with("renamed.apk", package="com.dsh.client")
        with self.assertRaisesRegex(ValueError, "APK_PACKAGE_NAME"):
            self.verify(apks={"standard": renamed, "low": self.repo.apks["low"]})

    def test_missing_flavor_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "APK_ARGUMENT_MISSING"):
            self.verify(flavors=("standard",))


    def shared_recovery_copy(self, name, corrupt=False, arbitrary=False):
        import zipfile
        with zipfile.ZipFile(self.repo.apks["standard"]) as source:
            mapping = json.loads(source.read("assets/recovery-asset-locations.json"))
            descriptor = json.loads(source.read("assets/runtime-descriptor.json"))
            replacements = {}
            drop = []
            for row in mapping["archives"]:
                physical = {"recovery-rootfs.bin": "offline-rootfs.bin",
                            "recovery-dsh-runtime.bin": "dsh-runtime.bin"}[row["asset"]]
                payload = source.read("assets/" + row["asset"])
                replacements["assets/" + physical] = payload
                descriptor["inputs"][physical] = hashlib.sha256(payload).hexdigest()
                row["source"] = physical
                drop.append("assets/" + row["asset"])
            if corrupt:
                replacements["assets/offline-rootfs.bin"] = b"changed shared archive"
            if arbitrary:
                mapping["archives"][0]["source"] = "unexpected.bin"
            replacements["assets/recovery-asset-locations.json"] = json.dumps(mapping).encode()
            replacements["assets/runtime-descriptor.json"] = json.dumps(descriptor).encode()
        return self.copy_with(name, replace=replacements, drop=drop), descriptor

    def test_shared_recovery_archives_are_valid_without_duplicate_files(self):
        import zipfile
        path, descriptor = self.shared_recovery_copy("shared-recovery.apk")
        gate = tool.Gate("shared recovery")
        with zipfile.ZipFile(path) as apk:
            self.assertNotIn("assets/recovery-rootfs.bin", apk.namelist())
            self.assertNotIn("assets/recovery-dsh-runtime.bin", apk.namelist())
            tool.verify_assets(gate, apk, descriptor, self.repo.lock)
            tool.verify_recovery(gate, apk, "standard", self.repo.lock)
        gate.done()

    def test_shared_recovery_bytes_cannot_be_substituted(self):
        import zipfile
        path, _ = self.shared_recovery_copy("bad-shared-recovery.apk", corrupt=True)
        with zipfile.ZipFile(path) as apk:
            with self.assertRaisesRegex(ValueError, "RECOVERY_ARCHIVE_HASH"):
                tool.verify_recovery(tool.Gate("bad shared"), apk, "standard", self.repo.lock)

    def test_recovery_mapping_cannot_name_an_arbitrary_asset(self):
        import zipfile
        path, _ = self.shared_recovery_copy("bad-recovery-mapping.apk", arbitrary=True)
        with zipfile.ZipFile(path) as apk:
            with self.assertRaisesRegex(ValueError, "RECOVERY_ARCHIVE_SOURCE"):
                tool.verify_recovery(tool.Gate("bad mapping"), apk, "standard", self.repo.lock)

    def test_apk_runtime_descriptor_must_match_source(self):
        changed = json.loads(json.dumps(self.repo.descriptor))
        changed["runtimeId"] = "f" * 64
        path = self.copy_with("changed-runtime-descriptor.apk", replace={
            "assets/runtime-descriptor.json": json.dumps(changed).encode()})
        with self.assertRaisesRegex(ValueError, "APK_RUNTIME_DESCRIPTOR_SOURCE"):
            self.verify(apks={"standard": path, "low": self.repo.apks["low"]})


class ReceiptBinding(unittest.TestCase):
    """发布前的回执核对必须是可执行代码，而不是 workflow 里的内联片段。"""

    def test_receipt_verifier_is_wired_and_checks_the_pair(self):
        verifier = load("verify_experimental_receipt", "tools/verify-experimental-receipt.py")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apks = {}
            for flavor in ("standard", "low"):
                apk = root / ("SunsetRNE-%s-experimental.apk" % flavor)
                apk.write_bytes(("payload-" + flavor).encode())
                apks[flavor] = {"flavor": flavor, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                "debuggable": True, "certificateSha256": "c" * 64}
            receipt = {"status": "PASS_EXPERIMENTAL_DEBUG_PACKAGE", "package": "com.dsh.clienu.SunsetRNE",
                       "source": {"commit": "a" * 40}, "certificate": {"sha256": "c" * 64},
                       "apks": list(apks.values())}
            (root / "experimental-package-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
            completed = subprocess.run([sys.executable, str(ROOT / "tools/verify-experimental-receipt.py"),
                                        "--artifacts", str(root), "--commit", "a" * 40],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
            self.assertEqual(completed.returncode, 0, completed.stdout.decode("utf-8", "replace"))
            # 换掉字节就拒绝，避免把未知发布覆盖到同一 tag。
            (root / "SunsetRNE-low-experimental.apk").write_bytes(b"tampered")
            completed = subprocess.run([sys.executable, str(ROOT / "tools/verify-experimental-receipt.py"),
                                        "--artifacts", str(root), "--commit", "a" * 40],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
            self.assertNotEqual(completed.returncode, 0)


class PublishSafety(unittest.TestCase):
    def setUp(self):
        self.text = workflow_text()
        self.lines = [line for line in self.text.splitlines() if not line.lstrip().startswith("#")]

    def test_push_and_pr_are_build_only(self):
        self.assertIn("workflow_dispatch:", self.text)
        # job 级条件（顶格 if:）只有 publish 一个，且必须同时要求 dispatch 与显式 publish。
        job_gate = [line for line in self.lines if line.startswith("    if:")]
        self.assertEqual(len(job_gate), 1, job_gate)
        self.assertIn("github.event_name == 'workflow_dispatch'", job_gate[0])
        self.assertIn("inputs.publish == true", job_gate[0])

    def test_top_level_is_read_only_and_publish_job_is_write(self):
        self.assertIn("permissions:", self.text)
        self.assertIn("contents: read", self.text)
        self.assertIn("contents: write", self.text)
        self.assertLess(self.text.index("contents: read"), self.text.index("contents: write"))

    def test_publish_is_explicit_and_prerelease(self):
        self.assertIn("inputs.publish", self.text)
        self.assertIn("--prerelease", self.text)
        self.assertIn("gh release create", self.text)
        self.assertIn("release_tag", self.text)

    def test_secret_is_only_consumed_by_the_build_job(self):
        occurrences = [line for line in self.lines if "secrets.DSHA_TEST_KEYSTORE_BASE64" in line]
        self.assertEqual(len(occurrences), 1, occurrences)
        self.assertIn("KEY_BASE64: ${{ secrets.DSHA_TEST_KEYSTORE_BASE64 }}", occurrences[0])
        self.assertNotIn("DSHA_PUBLISH_KEYSTORE_BASE64", self.text)
        self.assertNotIn("DSHA_KEYSTORE_PASSWORD", self.text)
        self.assertNotIn("assembleStandardRelease", self.text)
        self.assertNotIn("signPublishRelease", self.text)

    def test_inputs_and_identity_are_explicit(self):
        for name in ("asset_bundle_url", "asset_bundle_sha256", "test_certificate_sha256", "release_tag"):
            self.assertIn(name + ":", self.text)
        self.assertIn("tools/ci-assets.py", self.text)
        self.assertIn("tools/ci-test-package.py", self.text)
        self.assertIn("tools/ci-toolchain.py", self.text)

    def test_apks_are_separate_direct_downloads_and_reports_stay_archived(self):
        import re
        uploads = re.findall(r"(?ms)^      - (?:name: [^\n]+\n        )?uses: actions/upload-artifact@[^\n]+\n(.*?)(?=^      - |^  [a-z]|\Z)", self.text)
        direct = [block for block in uploads if "archive: false" in block]
        self.assertEqual(2, len(direct), "Standard / Low APK 必须各自作为单文件上传")
        paths = [re.search(r"(?m)^          path: (.+)$", block).group(1) for block in direct]
        self.assertEqual(set(paths), {"dist/SunsetRNE-standard-experimental.apk",
                                      "dist/SunsetRNE-low-experimental.apk"})
        self.assertTrue(all("name:" not in block for block in direct), "非归档模式按文件名命名")
        reports = [block for block in uploads if "dist/experimental-package-receipt.json" in block]
        self.assertEqual(1, len(reports))
        self.assertIn("archive: true", reports[0])
        self.assertNotIn("dist/SunsetRNE-*-experimental.apk\n", reports[0])
        self.assertNotIn("app/build/", reports[0], "发布回执必须解压到下载目录根部")
        self.assertIn("actions/download-artifact@70fc10c6e5e1ce46ad2ea6f2b72d43f7d47b13c3", self.text)
        self.assertIn("pattern: SunsetRNE-*", self.text)
        self.assertIn("merge-multiple: true", self.text)

    def test_actions_are_pinned_and_home_is_not_guessed(self):
        import re
        for action in re.findall(r"uses:\s*([^\s#]+)", self.text):
            self.assertRegex(action, r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")
        self.assertNotIn("export HOME=", self.text)
        self.assertNotRegex(self.text, r"(?m)^\s+HOME:")
        self.assertIn("DSHA_TEST_KEYSTORE", self.text)
        self.assertIn("--init-script", self.text)
        self.assertIn("chmod 600", self.text)
        self.assertIn("trap", self.text)
        self.assertNotRegex(self.text, r"(?m)^  (?:push|pull_request):")


class WorkflowExecution(unittest.TestCase):
    def build_script(self):
        lines = workflow_text().splitlines()
        start = next(i for i, line in enumerate(lines)
                     if '- name: Build both flavors with the fixed test key' in line)
        start = next(i for i in range(start, len(lines)) if lines[i].strip() == 'run: |') + 1
        body = []
        for line in lines[start:]:
            if line.startswith('          '):
                body.append(line[10:])
            elif not line.strip():
                body.append('')
            else:
                break
        return '\n'.join(body) + '\n'

    def test_fixed_key_lives_through_both_builds_and_is_then_removed(self):
        import base64
        with tempfile.TemporaryDirectory(prefix='dsha-workflow-lifetime-') as directory:
            root = Path(directory)
            runner, binaries = root / 'runner', root / 'bin'
            runner.mkdir()
            binaries.mkdir()
            keytool = binaries / 'keytool'
            keytool.write_text('#!/bin/sh\nprintf "SHA256: %s\\n" "' + ':'.join(['AA'] * 32) + '"\n')
            keytool.chmod(0o700)
            gradle = root / 'gradlew'
            gradle.write_text('''#!/bin/bash
set -euo pipefail
test -f "${DSHA_TEST_KEYSTORE:?}"
test "$1" = --init-script
test -s "$2"
printf '%s\\n' "$DSHA_TEST_KEYSTORE" >> "$BUILD_TRACE"
case "$*" in
  *assembleStandardDebug*) flavor=standard ;;
  *assembleLowDebug*) flavor=low ;;
  *) exit 9 ;;
esac
mkdir -p "app/build/outputs/apk/$flavor/debug"
touch "app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk"
''')
            gradle.chmod(0o700)
            script = root / 'step.sh'
            script.write_text(self.build_script())
            subprocess.run(['bash', '-n', str(script)], check=True)
            trace = root / 'build-calls.txt'
            environment = dict(os.environ, RUNNER_TEMP=str(runner),
                               KEY_BASE64=base64.b64encode(b'fixture-key-not-a-real-secret').decode(),
                               EXPECTED_CERT='a' * 64, BUILD_TRACE=str(trace),
                               PATH=str(binaries) + os.pathsep + os.environ.get('PATH', ''))
            result = subprocess.run(['bash', str(script)], cwd=root, env=environment,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = trace.read_text().splitlines()
            self.assertEqual(2, len(calls))
            self.assertEqual(calls[0], calls[1])
            self.assertFalse(Path(calls[0]).exists(), '构建完成后必须清理密钥')
            self.assertFalse(list(runner.glob('dsha-sunsetrne-*')), '初始化脚本也应清理')


if __name__ == "__main__":
    unittest.main(verbosity=2)
