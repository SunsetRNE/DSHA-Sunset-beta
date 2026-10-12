#!/usr/bin/env python3
"""Compile the current ShellService/ShizukuShell sources against minimal Android and Shizuku stubs and
execute the real Shizuku user-service destroy transaction on a host JVM.

The device symptom behind this test: `ps -A` showed 10+ leftover `<pkg>:shizuku` processes. Shizuku's
server only ever ends a user-service process through the `USER_SERVICE_TRANSACTION_destroy` binder
transaction (rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy == 16777115; officially
documented as "use 16777114 in aidl", because the generated Stub adds FIRST_CALL_TRANSACTION). This test
drives that exact transaction code into the real ShellService and asserts the real destroy path runs,
using the real rikka.shizuku shared artifact from the Gradle cache as the oracle. It also pins the
client-side teardown contract in ShizukuShell (an unbind must ask the server to remove the record),
because the real Shizuku client needs a live manager and is not a host-runnable unit.

No device, no Gradle, no APK: the Android framework classes below are deliberately minimal host stubs,
so this is component behavior evidence, not Android platform acceptance.

The contract checks are strict: the AIDL declaration and the hand-maintained AIDL artifact
(tools/aidl-stub/.../IShellService.java) must both agree with the transaction Shizuku actually sends.
A mismatch fails the run instead of printing an informational note -- build.sh installs that exact stub
into the APK whenever the host `aidl` binary cannot run, so a wrong transaction here ships to devices.

`--fixture-root DIR` overlays an out-of-tree copy of the contract sources (the production worktree is
only opened for reading, never written). By default the script also proves its own oracle can go red:
it copies the current AIDL and stub into a temporary overlay, tampers each in turn, and re-runs this same
entry point, requiring a non-zero exit with the matching diagnostic. `--no-fixture-proof` skips that.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DESTROY_TRANSACTION = 16777115
DESTROY_AIDL_DECLARED_ID = 16777114
FIXTURE_TIMEOUT_SECONDS = 300

AIDL_REL = 'app/src/main/aidl/com/deepseekharness/app/IShellService.aidl'
STUB_REL = 'tools/aidl-stub/com/deepseekharness/app/IShellService.java'
PROTOCOL_REL = 'app/src/main/java/com/deepseekharness/app/util/ShizukuUserServiceProtocol.java'
SHELL_SERVICE_REL = 'app/src/main/java/com/deepseekharness/app/ShellService.java'
SHIZUKU_SHELL_REL = 'app/src/main/java/com/deepseekharness/app/ShizukuShell.java'

ANDROID_STUBS = {
    'android/os/IInterface.java': """
package android.os;
public interface IInterface { IBinder asBinder(); }
""",
    'android/os/IBinder.java': """
package android.os;
public interface IBinder {
  int FIRST_CALL_TRANSACTION = 0x00000001;
  int INTERFACE_TRANSACTION = ('_' << 24) | ('N' << 16) | ('T' << 8) | 'F';
  int FLAG_ONEWAY = 0x00000001;
  interface DeathRecipient { void binderDied(); }
  boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
  IInterface queryLocalInterface(String descriptor);
  boolean pingBinder();
  boolean isBinderAlive();
  String getInterfaceDescriptor() throws RemoteException;
  void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
  boolean unlinkToDeath(DeathRecipient recipient, int flags);
}
""",
    'android/os/RemoteException.java': """
package android.os;
public class RemoteException extends Exception {
  public RemoteException() { super(); }
  public RemoteException(String message) { super(message); }
}
""",
    'android/os/Parcel.java': """
package android.os;
import java.util.ArrayList;
import java.util.List;
/** Host stand-in for the binder parcel surface the AIDL stub and ShellService actually use. */
public final class Parcel {
  private String token;
  private final List<Object> values = new ArrayList<>();
  private int position;
  public static Parcel obtain() { return new Parcel(); }
  public void recycle() { }
  public void writeInterfaceToken(String descriptor) { token = descriptor; }
  public void enforceInterface(String descriptor) {
    if (!descriptor.equals(token)) throw new SecurityException("BAD_INTERFACE_TOKEN:" + token);
  }
  public void writeNoException() { }
  public void readException() { }
  public void writeString(String value) { values.add(value); }
  public String readString() { return (String) values.get(position++); }
  public void writeInt(int value) { values.add(value); }
  public int readInt() { return (Integer) values.get(position++); }
  public void writeStrongBinder(IBinder value) { values.add(value); }
  public IBinder readStrongBinder() { return (IBinder) values.get(position++); }
  public void setDataPosition(int next) { position = next; }
}
""",
    'android/os/Binder.java': """
package android.os;
public class Binder implements IBinder {
  private IInterface owner;
  private String descriptor = "";
  public Binder() { }
  public void attachInterface(IInterface owner, String descriptor) {
    this.owner = owner;
    this.descriptor = descriptor == null ? "" : descriptor;
  }
  public IInterface queryLocalInterface(String name) { return descriptor.equals(name) ? owner : null; }
  public String getInterfaceDescriptor() { return descriptor; }
  public IBinder asBinder() { return this; }
  public boolean pingBinder() { return true; }
  public boolean isBinderAlive() { return true; }
  public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    return onTransact(code, data, reply, flags);
  }
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    return false;
  }
  public void linkToDeath(DeathRecipient recipient, int flags) { }
  public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
  public static int getCallingUid() { return 2000; }
}
""",
    'android/os/Process.java': """
package android.os;
/** The uid is injected so the same harness can act as a privileged user service or as a test instance. */
public class Process {
  public static int myUid() { return Integer.parseInt(System.getProperty("dsha.fake.uid", "10123")); }
  public static int myPid() { return 4242; }
  public static void killProcess(int pid) { }
}
""",
    # Compile-time-only annotation, so the test needs no Gradle-cache artifact to build.
    'androidx/annotation/Keep.java': """
package androidx.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface Keep { }
""",
    # Local copy of the documented Shizuku constant; the real rikka.shizuku shared artifact is cross-checked
    # separately by javap whenever the Gradle cache is available.
    'rikka/shizuku/ShizukuApiConstants.java': """
package rikka.shizuku;
public interface ShizukuApiConstants {
  int USER_SERVICE_TRANSACTION_destroy = 16777115;
}
""",
}

# Only used by the optional real-library compile of ShizukuShell (it reads BuildConfig.VERSION_CODE).
LIBRARY_STUBS = {
    'com/deepseekharness/app/BuildConfig.java': """
package com.deepseekharness.app;
public final class BuildConfig {
  public static final int VERSION_CODE = 999;
  private BuildConfig() { }
}
""",
}

HARNESS = """
package com.deepseekharness.app;

import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.lang.reflect.Field;
import rikka.shizuku.ShizukuApiConstants;
import com.deepseekharness.app.util.ShizukuUserServiceProtocol;

/** Host harness: drives the real ShellService and the real AIDL artifact; no Android platform involved. */
public final class ShizukuDestroyHarness {
  private static final String MODE = System.getProperty("dsha.harness.mode", "");
  private static final String DESCRIPTOR = IShellService.DESCRIPTOR;

  public static void main(String[] args) throws Exception {
    int raw = ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;
    if (raw != ShizukuUserServiceProtocol.DESTROY_TRANSACTION) {
      fail("LIBRARY_CONTRACT_MOVED: library=" + raw
          + " protocol=" + ShizukuUserServiceProtocol.DESTROY_TRANSACTION);
    }
    if (raw - ShizukuUserServiceProtocol.DESTROY_AIDL_DECLARED_ID != 1) {
      fail("AIDL_ID_RELATION_BROKEN: raw=" + raw
          + " declared=" + ShizukuUserServiceProtocol.DESTROY_AIDL_DECLARED_ID);
    }
    System.out.println("HARNESS_CODES=stub=" + IShellService.Stub.TRANSACTION_destroy
        + " raw=" + raw + " protocol=" + ShizukuUserServiceProtocol.DESTROY_TRANSACTION);
    // Compiled-bytecode oracle for the hand-maintained AIDL artifact: build.sh copies this exact stub into
    // the APK when the host aidl binary is unusable, so a mismatched transaction is a shipping defect.
    if (IShellService.Stub.TRANSACTION_destroy != raw) {
      fail("STUB_TRANSACTION_MISMATCH: stub=" + IShellService.Stub.TRANSACTION_destroy
          + " expected=" + raw);
    }
    ShellService service = new ShellService();
    switch (MODE) {
      case "shizuku-destroy-oneway":
        run(service, raw, true, IBinder.FLAG_ONEWAY, true);
        return;
      case "shizuku-destroy-sync":
        run(service, raw, true, 0, true);
        return;
      case "wrong-token":
        run(service, raw, false, IBinder.FLAG_ONEWAY, false);
        return;
      case "unrelated-code":
        run(service, raw - 2, true, IBinder.FLAG_ONEWAY, false);
        return;
      case "service-uid-must-exit":
        run(service, raw, true, IBinder.FLAG_ONEWAY, true);
        // Reaching here means destroy() did not terminate the privileged service process.
        new File(System.getProperty("dsha.harness.marker")).createNewFile();
        System.out.println("HARNESS_RESULT=NO_EXIT");
        System.exit(9);
        return;
      default:
        fail("UNKNOWN_MODE:" + MODE);
    }
  }

  private static void run(ShellService service, int code, boolean goodToken, int flags, boolean expectDestroy)
      throws Exception {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    data.writeInterfaceToken(goodToken ? DESCRIPTOR : DESCRIPTOR + ".other");
    System.out.println("HARNESS_ENTERED code=" + code + " flags=" + flags);
    boolean handled;
    try {
      handled = service.onTransact(code, data, reply, flags);
    } catch (SecurityException expected) {
      handled = false;
    }
    boolean closed = ((Boolean) field(service, "closing")).booleanValue();
    System.out.println("HARNESS_HANDLED=" + handled + " HARNESS_CLOSING=" + closed);
    if (expectDestroy && !closed) {
      fail("DESTROY_NOT_ROUTED: code=" + code + " handled=" + handled);
    }
    if (!expectDestroy && closed) {
      fail("UNEXPECTED_DESTROY: code=" + code);
    }
    if (expectDestroy && !handled) {
      fail("DESTROY_NOT_ACKNOWLEDGED: code=" + code);
    }
    System.out.println("HARNESS_RESULT=PASS");
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void fail(String message) {
    System.out.println("HARNESS_RESULT=FAIL " + message);
    System.exit(1);
  }
}
"""


def fail(code):
    raise SystemExit(code)


class SourceTree:
    """Resolve repository-relative source paths, with an optional out-of-tree fixture overlay.

    A fixture overlay is an independent directory tree whose files win when present; everything else
    still comes from the production worktree, which is only ever opened for reading.
    """

    def __init__(self, root, overlay=None):
        self.root = Path(root)
        self.overlay = Path(overlay) if overlay else None

    def resolve(self, relative):
        if self.overlay is not None:
            candidate = self.overlay / relative
            if candidate.is_file():
                return candidate
        return self.root / relative

    def sourcepath_roots(self, *relative_dirs):
        """Overlay directories first (shadowing), then the production ones; no duplicate entries."""
        roots = []
        for relative in relative_dirs:
            if self.overlay is not None:
                candidate = self.overlay / relative
                if candidate.is_dir():
                    roots.append(str(candidate))
            roots.append(str(self.root / relative))
        return roots


def version_key(text):
    return tuple(int(part) for part in re.findall(r'\d+', text))


def java_tools(explicit):
    home = explicit or os.environ.get('DSHA_JAVA17') or os.environ.get('JAVA_HOME')
    java = None
    if home:
        candidate = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if candidate.is_file():
            java = candidate
    java = java or shutil.which('java')
    javac = shutil.which('javac')
    if not java or not javac:
        fail('JDK17_REQUIRED')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=30)
    text = version.stdout + version.stderr
    match = re.search(r'version "(\d+)', text)
    if version.returncode or not match or int(match.group(1)) != 17:
        fail('JDK17_REQUIRED: ' + text.strip())
    return str(java), javac


def android_jar(explicit):
    candidates = []
    for root in (explicit, os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME'),
                 '/root/Android', '/opt/android-sdk', '/usr/lib/android-sdk'):
        if root:
            candidates.extend(Path(root).glob('platforms/android-3*/android.jar'))
    for jar in sorted(candidates, key=lambda path: version_key(str(path)), reverse=True):
        if jar.is_file():
            return jar
    fail('ANDROID_JAR_MISSING: set ANDROID_SDK_ROOT or pass --sdk')


def gradle_homes(explicit):
    homes = []
    for candidate in (explicit, os.environ.get('GRADLE_USER_HOME'), Path.home() / '.gradle'):
        if candidate and Path(candidate).is_dir() and Path(candidate) not in homes:
            homes.append(Path(candidate))
    return homes


def real_library_destroy_transaction(javap, homes, work):
    """Cross-check the documented constant against the real rikka.shizuku artifact when it is cached."""
    aar = newest_shizuku_aar(homes, 'shared')
    if aar is None:
        return None
    classes = extract_classes_jar(aar, work, 'shared')
    completed = subprocess.run([str(javap), '-p', '-constants', '-cp', str(classes),
                                'rikka.shizuku.ShizukuApiConstants'],
                               capture_output=True, text=True, timeout=60)
    match = re.search(r'USER_SERVICE_TRANSACTION_destroy\s*=\s*(\d+)', completed.stdout)
    if completed.returncode != 0 or not match:
        fail('SHIZUKU_LIBRARY_INSPECTION_FAILED: ' + completed.stdout + completed.stderr)
    return int(match.group(1))


def newest_shizuku_aar(homes, artifact):
    for home in homes:
        hits = [path for path in
                home.glob('caches/modules-2/files-2.1/dev.rikka.shizuku/' + artifact + '/*/*/*.aar')
                if path.is_file()]
        if hits:
            return sorted(hits, key=lambda path: version_key(path.parent.parent.name))[-1]
    return None


def extract_classes_jar(aar, work, name):
    target = work / 'shizuku-lib'
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as archive:
        archive.extract('classes.jar', target)
    return (target / 'classes.jar').replace(target / (name + '-classes.jar'))


def run_step(command, label, timeout):
    completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                               errors='replace', timeout=timeout)
    if completed.returncode != 0:
        print(completed.stdout, file=sys.stderr)
        print(completed.stderr, file=sys.stderr)
        fail(label + '_FAILED')
    return completed


def aidl_declared_destroy_id(path):
    match = re.search(r'void\s+destroy\s*\(\s*\)\s*=\s*(\d+)\s*;',
                      Path(path).read_text(encoding='utf-8'))
    return int(match.group(1)) if match else None


def check_teardown_contract(tree):
    """Pin the ShizukuShell teardown contract: an unbind must ask the server to remove the record.

    remove=false only unregisters the callback on the server (13.1.5) or is a literal no-op (12.2.0),
    so a record whose callback was unregistered can never be reclaimed once the client dies.
    """
    source = tree.resolve(SHIZUKU_SHELL_REL).read_text(encoding='utf-8')
    calls = [' '.join(call.split())
             for call in re.findall(r'unbindUserService\s*\(([^;]*?)\)\s*;', source, re.S)]
    if not calls:
        fail('TEARDOWN_CONTRACT_MISSING: no unbindUserService call in ShizukuShell')
    if not any(re.search(r',\s*true\s*$', call) for call in calls):
        fail('TEARDOWN_CONTRACT_MISSING: no unbindUserService(..., true) teardown in ShizukuShell')
    return calls


def verify_fixtures(args):
    """Prove the strict contract checks can actually go red, using an out-of-tree temporary copy.

    The production worktree is never modified: the current AIDL and stub are copied into a temporary
    overlay, each is tampered in turn, and the SAME entry point (this script) is re-run against the
    overlay. The pristine copy must pass and both tampered copies must exit non-zero with the matching
    diagnostic, so a future edit that downgrades a check back to a note is caught here.
    """
    with tempfile.TemporaryDirectory(prefix='shizuku-lifecycle-fixture-') as folder:
        overlay = Path(folder) / 'overlay'
        for relative in (AIDL_REL, STUB_REL):
            target = overlay / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        command = [sys.executable, '-B', str(Path(__file__).resolve()), '--fixture-root', str(overlay)]
        if args.java:
            command += ['--java', args.java]
        if args.sdk:
            command += ['--sdk', args.sdk]
        if args.gradle_home:
            command += ['--gradle-home', args.gradle_home]

        def invoke(label, expect_success, marker):
            completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                                       errors='replace', timeout=FIXTURE_TIMEOUT_SECONDS)
            output = completed.stdout + completed.stderr
            if expect_success:
                if completed.returncode != 0:
                    print(output, file=sys.stderr)
                    fail('FIXTURE_PROOF_REJECTED_PRISTINE: ' + label)
            else:
                if completed.returncode == 0:
                    print(output, file=sys.stderr)
                    fail('FIXTURE_TAMPER_NOT_DETECTED: ' + label)
                if marker not in output:
                    print(output, file=sys.stderr)
                    fail('FIXTURE_TAMPER_WRONG_DIAGNOSTIC: ' + label + ' expected=' + marker)
            print('FIXTURE_PROOF=' + label + ' rc=' + str(completed.returncode)
                  + (' PASS' if expect_success else ' RED:' + marker))

        invoke('pristine-contract-copy', True, None)

        aidl = overlay / AIDL_REL
        original = aidl.read_text(encoding='utf-8')
        tampered, count = re.subn(
            r'(void\s+destroy\s*\(\s*\)\s*=\s*)' + str(DESTROY_AIDL_DECLARED_ID) + r'(\s*;)',
            lambda match: match.group(1) + str(DESTROY_AIDL_DECLARED_ID - 1) + match.group(2),
            original, count=1)
        if count != 1:
            fail('FIXTURE_AIDL_TAMPER_PATTERN_MISSING')
        aidl.write_text(tampered, encoding='utf-8')
        invoke('aidl-destroy-id-off-by-one', False, 'AIDL_DESTROY_ID_MISMATCH')
        aidl.write_text(original, encoding='utf-8')

        stub = overlay / STUB_REL
        original = stub.read_text(encoding='utf-8')
        tampered, count = re.subn(
            r'(TRANSACTION_destroy\s*=\s*)' + str(DESTROY_TRANSACTION) + r'(\s*;)',
            lambda match: match.group(1) + str(DESTROY_AIDL_DECLARED_ID) + match.group(2),
            original, count=1)
        if count != 1:
            fail('FIXTURE_STUB_TAMPER_PATTERN_MISSING')
        stub.write_text(tampered, encoding='utf-8')
        invoke('stub-transaction-off-by-one', False, 'STUB_TRANSACTION_MISMATCH')
        stub.write_text(original, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java')
    parser.add_argument('--sdk')
    parser.add_argument('--gradle-home')
    parser.add_argument('--fixture-root', metavar='DIR',
                        help='read contract sources from this out-of-tree overlay when the file exists '
                             '(the production worktree is never written)')
    parser.add_argument('--no-fixture-proof', action='store_true',
                        help='skip re-running this entry point against tampered temp copies of the AIDL/stub')
    args = parser.parse_args()
    if sys.flags.optimize:
        fail('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    java, javac = java_tools(args.java)
    javap = Path(javac).with_name('javap.exe' if os.name == 'nt' else 'javap')
    if not javap.is_file():
        fail('JDK_JAVAP_MISSING: ' + str(javap))
    sdk = android_jar(args.sdk)
    homes = gradle_homes(args.gradle_home)
    tree = SourceTree(ROOT, args.fixture_root)

    check_teardown_contract(tree)
    declared = aidl_declared_destroy_id(tree.resolve(AIDL_REL))
    if declared is None:
        fail('AIDL_DESTROY_MISSING')

    with tempfile.TemporaryDirectory(prefix='shizuku-lifecycle-') as folder:
        work = Path(folder)
        generated = work / 'generated'
        run_step([sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                  '--output', str(generated)], 'UI_LANGUAGE_GENERATION', 40)
        stub_sources = []
        for name, text in ANDROID_STUBS.items():
            path = work / 'stub' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text.lstrip('\n'), encoding='utf-8')
            stub_sources.append(path)
        harness = work / 'harness/com/deepseekharness/app/ShizukuDestroyHarness.java'
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS.lstrip('\n'), encoding='utf-8')
        library_transaction = real_library_destroy_transaction(javap, homes, work)

        host_out = work / 'hostout'
        run_step([javac, '-nowarn', '-encoding', 'UTF-8', '-d', str(host_out), *map(str, stub_sources)],
                 'ANDROID_STUB_COMPILE', 90)

        # Real current sources: ShellService, the destroy-protocol collaborator and the shipped AIDL
        # artifact; the rest of the dependency graph is pulled from source.
        source_path = os.pathsep.join(tree.sourcepath_roots('app/src/main/java', 'tools/aidl-stub')
                                      + [str(generated)])
        compile_cp = os.pathsep.join(map(str, [host_out, sdk]))
        main_out = work / 'mainout'
        run_step([javac, '-nowarn', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                  '-d', str(main_out), '-cp', compile_cp, '-sourcepath', source_path,
                  str(tree.resolve(SHELL_SERVICE_REL)),
                  str(tree.resolve(PROTOCOL_REL)),
                  str(tree.resolve(STUB_REL))],
                 'CURRENT_SOURCE_COMPILE', 180)

        compile_cp = os.pathsep.join(map(str, [main_out, host_out, sdk]))
        harness_out = work / 'harnessout'
        run_step([javac, '-nowarn', '-encoding', 'UTF-8', '-d', str(harness_out), '-cp', compile_cp,
                  str(harness)], 'HARNESS_COMPILE', 90)

        # Optional but stronger: compile the edited ShizukuShell against the real shipped Shizuku artifacts,
        # so the teardown call is checked against the actual library signatures rather than our stub.
        library_jars = None
        for artifact in ('api', 'shared', 'provider'):
            aar = newest_shizuku_aar(homes, artifact)
            if aar is None:
                library_jars = None
                break
            jar = extract_classes_jar(aar, work, artifact)
            library_jars = (library_jars or []) + [jar]
        if library_jars:
            library_stub_out = work / 'librarystubout'
            library_stubs = []
            for name, text in LIBRARY_STUBS.items():
                path = work / 'librarystub' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text.lstrip('\n'), encoding='utf-8')
                library_stubs.append(path)
            run_step([javac, '-nowarn', '-encoding', 'UTF-8', '-d', str(library_stub_out),
                      *map(str, library_stubs)], 'LIBRARY_STUB_COMPILE', 90)
            library_cp = os.pathsep.join(
                map(str, [*library_jars, library_stub_out, host_out, sdk]))
            run_step([javac, '-nowarn', '-encoding', 'UTF-8', '-d', str(work / 'libraryout'),
                      '-cp', library_cp, '-sourcepath', source_path,
                      str(tree.resolve(SHIZUKU_SHELL_REL))],
                     'SHIZUKU_SHELL_LIBRARY_COMPILE', 180)

        run_cp = os.pathsep.join(map(str, [harness_out, main_out, host_out, sdk]))
        marker = work / 'service-still-alive'
        cases = [
            # mode, injected uid, whether the JVM must survive, expected destroy flag
            ('shizuku-destroy-oneway', '10123', True, True),
            ('shizuku-destroy-sync', '10123', True, True),
            ('wrong-token', '10123', True, False),
            ('unrelated-code', '10123', True, False),
            ('service-uid-must-exit', '2000', False, True),
            ('service-uid-must-exit', '0', False, True),
        ]
        stub_transaction = None
        protocol_transaction = None
        for mode, uid, survives, closing in cases:
            if marker.exists():
                marker.unlink()
            completed = subprocess.run(
                [java, '-Dfile.encoding=UTF-8', '-Ddsha.fake.uid=' + uid, '-Ddsha.harness.mode=' + mode,
                 '-Ddsha.harness.marker=' + str(marker), '-cp', run_cp,
                 'com.deepseekharness.app.ShizukuDestroyHarness'],
                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
            output = completed.stdout + completed.stderr
            if not survives:
                # destroy() must call System.exit(0) inside the privileged user-service process, so the
                # harness never returns from onTransact: rc 0 with no PASS and no marker file is the proof
                # (the harness itself only exits 1 on failure and 9 when the process survived).
                if completed.returncode != 0:
                    print(output, file=sys.stderr)
                    fail('SERVICE_DID_NOT_EXIT_CLEANLY: uid=' + uid + ' rc=' + str(completed.returncode))
                if marker.exists():
                    fail('SERVICE_PROCESS_SURVIVED_DESTROY: uid=' + uid)
                if 'HARNESS_ENTERED' not in output:
                    print(output, file=sys.stderr)
                    fail('HARNESS_NEVER_ENTERED: uid=' + uid)
                if 'HARNESS_RESULT=' in output:
                    print(output, file=sys.stderr)
                    fail('DESTROY_DID_NOT_TERMINATE_SERVICE_PROCESS: uid=' + uid)
                continue
            if completed.returncode != 0 or 'HARNESS_RESULT=PASS' not in output:
                print(output, file=sys.stderr)
                fail('CASE_FAILED: ' + mode + ' uid=' + uid)
            observed = 'HARNESS_CLOSING=true' in output
            if observed != closing:
                print(output, file=sys.stderr)
                fail('CASE_FLAG_MISMATCH: ' + mode + ' uid=' + uid + ' closing=' + str(observed))
            match = re.search(r'HARNESS_CODES=stub=(\d+) raw=(\d+) protocol=(\d+)', output)
            if match:
                stub_transaction = int(match.group(1))
                protocol_transaction = int(match.group(3))
        if stub_transaction is None or protocol_transaction is None:
            fail('HARNESS_CODE_REPORT_MISSING')

        # Strict contract checks. AIDL declaration + FIRST_CALL_TRANSACTION(1) must equal the transaction
        # Shizuku sends, and the hand-maintained stub build.sh copies into the APK must carry the same
        # code. A mismatch is a shipping defect, never an informational note.
        if stub_transaction != DESTROY_TRANSACTION:
            fail('STUB_TRANSACTION_MISMATCH: ' + STUB_REL + ' compiles to destroy transaction '
                 + str(stub_transaction) + ' but Shizuku sends ' + str(DESTROY_TRANSACTION)
                 + '; build.sh installs this stub into the APK when the host aidl binary is unusable')
        if declared != DESTROY_AIDL_DECLARED_ID:
            fail('AIDL_DESTROY_ID_MISMATCH: ' + AIDL_REL + ' declares destroy() = ' + str(declared)
                 + ' but must declare ' + str(DESTROY_AIDL_DECLARED_ID)
                 + '; the generated Stub adds FIRST_CALL_TRANSACTION, giving ' + str(DESTROY_TRANSACTION))
        if declared + 1 != stub_transaction:
            fail('AIDL_STUB_ID_DISAGREE: aidl ' + str(declared) + ' + FIRST_CALL_TRANSACTION != stub '
                 + str(stub_transaction))
        if protocol_transaction != DESTROY_TRANSACTION:
            fail('PROTOCOL_TRANSACTION_MISMATCH: ' + PROTOCOL_REL + ' compiles to '
                 + str(protocol_transaction) + ' but the Shizuku transaction is ' + str(DESTROY_TRANSACTION))

        if library_jars:
            print('NOTE: the edited ShizukuShell also compiled against the real cached rikka.shizuku '
                  'api/shared/provider artifacts')
        else:
            print('SKIP: SHIZUKU_LIBRARY_SIGNATURE_ORACLE_UNAVAILABLE - the real rikka.shizuku artifacts '
                  'were not found in the Gradle cache, so ShizukuShell was not compiled against the shipped '
                  'library signatures')
        if library_transaction is None:
            print('SKIP: SHIZUKU_LIBRARY_ORACLE_UNAVAILABLE - the real rikka.shizuku shared artifact was '
                  'not found in the Gradle cache, so the transaction was checked against the documented '
                  'constant ' + str(DESTROY_TRANSACTION) + ', the compiled stub and the compiled protocol '
                  'only')
        elif library_transaction != DESTROY_TRANSACTION:
            fail('SHIZUKU_LIBRARY_CONTRACT_MOVED: library=' + str(library_transaction))
        else:
            print('NOTE: javap oracle rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy == '
                  + str(library_transaction))

    if args.fixture_root is None and not args.no_fixture_proof:
        verify_fixtures(args)
    print('PASS real ShellService handles the Shizuku destroy transaction 16777115, enforces the interface '
          'token, ignores unrelated transactions, and lets only privileged uids terminate the service '
          'process; ShizukuShell teardown asks the server to remove the record; the AIDL declaration and '
          'the hand-maintained stub carry the same transaction and the oracle rejects tampered copies')
    return 0


if __name__ == '__main__':
    sys.exit(main())
