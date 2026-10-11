package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 首次写入前预检的判定表；纯逻辑，不访问设备文件系统。 */
public final class AdbKeyTablePreflightTest {
  private static String reason(
      boolean directory,
      boolean present,
      boolean regular,
      boolean link,
      boolean owner,
      boolean mode,
      boolean canonical,
      boolean label) {
    return AdbKeyTablePreflight.reason(directory, present, regular, link, owner, mode, canonical, label);
  }

  private static String ready(boolean present) {
    return reason(true, present, true, true, true, true, true, true);
  }

  @Test
  public void existingEmptyOrNonEmptyTableIsReadyForTheOwnedWritePath() {
    // D1 只判断「能否沿用既有 inode / 标签」；既有空表与非空表都可继续。
    assertEquals("", ready(true));
  }

  @Test
  public void missingTableGetsItsOwnUninitializedReasonInsteadOfBeingCreated() {
    assertEquals(AdbKeyTablePreflight.MISSING_UNSUPPORTED, ready(false));
  }

  @Test
  public void untrustedDirectoryIsReportedBeforeAnythingElse() {
    assertEquals(
        AdbKeyTablePreflight.DIRECTORY_UNTRUSTED,
        reason(false, false, false, false, false, false, false, false));
    assertEquals(
        AdbKeyTablePreflight.DIRECTORY_UNTRUSTED,
        reason(false, true, true, true, true, true, true, true));
  }

  @Test
  public void symlinkHardlinkAndMetadataAnomaliesAreIdentityFailures() {
    assertEquals(AdbKeyTablePreflight.IDENTITY_UNVERIFIED, reason(true, true, false, true, true, true, true, true));
    assertEquals(AdbKeyTablePreflight.IDENTITY_UNVERIFIED, reason(true, true, true, false, true, true, true, true));
    assertEquals(AdbKeyTablePreflight.IDENTITY_UNVERIFIED, reason(true, true, true, true, true, true, false, true));
    assertEquals(AdbKeyTablePreflight.IDENTITY_UNVERIFIED, reason(true, true, true, true, false, true, true, true));
    assertEquals(AdbKeyTablePreflight.IDENTITY_UNVERIFIED, reason(true, true, true, true, true, false, true, true));
  }

  @Test
  public void unreadableLabelStopsTheWrite() {
    assertEquals(AdbKeyTablePreflight.LABEL_UNVERIFIED, reason(true, true, true, true, true, true, true, false));
  }
}
