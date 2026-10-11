package com.deepseekharness.app.util;

import org.junit.Assert;
import org.junit.Test;

/** Shizuku 的专属单测：判定、来源标注与「不是权威」的措辞都有断言。 */
public class ShizukuStatusTest {

  @Test
  public void levelsFollowObservedFacts() {
    Assert.assertEquals(
        ShizukuStatus.Level.NOT_INSTALLED,
        ShizukuStatus.level(false, false, false, false, ""));
    Assert.assertEquals(
        ShizukuStatus.Level.MANAGER_INSTALLED,
        ShizukuStatus.level(false, true, false, false, ""));
    Assert.assertEquals(
        ShizukuStatus.Level.PERMISSION_REQUIRED,
        ShizukuStatus.level(true, true, false, false, ""));
    Assert.assertEquals(
        ShizukuStatus.Level.CONNECTING,
        ShizukuStatus.level(true, true, true, false, ""));
    Assert.assertEquals(
        ShizukuStatus.Level.CONNECT_FAILED,
        ShizukuStatus.level(true, true, true, false, "binder died"));
    Assert.assertEquals(
        ShizukuStatus.Level.READY, ShizukuStatus.level(true, true, true, true, "binder died"));
  }

  @Test
  public void connectFailureKeepsTheOriginalReason() {
    String text = ShizukuStatus.describe(ShizukuStatus.Level.CONNECT_FAILED, "binder died");
    Assert.assertTrue(text, text.contains("binder died"));
  }

  @Test
  public void sourcePrefersRootThenAdb() {
    Assert.assertEquals(ShizukuStatus.Source.ROOT, ShizukuStatus.source(true, true));
    Assert.assertEquals(ShizukuStatus.Source.ADB, ShizukuStatus.source(false, true));
    Assert.assertEquals(ShizukuStatus.Source.UNKNOWN, ShizukuStatus.source(false, false));
  }

  @Test
  public void everySourceLabelSaysItIsNotTheAuthority() {
    for (ShizukuStatus.Source source : ShizukuStatus.Source.values()) {
      String label = ShizukuStatus.sourceLabel(source);
      Assert.assertTrue(label, label.contains("中间层"));
      Assert.assertTrue(label, label.contains("不是授权本身"));
    }
  }

  @Test
  public void summaryNeverClaimsShizukuAloneGrantsAccess() {
    String summary =
        ShizukuStatus.summary(ShizukuStatus.Level.READY, "", ShizukuStatus.Source.ADB);
    Assert.assertTrue(summary, summary.contains("无需 ADB 配对"));
    Assert.assertTrue(summary, summary.contains("不是授权本身"));
  }
}
