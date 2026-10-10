package com.deepseekharness.app.util;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADB 自举的纯请求校验；允许结果不代表设备授权或动作已执行。
 *
 * <p>后续特权入口必须独立核验原生授权、当前安装包身份与设备状态。本类不执行命令，
 * 也不改变 DeviceShellPolicy；原生执行器逐次核验短期授权。
 */
public final class AdbBootstrapPolicy {
  public enum Action {
    AUTHORIZE,
    REVOKE,
    NARROW,
    OPEN,
    CLOSE,
    STATUS
  }

  /** 后续特权实现必须检查的条件；不是可以直接执行的 shell 模板。 */
  public enum Requirement {
    VERIFIED_APK_IDENTITY,
    ROOT_NATIVE_GRANT,
    BOOTSTRAP_NATIVE_GRANT,
    ADB_NATIVE_GRANT,
    VERIFIED_CLIENT_PUBLIC_KEY,
    KEY_TABLE_SNAPSHOT,
    OWNED_KEY_ONLY,
    IPV4_AND_IPV6_LOOPBACK_GUARD,
    OBSERVED_LISTENER_STATE
  }

  public static final String OPERATION = "adb-bootstrap";
  public static final String ADB_KEYS = "/data/misc/adb/adb_keys";
  public static final String PORT_PROPERTY = "service.adb.tcp.port";
  public static final int DEFAULT_PORT = 5555;
  private static final Set<String> FIELDS =
      Set.of("operation", "action", "port", "selfPackage", "selfUid", "authority");

  public static final class Request {
    public final Action action;
    public final int port;

    private Request(Action action) {
      this.action = action;
      this.port = DEFAULT_PORT;
    }
  }

  public static final class Result {
    public final boolean allowed;
    public final String reason;
    public final Request request;

    private Result(boolean allowed, String reason, Request request) {
      this.allowed = allowed;
      this.reason = reason;
      this.request = request;
    }

    private static Result allow(Action action) {
      return new Result(true, "", new Request(action));
    }

    private static Result deny(String reason) {
      return new Result(false, "[POLICY_BLOCKED] " + reason, null);
    }
  }

  private AdbBootstrapPolicy() {}

  /**
   * 严格接收固定字段和类型。installed 必须来自 PrivilegedPackageContext.launchIdentity，
   * 不能由请求自述的包名、UID 构造。
   */
  public static Result inspect(Map<String, ?> fields, SelfPackageIdentity installed) {
    if (fields == null || fields.size() > FIELDS.size()) return Result.deny("自举字段不在白名单");
    for (Object field : fields.keySet()) {
      if (!(field instanceof String) || !FIELDS.contains(field)) return Result.deny("自举字段不在白名单");
    }
    if (fields.containsKey("authority")
        && (!(fields.get("authority") instanceof String)
            || !((String) fields.get("authority")).matches("[a-f0-9]{48}")))
      return Result.deny("原生授权凭据格式无法核验");
    if (installed == null) return Result.deny("自举安装包身份未核验");
    if (!(fields.get("operation") instanceof String)
        || !(fields.get("action") instanceof String)
        || !(fields.get("selfPackage") instanceof String)
        || !(fields.get("selfUid") instanceof Integer)
        || (fields.containsKey("port") && !(fields.get("port") instanceof Integer)))
      return Result.deny("自举字段类型无法核验");
    return inspect(
        (String) fields.get("operation"),
        (String) fields.get("action"),
        fields.containsKey("port") ? (Integer) fields.get("port") : 0,
        (String) fields.get("selfPackage"),
        (Integer) fields.get("selfUid"),
        installed.name,
        installed.uid);
  }

  /** expectedPackage/expectedUid 是已核验安装包事实，禁止用请求值充当预期值。 */
  public static Result inspect(
      String operation,
      String action,
      int port,
      String selfPackage,
      int selfUid,
      String expectedPackage,
      int expectedUid) {
    if (!OPERATION.equals(operation)) return Result.deny("未知 Root 操作");
    if (!SelfPackageIdentity.validName(expectedPackage)
        || !SelfPackageIdentity.validName(selfPackage)
        || !expectedPackage.equals(selfPackage)) return Result.deny("自举请求的包名无法核验");
    // 多用户系统 UID 也可能大于 10000；必须检查 appId，拒绝 root/shell/isolated UID。
    int appId = expectedUid % 100000;
    if (expectedUid < 0 || appId < 10000 || appId >= 20000 || selfUid != expectedUid)
      return Result.deny("自举请求的 uid 无法核验");
    if (port != 0 && port != DEFAULT_PORT) return Result.deny("端口不在固定档位");
    Action parsed =
        action == null
            ? null
            : switch (action) {
              case "authorize" -> Action.AUTHORIZE;
              case "revoke" -> Action.REVOKE;
              case "narrow" -> Action.NARROW;
              case "open" -> Action.OPEN;
              case "close" -> Action.CLOSE;
              case "status" -> Action.STATUS;
              default -> null;
            };
    return parsed == null ? Result.deny("自举动作不在白名单") : Result.allow(parsed);
  }

  /**
   * AUTHORIZE/OPEN 必须先完成双栈防护，已有监听时写入公钥同样会扩大访问；REVOKE 只移除本应用拥有的公钥。
   * CLOSE/REVOKE 清理不依赖 ADB 开关仍开启，避免关闭开关后无法撤销。
   */
  public static List<Requirement> requirements(Action action) {
    if (action == null) throw new IllegalArgumentException("BOOTSTRAP_ACTION_REQUIRED");
    return switch (action) {
      case AUTHORIZE ->
          List.of(
              Requirement.VERIFIED_APK_IDENTITY,
              Requirement.ROOT_NATIVE_GRANT,
              Requirement.BOOTSTRAP_NATIVE_GRANT,
              Requirement.ADB_NATIVE_GRANT,
              Requirement.VERIFIED_CLIENT_PUBLIC_KEY,
              Requirement.KEY_TABLE_SNAPSHOT,
              Requirement.IPV4_AND_IPV6_LOOPBACK_GUARD,
              Requirement.OBSERVED_LISTENER_STATE);
      case REVOKE ->
          List.of(
              Requirement.VERIFIED_APK_IDENTITY,
              Requirement.ROOT_NATIVE_GRANT,
              Requirement.KEY_TABLE_SNAPSHOT,
              Requirement.OWNED_KEY_ONLY);
      case OPEN ->
          List.of(
              Requirement.VERIFIED_APK_IDENTITY,
              Requirement.ROOT_NATIVE_GRANT,
              Requirement.BOOTSTRAP_NATIVE_GRANT,
              Requirement.ADB_NATIVE_GRANT,
              Requirement.IPV4_AND_IPV6_LOOPBACK_GUARD,
              Requirement.OBSERVED_LISTENER_STATE);
      case NARROW ->
          List.of(
              Requirement.VERIFIED_APK_IDENTITY,
              Requirement.ROOT_NATIVE_GRANT,
              Requirement.BOOTSTRAP_NATIVE_GRANT,
              Requirement.IPV4_AND_IPV6_LOOPBACK_GUARD,
              Requirement.OBSERVED_LISTENER_STATE);
      case STATUS -> List.of(Requirement.VERIFIED_APK_IDENTITY, Requirement.ROOT_NATIVE_GRANT);
      case CLOSE ->
          List.of(
              Requirement.VERIFIED_APK_IDENTITY,
              Requirement.ROOT_NATIVE_GRANT,
              Requirement.OBSERVED_LISTENER_STATE);
    };
  }
}
