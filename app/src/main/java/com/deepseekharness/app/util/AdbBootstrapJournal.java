package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** root 事务记录的严格格式与恢复判据；文件自述不能扩大所有权或触发重放。 */
public final class AdbBootstrapJournal {
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "package",
          "uid",
          "clientKey",
          "keyOwned",
          "ownedLine",
          "portOwned",
          "portBoot",
          "ipv4Owned",
          "ipv6Owned",
          "pending",
          "keyPending",
          "keyBeforeSha",
          "keyAfterSha",
          "keyBackup",
          "revokePid",
          "revokeStarted",
          "revokeBoot");

  /** 网络事务允许的未决阶段；与公钥事务分开表达，互不覆盖。 */
  public static final Set<String> NETWORK_PHASES =
      Set.of("", "NARROW", "OPEN", "CLOSE", "CLOSE_RESTART_SENT");
  /** 公钥事务允许的未决阶段。 */
  public static final Set<String> KEY_PHASES =
      Set.of("", "AUTHORIZE", "REVOKE", "REVOKE_RESTART_SENT");

  private AdbBootstrapJournal() {}

  public static boolean validNetworkPhase(String phase) {
    return phase != null && NETWORK_PHASES.contains(phase);
  }

  public static boolean validKeyPhase(String phase) {
    return phase != null && KEY_PHASES.contains(phase);
  }

  public static void validate(Map<String, ?> record, String packageName, int uid)
      throws IOException {
    if (!SelfPackageIdentity.validName(packageName) || uid < 10000) fail();
    if (record == null
        || !Integer.valueOf(1).equals(record.get("schema"))
        || !packageName.equals(record.get("package"))
        || !Integer.valueOf(uid).equals(record.get("uid"))) fail();
    for (Object key : record.keySet())
      if (!(key instanceof String) || !FIELDS.contains(key)) fail();
    for (String key : new String[] {"keyOwned", "portOwned", "ipv4Owned", "ipv6Owned"})
      if (record.containsKey(key) && !(record.get(key) instanceof Boolean)) fail();
    for (String key :
        new String[] {
          "clientKey",
          "ownedLine",
          "portBoot",
          "revokeBoot",
          "pending",
          "keyPending",
          "keyBeforeSha",
          "keyAfterSha",
          "keyBackup"
        }) if (record.containsKey(key) && !(record.get(key) instanceof String)) fail();
    if (!validNetworkPhase(text(record, "pending"))) fail();
    String phase = text(record, "keyPending");
    if (!validKeyPhase(phase)) fail();
    for (String key : new String[] {"keyBeforeSha", "keyAfterSha"})
      if (record.containsKey(key) && !text(record, key).matches("[a-f0-9]{64}")) fail();
    if (record.containsKey("keyBackup")
        && !text(record, "keyBackup").matches("keys-" + Ids.UUID_PATTERN + "\\.before")) fail();
    if (record.containsKey("revokeBoot") && !Ids.uuid(text(record, "revokeBoot"))) fail();
    if (record.containsKey("portBoot") && !Ids.uuid(text(record, "portBoot"))) fail();
    if (Boolean.TRUE.equals(record.get("portOwned")) && !Ids.uuid(text(record, "portBoot"))) fail();
    String client = text(record, "clientKey");
    if (!client.isEmpty() && !AdbAuthorizedKeys.publicKey(client).equals(client)) fail();
    if (Boolean.TRUE.equals(record.get("keyOwned"))
        && (client.isEmpty()
            || !(client + " dsha:" + packageName).equals(text(record, "ownedLine")))) fail();
    if (!phase.isEmpty()
        && (!record.containsKey("keyBeforeSha")
            || !record.containsKey("keyAfterSha")
            || !record.containsKey("keyBackup")
            || !Boolean.TRUE.equals(record.get("keyOwned")))) fail();
    if (record.containsKey("revokePid")
        && (!(record.get("revokePid") instanceof Integer)
            || (Integer) record.get("revokePid") <= 1)) fail();
    if (record.containsKey("revokeStarted")
        && (!(record.get("revokeStarted") instanceof Long
                || record.get("revokeStarted") instanceof Integer)
            || ((Number) record.get("revokeStarted")).longValue() <= 0)) fail();
    if (phase.equals("REVOKE_RESTART_SENT")
        && (!record.containsKey("revokePid") || !record.containsKey("revokeStarted"))) fail();
  }

  /** 撤销已落盘后不再删除第二次；无关条目并发变动也不能变成整表恢复。 */
  public static boolean keyAlreadyRemoved(String phase, int remainingKeyCount) {
    return ("REVOKE".equals(phase) || "REVOKE_RESTART_SENT".equals(phase))
        && remainingKeyCount == 0;
  }

  public static boolean restartMayBeSent(String phase) {
    return "REVOKE".equals(phase);
  }

  /**
   * 是否仍要保留「上次结果未确认」的屏障。
   *
   * <p>只有**可信响应**同时确认「持久未决状态已核实」「网络与公钥两个阶段都已结束」「观测完整」
   * 且本次结果成功时，才允许解除。旧格式、缺字段、原生异常（{@code reply == null}）都回到
   * 旧的结果码判据：可能已写入设备的未知结果保持未决，绝不会把已有的未决清成已确认。
   */
  public static boolean unresolved(
      boolean previous, AdbBootstrapReply reply, String outcome, String code) {
    if (reply != null && reply.trusted) {
      if (!reply.pendingVerified) return true;
      if (!reply.networkPending.isEmpty() || !reply.keyPending.isEmpty()) return true;
      if ("PENDING_ACTION".equals(code)) return true;
      if (!"OK".equals(outcome)) return previous;
      if ("OBSERVATION_INCOMPLETE".equals(code) || !reply.observationComplete()) return true;
      return false;
    }
    if ("UNKNOWN".equals(outcome) || "PENDING_ACTION".equals(code)) return true;
    return previous;
  }

  public static void checkRevokeRecovery(String phase, int count) throws IOException {
    if ("REVOKE_RESTART_SENT".equals(phase) && count != 0)
      throw new IOException("KEY_REAPPEARED_AFTER_REVOKE");
  }

  public static String sha256(byte[] bytes) throws Exception {
    StringBuilder result = new StringBuilder();
    for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
      result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private static String text(Map<String, ?> record, String field) {
    Object value = record.get(field);
    return value instanceof String ? (String) value : "";
  }

  private static void fail() throws IOException {
    throw new IOException("BOOTSTRAP_JOURNAL_INVALID");
  }
}
