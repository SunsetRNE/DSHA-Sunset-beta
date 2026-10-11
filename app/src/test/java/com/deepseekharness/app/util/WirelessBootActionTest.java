package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 开机 / 覆盖升级自动开启无线调试的判据；纯逻辑，不读系统设置。 */
public final class WirelessBootActionTest {
  @Test
  public void missingSecureSettingsNeverWrites() {
    assertEquals(
        WirelessBootAction.Decision.SKIP_NO_PERMISSION,
        WirelessBootAction.decide(false, false, false));
    // 即使互斥也生效，第一个不通过的条件优先；两者都不写。
    assertEquals(
        WirelessBootAction.Decision.SKIP_NO_PERMISSION,
        WirelessBootAction.decide(false, true, false));
  }

  @Test
  public void bootstrapMutexBlocksTheBootPathToo() {
    // F02：自举未决 / 最近一次正因无线调试被拒时，开机与覆盖升级都不得把它开回来。
    assertEquals(
        WirelessBootAction.Decision.SKIP_MUTEX, WirelessBootAction.decide(true, true, false));
    assertEquals(
        WirelessBootAction.Decision.SKIP_MUTEX, WirelessBootAction.decide(true, true, true));
  }

  @Test
  public void alreadyEnabledIsNotRewritten() {
    assertEquals(
        WirelessBootAction.Decision.SKIP_ALREADY_ON, WirelessBootAction.decide(true, false, true));
  }

  @Test
  public void onlyTheCleanCaseEnablesAndWrites() {
    WirelessBootAction.Decision enable = WirelessBootAction.decide(true, false, false);
    assertEquals(WirelessBootAction.Decision.ENABLE, enable);
    assertTrue(WirelessBootAction.writesSettings(enable));
    for (WirelessBootAction.Decision other :
        new WirelessBootAction.Decision[] {
          WirelessBootAction.Decision.SKIP_NO_PERMISSION,
          WirelessBootAction.Decision.SKIP_MUTEX,
          WirelessBootAction.Decision.SKIP_ALREADY_ON
        }) {
      assertFalse(WirelessBootAction.writesSettings(other));
    }
  }
}
