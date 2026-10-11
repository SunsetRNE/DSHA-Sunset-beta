package com.deepseekharness.app;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.util.AdbAuthorizedKeys;
import com.deepseekharness.app.util.AdbBootstrapEngine;
import com.deepseekharness.app.util.AdbKeyTablePreflight;
import com.deepseekharness.app.util.SelfPackageIdentity;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/** root 专用的固定路径、锁和事务存储；调用方不能传入任意设备文件。 */
final class RootBootstrapFiles implements AutoCloseable, RootBootstrapExecutor.Store {
  // Linux/Bionic arm64 固定值；OsConstants 的公开字段直到 API 27 才加入。
  private static final int O_CLOEXEC = 0x80000;
  private static final String BASE = "/data/adb/dsha-adb-bootstrap";
  private static final String KEYS = "/data/misc/adb/adb_keys";
  private final SelfPackageIdentity identity;
  private final FileOutputStream lockStream;
  private final FileLock lock;

  RootBootstrapFiles(SelfPackageIdentity identity, boolean create) throws Exception {
    this.identity = identity;
    if (!new File(BASE).exists() && !create) {
      lockStream = null;
      lock = null;
      return;
    }
    trustedDirectory("/data/adb", true);
    if (!new File(BASE).exists()) Os.mkdir(BASE, 0700);
    trustedDirectory(BASE, true);
    String target = BASE + "/lock";
    if (!create && !new File(target).exists()) {
      if (new File(BASE + "/state.json").exists()) throw new IOException("STATE_LOCK_MISSING");
      lockStream = null;
      lock = null;
      return;
    }
    ParcelFileDescriptor owned =
        open(target, OsConstants.O_RDWR | (create ? OsConstants.O_CREAT : 0), 0600, 0);
    lockStream = new ParcelFileDescriptor.AutoCloseOutputStream(owned);
    FileLock acquired;
    try {
      acquired = lockStream.getChannel().tryLock();
    } catch (Exception error) {
      lockStream.close();
      throw error;
    }
    if (acquired == null) {
      lockStream.close();
      throw new IOException("BOOTSTRAP_BUSY");
    }
    lock = acquired;
  }

  public JSONObject load() throws Exception {
    if (!new File(BASE + "/state.json").exists())
      return new JSONObject()
          .put("schema", 1)
          .put("package", identity.name)
          .put("uid", identity.uid);
    trustedDirectory(BASE, true);
    JSONObject value =
        new JSONObject(new String(read(BASE + "/state.json", 65536, 0), StandardCharsets.UTF_8));
    if (value.getInt("schema") != 1
        || value.getInt("uid") != identity.uid
        || !identity.name.equals(value.getString("package")))
      throw new IOException("BOOTSTRAP_OTHER_OWNER");
    validate(value);
    return value;
  }

  private void validate(JSONObject value) throws Exception {
    java.util.Map<String, Object> record = new java.util.HashMap<>();
    java.util.Iterator<String> names = value.keys();
    while (names.hasNext()) {
      String name = names.next();
      record.put(name, value.get(name));
    }
    com.deepseekharness.app.util.AdbBootstrapJournal.validate(record, identity.name, identity.uid);
  }

  public void save(JSONObject value) throws Exception {
    validate(value);
    if (lock == null) throw new IOException("BOOTSTRAP_LOCK_REQUIRED");
    trustedDirectory(BASE, true);
    String temporary = BASE + "/state-" + java.util.UUID.randomUUID() + ".new";
    writeNew(temporary, value.toString().getBytes(StandardCharsets.UTF_8), 0, 0, 0600, null);
    verifyOwnedFile(temporary, 0);
    Os.rename(temporary, BASE + "/state.json");
    syncDirectory(BASE);
  }

  public byte[] keys() throws Exception {
    trustedDirectory("/data/misc/adb", false);
    try {
      return read(KEYS, AdbAuthorizedKeys.MAX_TABLE_BYTES, -1);
    } catch (android.system.ErrnoException missing) {
      // 明确区分「表不存在」与其它 I/O：不能把所有读取异常都当成空表。
      if (missing.errno == OsConstants.ENOENT)
        throw new AdbBootstrapEngine.Refused(AdbKeyTablePreflight.MISSING_UNSUPPORTED);
      throw missing;
    }
  }

  /**
   * D1：首次写入共享授权表之前的无副作用预检。
   *
   * <p>只读取事实并判定，不创建、不写入、不改标签；不能安全继续时抛出明确拒绝码，使整次
   * 动作在防火墙 / 公钥 / 监听写入之前停住。首次建表本身（D2）尚未实现。
   */
  public void preflightKeyTable() throws Exception {
    boolean directoryTrusted = true;
    try {
      trustedDirectory("/data/misc/adb", false);
    } catch (Exception untrusted) {
      directoryTrusted = false;
    }
    boolean present = false;
    StructStat info = null;
    try {
      info = Os.lstat(KEYS);
      present = true;
    } catch (android.system.ErrnoException problem) {
      if (problem.errno != OsConstants.ENOENT)
        throw new AdbBootstrapEngine.Refused("ADB_KEY_TABLE_PERMISSION_UNVERIFIED");
    }
    boolean canonical = false;
    boolean labelReadable = false;
    if (present) {
      try {
        canonical = new File(KEYS).getCanonicalPath().equals(KEYS);
      } catch (IOException redirected) {
        canonical = false;
      }
      try {
        labelReadable = RootBootstrapFileLabels.read(KEYS).length > 0;
      } catch (Exception unavailable) {
        labelReadable = false;
      }
    }
    String reason =
        AdbKeyTablePreflight.reason(
            directoryTrusted,
            present,
            present && info != null && OsConstants.S_ISREG(info.st_mode),
            present && info != null && info.st_nlink == 1,
            present && info != null && (info.st_uid == 0 || info.st_uid == 1000),
            present && info != null && (info.st_mode & 0022) == 0,
            canonical,
            labelReadable);
    if (!reason.isEmpty()) throw new AdbBootstrapEngine.Refused(reason);
  }

  public void replaceKeys(
      AdbAuthorizedKeys.Change change,
      JSONObject state,
      String phase,
      RootBootstrapExecutor.GrantCheck grant)
      throws Exception {
    if (lock == null) throw new IOException("BOOTSTRAP_LOCK_REQUIRED");
    trustedDirectory("/data/misc/adb", false);
    StructStat before = verifyOwnedFile(KEYS, -1);
    byte[] label = RootBootstrapFileLabels.read(KEYS);
    if (label.length == 0 || !Arrays.equals(keys(), change.before))
      throw new IOException("KEY_TABLE_CHANGED");
    String id = java.util.UUID.randomUUID().toString();
    String backup = BASE + "/keys-" + id + ".before";
    writeNew(backup, change.before, 0, 0, 0600, null);
    state
        .put("keyPending", phase)
        .put("keyBeforeSha", sha(change.before))
        .put("keyAfterSha", sha(change.after))
        .put("keyBackup", new File(backup).getName());
    save(state);
    // 公钥已被外部清除时只建立撤销证据，不重写共享授权表。
    if (Arrays.equals(change.before, change.after)) return;
    String temporary = "/data/misc/adb/.dsha-" + id;
    writeNew(temporary, change.after, before.st_uid, before.st_gid, before.st_mode & 0777, label);
    grant.check();
    StructStat current = verifyOwnedFile(KEYS, -1);
    if (current.st_dev != before.st_dev
        || current.st_ino != before.st_ino
        || current.st_uid != before.st_uid
        || current.st_gid != before.st_gid
        || current.st_mode != before.st_mode
        || !Arrays.equals(keys(), change.before)
        || !Arrays.equals(RootBootstrapFileLabels.read(KEYS), label))
      throw new IOException("KEY_TABLE_CHANGED");
    StructStat written = verifyOwnedFile(temporary, before.st_uid);
    if (written.st_uid != before.st_uid
        || written.st_gid != before.st_gid
        || (written.st_mode & 0777) != (before.st_mode & 0777)
        || !Arrays.equals(RootBootstrapFileLabels.read(temporary), label))
      throw new IOException("KEY_METADATA_UNVERIFIED");
    Os.rename(temporary, KEYS);
    syncDirectory("/data/misc/adb");
    if (!Arrays.equals(keys(), change.after)) throw new IOException("KEY_WRITE_UNVERIFIED");
    // 调用方在最后的身份/会话复核后提交 keyPending，避免中断丢失阶段证据。
  }

  static String text(String path, int limit) throws Exception {
    return new String(read(path, limit, -2), StandardCharsets.UTF_8);
  }

  private static byte[] read(String path, int limit, int owner) throws Exception {
    try (FileInputStream input =
            new ParcelFileDescriptor.AutoCloseInputStream(
                open(path, OsConstants.O_RDONLY, 0, owner));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (bytes.size() + count > limit) throw new IOException("FILE_TOO_LARGE");
        bytes.write(buffer, 0, count);
      }
      return bytes.toByteArray();
    }
  }

  private static ParcelFileDescriptor open(String path, int flags, int mode, int owner)
      throws Exception {
    if (owner != -2 && !new File(path).getCanonicalPath().equals(path))
      throw new IOException("PATH_REDIRECTED");
    FileDescriptor fd =
        Os.open(path, flags | OsConstants.O_NOFOLLOW | O_CLOEXEC | OsConstants.O_NONBLOCK, mode);
    try {
      StructStat info = Os.fstat(fd);
      if (!OsConstants.S_ISREG(info.st_mode)
          || (owner >= 0 && info.st_uid != owner)
          || (owner == -1 && info.st_uid != 0 && info.st_uid != 1000)
          || (info.st_mode & 0022) != 0
          || info.st_nlink != 1) throw new IOException("FILE_IDENTITY_UNVERIFIED");
      return ParcelFileDescriptor.dup(fd);
    } finally {
      Os.close(fd);
    }
  }

  private static StructStat verifyOwnedFile(String path, int owner) throws Exception {
    if (!new File(path).getCanonicalPath().equals(path)) throw new IOException("PATH_REDIRECTED");
    StructStat info = Os.lstat(path);
    if (!OsConstants.S_ISREG(info.st_mode)
        || info.st_nlink != 1
        || (info.st_mode & 0022) != 0
        || (owner >= 0 && info.st_uid != owner)
        || (owner == -1 && info.st_uid != 0 && info.st_uid != 1000))
      throw new IOException("FILE_IDENTITY_UNVERIFIED");
    return info;
  }

  private static void trustedDirectory(String path, boolean rootOnly) throws Exception {
    if (!new File(path).getCanonicalPath().equals(path))
      throw new IOException("DIRECTORY_REDIRECTED");
    StructStat info = Os.lstat(path);
    if (!OsConstants.S_ISDIR(info.st_mode)
        || (info.st_mode & 0022) != 0
        || (rootOnly ? info.st_uid != 0 : info.st_uid != 0 && info.st_uid != 1000))
      throw new IOException("DIRECTORY_IDENTITY_UNVERIFIED");
  }

  private static void writeNew(String path, byte[] bytes, int uid, int gid, int mode, byte[] label)
      throws Exception {
    try (FileOutputStream output =
        new ParcelFileDescriptor.AutoCloseOutputStream(
            open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL, 0600, 0))) {
      output.write(bytes);
      Os.fchown(output.getFD(), uid, gid);
      Os.fchmod(output.getFD(), mode);
      // 仅将原授权表的标签复制到同目录临时文件，不改 SELinux 规则或 permissive 状态。
      if (label != null) RootBootstrapFileLabels.copyTo(path, label);
      output.getFD().sync();
    }
  }

  private static void syncDirectory(String path) throws Exception {
    FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
    try {
      if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
        throw new IOException("DIRECTORY_IDENTITY_UNVERIFIED");
      Os.fsync(fd);
    } finally {
      Os.close(fd);
    }
  }

  static String sha(byte[] bytes) throws Exception {
    StringBuilder result = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  @Override
  public void close() throws IOException {
    if (lock != null)
      try {
        lock.release();
      } finally {
        lockStream.close();
      }
  }
}
