#!/usr/bin/env python3
"""独立 host 回归：桥端口回退、.bridge_port 端点契约与就绪门槛（javac + JUnit，不跑 Gradle）。

用法：
  python3 -B tools/test-bridge-endpoint-native.py --jdk <JDK> --gradle-home ~/.gradle

只编译被测测试类并用 -sourcepath 拉入其真正依赖的纯逻辑生产源码，不触碰 Android、
不访问设备、不写仓库内任何文件。

另外在能找到 android.jar / 预编译 app classes / R.jar 时，用同一份生产源码做一次
**HttpShellService 的 javac 类型检查**（只检查接线，不在 JVM 里执行该 Android 类）：
它能证明「缺根不发布就绪、就绪走 publishReady、恢复重发走同一新根」这段接线至少类型成立；
找不到输入时明确打印 SKIP，不冒充验证过。行为证据由 BridgeEndpointFilesTest 的真实文件读写负责。

再附加一项**编译期结构不变量**：用 JDK JavaCompiler/JavacTask.parse（只 parse、不 analyze，
所以不需要 Android classpath）解析真实 HttpShellService.java，证明 bindAndServe 里
publishReadyEndpointFor、LIFECYCLE.publish 与 running/instance 就绪赋值落在同一把
synchronized (LIFECYCLE) 临界区内；正 fixture 接受，锁外发布的负 fixture 必须被拒。
这是静态结构证明，**不是 Android 运行期竞态证据**。
"""
import argparse
import glob
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TESTS = [
    'util.BridgePortPolicyTest',
    'util.BridgeEndpointFilesTest',
]
# 静态并发不变量（编译期结构）：真实源 + 正/负 fixture，见 structure_check()。
LOCK_FIXTURES = ROOT / 'tools/fixtures/bridge-publication-lock'
LOCK_CHECKER = LOCK_FIXTURES / 'BridgePublicationLockCheck.java'
LOCK_SOURCES = [
    ROOT / 'app/src/main/java/com/deepseekharness/app/HttpShellService.java',
    LOCK_FIXTURES / 'positive/HttpShellService.java',
]
# 每个负 fixture 必须被拒，且诊断里要带出它违反的那个标记。
LOCK_NEGATIVES = [
    (
        LOCK_FIXTURES / 'negative-endpoint-outside-lock/HttpShellService.java',
        'OUTSIDE_LIFECYCLE_BLOCK:publishReadyEndpointFor',
    ),
    (
        LOCK_FIXTURES / 'negative-readiness-outside-lock/HttpShellService.java',
        'OUTSIDE_LIFECYCLE_BLOCK:running',
    ),
]
# HttpShellService 接线与 RootBootstrapCard 新 detail 文案检查需要从源码编译的类。
TYPECHECK_SOURCES = [
    'app/src/main/java/com/deepseekharness/app/HttpShellService.java',
    'app/src/main/java/com/deepseekharness/app/util/BridgePortPolicy.java',
    'app/src/main/java/com/deepseekharness/app/util/BridgeEndpointFiles.java',
    'app/src/main/java/com/deepseekharness/app/util/CredentialPaths.java',
    'app/src/main/java/com/deepseekharness/app/util/CredentialPathRules.java',
    'app/src/main/java/com/deepseekharness/app/ui/RootBootstrapCard.java',
]
APP_CLASSES = (
    'app/build/intermediates/javac/standardDebug/compileStandardDebugJavaWithJavac/classes'
)
R_JAR = (
    'app/build/intermediates/compile_and_runtime_r_class_jar/standardDebug/'
    'processStandardDebugResources/R.jar'
)


def jar(cache, group, name, version):
    matches = sorted((cache / 'caches/modules-2/files-2.1' / group / name / version).glob('**/*.jar'))
    if len(matches) != 1:
        raise RuntimeError('EXACT_JVM_TEST_DEPENDENCY_REQUIRED:' + name)
    return matches[0]


def android_jar(explicit):
    if explicit:
        return Path(explicit).resolve()
    roots = [
        os.environ.get('ANDROID_HOME'),
        os.environ.get('ANDROID_SDK_ROOT'),
        str(Path.home() / 'Android'),
        '/root/Android',
    ]
    found = []
    for root in roots:
        if root:
            found.extend(glob.glob(str(Path(root) / 'platforms' / 'android-*' / 'android.jar')))
    if not found:
        return None

    def api(path):
        match = re.search(r'android-(\d+)', path)
        return int(match.group(1)) if match else 0

    return Path(sorted(found, key=api)[-1])


def dependency_classpath(cache, work):
    """Gradle 模块缓存里 AndroidX/Material 以 .aar 存放；解出 classes.jar 再加普通 jar。"""
    import zipfile

    entries = []
    aar_dir = work / 'aar'
    aar_dir.mkdir()
    index = 0
    for aar in sorted((cache / 'caches/modules-2/files-2.1').glob('**/*.aar')):
        index += 1
        target = aar_dir / (str(index) + '.jar')
        try:
            with zipfile.ZipFile(aar) as archive:
                data = archive.read('classes.jar')
        except (zipfile.BadZipFile, KeyError):
            continue
        if not data:
            continue
        target.write_bytes(data)
        entries.append(str(target))
    for plain in sorted((cache / 'caches/modules-2/files-2.1').glob('**/*.jar')):
        name = plain.name
        if name.endswith('-sources.jar') or name.endswith('-javadoc.jar'):
            continue
        entries.append(str(plain))
    return entries


def typecheck(java_root, cache, work, android, app_classes, r_jar, dependencies):
    if android is None or not Path(app_classes).is_dir() or not Path(r_jar).is_file():
        print('SKIP HttpShellService javac wiring type-check: '
              'android.jar / prebuilt app classes / R.jar not present')
        return False
    javac = java_root / ('javac.exe' if os.name == 'nt' else 'javac')
    classpath = os.pathsep.join(
        [str(app_classes), str(r_jar), str(android)] + dependencies)
    out = work / 'typecheck-classes'
    command = [
        str(javac), '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
        '-proc:none', '-source', '17', '-target', '17',
        '-cp', classpath, '-d', str(out),
    ] + [str(ROOT / source) for source in TYPECHECK_SOURCES]
    done = subprocess.run(command, capture_output=True, text=True, timeout=300)
    if done.returncode != 0:
        sys.stderr.write(done.stdout + done.stderr)
        raise RuntimeError('HTTPSHELLSERVICE_TYPECHECK_FAILED')
    print('PASS HttpShellService/RootBootstrapCard javac wiring type-check: readiness gate/republish '
          'call the compiled BridgeEndpointFiles API (missing root -> BRIDGE_DATA_ROOT_UNAVAILABLE, '
          'ready -> publishReady, restore -> publishReady on the current root) and the new '
          'ADB_SWITCH_ENABLE_NOT_PERSISTED / ADB_CHANNEL_START_FAILED detail cases compile')
    return True


def structure_check(java_root, work):
    """用 JDK 编译器 AST 证明 bindAndServe 的端点发布/就绪转换共用一个 LIFECYCLE 临界区。

    只 parse 不 analyze，因此不需要 android.jar / app classes；在临时目录编译检查器，
    对真实源与 fixture 各跑一次。负 fixture 若被接受，或诊断不是预期的锁外标记，立即失败。
    这是静态结构回归，不是 Android 运行期竞态证据。
    """
    javac = java_root / ('javac.exe' if os.name == 'nt' else 'javac')
    java = java_root / ('java.exe' if os.name == 'nt' else 'java')
    classes = work / 'bridge-publication-lock-check'
    compiled = subprocess.run(
        [str(javac), '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '17',
         '-proc:none', '-nowarn', '-d', str(classes), str(LOCK_CHECKER)],
        capture_output=True, text=True, timeout=120)
    if compiled.returncode != 0:
        sys.stderr.write(compiled.stdout + compiled.stderr)
        raise RuntimeError('BRIDGE_PUBLICATION_LOCK_CHECKER_COMPILE_FAILED')

    def run(source):
        return subprocess.run(
            [str(java), '-Dfile.encoding=UTF-8', '-cp', str(classes),
             'BridgePublicationLockCheck', str(source)],
            capture_output=True, text=True, timeout=120)

    for source in LOCK_SOURCES:
        done = run(source)
        if done.returncode != 0:
            sys.stderr.write(done.stdout + done.stderr)
            raise RuntimeError('BRIDGE_PUBLICATION_LOCK_STRUCTURE_REJECTED:' + source.name)
    for source, expected in LOCK_NEGATIVES:
        done = run(source)
        output = done.stdout + done.stderr
        if done.returncode == 0:
            raise RuntimeError('BRIDGE_PUBLICATION_LOCK_NEGATIVE_FIXTURE_ACCEPTED:' + source.name)
        if expected not in output:
            sys.stderr.write(output)
            raise RuntimeError(
                'BRIDGE_PUBLICATION_LOCK_NEGATIVE_FIXTURE_WRONG_DIAGNOSTIC:'
                + source.name + ':' + expected)
    print('PASS HttpShellService bindAndServe synchronized(LIFECYCLE) structure (JDK JavaCompiler '
          'AST, parse-only, no Android classpath): publishReadyEndpointFor + LIFECYCLE.publish + '
          'running/instance readiness assignments share one critical section; positive fixture '
          'accepted, %d lock-escape negative fixture(s) rejected. Static structural invariant, '
          'NOT Android dynamic race evidence.' % len(LOCK_NEGATIVES))


def main():
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True)
    parser.add_argument('--gradle-home', required=True)
    parser.add_argument('--android-jar', help='Override the auto-detected android.jar')
    parser.add_argument('--app-classes', default=str(ROOT / APP_CLASSES))
    parser.add_argument('--r-jar', default=str(ROOT / R_JAR))
    parser.add_argument('--skip-typecheck', action='store_true')
    args = parser.parse_args()
    java_root = Path(args.jdk).resolve() / 'bin'
    java = java_root / ('java.exe' if os.name == 'nt' else 'java')
    javac = java_root / ('javac.exe' if os.name == 'nt' else 'javac')
    cache = Path(args.gradle_home).resolve()
    dependencies = [
        jar(cache, 'junit', 'junit', '4.13.2'),
        jar(cache, 'org.hamcrest', 'hamcrest-core', '1.3'),
    ]
    cp = os.pathsep.join(map(str, dependencies))
    with tempfile.TemporaryDirectory(prefix='dsha-bridge-endpoint-native-') as folder:
        work = Path(folder)
        test_root = ROOT / 'app/src/test/java/com/deepseekharness/app'
        sources = [test_root / (name.replace('.', '/') + '.java') for name in TESTS]
        classes = work / 'classes'
        command = [
            str(javac), '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '17',
            '-cp', cp, '-d', str(classes), '-sourcepath',
            os.pathsep.join(map(str, [ROOT / 'app/src/main/java', ROOT / 'app/src/test/java'])),
        ]
        subprocess.run(command + list(map(str, sources)), check=True, timeout=90)
        subprocess.run(
            [str(java), '-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + cp,
             'org.junit.runner.JUnitCore', *['com.deepseekharness.app.' + name for name in TESTS]],
            check=True, timeout=60)
        structure_check(java_root, work)
        checked = False
        if not args.skip_typecheck:
            checked = typecheck(
                java_root, cache, work, android_jar(args.android_jar), args.app_classes,
                args.r_jar, dependency_classpath(cache, work))
    print('PASS bridge endpoint/port contract: bounded 3090..3109 fallback, 0=unavailable, '
          'missing-only legacy default, stale generation cannot erase the newer endpoint, '
          'tokens never shared, readiness requires same-root token/header + port readback, '
          'root switch republishes the active port into the current root, '
          'bindAndServe publication lock is structurally one synchronized(LIFECYCLE) section '
          '(static JDK-AST invariant, not runtime race evidence) '
          '(HttpShellService javac type-check: %s).' % ('PASS' if checked else 'SKIPPED'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
