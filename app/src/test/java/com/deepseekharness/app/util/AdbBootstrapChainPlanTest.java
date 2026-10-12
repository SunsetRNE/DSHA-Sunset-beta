package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 一键自举链的续链与总开关意图判定；纯逻辑，不启动 Android。 */
public final class AdbBootstrapChainPlanTest {
  private static AdbBootstrapChainPlan.Outcome after(
      boolean chainActive,
      boolean stepSucceeded,
      boolean intentUnchanged,
      boolean nextExists,
      boolean nextAccepted) {
    return AdbBootstrapChainPlan.after(
        chainActive, stepSucceeded, intentUnchanged, nextExists, nextAccepted);
  }

  @Test
  public void submittedNextStepContinues() {
    assertEquals(AdbBootstrapChainPlan.Outcome.CONTINUE, after(true, true, true, true, true));
  }

  @Test
  public void rejectedNextStepAbortsInsteadOfReportingSuccess() {
    // F04：下一步（OPEN）提交失败时队列看起来"空"，旧实现会据此报告整链成功。
    assertEquals(AdbBootstrapChainPlan.Outcome.ABORT, after(true, true, true, true, false));
  }

  @Test
  public void intentChangeBetweenStepsStopsAllFurtherWrites() {
    // F01：用户在步骤之间关闭 / 重新选择总开关后，旧确认不得再派发新的设备写入，
    // 即使下一步本来可以受理。
    assertEquals(AdbBootstrapChainPlan.Outcome.ABORT, after(true, true, false, true, true));
    assertEquals(AdbBootstrapChainPlan.Outcome.ABORT, after(true, true, false, true, false));
    // 但最后一步已经完成时，用户随后怎么改开关都不算旧链的失败。
    assertEquals(AdbBootstrapChainPlan.Outcome.COMPLETE, after(true, true, false, false, false));
  }

  @Test
  public void onlyAnExhaustedQueueAfterASuccessfulStepCompletes() {
    assertEquals(AdbBootstrapChainPlan.Outcome.COMPLETE, after(true, true, true, false, false));
  }

  @Test
  public void failureOrUnclearedPendingStopsTheChain() {
    for (boolean intent : new boolean[] {false, true})
      for (boolean nextExists : new boolean[] {false, true})
        for (boolean accepted : new boolean[] {false, true})
          assertEquals(
              AdbBootstrapChainPlan.Outcome.ABORT,
              after(true, false, intent, nextExists, accepted));
  }

  @Test
  public void nothingHappensWhenNoChainIsRunning() {
    assertEquals(AdbBootstrapChainPlan.Outcome.IDLE, after(false, true, true, true, true));
  }

  @Test
  public void autoEnableOnlySurvivesAnUnchangedUserIntent() {
    assertTrue(AdbBootstrapChainPlan.autoEnableAllowed(7L, 7L));
    // 用户在途中关闭或重新选择：代次变化使旧链的自动启用失效。
    assertFalse(AdbBootstrapChainPlan.autoEnableAllowed(7L, 8L));
    assertFalse(AdbBootstrapChainPlan.autoEnableAllowed(8L, 7L));
  }
}
