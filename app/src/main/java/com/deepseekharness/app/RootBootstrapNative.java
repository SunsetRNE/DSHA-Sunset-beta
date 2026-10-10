package com.deepseekharness.app;

import com.deepseekharness.app.runtime.PrivilegedPackageContext;
import com.deepseekharness.app.util.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** 已核验 APK 的固定原生 I/O 入口；不从请求接收命令或设备路径。 */
final class RootBootstrapNative implements RootBootstrapExecutor.SystemAccess {
  private final SelfPackageIdentity identity;
  private final String ticket;

  private RootBootstrapNative(SelfPackageIdentity identity, String ticket) {
    this.identity = identity;
    this.ticket = ticket;
  }

  static String execute(
      SelfPackageIdentity identity, AdbBootstrapPolicy.Action action, String ticket) {
    boolean dispatched = false;
    try {
      RootBootstrapNative system = new RootBootstrapNative(identity, ticket);
      system.grant(action);
      try (RootBootstrapFiles files =
          new RootBootstrapFiles(identity, action != AdbBootstrapPolicy.Action.STATUS)) {
        RootBootstrapExecutor host = new RootBootstrapExecutor(identity, action, files, system);
        dispatched = true;
        AdbBootstrapEngine.Outcome result = AdbBootstrapEngine.execute(action, host);
        return new JSONObject()
                .put("status", result.status)
                .put(
                    "code",
                    action == AdbBootstrapPolicy.Action.STATUS
                            && host.pending()
                            && result.status.equals("OK")
                        ? "PENDING_ACTION"
                        : result.code)
                .put("keyState", result.snapshot.key)
                .put("listenerState", result.snapshot.listener)
                .put("guardState", result.snapshot.guard)
                .toString()
            + "\n[EXIT="
            + (result.status.equals("OK") ? 0 : result.status.equals("REFUSED") ? 126 : 125)
            + "]";
      }
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      String code = error.getMessage();
      if (code == null || !code.matches("[A-Z0-9_]{1,96}")) code = "BOOTSTRAP_PREFLIGHT_FAILED";
      return "{\"status\":\""
          + (dispatched ? "UNKNOWN" : "REFUSED")
          + "\",\"code\":\""
          + code
          + "\",\"keyState\":\"UNKNOWN\",\"listenerState\":\"UNKNOWN\",\"guardState\":\"UNKNOWN\"}\n[EXIT="
          + (dispatched ? 125 : 126)
          + "]";
    }
  }

  @Override
  public String grant(AdbBootstrapPolicy.Action action) throws Exception {
    SelfPackageIdentity current =
        PrivilegedPackageContext.launchIdentity(identity.name, identity.uid);
    if (!current.source.equals(identity.source)) throw new IOException("APK_CHANGED");
    try {
      return RootBootstrapAuthority.check(identity.uid, identity.name, ticket, action);
    } catch (IOException unavailable) {
      throw new AdbBootstrapEngine.Refused("NATIVE_GRANT_UNAVAILABLE");
    }
  }

  @Override
  public String read(String path, int limit) throws Exception {
    try {
      return RootBootstrapFiles.text(path, limit);
    } catch (android.system.ErrnoException error) {
      if (error.errno == android.system.OsConstants.ENOENT)
        throw new FileNotFoundException("PROC_ENTRY_ABSENT");
      throw new IOException("PROC_READ_UNVERIFIED", error);
    }
  }

  @Override
  public Set<String> socketInodes(int pid) throws Exception {
    String base = "/proc/" + pid + "/fd/";
    String[] names = new File(base).list();
    if (names == null || names.length > 8192) throw new IOException("ADBD_SOCKETS_UNVERIFIED");
    Set<String> result = new HashSet<>();
    for (String name : names) {
      if (!name.matches("[0-9]+")) throw new IOException("ADBD_SOCKETS_UNVERIFIED");
      String target = android.system.Os.readlink(base + name);
      if (target.matches("socket:\\[[0-9]+\\]"))
        result.add(target.substring(8, target.length() - 1));
    }
    return result;
  }

  @Override
  public long now() {
    return android.os.SystemClock.elapsedRealtime();
  }

  @Override
  public void pause() throws InterruptedException {
    Thread.sleep(150);
  }

  @Override
  public RootSupportProbe run(List<String> args) throws Exception {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    try {
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, 3000, 262144, Compat::destroy);
      if (result.timedOut || result.truncated) throw new IOException("COMMAND_RESULT_UNKNOWN");
      return RootSupportProbe.parse(result.output + "\n[EXIT=" + result.exitCode + "]");
    } finally {
      Compat.destroy(process);
    }
  }
}
