package com.deepseekharness.app.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * 迁移等价性 + ADR 0005 新语义：
 *
 * <p>旧判据（ADR 0004 之前）是 Root → Shizuku → ADB 三段；ADR 0005 把 Shizuku 也纳入 ADB 全局授权，
 * 因此总开关关闭时必须连 Shizuku 一起挡住，但**不影响** Root。期望值由本测试独立抄写的判据算出。
 */
public class DeviceAuthorizationTest {

  /** ADR 0005 的通道选择：总开关挡 ADB 家族（含 Shizuku），Root 独立。 */
  private static String expectedTransport(
      boolean rootEnabled,
      boolean rootPresent,
      boolean forceRoot,
      boolean forceAdb,
      boolean sensitiveRead,
      boolean shizukuPermission,
      boolean globalEnabled) {
    if (!forceAdb && rootEnabled && rootPresent) return "root";
    if (!globalEnabled) return "none";
    if (!forceAdb && !forceRoot && !sensitiveRead && shizukuPermission) return "shizuku";
    return "adb";
  }

  /** 历史判据（总开关语义改为只挡 ADB 之前）：用于证明「总开关开时行为不变」。 */
  private static String legacyTransport(
      boolean rootEnabled,
      boolean rootPresent,
      boolean forceRoot,
      boolean forceAdb,
      boolean sensitiveRead,
      boolean shizukuPermission,
      boolean adbEnabled) {
    if (!forceAdb && rootEnabled && rootPresent) return "root";
    if (!forceAdb && !forceRoot && !sensitiveRead && shizukuPermission) return "shizuku";
    if (!adbEnabled) return "none";
    return "adb";
  }

  private static DeviceAuthorization.Snapshot snapshot(
      boolean rootEnabled, boolean rootPresent, boolean shizukuPermission, boolean adbEnabled) {
    return DeviceAuthorization.builder()
        .root(rootEnabled, rootPresent, 0)
        .shizuku(true, shizukuPermission, shizukuPermission, "")
        .adb(adbEnabled, adbEnabled ? "connected" : "", true, adbEnabled)
        .build();
  }

  @Test
  public void planMatchesAdb0005ForEveryCombination() {
    int cases = 0;
    for (int bits = 0; bits < 128; bits++) {
      boolean rootEnabled = (bits & 1) != 0;
      boolean rootPresent = (bits & 2) != 0;
      boolean forceRoot = (bits & 4) != 0;
      boolean forceAdb = (bits & 8) != 0;
      boolean sensitiveRead = (bits & 16) != 0;
      boolean shizukuPermission = (bits & 32) != 0;
      boolean globalEnabled = (bits & 64) != 0;
      String capability = sensitiveRead ? "sms.read" : "device.exec";
      DeviceAuthorization.Decision decision =
          DeviceAuthorization.plan(
              snapshot(rootEnabled, rootPresent, shizukuPermission, globalEnabled),
              forceRoot,
              forceAdb,
              capability);
      String expected =
          expectedTransport(
              rootEnabled,
              rootPresent,
              forceRoot,
              forceAdb,
              sensitiveRead,
              shizukuPermission,
              globalEnabled);
      String label =
          "bits="
              + bits
              + " root="
              + rootEnabled
              + "/"
              + rootPresent
              + " forceRoot="
              + forceRoot
              + " forceAdb="
              + forceAdb
              + " sms="
              + sensitiveRead
              + " shizuku="
              + shizukuPermission
              + " global="
              + globalEnabled;
      Assert.assertEquals(label, expected, decision.transport);
      Assert.assertEquals(label, !"none".equals(expected), decision.executable);
      cases++;
    }
    Assert.assertEquals(128, cases);
  }

  @Test
  public void globalSwitchOnKeepsTheLegacyChannelOrder() {
    for (int bits = 0; bits < 64; bits++) {
      boolean rootEnabled = (bits & 1) != 0;
      boolean rootPresent = (bits & 2) != 0;
      boolean forceRoot = (bits & 4) != 0;
      boolean forceAdb = (bits & 8) != 0;
      boolean sensitiveRead = (bits & 16) != 0;
      boolean shizukuPermission = (bits & 32) != 0;
      DeviceAuthorization.Decision decision =
          DeviceAuthorization.plan(
              snapshot(rootEnabled, rootPresent, shizukuPermission, true),
              forceRoot,
              forceAdb,
              sensitiveRead ? "sms.read" : "device.exec");
      Assert.assertEquals(
          "bits=" + bits,
          legacyTransport(
              rootEnabled,
              rootPresent,
              forceRoot,
              forceAdb,
              sensitiveRead,
              shizukuPermission,
              true),
          decision.transport);
    }
  }

  @Test
  public void globalSwitchOffBlocksEvenAnAuthorizedShizuku() {
    DeviceAuthorization.Snapshot snapshot = snapshot(false, false, true, false);
    DeviceAuthorization.Decision decision =
        DeviceAuthorization.plan(snapshot, false, false, "device.exec");
    Assert.assertEquals("none", decision.transport);
    Assert.assertEquals(DeviceAuthorization.Reason.ADB_GLOBAL_OFF, decision.reason);
    Assert.assertEquals(DeviceAuthorization.Authority.NONE, DeviceAuthorization.authority(snapshot));
    // 强制 ADB 也不能绕过总开关。
    Assert.assertEquals(
        "none",
        DeviceAuthorization.plan(snapshot, false, true, "device.exec").transport);
  }

  @Test
  public void globalSwitchOffDoesNotTouchRootShell() {
    DeviceAuthorization.Snapshot snapshot = snapshot(true, true, true, false);
    DeviceAuthorization.Decision decision =
        DeviceAuthorization.plan(snapshot, false, false, "device.exec");
    Assert.assertEquals("root", decision.transport);
    Assert.assertEquals(
        DeviceAuthorization.Authority.ROOT_UID0, DeviceAuthorization.authority(snapshot));
    // 总开关关闭时，全局授权必须显示为未授权（Root 不参与 ADB 全局授权）。
    Assert.assertEquals(
        DeviceAuthorization.State.ABSENT, DeviceAuthorization.globalState(snapshot));
    Assert.assertEquals(
        DeviceAuthorization.Reason.ADB_GLOBAL_OFF, DeviceAuthorization.globalReason(snapshot));
    Assert.assertNull(DeviceAuthorization.grantedSource(snapshot));
  }

  @Test
  public void anySingleSourceActivatesTheGlobalGrant() {
    // 自举监听有效
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTED,
        DeviceAuthorization.globalState(
            DeviceAuthorization.builder()
                .adb(true, "connected", true, true)
                .bootstrap(true, 1, true)
                .build()));
    // 无线/ADB 已连接
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTED,
        DeviceAuthorization.globalState(snapshot(false, false, false, true)));
    // Shizuku 已授权并连接
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTED,
        DeviceAuthorization.globalState(
            DeviceAuthorization.builder()
                .adb(true, "", false, false)
                .shizuku(true, true, true, "")
                .build()));
    // USB 调试授权（只读检测命中）
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTED,
        DeviceAuthorization.globalState(
            DeviceAuthorization.builder().adb(true, "", false, false).usbDebugDetected(true).build()));
  }

  @Test
  public void globalGrantWithoutAnySourceIsAbsentWithAReason() {
    DeviceAuthorization.Snapshot snapshot =
        DeviceAuthorization.builder().adb(true, "", false, false).build();
    Assert.assertEquals(
        DeviceAuthorization.State.ABSENT, DeviceAuthorization.globalState(snapshot));
    // 无线这条「可授权但未验证」比「什么都没有」更接近可用，原因应取它。
    Assert.assertEquals(
        DeviceAuthorization.Reason.ADB_NOT_PAIRED, DeviceAuthorization.globalReason(snapshot));
    DeviceAuthorization.Snapshot off =
        DeviceAuthorization.builder().adb(false, "", false, false).usbDebugDetected(true).build();
    Assert.assertEquals(DeviceAuthorization.State.ABSENT, DeviceAuthorization.globalState(off));
    Assert.assertEquals(
        DeviceAuthorization.Reason.ADB_GLOBAL_OFF, DeviceAuthorization.globalReason(off));
  }

  @Test
  public void adbSourcesAreTheFourSectionsInOrder() {
    java.util.List<DeviceAuthorization.Pipe> pipes = new java.util.ArrayList<>();
    for (DeviceAuthorization.PipeStatus status :
        DeviceAuthorization.adbSources(snapshot(false, false, true, true)))
      pipes.add(status.pipe);
    Assert.assertEquals(
        java.util.List.of(
            DeviceAuthorization.Pipe.ADB_USB,
            DeviceAuthorization.Pipe.ADB_TCP,
            DeviceAuthorization.Pipe.SHIZUKU_BINDER,
            DeviceAuthorization.Pipe.ADB_BOOTSTRAP),
        pipes);
  }

  @Test
  public void sensitiveReadOnRootChannelRestrictsToTheAppUser() {
    DeviceAuthorization.Snapshot snapshot = snapshot(true, true, true, true);
    DeviceAuthorization.Decision sms =
        DeviceAuthorization.plan(snapshot, false, false, "sms.read");
    Assert.assertEquals("root", sms.transport);
    Assert.assertEquals(DeviceAuthorization.Pipe.ROOT_SU, sms.pipe);
    Assert.assertTrue(sms.restrictToAppUser);
    DeviceAuthorization.Decision normal =
        DeviceAuthorization.plan(snapshot, false, false, "device.exec");
    Assert.assertFalse(normal.restrictToAppUser);
  }

  @Test
  public void sensitiveReadNeverUsesShizuku() {
    DeviceAuthorization.Snapshot snapshot = snapshot(false, false, true, false);
    DeviceAuthorization.Decision decision =
        DeviceAuthorization.plan(snapshot, false, false, "sms.read");
    Assert.assertEquals("none", decision.transport);
    Assert.assertEquals(DeviceAuthorization.Reason.SMS_NEEDS_ROOT_OR_ADB, decision.reason);
  }

  @Test
  public void authorityFollowsTheExecutionOrder() {
    Assert.assertEquals(
        DeviceAuthorization.Authority.ROOT_UID0,
        DeviceAuthorization.authority(snapshot(true, true, true, true)));
    Assert.assertEquals(
        DeviceAuthorization.Authority.ADB_SHELL,
        DeviceAuthorization.authority(snapshot(false, false, true, true)));
    Assert.assertEquals(
        DeviceAuthorization.Authority.SHIZUKU_ADB,
        DeviceAuthorization.authority(
            DeviceAuthorization.builder()
                .adb(true, "", false, false)
                .shizuku(true, true, true, "")
                .build()));
    Assert.assertEquals(
        DeviceAuthorization.Authority.SHIZUKU_ROOT,
        DeviceAuthorization.authority(
            DeviceAuthorization.builder()
                .root(true, false, 0)
                .adb(true, "", false, false)
                .shizuku(true, true, true, "")
                .build()));
    Assert.assertEquals(
        DeviceAuthorization.Authority.NONE,
        DeviceAuthorization.authority(snapshot(false, false, false, false)));
  }

  @Test
  public void pipesCarryStatesAndReasons() {
    DeviceAuthorization.Snapshot snapshot =
        DeviceAuthorization.builder()
            .root(true, false, 4)
            .shizuku(true, false, false, "binder died")
            .adb(true, "need_pair", false, false)
            .bootstrap(true, 2, false)
            .wireless(true, true, true)
            .usbDebugDetected(false)
            .build();
    java.util.Map<DeviceAuthorization.Pipe, DeviceAuthorization.PipeStatus> byPipe =
        new java.util.LinkedHashMap<>();
    for (DeviceAuthorization.PipeStatus status : DeviceAuthorization.pipes(snapshot))
      byPipe.put(status.pipe, status);
    Assert.assertEquals(6, byPipe.size());
    Assert.assertEquals(
        DeviceAuthorization.State.BLOCKED, byPipe.get(DeviceAuthorization.Pipe.ROOT_SU).state);
    Assert.assertEquals(
        DeviceAuthorization.Reason.ROOT_SU_NOT_FOUND,
        byPipe.get(DeviceAuthorization.Pipe.ROOT_SU).reason);
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTABLE,
        byPipe.get(DeviceAuthorization.Pipe.SHIZUKU_BINDER).state);
    Assert.assertEquals(
        DeviceAuthorization.Reason.SHIZUKU_NO_PERMISSION,
        byPipe.get(DeviceAuthorization.Pipe.SHIZUKU_BINDER).reason);
    Assert.assertEquals(
        DeviceAuthorization.State.BLOCKED,
        byPipe.get(DeviceAuthorization.Pipe.ADB_BOOTSTRAP).state);
    Assert.assertEquals(
        DeviceAuthorization.Reason.WIRELESS_DEBUG_IN_USE,
        byPipe.get(DeviceAuthorization.Pipe.ADB_BOOTSTRAP).reason);
    Assert.assertEquals(
        DeviceAuthorization.Reason.ADB_NOT_PAIRED,
        byPipe.get(DeviceAuthorization.Pipe.ADB_TCP).reason);
    Assert.assertEquals(
        DeviceAuthorization.State.UNKNOWN, byPipe.get(DeviceAuthorization.Pipe.ADB_USB).state);
  }

  @Test
  public void keepaliveCredentialFollowsTheMutex() {
    DeviceAuthorization.Snapshot blocked =
        DeviceAuthorization.builder()
            .adb(true, "connected", true, true)
            .wireless(true, true, true)
            .build();
    Assert.assertEquals(
        DeviceAuthorization.State.BLOCKED, DeviceAuthorization.keepalive(blocked).state);
    Assert.assertEquals(
        DeviceAuthorization.Reason.WIRELESS_REOPEN_BLOCKED,
        DeviceAuthorization.keepalive(blocked).reason);
    DeviceAuthorization.Snapshot available =
        DeviceAuthorization.builder()
            .adb(true, "connected", true, true)
            .wireless(false, false, true)
            .build();
    Assert.assertEquals(
        DeviceAuthorization.State.GRANTED, DeviceAuthorization.keepalive(available).state);
    DeviceAuthorization.Snapshot off =
        DeviceAuthorization.builder().adb(false, "", false, false).build();
    Assert.assertEquals(
        DeviceAuthorization.State.ABSENT, DeviceAuthorization.keepalive(off).state);
  }

  @Test
  public void fingerprintChangesWithAnyAuthorizationFact() {
    DeviceAuthorization.Snapshot base = snapshot(true, true, true, true);
    long value = base.fingerprint();
    Assert.assertEquals(value, snapshot(true, true, true, true).fingerprint());
    Assert.assertEquals(base, snapshot(true, true, true, true));
    Assert.assertNotEquals(
        value, DeviceAuthorization.builder().root(false, true, 0).build().fingerprint());
    Assert.assertNotEquals(
        value, DeviceAuthorization.builder().root(true, true, 1).build().fingerprint());
    Assert.assertNotEquals(
        value,
        DeviceAuthorization.builder()
            .root(true, true, 0)
            .adb(true, "connected", true, true)
            .build()
            .fingerprint());
    Assert.assertNotEquals(
        value, DeviceAuthorization.builder().screenGrant(true).build().fingerprint());
  }
}
