package com.deepseekharness.app;

import com.deepseekharness.app.util.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** 仅由已核验 APK 的 RootShellMain 调用；通用 shell 不识别此命名动作。 */
final class RootBootstrapExecutor implements AdbBootstrapEngine.Host {
  interface GrantCheck {
    void check() throws Exception;
  }

  interface Store {
    JSONObject load() throws Exception;

    void save(JSONObject state) throws Exception;

    byte[] keys() throws Exception;

    void replaceKeys(
        AdbAuthorizedKeys.Change change, JSONObject state, String phase, GrantCheck grant)
        throws Exception;
  }

  interface SystemAccess {
    String grant(AdbBootstrapPolicy.Action action) throws Exception;

    String read(String path, int limit) throws Exception;

    RootSupportProbe run(List<String> args) throws Exception;

    java.util.Set<String> socketInodes(int pid) throws Exception;

    long now();

    void pause() throws InterruptedException;
  }

  private final SelfPackageIdentity identity;
  private final AdbBootstrapPolicy.Action action;
  private final String tag, boot;
  private final Store files;
  private final SystemAccess system;
  private final JSONObject state;
  private String publicKey;
  private boolean dispatched;

  @Override
  public boolean dispatched() {
    return dispatched;
  }

  private void save() throws Exception {
    dispatched = true;
    files.save(state);
  }

  RootBootstrapExecutor(
      SelfPackageIdentity identity,
      AdbBootstrapPolicy.Action action,
      Store files,
      SystemAccess system)
      throws Exception {
    this.identity = identity;
    this.action = action;
    this.files = files;
    this.system = system;
    tag = "dsha-bootstrap-" + identity.uid;
    boot = system.read("/proc/sys/kernel/random/boot_id", 128).trim();
    if (!Ids.uuid(boot)) throw new IOException("BOOT_ID_UNVERIFIED");
    state = files.load();
  }

  boolean pending() {
    return !(state.optString("pending", "") + state.optString("keyPending", "")).isEmpty();
  }

  @Override
  public void checkGrant(AdbBootstrapPolicy.Action requested) throws Exception {
    publicKey = system.grant(requested);
  }

  @Override
  public void checkPending(AdbBootstrapPolicy.Action requested) throws Exception {
    String pending = state.optString("pending", "") + state.optString("keyPending", "");
    if (!pending.isEmpty()
        && requested != AdbBootstrapPolicy.Action.STATUS
        && requested != AdbBootstrapPolicy.Action.CLOSE
        && requested != AdbBootstrapPolicy.Action.REVOKE)
      throw new AdbBootstrapEngine.Refused("PENDING_ACTION_OBSERVE_AND_CLEANUP");
  }

  @Override
  public void narrow() throws Exception {
    // 两族都要可查询；不以 IPv6 工具缺失代替 IPv6 不存在。
    command(false, "/system/bin/iptables", "-w", "2", "-S", "INPUT");
    command(false, "/system/bin/ip6tables", "-w", "2", "-S", "INPUT");
    verifyListenerScope();
    String observed = listener();
    if (!observed.equals("CLOSED")
        && (!state.optBoolean("portOwned") || !boot.equals(state.optString("portBoot"))))
      throw new AdbBootstrapEngine.Refused("ADB_LISTENER_ALREADY_IN_USE");
    narrowFamily("/system/bin/iptables", "ipv4Owned");
    narrowFamily("/system/bin/ip6tables", "ipv6Owned");
    verifyGuard();
    state.put("pending", "");
    save();
  }

  private List<String> rule(String tool, String operation) {
    List<String> args = new ArrayList<>(Arrays.asList(tool, "-w", "2", operation, "INPUT"));
    if (operation.equals("-I")) args.add("1");
    args.addAll(
        Arrays.asList(
            "!",
            "-i",
            "lo",
            "-p",
            "tcp",
            "--dport",
            "5555",
            "-m",
            "comment",
            "--comment",
            tag,
            "-j",
            "DROP"));
    return args;
  }

  private void narrowFamily(String tool, String field) throws Exception {
    RootSupportProbe exists = raw(rule(tool, "-C"), false);
    if (exists.exitCode != 0 && exists.exitCode != 1)
      throw new IOException("FIREWALL_QUERY_FAILED");
    if (exists.exitCode == 0 && !state.optBoolean(field))
      throw new IOException("FIREWALL_OWNER_UNKNOWN");
    String listing = command(false, tool, "-w", "2", "-S", "INPUT");
    if (exists.exitCode == 0 && AdbBootstrapEngine.firstRuleProtects(listing, tag)) return;
    state.put(field, true).put("pending", "NARROW");
    save();
    require(raw(rule(tool, "-I"), true));
    if (!AdbBootstrapEngine.firstRuleProtects(command(false, tool, "-w", "2", "-S", "INPUT"), tag))
      throw new IOException("FIREWALL_ORDER_UNVERIFIED");
  }

  @Override
  public void verifyGuard() throws Exception {
    for (String tool : new String[] {"/system/bin/iptables", "/system/bin/ip6tables"})
      if (!AdbBootstrapEngine.firstRuleProtects(
          command(false, tool, "-w", "2", "-S", "INPUT"), tag))
        throw new IOException("DUAL_STACK_GUARD_UNVERIFIED");
  }

  private boolean ownsPort() {
    return state.optBoolean("portOwned") && boot.equals(state.optString("portBoot"));
  }

  private void verifyListenerScope() throws Exception {
    String port = command(false, "/system/bin/getprop", "service.adb.tcp.port").trim();
    if (!disabled(port) && !(port.equals("5555") && ownsPort()))
      throw new AdbBootstrapEngine.Refused("OTHER_ADB_PORT_IN_USE");
    if (!disabled(command(false, "/system/bin/getprop", "persist.adb.tcp.port").trim()))
      throw new AdbBootstrapEngine.Refused("PERSISTENT_ADB_PORT_IN_USE");
    // 系统「无线调试」是独立的 ADB TLS 入口。单独给一个码，用户才知道该去关哪个开关，
    // 而不是看到「另一个端口的 ADB 监听」这种无从下手的话；行为仍是拒绝接管。
    if (!disabled(command(false, "/system/bin/getprop", "service.adb.tls.port").trim())
        || command(false, "/system/bin/getprop", "persist.adb.tls_server.enable")
            .trim()
            .equals("1")) throw new AdbBootstrapEngine.Refused("WIRELESS_DEBUG_IN_USE");
    ProcStat daemon = daemonIdentity();
    if (daemon == null) return;
    var inodes = system.socketInodes(daemon.pid);
    AdbBootstrapEngine.verifyDaemonListeners(
        system.read("/proc/net/tcp", 1048576), system.read("/proc/net/tcp6", 1048576), inodes);
    if (!sameDaemon(daemon.pid, daemon.started)) throw new IOException("ADBD_IDENTITY_CHANGED");
  }

  @Override
  public void authorize() throws Exception {
    checkGrant(action);
    verifyGuard();
    verifyListenerScope();
    if (!command(false, "/system/bin/getprop", "ro.adb.secure").trim().equals("1"))
      throw new IOException("ADB_AUTHENTICATION_UNVERIFIED");
    String key = AdbAuthorizedKeys.publicKey(publicKey);
    byte[] table = files.keys();
    String prior = state.optString("clientKey", "");
    if (state.optBoolean("keyOwned") && !key.equals(prior))
      throw new IOException("CLIENT_KEY_CHANGED_REVOKE_FIRST");
    if (state.optBoolean("keyOwned") && AdbAuthorizedKeys.count(table, key) > 0) {
      AdbAuthorizedKeys.revoke(table, state.getString("ownedLine"));
      return;
    }
    AdbAuthorizedKeys.Change change = AdbAuthorizedKeys.authorize(table, key, identity.name);
    state.put("clientKey", key).put("keyOwned", change.inserted).put("ownedLine", change.ownedLine);
    if (!change.inserted) {
      state.put("pending", "");
      save();
      return;
    }
    checkGrant(action);
    verifyGuard();
    dispatched = true;
    files.replaceKeys(
        change,
        state,
        "AUTHORIZE",
        () -> {
          checkGrant(action);
          verifyGuard();
          verifyListenerScope();
        });
    state.put("keyPending", "");
    save();
  }

  @Override
  public void verifyKey() throws Exception {
    checkGrant(action);
    String key = AdbAuthorizedKeys.publicKey(publicKey);
    if (!key.equals(state.optString("clientKey", ""))
        || AdbAuthorizedKeys.count(files.keys(), key) != 1)
      throw new AdbBootstrapEngine.Refused("CLIENT_KEY_NOT_AUTHORIZED");
    if (!command(false, "/system/bin/getprop", "ro.adb.secure").trim().equals("1"))
      throw new AdbBootstrapEngine.Refused("ADB_AUTHENTICATION_UNVERIFIED");
  }

  @Override
  public void open() throws Exception {
    verifyListenerScope();
    String property = command(false, "/system/bin/getprop", "service.adb.tcp.port").trim();
    String observed = listener();
    if (state.optBoolean("portOwned")
        && boot.equals(state.optString("portBoot"))
        && property.equals("5555")
        && observed.equals("OPEN")) return;
    if (!disabled(property) || !observed.equals("CLOSED"))
      throw new IOException("ADB_LISTENER_ALREADY_IN_USE");
    if (!disabled(command(false, "/system/bin/getprop", "persist.adb.tcp.port").trim()))
      throw new IOException("PERSISTENT_ADB_PORT_IN_USE");
    verifyKey();
    verifyGuard();
    state.put("portOwned", true).put("portBoot", boot).put("pending", "OPEN");
    save();
    command(true, "/system/bin/setprop", "service.adb.tcp.port", "5555");
    command(true, "/system/bin/setprop", "ctl.restart", "adbd");
    awaitListener("OPEN");
    verifyListenerScope();
    verifyGuard();
    state.put("pending", "");
    save();
  }

  @Override
  public void close() throws Exception {
    String property = command(false, "/system/bin/getprop", "service.adb.tcp.port").trim();
    if (!disabled(property) && !property.equals("5555"))
      throw new IOException("OTHER_ADB_PORT_IN_USE");
    String observed = listener();
    if ((!observed.equals("CLOSED") || property.equals("5555"))
        && (!state.optBoolean("portOwned") || !boot.equals(state.optString("portBoot"))))
      throw new IOException("ADB_LISTENER_NOT_OWNED");
    String phase = state.optString("pending", "");
    if (phase.equals("CLOSE_RESTART_SENT")) {
      if (!disabled(property) || !observed.equals("CLOSED"))
        throw new IOException("ADBD_RESTART_RESULT_UNKNOWN");
    } else if (!observed.equals("CLOSED") || property.equals("5555")) {
      // 属性和重启各有发送前证据；恢复只能向前推进，不能重发未知步骤。
      if (!phase.equals("CLOSE")) {
        state.put("pending", "CLOSE");
        save();
        command(true, "/system/bin/setprop", "service.adb.tcp.port", "-1");
      } else if (!disabled(property)) {
        throw new IOException("PORT_CLOSE_RESULT_UNKNOWN");
      }
      state.put("pending", "CLOSE_RESTART_SENT");
      save();
      command(true, "/system/bin/setprop", "ctl.restart", "adbd");
      awaitListener("CLOSED");
    }
    state.put("portOwned", false);
    removeGuard("/system/bin/iptables", "ipv4Owned");
    removeGuard("/system/bin/ip6tables", "ipv6Owned");
    // 网络事务与公钥事务独立记录，关闭端口不能覆盖公钥的恢复证据。
    state.put("pending", "");
    save();
  }

  private void removeGuard(String tool, String field) throws Exception {
    if (!state.optBoolean(field)) return;
    if (!listener().equals("CLOSED")) throw new IOException("LISTENER_NOT_CLOSED");
    for (int attempt = 0; attempt < 8; attempt++) {
      RootSupportProbe exists = raw(rule(tool, "-C"), false);
      if (exists.exitCode == 1) {
        state.put(field, false);
        save();
        return;
      }
      if (!exists.succeeded()) throw new IOException("FIREWALL_QUERY_FAILED");
      require(raw(rule(tool, "-D"), true));
    }
    throw new IOException("FIREWALL_CLEANUP_INCOMPLETE");
  }

  @Override
  public void revoke() throws Exception {
    if (!state.optBoolean("keyOwned")) {
      if (!state.optString("clientKey", "").isEmpty()
          && AdbAuthorizedKeys.count(files.keys(), state.optString("clientKey")) > 0)
        throw new IOException("PREEXISTING_KEY_NOT_OWNED");
      return;
    }
    byte[] table = files.keys();
    String phase = state.optString("keyPending", "");
    if (phase.equals("AUTHORIZE")
        && AdbBootstrapJournal.sha256(table).equals(state.optString("keyBeforeSha"))) {
      clearKeyOwnership();
      return;
    }
    int remaining = AdbAuthorizedKeys.count(table, state.getString("clientKey"));
    AdbBootstrapJournal.checkRevokeRecovery(phase, remaining);
    boolean removed = AdbBootstrapJournal.keyAlreadyRemoved(phase, remaining);
    if (!removed) {
      AdbAuthorizedKeys.Change change =
          AdbAuthorizedKeys.revoke(table, state.getString("ownedLine"));
      ProcStat daemon = daemonIdentity();
      if (daemon != null)
        state
            .put("revokePid", daemon.pid)
            .put("revokeStarted", daemon.started)
            .put("revokeBoot", boot);
      checkGrant(action);
      dispatched = true;
      files.replaceKeys(change, state, "REVOKE", () -> checkGrant(action));
      phase = "REVOKE";
    }
    if (phase.equals("REVOKE")) {
      // 公钥落盘后重新绑定当前 daemon，避免删除前的进程恰好已被替换。
      ProcStat current = daemonIdentity();
      if (current == null) {
        if (AdbAuthorizedKeys.count(files.keys(), state.getString("clientKey")) != 0)
          throw new IOException("KEY_REAPPEARED_AFTER_REVOKE");
        clearKeyOwnership();
        return;
      }
      state
          .put("revokePid", current.pid)
          .put("revokeStarted", current.started)
          .put("revokeBoot", boot);
      save();
    }
    int pid = state.getInt("revokePid");
    long started = state.getLong("revokeStarted");
    if (state.optString("revokeBoot", "").isEmpty())
      throw new IOException("ADBD_RESTART_IDENTITY_UNVERIFIED");
    if (boot.equals(state.optString("revokeBoot")) && sameDaemon(pid, started)) {
      if (!AdbBootstrapJournal.restartMayBeSent(phase))
        throw new IOException("ADBD_RESTART_RESULT_UNKNOWN");
      // 先记下发送意图；结果未知时后续撤销只观察，不能再发送同一重启。
      state.put("keyPending", "REVOKE_RESTART_SENT");
      save();
      command(true, "/system/bin/setprop", "ctl.restart", "adbd");
      long deadline = system.now() + 6000;
      while (sameDaemon(pid, started) && system.now() < deadline) {
        checkGrant(action);
        system.pause();
      }
      if (sameDaemon(pid, started)) throw new IOException("ADBD_RESTART_RESULT_UNKNOWN");
    }
    if (AdbAuthorizedKeys.count(files.keys(), state.getString("clientKey")) != 0)
      throw new IOException("KEY_REAPPEARED_AFTER_REVOKE");
    clearKeyOwnership();
  }

  private void clearKeyOwnership() throws Exception {
    state.put("keyOwned", false).put("ownedLine", "").put("keyPending", "");
    save();
  }

  private ProcStat daemonIdentity() throws Exception {
    String uid = command(false, "/system/bin/ps", "-A", "-o", "PID,UID,NAME");
    String context = command(false, "/system/bin/ps", "-A", "-o", "PID,CONTEXT,NAME");
    if (AdbdIdentity.absent(uid, context)) {
      String againUid = command(false, "/system/bin/ps", "-A", "-o", "PID,UID,NAME");
      String againContext = command(false, "/system/bin/ps", "-A", "-o", "PID,CONTEXT,NAME");
      if (AdbdIdentity.absent(againUid, againContext)) return null;
      throw new IOException("ADBD_IDENTITY_CHANGED");
    }
    AdbdIdentity.Evidence evidence = AdbdIdentity.assess(uid, 0, context, 0);
    if (evidence.pid <= 1 || evidence.level == AdbdIdentity.Level.UNKNOWN)
      throw new IOException("ADBD_IDENTITY_UNVERIFIED");
    ProcStat stat =
        ProcStat.parse(system.read("/proc/" + evidence.pid + "/stat", 8192), evidence.pid);
    if (stat == null || stat.exited()) throw new IOException("ADBD_IDENTITY_UNVERIFIED");
    String againUid = command(false, "/system/bin/ps", "-A", "-o", "PID,UID,NAME");
    String againContext = command(false, "/system/bin/ps", "-A", "-o", "PID,CONTEXT,NAME");
    AdbdIdentity.Evidence again = AdbdIdentity.assess(againUid, 0, againContext, 0);
    String comm = system.read("/proc/" + stat.pid + "/comm", 128).trim();
    if (again.pid != stat.pid
        || again.uid != evidence.uid
        || !again.selinuxContext.equals(evidence.selinuxContext)
        || !comm.equals("adbd")
        || !sameDaemon(stat.pid, stat.started)) throw new IOException("ADBD_IDENTITY_CHANGED");
    return stat;
  }

  private boolean sameDaemon(int pid, long started) throws Exception {
    try {
      ProcStat now = ProcStat.parse(system.read("/proc/" + pid + "/stat", 8192), pid);
      if (now == null) throw new IOException("ADBD_IDENTITY_UNVERIFIED");
      return now.started == started && !now.exited();
    } catch (java.io.FileNotFoundException missing) {
      return false;
    }
  }

  @Override
  public AdbBootstrapEngine.Snapshot observe() throws Exception {
    String keyState = "UNKNOWN", observed = "UNKNOWN", guard = "UNVERIFIED";
    try {
      String key = state.optString("clientKey", "");
      if (key.isEmpty()) keyState = "NOT_REGISTERED";
      else {
        byte[] table = files.keys();
        int count = AdbAuthorizedKeys.count(table, key);
        if (count == 0) keyState = "ABSENT";
        else if (count == 1 && !state.optBoolean("keyOwned")) keyState = "EXISTING";
        else if (count == 1) {
          AdbAuthorizedKeys.revoke(table, state.getString("ownedLine"));
          keyState = "OWNED";
        }
      }
    } catch (InterruptedException interrupted) {
      throw interrupted;
    } catch (Exception unavailable) {
    }
    if (!state.optString("keyPending", "").isEmpty()) keyState = "UNKNOWN";
    try {
      observed = listener();
    } catch (InterruptedException interrupted) {
      throw interrupted;
    } catch (Exception unavailable) {
    }
    try {
      String v4 = command(false, "/system/bin/iptables", "-w", "2", "-S", "INPUT");
      String v6 = command(false, "/system/bin/ip6tables", "-w", "2", "-S", "INPUT");
      if (AdbBootstrapEngine.firewallListingComplete(v4)
          && AdbBootstrapEngine.firewallListingComplete(v6)) {
        guard = "UNPROTECTED";
        if (AdbBootstrapEngine.firstRuleProtects(v4, tag)
            && AdbBootstrapEngine.firstRuleProtects(v6, tag)) {
          verifyListenerScope();
          guard = "PROTECTED";
        }
      }
    } catch (InterruptedException interrupted) {
      throw interrupted;
    } catch (Exception unavailable) {
    }
    return new AdbBootstrapEngine.Snapshot(keyState, observed, guard);
  }

  private static boolean disabled(String property) {
    return property.isEmpty() || property.equals("0") || property.equals("-1");
  }

  private String listener() throws Exception {
    return AdbBootstrapEngine.listener(
        system.read("/proc/net/tcp", 1048576), system.read("/proc/net/tcp6", 1048576));
  }

  private void awaitListener(String expected) throws Exception {
    long deadline = system.now() + 6000;
    do {
      checkGrant(action);
      if (listener().equals(expected)) return;
      system.pause();
    } while (system.now() < deadline);
    throw new IOException("LISTENER_STATE_UNVERIFIED");
  }

  private String command(boolean mutation, String... args) throws Exception {
    return require(raw(Arrays.asList(args), mutation));
  }

  private static String require(RootSupportProbe value) throws IOException {
    if (!value.succeeded())
      throw new IOException(value.exitCode < 0 ? "COMMAND_RESULT_UNKNOWN" : "COMMAND_FAILED");
    return value.body;
  }

  private RootSupportProbe raw(List<String> args, boolean mutation) throws Exception {
    if (mutation) {
      checkGrant(action);
      dispatched = true;
    }
    return system.run(args);
  }
}
