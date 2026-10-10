#!/usr/bin/env python3
"""用真实 JUnit/JSON 执行 root 策略与生产事务，假 I/O 不启动 Android 或设备命令。"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TESTS = [
    "com.deepseekharness.app.RootBootstrapExecutorTest",
    "com.deepseekharness.app.util.AdbBootstrapGrantTest",
    "com.deepseekharness.app.util.AdbBootstrapPolicyTest",
    "com.deepseekharness.app.util.AdbdIdentityTest",
    "com.deepseekharness.app.util.RootSupportProbeTest",
    "com.deepseekharness.app.util.AdbAuthorizedKeysTest",
    "com.deepseekharness.app.util.AdbBootstrapEngineTest",
    "com.deepseekharness.app.util.AdbBootstrapJournalTest",
    "com.deepseekharness.app.util.DeviceShellPolicyTest",
    "com.deepseekharness.app.util.RootShellCandidatesTest",
    "com.deepseekharness.app.util.SelfPackageIdentityTest",
]
# 构建锁中已有的公开 Maven 依赖；不从网络下载，不使用伪造的 JUnit 类。
JARS = {
    "org.json/json/20240303": ("json-20240303.jar", "0ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a"),
    "junit/junit/4.13.2": ("junit-4.13.2.jar", "8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12"),
    "org.hamcrest/hamcrest-core/1.3": (
        "hamcrest-core-1.3.jar", "42a25dc3219429f0e5d060061f71acb49bf010a0"
    ),
}


def dependency(cache, relative, filename, digest):
    path = cache / "caches/modules-2/files-2.1" / relative / digest.lstrip("0") / filename
    if not path.is_file() or hashlib.sha1(path.read_bytes()).hexdigest() != digest:
        raise SystemExit("JUNIT_CACHE_MISSING_OR_MISMATCH: " + str(path))
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-user-home", type=Path)
    args = parser.parse_args()
    if sys.flags.optimize:
        raise SystemExit("TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON")
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK17_REQUIRED")
    version = subprocess.run([javac, "-version"], capture_output=True, text=True, check=True)
    if not re.search(r"\bjavac 17(?:\.|\s|$)", version.stdout + version.stderr):
        raise SystemExit("JDK17_REQUIRED: " + (version.stdout + version.stderr).strip())
    cache = args.gradle_user_home or Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = [dependency(cache, relative, *values) for relative, values in JARS.items()]
    classpath = os.pathsep.join(map(str, jars))
    print(version.stdout.strip() or version.stderr.strip(), flush=True)
    with tempfile.TemporaryDirectory(prefix="dsha-root-support-tests-") as folder:
        temporary = Path(folder)
        generated, classes = temporary / "generated", temporary / "classes"
        classes.mkdir()
        subprocess.run(
            [sys.executable, "-B", str(ROOT / "tools/prepare-ui-languages.py"),
             "--output", str(generated)], cwd=ROOT, check=True, timeout=60
        )
        sources = ROOT / "app/src/main/java"
        test_sources = [ROOT / "app/src/test/java" / (name.replace(".", "/") + ".java") for name in TESTS]
        subprocess.run(
            [javac, "-encoding", "UTF-8", "--release", "17", "-cp", classpath,
             "-d", str(classes), "-sourcepath", os.pathsep.join(map(str, [sources, generated])),
             *map(str, test_sources)], cwd=ROOT, check=True, timeout=90
        )
        subprocess.run(
            [java, "-cp", os.pathsep.join([str(classes), classpath]),
             "org.junit.runner.JUnitCore", *TESTS], cwd=ROOT, check=True, timeout=60
        )
    print("PASS root policy and production executor tests; fake I/O, no Android SDK or device execution", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
