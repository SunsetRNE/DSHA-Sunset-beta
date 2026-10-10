package com.deepseekharness.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.AdbBootstrapPolicy;
import com.deepseekharness.app.util.Constants;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import org.json.JSONObject;

/** 单次原生动作的短期授权代理；每次特权写入都回到仍存活的应用进程检查开关。 */
public final class RootBootstrapAuthority implements AutoCloseable {
  private final Context app;
  private final AdbBootstrapPolicy.Action action;
  private volatile String publicKey;
  private final com.deepseekharness.app.util.AdbBootstrapGrant grant;
  private final LocalServerSocket server;
  private final long expires = SystemClock.elapsedRealtime() + 120000;
  private final SharedPreferences channelPrefs, grantPrefs;
  private volatile boolean closed;
  public final String ticket;
  private final SharedPreferences.OnSharedPreferenceChangeListener changes;

  public RootBootstrapAuthority(Context context, AdbBootstrapPolicy.Action action, String key)
      throws IOException {
    app = context.getApplicationContext();
    if (action == null || !allowed(app, action)) throw new IOException("NATIVE_GRANT_REVOKED");
    this.action = action;
    this.publicKey = key == null ? "" : key;
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    StringBuilder value = new StringBuilder();
    for (byte b : random) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    ticket = value.toString();
    grant =
        new com.deepseekharness.app.util.AdbBootstrapGrant(
            action,
            RootShell.authorizationRevision(),
            new DeviceGrants(app).adbBootstrapRevision(),
            SystemClock.elapsedRealtime());
    server = new LocalServerSocket(name(app.getApplicationInfo().uid, ticket));
    channelPrefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    grantPrefs = app.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    changes =
        (prefs, changed) -> {
          if (changed == null
              || Constants.KEY_ALLOW_ROOT_SHELL.equals(changed)
              || (needsBootstrap(action) && "adb_bootstrap".equals(changed))) grant.revoke();
        };
    try {
      channelPrefs.registerOnSharedPreferenceChangeListener(changes);
      grantPrefs.registerOnSharedPreferenceChangeListener(changes);
      Thread thread = new Thread(this::serve, "root-bootstrap-authority");
      thread.setDaemon(true);
      thread.start();
    } catch (RuntimeException failed) {
      close();
      throw new IOException("NATIVE_AUTHORITY_UNAVAILABLE", failed);
    }
  }

  public static boolean needsBootstrap(AdbBootstrapPolicy.Action action) {
    return com.deepseekharness.app.util.AdbBootstrapGrant.needsBootstrap(action);
  }

  public static boolean needsClientKey(AdbBootstrapPolicy.Action action) {
    return com.deepseekharness.app.util.AdbBootstrapGrant.needsClientKey(action);
  }

  /**
   * 门禁：root 许可 + 独立自举许可。
   *
   * <p>不再要求「ADB 设备通道」先行开启 —— 自举的用途本来就是免配对地建立 ADB 授权，
   * 要求 ADB 先开启会与该用途自相矛盾。
   */
  public static boolean allowed(Context app, AdbBootstrapPolicy.Action action) {
    return RootShell.enabled(app)
        && (!needsBootstrap(action) || new DeviceGrants(app).adbBootstrapAllowed());
  }

  /** 从提交时开始持有同一票据；环境准备期间撤销后重新开启也不能复活旧动作。 */
  public boolean valid() {
    return !closed
        && grant.valid(
            RootShell.enabled(app),
            new DeviceGrants(app).adbBootstrapAllowed(),
            RootShell.authorizationRevision(),
            new DeviceGrants(app).adbBootstrapRevision(),
            SystemClock.elapsedRealtime());
  }

  public void prepareKey(String key) throws IOException {
    if (!valid()) throw new IOException("NATIVE_GRANT_REVOKED");
    publicKey = key == null ? "" : key;
  }

  private void serve() {
    while (!closed && SystemClock.elapsedRealtime() < expires) {
      try (LocalSocket socket = server.accept()) {
        socket.setSoTimeout(2000);
        if (socket.getPeerCredentials().getUid() != 0) continue;
        DataInputStream input = new DataInputStream(socket.getInputStream());
        boolean matches = ticket.equals(input.readUTF()) && action.name().equals(input.readUTF());
        boolean ok = matches && valid();
        JSONObject response =
            new JSONObject().put("allowed", ok).put("publicKey", ok ? publicKey : "");
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeUTF(response.toString());
        output.flush();
      } catch (Exception unavailable) {
        // 不输出票据、公钥或 socket 原始数据；连接失败即不提供许可。
        if (closed) return;
      }
    }
  }

  private static String name(int uid, String ticket) {
    return "dsha-bootstrap-" + uid + "-" + ticket;
  }

  /** 只供已核验 APK 的 root 入口调用；对端 UID 必须仍是本应用。 */
  public static String check(
      int uid, String packageName, String ticket, AdbBootstrapPolicy.Action action)
      throws Exception {
    if (ticket == null || !ticket.matches("[a-f0-9]{48}"))
      throw new IOException("NATIVE_AUTHORITY_INVALID");
    try (LocalSocket socket = new LocalSocket()) {
      socket.connect(
          new LocalSocketAddress(name(uid, ticket), LocalSocketAddress.Namespace.ABSTRACT));
      socket.setSoTimeout(2000);
      if (socket.getPeerCredentials().getUid() != uid)
        throw new IOException("NATIVE_AUTHORITY_PEER_MISMATCH");
      android.net.Credentials peer = socket.getPeerCredentials();
      android.content.Context system =
          com.deepseekharness.app.runtime.PrivilegedPackageContext.systemContext();
      android.app.ActivityManager manager =
          (android.app.ActivityManager) system.getSystemService(Context.ACTIVITY_SERVICE);
      java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
          manager.getRunningAppProcesses();
      boolean nativeApp = false;
      if (processes != null)
        for (var process : processes) {
          if (process.pid == peer.getPid()
              && process.uid == uid
              && packageName.equals(process.processName)
              && process.pkgList != null
              && java.util.Arrays.asList(process.pkgList).contains(packageName)) nativeApp = true;
        }
      // 相同 UID 的容器子进程不能单靠自行开 socket 充当原生授权进程。
      if (!nativeApp) throw new IOException("NATIVE_APP_PROCESS_UNVERIFIED");
      android.system.StructStat ownNet = android.system.Os.stat("/proc/self/ns/net");
      android.system.StructStat initNet = android.system.Os.stat("/proc/1/ns/net");
      android.system.StructStat appNet =
          android.system.Os.stat("/proc/" + peer.getPid() + "/ns/net");
      if (ownNet.st_dev != initNet.st_dev
          || ownNet.st_ino != initNet.st_ino
          || ownNet.st_dev != appNet.st_dev
          || ownNet.st_ino != appNet.st_ino)
        throw new IOException("NATIVE_NETWORK_NAMESPACE_MISMATCH");
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeUTF(ticket);
      output.writeUTF(action.name());
      output.flush();
      String response = new DataInputStream(socket.getInputStream()).readUTF();
      if (response.length() > 4096) throw new IOException("NATIVE_AUTHORITY_INVALID");
      JSONObject value = new JSONObject(response);
      if (!value.getBoolean("allowed")) throw new IOException("NATIVE_GRANT_REVOKED");
      return value.getString("publicKey");
    }
  }

  @Override
  public void close() {
    closed = true;
    grant.revoke();
    try {
      channelPrefs.unregisterOnSharedPreferenceChangeListener(changes);
    } catch (RuntimeException ignored) {
    }
    try {
      grantPrefs.unregisterOnSharedPreferenceChangeListener(changes);
    } catch (RuntimeException ignored) {
    }
    try {
      server.close();
    } catch (IOException ignored) {
    }
  }
}
