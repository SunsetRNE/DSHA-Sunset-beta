package com.deepseekharness.app;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.*;
import com.deepseekharness.app.util.AdbBootstrapPolicy.Action;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** 执行生产事务代码；替换系统 I/O，无 Android、shell、网络或真实文件访问。 */
public final class RootBootstrapExecutorTest {
  private static final String BOOT = "11111111-1111-4111-8111-111111111111";
  private static final String HEADER =
      "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
  private static final String RULE =
      "-A INPUT ! -i lo -p tcp -m tcp --dport 5555 -m comment --comment dsha-bootstrap-10281 -j DROP\n";
  private static final String KEY = com.deepseekharness.app.util.AdbAuthorizedKeysTest.key(3);

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static final class Device
      implements RootBootstrapExecutor.Store, RootBootstrapExecutor.SystemAccess {
    JSONObject journal =
        new JSONObject().put("schema", 1).put("package", "com.dsh.client").put("uid", 10281);
    byte[] table = bytes("other-key owner\n");
    String port = "", persistent = "", tls = "", tlsEnabled = "0", tcp = HEADER, tcp6 = HEADER;
    boolean v4,
        v6,
        grant = true,
        daemon = true,
        failPs,
        failV6,
        failRestart,
        failReplace,
        revokeBeforeCommit,
        /** 模拟只接受 `-w`（不接受 `-w 2`）的 iptables。 */
        rejectWaitSeconds,
        /** 模拟 iptables 完全不可用（例如权限被拒）。 */
        firewallBroken,
        /** 模拟插入规则失败，用来检查失败原文是否带出去。 */
        failInsert,
        /** 模拟本机 `ps` 不认 LABEL 列（只认 CONTEXT 的老构建）。 */
        rejectLabelColumns,
        /** 模拟本机 `ps` 认 CONTEXT 列（默认真机行为：只认 LABEL）。 */
        acceptContextColumns;
    int restarts, replacements, saves;
    long time, started = 100;
    final List<String> writes = new ArrayList<>();
    final Set<String> inodes = new HashSet<>(Set.of("123"));

    Device() throws Exception {}

    public JSONObject load() throws Exception {
      return new JSONObject(journal.toString());
    }

    public void save(JSONObject state) throws Exception {
      Map<String, Object> values = new HashMap<>();
      Iterator<String> keys = state.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        values.put(key, state.get(key));
      }
      AdbBootstrapJournal.validate(values, "com.dsh.client", 10281);
      journal = new JSONObject(state.toString());
      saves++;
    }

    public byte[] keys() {
      return table.clone();
    }

    /** D1：模拟平台还不能安全完成首次写入（例如系统授权表缺失）。 */
    boolean keyTableReady = true;

    public void preflightKeyTable() throws Exception {
      if (!keyTableReady)
        throw new AdbBootstrapEngine.Refused(AdbKeyTablePreflight.MISSING_UNSUPPORTED);
    }

    public void replaceKeys(
        AdbAuthorizedKeys.Change change,
        JSONObject state,
        String phase,
        RootBootstrapExecutor.GrantCheck check)
        throws Exception {
      state
          .put("keyPending", phase)
          .put("keyBeforeSha", AdbBootstrapJournal.sha256(change.before))
          .put("keyAfterSha", AdbBootstrapJournal.sha256(change.after))
          .put("keyBackup", "keys-" + BOOT + ".before");
      save(state);
      if (revokeBeforeCommit) grant = false;
      check.check();
      if (failReplace) throw new IOException("KEY_TABLE_CHANGED");
      assertArrayEquals(table, change.before);
      replacements++;
      table = change.after.clone();
      writes.add("keys:" + phase);
    }

    public String grant(Action action) throws Exception {
      if (!grant) throw new AdbBootstrapEngine.Refused("NATIVE_GRANT_REVOKED");
      return KEY;
    }

    public String read(String path, int limit) throws Exception {
      if (path.equals("/proc/sys/kernel/random/boot_id")) return BOOT;
      if (path.equals("/proc/net/tcp")) return tcp;
      if (path.equals("/proc/net/tcp6")) return tcp6;
      if (path.equals("/proc/321/comm")) return "adbd\n";
      if (path.equals("/proc/321/stat")) {
        if (!daemon) throw new java.io.FileNotFoundException();
        return "321 (adbd) S 1 321 321" + " 0".repeat(15) + " " + started + " 0 0\n";
      }
      throw new AssertionError("unexpected read: " + path);
    }

    public Set<String> socketInodes(int pid) {
      assertEquals(321, pid);
      return inodes;
    }

    public long now() {
      return time;
    }

    public void pause() {
      time += 150;
    }

    public RootSupportProbe run(List<String> args) throws Exception {
      String tool = args.get(0), body = "";
      int exit = 0;
      if (tool.endsWith("/getprop")) {
        body =
            switch (args.get(1)) {
              case "service.adb.tcp.port" -> port;
              case "persist.adb.tcp.port" -> persistent;
              case "service.adb.tls.port" -> tls;
              case "persist.adb.tls_server.enable" -> tlsEnabled;
              case "ro.adb.secure" -> "1";
              default -> throw new AssertionError(args);
            };
      } else if (tool.endsWith("/setprop")) {
        assertTrue(grant);
        writes.add(String.join(" ", args));
        if (args.get(1).equals("service.adb.tcp.port")) port = args.get(2);
        else if (args.get(1).equals("ctl.restart")) {
          restarts++;
          if (failRestart) throw new IOException("COMMAND_RESULT_UNKNOWN");
          started++;
          tcp = port.equals("5555") ? HEADER + row(false, "15B3") : HEADER;
        } else throw new AssertionError(args);
      } else if (tool.endsWith("/ps")) {
        if (failPs) return RootSupportProbe.parse("permission denied\n[EXIT=1]");
        String spec = args.get(3);
        String adbdPid = daemon ? "321 " : "";
        // 真机里一定存在名字带空格的内核线程：三列表格不能因此判成不可信。
        if (spec.contains("UID"))
          body =
              "PID UID NAME\n1 0 init\n692 0 [irq/261-q6v5 wdog]\n"
                  + (daemon ? "321 0 adbd\n" : "");
        else if (spec.contains("LABEL")) {
          // 真机 Android 16（toybox）：LABEL 可用，CONTEXT 直接 bad -o 退出。
          if (rejectLabelColumns)
            return RootSupportProbe.parse("ps: bad -o 'PID,LABEL,NAME'\n[EXIT=1]");
          body =
              "PID LABEL NAME\n1 u:r:init:s0 init\n692 u:r:kernel:s0 [irq/261-q6v5 wdog]\n"
                  + adbdPid
                  + (daemon ? "u:r:adbd:s0 adbd\n" : "");
        } else if (spec.contains("CONTEXT")) {
          if (!acceptContextColumns)
            return RootSupportProbe.parse("ps: bad -o 'PID,CONTEXT,NAME'\n[EXIT=1]");
          body =
              "PID CONTEXT NAME\n1 u:r:init:s0 init\n"
                  + "692 u:r:kernel:s0 [irq/261-q6v5 wdog]\n"
                  + adbdPid
                  + (daemon ? "u:r:adbd:s0 adbd\n" : "");
        } else throw new AssertionError(args);
      } else if (tool.endsWith("tables")) {
        boolean ipv6 = tool.endsWith("ip6tables"), exists = ipv6 ? v6 : v4;
        int wait = args.indexOf("-w");
        if (firewallBroken) {
          body = "iptables: Permission denied (are you root?)";
          exit = 1;
          return RootSupportProbe.parse(body + "\n[EXIT=" + exit + "]");
        }
        if (rejectWaitSeconds
            && wait >= 0
            && wait + 1 < args.size()
            && args.get(wait + 1).equals("2")) {
          body = "iptables v1.8.9 (legacy): unrecognized option '-w 2'";
          exit = 2;
          return RootSupportProbe.parse(body + "\n[EXIT=" + exit + "]");
        }
        String op = null;
        for (String candidate : new String[] {"-S", "-C", "-I", "-D"})
          if (args.contains(candidate)) {
            op = candidate;
            break;
          }
        if (op == null) throw new AssertionError(args);
        if (op.equals("-S")) body = "-P INPUT ACCEPT\n" + (exists ? RULE : "");
        else if (op.equals("-C")) exit = exists ? 0 : 1;
        else if (op.equals("-I") || op.equals("-D")) {
          assertTrue(grant);
          if (op.equals("-I") && failInsert) {
            body = "iptables: Permission denied";
            exit = 1;
          } else {
            writes.add((ipv6 ? "v6:" : "v4:") + op);
            if (ipv6 && failV6) throw new IOException("COMMAND_RESULT_UNKNOWN");
            if (ipv6) v6 = op.equals("-I");
            else v4 = op.equals("-I");
          }
        } else throw new AssertionError(args);
      } else throw new AssertionError(args);
      return RootSupportProbe.parse(body + "\n[EXIT=" + exit + "]");
    }

    AdbBootstrapEngine.Outcome execute(Action action) throws Exception {
      SelfPackageIdentity identity =
          SelfPackageIdentity.launch(
              "com.dsh.client",
              10281,
              "/data/app/com.dsh.client-1/base.apk",
              "/data/app/com.dsh.client-1/base.apk");
      last = new RootBootstrapExecutor(identity, action, this, this);
      return AdbBootstrapEngine.execute(action, last);
    }

    RootBootstrapExecutor last;

    /**
     * 复刻 {@link RootBootstrapNative#execute} 的响应编码与宿主消费路径。
     *
     * <p>完成判据与编解码都在生产接缝 {@link AdbBootstrapReply} 里；这里只负责把它接到
     * 假设备的事务结果上，让「生产响应 → 控制器归约」的组合可以在主机测试里跑通。
     */
    AdbBootstrapReply reply(Action action) throws Exception {
      AdbBootstrapEngine.Outcome result = execute(action);
      boolean verified = false;
      String network = "";
      String key = "";
      try {
        JSONObject durable = last.durableRecord();
        verified = true;
        network = durable.optString("pending", "");
        key = durable.optString("keyPending", "");
      } catch (Exception unreadable) {
        verified = false;
      }
      String code = result.code;
      if (verified && result.status.equals("OK") && (!network.isEmpty() || !key.isEmpty()))
        code = "PENDING_ACTION";
      return AdbBootstrapReply.parse(
          AdbBootstrapReply.encode(
              result.status,
              code,
              result.snapshot.key,
              result.snapshot.listener,
              result.snapshot.guard,
              result.evidence,
              verified,
              network,
              key));
    }

    void authorized() throws Exception {
      assertEquals("OK", execute(Action.AUTHORIZE).status);
    }

    void opened() throws Exception {
      authorized();
      assertEquals("OK", execute(Action.OPEN).status);
    }
  }

  private static String row(boolean ipv6, String port) {
    String address = ipv6 ? "0".repeat(32) : "00000000";
    return "0: "
        + address
        + ":"
        + port
        + " "
        + address
        + ":0000 0A 0000:0000 00:0000 0000 0 0 123\n";
  }

  @Test
  public void fiveActionsUseProductionTransactionAndStatusWritesNothing() throws Exception {
    Device d = new Device();
    assertEquals("OK", d.execute(Action.NARROW).status);
    d.opened();
    assertTrue(d.writes.indexOf("v6:-I") < d.writes.indexOf("keys:AUTHORIZE"));
    int saves = d.saves, writes = d.writes.size();
    assertEquals("OK", d.execute(Action.STATUS).status);
    assertEquals(saves, d.saves);
    assertEquals(writes, d.writes.size());
    assertEquals("OK", d.execute(Action.CLOSE).status);
    assertFalse(d.v4 || d.v6);
    assertEquals("OK", d.execute(Action.REVOKE).status);
    assertArrayEquals(bytes("other-key owner\n"), d.table);
  }

  @Test
  public void otherDaemonPortsRefuseBeforeAnyWriteOnEitherStack() throws Exception {
    for (boolean ipv6 : new boolean[] {false, true}) {
      Device d = new Device();
      if (ipv6) d.tcp6 += row(true, "15B4");
      else d.tcp += row(false, "15B4");
      assertEquals("REFUSED", d.execute(Action.AUTHORIZE).status);
      assertTrue(d.writes.isEmpty());
      assertEquals(0, d.replacements);
    }
    for (String field : new String[] {"port", "persistent", "tls"}) {
      Device d = new Device();
      if (field.equals("port")) d.port = "5556";
      if (field.equals("persistent")) d.persistent = "5555";
      if (field.equals("tls")) d.tls = "37123";
      assertEquals("REFUSED", d.execute(Action.AUTHORIZE).status);
      assertTrue(d.writes.isEmpty());
    }
  }

  @Test
  public void iptablesWithoutSecondsWaitStillAuthorizes() throws Exception {
    // 本机实测 `-w 2` 被拒：适配后退到 `-w`，链子必须照常走完，而不是报 COMMAND_FAILED。
    Device d = new Device();
    d.rejectWaitSeconds = true;
    assertEquals("OK", d.execute(Action.AUTHORIZE).status);
    assertTrue(d.v4 && d.v6);
  }

  @Test
  public void unusableFirewallRefusesWithCommandEvidence() throws Exception {
    Device d = new Device();
    d.firewallBroken = true;
    var outcome = d.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", outcome.status);
    assertEquals("GUARD_TOOL_UNAVAILABLE", outcome.code);
    assertTrue(outcome.evidence.contains("/system/bin/iptables"));
    assertTrue(outcome.evidence.contains("(exit=1)"));
    assertTrue(outcome.evidence.contains("Permission denied"));
    assertTrue(d.writes.isEmpty());
    assertEquals(0, d.replacements);
  }

  @Test
  public void failingMutationCarriesArgvExitAndOutput() throws Exception {
    Device d = new Device();
    d.failInsert = true;
    var outcome = d.execute(Action.AUTHORIZE);
    // 已经派发过写入：结果按未知处理，但失败原文必须带出来。
    assertEquals("UNKNOWN", outcome.status);
    assertEquals("COMMAND_FAILED", outcome.code);
    assertTrue(outcome.evidence.contains("-I"));
    assertTrue(outcome.evidence.contains("(exit=1)"));
    assertTrue(outcome.evidence.contains("Permission denied"));
  }

  @Test
  public void repeatedAuthorizeKeepsTheOwnedKey() throws Exception {
    // 幂等：重跑 AUTHORIZE 不能撤销自己刚写入的公钥，否则第二次链子的会话核验必然失败。
    Device d = new Device();
    d.authorized();
    assertEquals(1, AdbAuthorizedKeys.count(d.table, KEY));
    var again = d.execute(Action.AUTHORIZE);
    assertEquals("OK", again.status);
    assertEquals(1, AdbAuthorizedKeys.count(d.table, KEY));
    assertTrue(d.journal.optBoolean("keyOwned"));
    assertEquals("OK", d.execute(Action.REVOKE).status);
    assertEquals(0, AdbAuthorizedKeys.count(d.table, KEY));
  }

  @Test
  public void psContextColumnsFallBackToLabelOnToybox() throws Exception {
    // 真机 Android 16 复现：`ps -o PID,CONTEXT,NAME` 报 bad -o；换 LABEL 后授权必须成功。
    Device d = new Device();
    assertEquals("OK", d.execute(Action.AUTHORIZE).status);
    assertTrue(d.v4 && d.v6);
  }

  @Test
  public void psContextStillWorksOnBuildsThatOnlyAcceptContext() throws Exception {
    Device d = new Device();
    d.rejectLabelColumns = true;
    d.acceptContextColumns = true;
    assertEquals("OK", d.execute(Action.AUTHORIZE).status);
    assertTrue(d.v4 && d.v6);
  }

  @Test
  public void unsupportedPsColumnsRefuseWithBothCommandOutputs() throws Exception {
    Device d = new Device();
    d.rejectLabelColumns = true;
    var outcome = d.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", outcome.status);
    assertEquals("ADBD_SNAPSHOT_UNSUPPORTED", outcome.code);
    assertTrue(outcome.evidence.contains("PID,LABEL,NAME"));
    assertTrue(outcome.evidence.contains("PID,CONTEXT,NAME"));
    assertTrue(outcome.evidence.contains("bad -o"));
    assertFalse(outcome.evidence.contains("[EXIT="));
    assertTrue(d.writes.isEmpty());
    assertEquals(0, d.replacements);
  }

  @Test
  public void systemWirelessDebuggingGetsItsOwnCodeAndNeverTakesOverForeignPorts()
      throws Exception {
    // 无线调试（ADB TLS 入口）单独一个码，用户才知道该去关哪个开关。
    Device tlsPort = new Device();
    tlsPort.tls = "37123";
    var byTlsPort = tlsPort.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", byTlsPort.status);
    assertEquals("WIRELESS_DEBUG_IN_USE", byTlsPort.code);
    assertTrue(tlsPort.writes.isEmpty());
    assertEquals(0, tlsPort.replacements);

    Device tlsFlag = new Device();
    tlsFlag.tlsEnabled = "1";
    var byTlsFlag = tlsFlag.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", byTlsFlag.status);
    assertEquals("WIRELESS_DEBUG_IN_USE", byTlsFlag.code);
    assertTrue(tlsFlag.writes.isEmpty());

    // 其它来源的 5555 端口仍然只报「别的监听」，不能被误标成无线调试。
    Device foreign = new Device();
    foreign.port = "5556";
    var refused = foreign.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", refused.status);
    assertEquals("OTHER_ADB_PORT_IN_USE", refused.code);
    assertTrue(foreign.writes.isEmpty());
  }

  /**
   * F06 / D1：平台还不能安全完成首次写入时，AUTHORIZE 必须在**任何**设备写入之前明确拒绝，
   * 只读与清理路径不受影响。这不是首次建表能力的实现（D2 仍未完成）。
   */
  @Test
  public void unsupportedFirstWriteRefusesBeforeAnyDeviceWrite() throws Exception {
    Device d = new Device();
    d.keyTableReady = false;
    var outcome = d.execute(Action.AUTHORIZE);
    assertEquals("REFUSED", outcome.status);
    assertEquals(AdbKeyTablePreflight.MISSING_UNSUPPORTED, outcome.code);
    assertTrue("防火墙 / 公钥 / 监听写入必须为零", d.writes.isEmpty());
    assertEquals(0, d.replacements);
    assertFalse(d.v4 || d.v6);
    // 只读与清理仍需可用：预检不能把 STATUS / CLOSE / REVOKE 一起挡死。
    assertEquals("OK", d.execute(Action.STATUS).status);
    assertEquals("OK", d.execute(Action.CLOSE).status);
    assertEquals("OK", d.execute(Action.REVOKE).status);
  }

  @Test
  public void secondStackFailureCannotAuthorizeAndKeepsFirstStackEvidence() throws Exception {
    Device d = new Device();
    d.failV6 = true;
    assertEquals("UNKNOWN", d.execute(Action.AUTHORIZE).status);
    assertEquals(0, d.replacements);
    assertTrue(d.v4);
    assertFalse(d.v6);
    assertEquals("NARROW", d.journal.getString("pending"));
    assertEquals("REFUSED", d.execute(Action.OPEN).status);
  }

  /**
   * F03 回归：IPv6 写失败留下持久网络未决后，一次「无事可做」的 REVOKE 成功
   * 不能把本地未决洗成已确认；只有 CLOSE 真正清理完两个阶段才允许解除。
   */
  @Test
  public void noOpRevokeCannotClearTheDurableNetworkPending() throws Exception {
    Device d = new Device();
    d.failV6 = true;
    boolean unresolved = false;

    AdbBootstrapReply authorize = d.reply(Action.AUTHORIZE);
    assertEquals("UNKNOWN", authorize.status);
    assertTrue("响应必须独立报告未决状态已核实", authorize.pendingVerified);
    assertEquals("NARROW", authorize.networkPending);
    assertEquals("", authorize.keyPending);
    unresolved =
        AdbBootstrapJournal.unresolved(unresolved, authorize, authorize.status, authorize.code);
    assertTrue(unresolved);

    // 显式 REVOKE：尚无 keyOwned，动作本身是成功的无操作，但网络事务仍未结束。
    AdbBootstrapReply revoke = d.reply(Action.REVOKE);
    assertEquals("OK", revoke.status);
    assertTrue(revoke.pendingVerified);
    assertEquals("NARROW", revoke.networkPending);
    unresolved = AdbBootstrapJournal.unresolved(unresolved, revoke, revoke.status, revoke.code);
    assertTrue("无操作 REVOKE 不得清除持久未决", unresolved);

    // 只有真正清理掉两族规则之后，可信响应才允许解除。
    d.failV6 = false;
    AdbBootstrapReply close = d.reply(Action.CLOSE);
    assertEquals("OK", close.status);
    assertEquals("", close.networkPending);
    assertEquals("", close.keyPending);
    unresolved = AdbBootstrapJournal.unresolved(unresolved, close, close.status, close.code);
    assertFalse(unresolved);
  }

  @Test
  public void closeUnknownRestartIsObservedAcrossNewExecutorWithoutResend() throws Exception {
    Device d = new Device();
    d.opened();
    d.failRestart = true;
    assertEquals("UNKNOWN", d.execute(Action.CLOSE).status);
    int restarts = d.restarts;
    assertEquals("CLOSE_RESTART_SENT", d.journal.getString("pending"));
    assertEquals("ADBD_RESTART_RESULT_UNKNOWN", d.execute(Action.CLOSE).code);
    assertEquals(restarts, d.restarts);
    assertTrue(d.v4 && d.v6);
    d.tcp = HEADER;
    assertEquals("OK", d.execute(Action.CLOSE).status);
    assertEquals(restarts, d.restarts);
  }

  @Test
  public void revokeUnknownRestartNeverResendsEvenWhenKeyReappears() throws Exception {
    Device d = new Device();
    d.authorized();
    byte[] original = d.table.clone();
    d.failRestart = true;
    assertEquals("UNKNOWN", d.execute(Action.REVOKE).status);
    int restarts = d.restarts, changes = d.replacements;
    assertEquals("ADBD_RESTART_RESULT_UNKNOWN", d.execute(Action.REVOKE).code);
    d.table = original;
    assertEquals("KEY_REAPPEARED_AFTER_REVOKE", d.execute(Action.REVOKE).code);
    assertEquals(restarts, d.restarts);
    assertEquals(changes, d.replacements);
    assertEquals("REVOKE_RESTART_SENT", d.journal.getString("keyPending"));
  }

  @Test
  public void daemonAlreadyStoppedStillAllowsSelectiveRevoke() throws Exception {
    Device d = new Device();
    d.authorized();
    d.daemon = false;
    assertEquals("OK", d.execute(Action.REVOKE).status);
    assertArrayEquals(bytes("other-key owner\n"), d.table);
    assertEquals(0, d.restarts);
    d = new Device();
    d.authorized();
    d.failPs = true;
    int changes = d.replacements;
    assertEquals("COMMAND_FAILED", d.execute(Action.REVOKE).code);
    assertEquals(changes, d.replacements);
  }

  @Test
  public void failedKeyCommitAndRevokedGrantRetainRecoveryEvidence() throws Exception {
    for (boolean revoke : new boolean[] {false, true}) {
      Device d = new Device();
      d.failReplace = !revoke;
      d.revokeBeforeCommit = revoke;
      assertEquals("UNKNOWN", d.execute(Action.AUTHORIZE).status);
      assertEquals(0, d.replacements);
      assertEquals("AUTHORIZE", d.journal.getString("keyPending"));
      d.grant = true;
      assertEquals("OK", d.execute(Action.REVOKE).status);
      assertEquals(0, d.restarts);
    }
  }

  @Test
  public void conflictsCannotBeReportedOwnedAndDoNotHideListenerEvidence() throws Exception {
    Device d = new Device();
    d.authorized();
    d.table =
        bytes(
            new String(d.table, StandardCharsets.US_ASCII).replace("dsha:com.dsh.client", "other"));
    var state = d.execute(Action.STATUS).snapshot;
    assertEquals("UNKNOWN", state.key);
    assertEquals("CLOSED", state.listener);
    assertEquals("KEY_OWNERSHIP_CONFLICT", d.execute(Action.REVOKE).code);
  }

  @Test
  public void existingKeyIsNotClaimedAndCleanupDoesNotEraseKeyPending() throws Exception {
    Device d = new Device();
    d.table = bytes("  " + KEY + " original-owner\n");
    assertEquals("OK", d.execute(Action.AUTHORIZE).status);
    assertFalse(d.journal.getBoolean("keyOwned"));
    assertEquals(0, d.replacements);
    assertEquals("PREEXISTING_KEY_NOT_OWNED", d.execute(Action.REVOKE).code);
    d = new Device();
    d.failReplace = true;
    d.execute(Action.AUTHORIZE);
    assertEquals("AUTHORIZE", d.journal.getString("keyPending"));
    d.execute(Action.CLOSE);
    assertEquals("AUTHORIZE", d.journal.getString("keyPending"));
  }
}
