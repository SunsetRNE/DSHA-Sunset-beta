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

  private AdbBootstrapJournal() {}

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
    if (!Set.of("", "NARROW", "OPEN", "CLOSE", "CLOSE_RESTART_SENT")
        .contains(text(record, "pending"))) fail();
    String phase = text(record, "keyPending");
    if (!Set.of("", "AUTHORIZE", "REVOKE", "REVOKE_RESTART_SENT").contains(phase)) fail();
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

  /** 请求结果与待核验设备动作分开；失败刷新不能洗掉上次未知。 */
  public static boolean unresolved(
      boolean previous, String outcome, String code, String key, String listener, String guard) {
    if ("UNKNOWN".equals(outcome) || "PENDING_ACTION".equals(code)) return true;
    if ("OK".equals(outcome)
        && !"OBSERVATION_INCOMPLETE".equals(code)
        && new AdbBootstrapEngine.Snapshot(key, listener, guard).complete()) return false;
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
