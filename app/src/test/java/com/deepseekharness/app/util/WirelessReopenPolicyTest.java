package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 自举 ↔ 无线调试互斥判据；纯逻辑，不读写系统设置。 */
public final class WirelessReopenPolicyTest {
  @Test
  public void anyLocalEvidenceBlocksAutomaticReopen() {
    assertTrue(WirelessReopenPolicy.blocked(true, false, false, "", false));
    assertTrue(WirelessReopenPolicy.blocked(false, true, false, "", false));
    assertTrue(WirelessReopenPolicy.blocked(false, false, true, "", false));
    assertTrue(WirelessReopenPolicy.blocked(false, false, false, "", true));
    assertTrue(WirelessReopenPolicy.blocked(false, false, false, "WIRELESS_DEBUG_IN_USE", false));
  }

  @Test
  public void quietEvidenceDoesNotBlockTheExistingWirelessPath() {
    assertFalse(WirelessReopenPolicy.blocked(false, false, false, "COMPLETE", false));
    assertFalse(WirelessReopenPolicy.blocked(false, false, false, null, false));
  }

  @Test
  public void releaseNeedsBothATrustedCleanupAndAnExplicitChoice() {
    assertTrue(WirelessReopenPolicy.releasable(false, true, true));
    // 成功 STATUS 或无操作清理本身不构成用户选择。
    assertFalse(WirelessReopenPolicy.releasable(false, true, false));
    // 用户选择也代替不了可信清理结果。
    assertFalse(WirelessReopenPolicy.releasable(false, false, true));
    assertFalse(WirelessReopenPolicy.releasable(true, true, true));
  }

  @Test
  public void explicitRecordAlwaysWins() {
    assertTrue(WirelessReopenPolicy.persistedBlock(true, true, "STATUS"));
    assertFalse(WirelessReopenPolicy.persistedBlock(true, false, "AUTHORIZE"));
  }

  @Test
  public void legacyBootstrapActionsStayBlockedUntilTheUserLooks() {
    // 旧数据没有显式记录：确认过会改设备状态的动作 → 保守禁止自动重开。
    for (String action : new String[] {"AUTHORIZE", "NARROW", "OPEN"})
      assertTrue(action, WirelessReopenPolicy.persistedBlock(false, false, action));
  }

  @Test
  public void legacyReadOnlyOrCleanupUsersKeepTheirWirelessPath() {
    // 只做过只读刷新 / 清理，或压根没有自举记录的普通无线配对用户不被拦住。
    for (String action : new String[] {"", "STATUS", "CLOSE", "REVOKE", "UNKNOWN", null})
      assertFalse(
          String.valueOf(action), WirelessReopenPolicy.persistedBlock(false, false, action));
  }
}
