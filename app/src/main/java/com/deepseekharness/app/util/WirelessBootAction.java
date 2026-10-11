package com.deepseekharness.app.util;

/**
 * 开机 / 覆盖升级广播里「要不要自动开启系统无线调试」的唯一判据（F02）。
 *
 * <p>接收器只读取本机证据：不申请 Root、不执行自举、不在广播里补做设备写动作。判据与保活共用
 * 同一个互斥结论（{@link WirelessReopenPolicy}），所以「自举未决 / 最近一次正因无线调试被拒」
 * 时开机也不会把它开回来。
 */
public final class WirelessBootAction {
  public enum Decision {
    /** 没有 WRITE_SECURE_SETTINGS：让 Shizuku / 看门狗兜底。 */
    SKIP_NO_PERMISSION,
    /** 自举互斥生效：保持无线调试关闭，只在日志里说明。 */
    SKIP_MUTEX,
    /** 已经是开着的：不重复写设置。 */
    SKIP_ALREADY_ON,
    /** 可以自动开启（唯一会写系统设置的分支）。 */
    ENABLE
  }

  private WirelessBootAction() {}

  public static Decision decide(
      boolean hasSecureSettings, boolean reopenBlocked, boolean alreadyEnabled) {
    if (!hasSecureSettings) return Decision.SKIP_NO_PERMISSION;
    if (reopenBlocked) return Decision.SKIP_MUTEX;
    if (alreadyEnabled) return Decision.SKIP_ALREADY_ON;
    return Decision.ENABLE;
  }

  /** 只有 ENABLE 允许写 `adb_wifi_enabled`；其余分支必须零设备写入。 */
  public static boolean writesSettings(Decision decision) {
    return decision == Decision.ENABLE;
  }
}
