package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.AdbBootstrapPolicy.Action;
import org.junit.Test;

public final class AdbBootstrapGrantTest {
  @Test
  public void defaultsRequireSeparateNativePermissions() {
    var grant = new AdbBootstrapGrant(Action.AUTHORIZE, 1, 2, 100);
    assertFalse(grant.valid(false, false, 1, 2, 100));
    assertFalse(grant.valid(true, false, 1, 2, 100));
    assertTrue(grant.valid(true, true, 1, 2, 100));
  }

  /**
   * 自举的用途是免配对地建立 ADB 授权，所以「ADB 设备通道」不再参与许可判定。
   *
   * <p>锁住这条语义：授权公钥、收窄与开启监听都只要求 root 与独立自举授权，
   * 否则会退回「要先有 ADB 才能自举 ADB」的循环。
   */
  @Test
  public void adbChannelStateDoesNotGateBootstrapActions() {
    for (var action : new Action[] {Action.AUTHORIZE, Action.NARROW, Action.OPEN}) {
      var grant = new AdbBootstrapGrant(action, 1, 2, 100);
      assertTrue(grant.valid(true, true, 1, 2, 100));
    }
  }

  @Test
  public void preparationCannotAcquireNewPermissionAfterOffOnCycle() {
    var grant = new AdbBootstrapGrant(Action.OPEN, 1, 2, 100);
    assertFalse(grant.valid(true, true, 3, 2, 101));
    assertFalse(grant.valid(true, true, 1, 4, 101));
    grant.revoke();
    assertFalse(grant.valid(true, true, 1, 2, 101));
  }

  @Test
  public void grantExpiresWithoutRenewingOnChecks() {
    var grant = new AdbBootstrapGrant(Action.OPEN, 1, 2, 100);
    assertTrue(grant.valid(true, true, 1, 2, 120099));
    assertFalse(grant.valid(true, true, 1, 2, 120100));
    assertFalse(grant.valid(true, true, 1, 2, -1));
  }

  @Test
  public void cleanupRemainsAvailableAfterBootstrapIsDisabled() {
    for (var action : new Action[] {Action.CLOSE, Action.REVOKE, Action.STATUS}) {
      var grant = new AdbBootstrapGrant(action, 1, 2, 100);
      assertTrue(grant.valid(true, false, 1, 6, 101));
      assertFalse(grant.valid(false, false, 1, 6, 101));
      assertFalse(grant.valid(true, false, 2, 6, 101));
    }
  }
}
