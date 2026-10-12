package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.List;

/** 固定自举状态机；系统适配器实现实际 I/O，测试用假设备验证顺序与未知结果不重放。 */
public final class AdbBootstrapEngine {
  public interface Host {
    void checkGrant(AdbBootstrapPolicy.Action action) throws Exception;

    void checkPending(AdbBootstrapPolicy.Action action) throws Exception;

    /**
     * 任何设备写入之前的无副作用预检（F06 / D1）。
     *
     * <p>默认无要求；会首次写入共享授权表的适配器在这里区分可信缺失与元数据异常，并在
     * 平台还不能安全初始化时明确拒绝，保持防火墙 / 公钥 / 监听写入为零。
     */
    default void preflight(AdbBootstrapPolicy.Action action) throws Exception {}

    void narrow() throws Exception;

    void verifyGuard() throws Exception;

    void authorize() throws Exception;

    void verifyKey() throws Exception;

    void open() throws Exception;

    void close() throws Exception;

    void revoke() throws Exception;

    Snapshot observe() throws Exception;

    default boolean dispatched() {
      return true;
    }
  }

  public static final class Snapshot {
    public final String key, listener, guard;

    public boolean complete() {
      return java.util.Arrays.asList("NOT_REGISTERED", "ABSENT", "OWNED", "EXISTING").contains(key)
          && java.util.Arrays.asList("OPEN", "CLOSED", "CONNECTIONS_REMAIN").contains(listener)
          && java.util.Arrays.asList("PROTECTED", "UNPROTECTED").contains(guard);
    }

    public Snapshot(String key, String listener, String guard) {
      this.key = key;
      this.listener = listener;
      this.guard = guard;
    }
  }

  public static final class Outcome {
    public final String status, code;
    public final Snapshot snapshot;

    /** 失败时的可显示原文（通常是失败的设备命令）；成功或未采集时为空。 */
    public final String evidence;

    private Outcome(String status, String code, Snapshot snapshot, String evidence) {
      this.status = status;
      this.code = code;
      this.snapshot = snapshot;
      this.evidence = evidence == null ? "" : evidence;
    }
  }

  /**
   * 带可显示证据的失败。
   *
   * <p>码本身必须保持 {@code [A-Z0-9_]{1,96}} 才能进事务日志；原因（哪条命令、退出码、
   * stderr 首行）另走 evidence，只用于界面与诊断记录，不参与任何门禁判断。
   */
  public static class BootstrapFailure extends IOException {
    public final String evidence;

    public BootstrapFailure(String code, String evidence) {
      super(code);
      this.evidence = evidence == null ? "" : evidence;
    }
  }

  /** 在任何改动发生前可确定的拒绝。适配器一旦已派发，不得用此类型掩盖未知结果。 */
  public static final class Refused extends BootstrapFailure {
    public Refused(String code) {
      super(code, "");
    }

    public Refused(String code, String evidence) {
      super(code, evidence);
    }
  }

  private AdbBootstrapEngine() {}

  public static Outcome execute(AdbBootstrapPolicy.Action action, Host host) {
    boolean mutationStarted = false;
    try {
      if (action == null || host == null) throw new Refused("INVALID_ACTION");
      host.checkGrant(action);
      host.checkPending(action);
      // D1：预检必须在任何写入之前；它抛出的是「明确拒绝」，不得被当成未知结果。
      host.preflight(action);
      switch (action) {
        case STATUS -> {}
        case AUTHORIZE -> {
          mutationStarted = true;
          host.narrow();
          host.verifyGuard();
          host.checkGrant(action);
          host.authorize();
        }
        case NARROW -> {
          mutationStarted = true;
          host.narrow();
          host.verifyGuard();
        }
        case OPEN -> {
          host.verifyKey();
          mutationStarted = true;
          host.narrow();
          host.verifyGuard();
          host.checkGrant(action);
          host.open();
          host.verifyGuard();
        }
        case CLOSE -> {
          mutationStarted = true;
          host.close();
        }
        case REVOKE -> {
          mutationStarted = true;
          host.revoke();
        }
      }
      Snapshot observed = host.observe();
      return new Outcome(
          observed.complete() || action == AdbBootstrapPolicy.Action.STATUS ? "OK" : "UNKNOWN",
          observed.complete() ? "COMPLETE" : "OBSERVATION_INCOMPLETE",
          observed,
          "");
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      // 不隐式重试、补做相反动作或切通道；后续只能显式观察/清理。
      String status =
          mutationStarted && host.dispatched()
              ? "UNKNOWN"
              : error instanceof Refused ? "REFUSED" : "FAILED";
      String code = error.getMessage();
      if (code == null || !code.matches("[A-Z0-9_]{1,96}")) code = "BOOTSTRAP_IO_FAILED";
      // evidence 只用于显示与诊断；门禁、事务日志与未知语义仍只看 status/code。
      String evidence =
          error instanceof BootstrapFailure ? ((BootstrapFailure) error).evidence : "";
      return new Outcome(status, code, new Snapshot("UNKNOWN", "UNKNOWN", "UNKNOWN"), evidence);
    }
  }

  /** 校验 iptables -S 中第一条 INPUT 规则；规则存在但排在放行规则后不算防护。 */
  public static boolean firstRuleProtects(String listing, String ownerTag) {
    if (listing == null
        || listing.length() > 262144
        || (ownerTag == null || !ownerTag.matches("dsha-bootstrap-[0-9]+"))) return false;
    String expected =
        "-A INPUT ! -i lo -p tcp -m tcp --dport 5555 -m comment --comment " + ownerTag + " -j DROP";
    for (String line : listing.split("\\r?\\n")) {
      String normalized =
          line.trim().replace("\"" + ownerTag + "\"", ownerTag).replaceAll("[ \\t]+", " ");
      if (normalized.isEmpty()
          || normalized.equals("-P INPUT ACCEPT")
          || normalized.equals("-P INPUT DROP")) continue;
      return normalized.equals(expected) || normalized.equals(expected.replace(" -m tcp", ""));
    }
    return false;
  }

  public static boolean firewallListingComplete(String listing) {
    if (listing == null || listing.length() > 262144) return false;
    String[] lines = listing.trim().split("\\r?\\n");
    if (!(lines[0].equals("-P INPUT ACCEPT") || lines[0].equals("-P INPUT DROP"))) return false;
    for (int i = 1; i < lines.length; i++) if (!lines[i].startsWith("-A INPUT ")) return false;
    return true;
  }

  /** 两族表严格分别解析；未知格式不能当作没有监听。 */
  private static List<String[]> sockets(String tcp, String tcp6) throws IOException {
    java.util.ArrayList<String[]> result = new java.util.ArrayList<>();
    String[] tables = {tcp, tcp6};
    for (int family = 0; family < tables.length; family++) {
      String table = tables[family];
      if (table == null || table.trim().isEmpty() || table.length() > 1048576)
        throw new IOException("LISTENER_UNVERIFIED");
      String[] lines = table.trim().split("\\r?\\n");
      if (!lines[0].contains("local_address")
          || !(lines[0].contains("rem_address") || lines[0].contains("remote_address")))
        throw new IOException("LISTENER_UNVERIFIED");
      String address = "[0-9A-Fa-f]{" + (family == 0 ? 8 : 32) + "}:[0-9A-Fa-f]{4}";
      for (int i = 1; i < lines.length; i++) {
        String[] cols = lines[i].trim().split("\\s+");
        if (cols.length < 10
            || !cols[0].matches("[0-9]+:")
            || !cols[1].matches(address)
            || !cols[2].matches(address)
            || !cols[3].matches("(?:0[1-9A-Fa-f])")
            || !cols[7].matches("[0-9]+")
            || !cols[9].matches("[0-9]+")) throw new IOException("LISTENER_UNVERIFIED");
        result.add(cols);
      }
    }
    return result;
  }

  public static String listener(String tcp, String tcp6) throws IOException {
    boolean listening = false, connected = false;
    for (String[] cols : sockets(tcp, tcp6)) {
      if (!cols[1].endsWith(":15B3") && !cols[1].endsWith(":15b3")) continue;
      if (cols[3].equalsIgnoreCase("0A")) listening = true;
      else if (!cols[3].equalsIgnoreCase("06") && !cols[3].equalsIgnoreCase("07")) connected = true;
    }
    return listening ? "OPEN" : connected ? "CONNECTIONS_REMAIN" : "CLOSED";
  }

  /** 属性不是监听证明；共享公钥修改前还需排除 daemon 实际持有的其它 TCP 入口。 */
  public static void verifyDaemonListeners(String tcp, String tcp6, java.util.Set<String> inodes)
      throws IOException {
    if (inodes == null) throw new IOException("ADBD_SOCKETS_UNVERIFIED");
    for (String[] row : sockets(tcp, tcp6))
      if (row[3].equalsIgnoreCase("0A")
          && inodes.contains(row[9])
          && !row[1].substring(row[1].length() - 4).equalsIgnoreCase("15B3"))
        throw new Refused("OTHER_ADB_PORT_IN_USE");
  }
}
