package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.FakeSharedPreferences;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.RootBootstrapAuthority;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.runtime.RuntimeHostPorts;
import com.deepseekharness.app.util.AdbAuthorizedKeysTest;
import com.deepseekharness.app.util.AdbBootstrapPolicy;
import com.deepseekharness.app.util.AdbBootstrapReply;
import com.deepseekharness.app.util.Constants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * 控制器 I/O 粘合层的组合回归（F01/F04，修复计划 §7.2 的剩余控制器级用例）。
 *
 * <p>用真实的生产 {@link AdbBootstrapController} 配最小 Android / 设备替身：可控执行器决定
 * 何时推进步骤，可控等待在 2.5 秒窗口内模拟撤销，内存偏好可注入请求 / 结果保存失败。
 * 断言只关心外部可见行为：派发了什么、没有派发什么、开关有没有被反转、有没有把未知
 * 结果存成成功。状态机本身的规则另有 {@code util/AdbBootstrapChainSessionTest}。
 *
 * <p>红灯证据（2026-10-11，接缝已加、两条行为修复未加时同一套测试的结果）：
 *
 * <pre>
 * 1) autoEnableCommitFailureIsReportedAsItselfNotAsAUserChange
 *    expected:&lt;ADB_SWITCH_ENABLE_NOT_PERSISTED&gt; but was:&lt;ADB_SWITCH_INTENT_CHANGED&gt;
 * 2) channelStartFailureIsAlsoReportedAsItself
 *    expected:&lt;ADB_CHANNEL_START_FAILED&gt; but was:&lt;ADB_SWITCH_INTENT_CHANGED&gt;
 * 3) revokingTheSwitchDuringTheVerifyWaitRecordsUnknownInsteadOfSuccess
 *    AssertionError: Values should be different. Actual: OK
 * Tests run: 13, Failures: 3
 * </pre>
 *
 * <p>红灯证据（第二轮定向返修：只读 `id` 核验**执行期间**关闭 / 关闭再开启总开关）。
 * {@code AdbBridge.verifyHook} 只在替身里存在，让用户在核验返回之前插入选择；旧控制器在
 * 核验后只看原生授权 {@code valid()}，不看总开关代次，于是三次都报 OK：
 *
 * <pre>
 * 1) revokingTheSwitchDuringTheReadOnlySessionVerifyNeverReportsSuccess
 *    AssertionError: Values should be different. Actual: OK
 * 2) closingThenReopeningTheSwitchDuringTheReadOnlySessionVerifyCannotReviveTheOldChain
 *    AssertionError: Values should be different. Actual: OK
 * 3) standaloneOpenRejectsAVerificationThatFinishedAfterTheUserRevoked
 *    AssertionError: Values should be different. Actual: OK
 * Tests run: 17, Failures: 3
 * </pre>
 *
 * <p>其余用例在旧逻辑下即为绿灯，是本次交付的组合行为护栏（请求 / 结果保存失败、OPEN
 * 推进后被拒、并发 STATUS、步骤之间关闭再开启、等待期间撤销 Root 授权，以及「成功已发布
 * 之后再关闭不改写历史」的线性化点护栏）。
 */
public final class AdbBootstrapControllerTest {
  /** 手动执行器：任务只在测试要求时运行，从而能在步骤之间插入并发操作。 */
  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      while (!tasks.isEmpty()) tasks.poll().run();
    }
  }

  private static final class Rig {
    final Context app;
    final FakeSharedPreferences records;
    /** ADB 总开关的存储（与自举记录分开，避免把两个真相混在一个门里）。 */
    final FakeSharedPreferences switchPrefs;
    final ManualExecutor executor = new ManualExecutor();
    final AdbBootstrapController controller;
    Runnable settle = () -> {};
    /** 只读会话核验内部、返回之前的剧本：模拟核验执行期间的用户操作。 */
    Runnable verifyHook = () -> {};

    Rig(Path filesDir) throws Exception {
      app = new Context(filesDir.toFile());
      records = app.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
      switchPrefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
      Path keys = filesDir.resolve("adbkeys");
      Files.createDirectories(keys);
      Files.write(
          keys.resolve("adbkey.pub"),
          (AdbAuthorizedKeysTest.key(3) + " dsha").getBytes(StandardCharsets.US_ASCII));
      RootShell.reset();
      RootBootstrapAuthority.reset();
      DeviceBridgeService.reset();
      AdbBridge.reset();
      RuntimeHostPorts.reset();
      RootShell.responder = AdbBootstrapControllerTest::success;
      AdbBridge.verifyResult = "uid=0(root) gid=0(root)\n[EXIT=0]";
      AdbBridge.verifyHook = () -> verifyHook.run();
      controller = new AdbBootstrapController(app, executor, () -> settle.run());
    }

    boolean persistedPending() {
      return records.getBoolean("bootstrap_request_pending", false);
    }
  }

  private static Rig rig() throws Exception {
    return new Rig(Files.createTempDirectory("dsha-bootstrap-controller-"));
  }

  private static String success(AdbBootstrapPolicy.Action action) {
    String listener = action == AdbBootstrapPolicy.Action.OPEN ? "OPEN" : "CLOSED";
    return AdbBootstrapReply.encode(
        "OK", "COMPLETE", "OWNED", listener, "PROTECTED", "", true, "", "");
  }

  private static FakeSharedPreferences.CommitGate alwaysFailContaining(String key) {
    return keys -> !keys.contains(key);
  }

  private static FakeSharedPreferences.CommitGate failOnceContaining(String key) {
    boolean[] failed = {false};
    return keys -> {
      if (!failed[0] && keys.contains(key)) {
        failed[0] = true;
        return false;
      }
      return true;
    };
  }

  private static FakeSharedPreferences.CommitGate failAfter(int allowedCommits, String key) {
    int[] seen = {0};
    return keys -> {
      if (keys.contains(key) && ++seen[0] > allowedCommits) return false;
      return true;
    };
  }

  @Test
  public void chainRunsAllThreeStepsThenEnablesTheChannelWithTheConfirmedIntent() throws Exception {
    Rig rig = rig();

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN),
        RootShell.calls);
    assertEquals(1, AdbBridge.verifyCalls);
    assertEquals(1, DeviceBridgeService.applies);
    assertTrue(AdbSwitch.enabled(rig.app));
    // 自动启用不是用户的新选择：写值但不动意图代次。
    assertEquals(0L, AdbSwitch.revision(rig.app));
    assertFalse(rig.controller.chaining());
    assertFalse(rig.controller.needsObservation());
    assertFalse(rig.controller.snapshot().busy);
    assertEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("ADB_SESSION_ROOT", rig.controller.snapshot().detail);
    assertFalse(rig.persistedPending());
    // 自举互斥保持：整链成功也不代表用户选择了无线调试（F02）。
    assertTrue(rig.controller.blocksWirelessReopen());
  }

  @Test
  public void closingTheSwitchBetweenStepsStopsAllFurtherDeviceWrites() throws Exception {
    Rig rig = rig();
    RootShell.responder =
        action -> {
          if (action == AdbBootstrapPolicy.Action.NARROW) AdbSwitch.userSet(rig.app, false);
          return success(action);
        };

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(
        List.of(AdbBootstrapPolicy.Action.AUTHORIZE, AdbBootstrapPolicy.Action.NARROW),
        RootShell.calls);
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(1L, AdbSwitch.revision(rig.app));
    assertEquals(0, DeviceBridgeService.applies);
    assertEquals(0, AdbBridge.verifyCalls);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    assertTrue(rig.persistedPending());
    assertTrue(rig.controller.blocksWirelessReopen());
    // 旧链的下一步迟到也不能再写设备：未决未清时写入动作直接拒绝。
    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.OPEN));
    assertEquals(
        List.of(AdbBootstrapPolicy.Action.AUTHORIZE, AdbBootstrapPolicy.Action.NARROW),
        RootShell.calls);
  }

  @Test
  public void requestRecordFailureDispatchesNothingAndReleasesTheSingleFlight() throws Exception {
    Rig rig = rig();
    rig.records.gate(failOnceContaining("bootstrap_request_pending"));

    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.AUTHORIZE));

    assertTrue(RootShell.calls.isEmpty());
    assertEquals("REFUSED", rig.controller.snapshot().outcome);
    assertEquals("REQUEST_RECORD_FAILED", rig.controller.snapshot().detail);
    assertFalse(rig.controller.chaining());
    assertFalse(AdbSwitch.enabled(rig.app));
    // 未派发的意图被撤去，而不是把一次没发出去的动作留成未决。
    assertFalse(rig.persistedPending());
    assertFalse(rig.controller.needsObservation());
    // 单飞已经释放：只读刷新仍可提交，设备写入动作则受未决屏障约束。
    assertTrue(rig.controller.submit(AdbBootstrapPolicy.Action.STATUS));
    rig.executor.run();
    assertEquals(
        List.of(AdbBootstrapPolicy.Action.STATUS), RootShell.calls);
  }

  @Test
  public void requestRollbackFailureKeepsTheUnknownBarrierWithoutDispatching() throws Exception {
    Rig rig = rig();
    rig.records.gate(alwaysFailContaining("bootstrap_request_pending"));

    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.AUTHORIZE));

    assertTrue(RootShell.calls.isEmpty());
    assertFalse(rig.controller.chaining());
    assertFalse(AdbSwitch.enabled(rig.app));
    // 连回滚都没落盘：不能假装"什么都没发生"，保守保留未决。
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.controller.blocksWirelessReopen());
  }

  @Test
  public void resultRecordFailureStopsTheChainAndKeepsTheUnknownResult() throws Exception {
    Rig rig = rig();
    rig.records.gate(alwaysFailContaining("bootstrap_last_outcome"));

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(List.of(AdbBootstrapPolicy.Action.AUTHORIZE), RootShell.calls);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("RESULT_RECORD_FAILED", rig.controller.snapshot().detail);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.persistedPending());
    assertTrue(rig.controller.blocksWirelessReopen());
    // 结果没存下来就不续链：迟到的 NARROW 也不能派发。
    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.NARROW));
    assertEquals(List.of(AdbBootstrapPolicy.Action.AUTHORIZE), RootShell.calls);
  }

  @Test
  public void rejectedOpenAfterTheStateMachineAdvancedNeverReportsSuccess() throws Exception {
    Rig rig = rig();
    RootShell.responder =
        action -> {
          if (action == AdbBootstrapPolicy.Action.NARROW) RootBootstrapAuthority.allowed = false;
          return success(action);
        };

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(
        List.of(AdbBootstrapPolicy.Action.AUTHORIZE, AdbBootstrapPolicy.Action.NARROW),
        RootShell.calls);
    assertFalse(rig.controller.chaining());
    assertEquals(AdbBootstrapPolicy.Action.OPEN, rig.controller.snapshot().action);
    assertEquals("REFUSED", rig.controller.snapshot().outcome);
    assertEquals("NATIVE_GRANT_OR_SU_UNAVAILABLE", rig.controller.snapshot().detail);
    assertNotEquals("OK", rig.controller.snapshot().outcome);
    // 推进过的步骤没有换来任何设备写入或通道开启。
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(0, DeviceBridgeService.applies);
    assertEquals(0, AdbBridge.verifyCalls);
  }

  @Test
  public void resultRecordFailureOnTheLastStepNeverClaimsACompletedChain() throws Exception {
    Rig rig = rig();
    // 前两步的结果允许落盘，OPEN（最后一步）的结果保存失败。
    rig.records.gate(failAfter(2, "bootstrap_last_outcome"));

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN),
        RootShell.calls);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("RESULT_RECORD_FAILED", rig.controller.snapshot().detail);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.persistedPending());
    assertTrue(rig.controller.blocksWirelessReopen());
    // 监听与开关是真实设备状态：结果未知时保留，不假装成功也不撤销用户选择。
    assertEquals(1, AdbBridge.verifyCalls);
    assertEquals(1, DeviceBridgeService.applies);
    assertTrue(AdbSwitch.enabled(rig.app));
  }

  @Test
  public void revokingTheSwitchDuringTheVerifyWaitRecordsUnknownInsteadOfSuccess()
      throws Exception {
    Rig rig = rig();
    rig.settle = () -> AdbSwitch.userSet(rig.app, false);

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    // 旧逻辑：等待结束后仍核验并通过，整链被报告成 OK；用户撤销被静默吞掉。
    assertNotEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(1L, AdbSwitch.revision(rig.app));
    // 撤销后不再继续核验、不再写设备；未知结果与失败原文都留下。
    assertEquals(0, AdbBridge.verifyCalls);
    assertTrue(rig.controller.needsObservation());
    assertFalse(rig.controller.chaining());
    assertTrue(rig.persistedPending());
    assertTrue(rig.controller.blocksWirelessReopen());
    // 失败原文留在快照里，界面才有「查看失败原文」可点。
    assertFalse(rig.controller.snapshot().evidence.isEmpty());
  }

  @Test
  public void revokingTheNativeGrantDuringTheVerifyWaitIsAlsoUnknown() throws Exception {
    Rig rig = rig();
    rig.settle = () -> RootBootstrapAuthority.current.revoke();

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("NATIVE_GRANT_REVOKED", rig.controller.snapshot().detail);
    assertEquals(0, AdbBridge.verifyCalls);
    assertTrue(rig.controller.needsObservation());
    assertFalse(rig.controller.chaining());
    assertTrue(rig.persistedPending());
  }

  @Test
  public void autoEnableCommitFailureIsReportedAsItselfNotAsAUserChange() throws Exception {
    Rig rig = rig();
    // 只让"写总开关"这一步失败：意图代次没变，用户也没关闭任何东西。
    rig.switchPrefs.gate(alwaysFailContaining("adb_enabled"));

    assertTrue(rig.controller.submit(AdbBootstrapPolicy.Action.OPEN));
    rig.executor.run();

    assertNotEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_ENABLE_NOT_PERSISTED", rig.controller.snapshot().detail);
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(0L, AdbSwitch.revision(rig.app));
    assertEquals(0, DeviceBridgeService.applies);
    assertEquals(0, AdbBridge.verifyCalls);
    assertTrue(rig.controller.needsObservation());
  }

  @Test
  public void channelStartFailureIsAlsoReportedAsItself() throws Exception {
    Rig rig = rig();
    DeviceBridgeService.failApply = true;

    assertTrue(rig.controller.submit(AdbBootstrapPolicy.Action.OPEN));
    rig.executor.run();

    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_CHANNEL_START_FAILED", rig.controller.snapshot().detail);
    // 开关写值已经落盘：不假装撤销它，也不继续核验。
    assertTrue(AdbSwitch.enabled(rig.app));
    assertEquals(1, DeviceBridgeService.applies);
    assertEquals(0, AdbBridge.verifyCalls);
    assertTrue(rig.controller.needsObservation());
  }

  @Test
  public void concurrentStatusCannotJoinAnActiveChainAndIsAllowedOnlyAfterwards()
      throws Exception {
    Rig rig = rig();

    assertTrue(rig.controller.submitBootstrapChain());
    // 链在途（步骤已派发、还没推进）：外部只读刷新不能插入并改变归属。
    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.STATUS));
    assertTrue(RootShell.calls.isEmpty());

    rig.executor.run();
    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN),
        RootShell.calls);
    assertTrue(AdbSwitch.enabled(rig.app));
    assertEquals(1, DeviceBridgeService.applies);

    // 链结束后是一次新的独立只读请求：不碰开关、不再开启通道。
    assertTrue(rig.controller.submit(AdbBootstrapPolicy.Action.STATUS));
    rig.executor.run();
    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN,
            AdbBootstrapPolicy.Action.STATUS),
        RootShell.calls);
    assertEquals(AdbBootstrapPolicy.Action.STATUS, rig.controller.snapshot().action);
    assertEquals("OK", rig.controller.snapshot().outcome);
    assertEquals(0L, AdbSwitch.revision(rig.app));
    assertEquals(1, DeviceBridgeService.applies);
  }

  @Test
  public void closingThenReopeningTheSwitchCannotReviveTheOldChain() throws Exception {
    Rig rig = rig();
    RootShell.responder =
        action -> {
          if (action == AdbBootstrapPolicy.Action.NARROW) {
            AdbSwitch.userSet(rig.app, false);
            AdbSwitch.userSet(rig.app, true);
          }
          return success(action);
        };

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertEquals(
        List.of(AdbBootstrapPolicy.Action.AUTHORIZE, AdbBootstrapPolicy.Action.NARROW),
        RootShell.calls);
    // 开关重新打开是用户自己的两次选择；旧链没有因此获得任何新的写入。
    assertTrue(AdbSwitch.enabled(rig.app));
    assertEquals(2L, AdbSwitch.revision(rig.app));
    assertEquals(0, DeviceBridgeService.applies);
    assertEquals(0, AdbBridge.verifyCalls);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    assertTrue(rig.controller.needsObservation());
    assertFalse(rig.controller.submit(AdbBootstrapPolicy.Action.OPEN));
    assertEquals(
        List.of(AdbBootstrapPolicy.Action.AUTHORIZE, AdbBootstrapPolicy.Action.NARROW),
        RootShell.calls);
  }

  /**
   * 竞态：只读 `id` 核验**正在执行**时用户关闭总开关。
   *
   * <p>旧逻辑只在等待之后、核验之前回查意图；核验返回后只看原生授权 {@code valid()}，不看
   * ADB 总开关代次，于是核验照常通过并把整链报成 OK / ADB_SESSION_ROOT。核验本身是真实观测，
   * 但「用户已撤销后的核验结果」不能再作为本次确认的成功依据。
   */
  @Test
  public void revokingTheSwitchDuringTheReadOnlySessionVerifyNeverReportsSuccess() throws Exception {
    Rig rig = rig();
    rig.verifyHook = () -> AdbSwitch.userSet(rig.app, false);

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertNotEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    // 用户的关闭保持原样：核验之后没有任何自动重开。
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(1L, AdbSwitch.revision(rig.app));
    // 单端点、单次只读 id：核验只发了一次；核验后没有重放 Root 写动作，也没有第二次自动启用。
    assertEquals(1, AdbBridge.verifyCalls);
    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN),
        RootShell.calls);
    assertEquals(1, DeviceBridgeService.applies);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.persistedPending());
    assertTrue(rig.controller.blocksWirelessReopen());
    // 核验原文保留在失败证据里：撤销不能让真实观测消失。
    assertFalse(rig.controller.snapshot().evidence.isEmpty());
  }

  /** 竞态：只读核验执行期间用户关闭再开启总开关，旧链同样不能借新选择复活。 */
  @Test
  public void closingThenReopeningTheSwitchDuringTheReadOnlySessionVerifyCannotReviveTheOldChain()
      throws Exception {
    Rig rig = rig();
    rig.verifyHook =
        () -> {
          AdbSwitch.userSet(rig.app, false);
          AdbSwitch.userSet(rig.app, true);
        };

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();

    assertNotEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    // 重新打开是用户自己的两次新选择：保持他的值，旧链没有因此获得任何新的成功判定。
    assertTrue(AdbSwitch.enabled(rig.app));
    assertEquals(2L, AdbSwitch.revision(rig.app));
    assertEquals(1, AdbBridge.verifyCalls);
    assertEquals(
        List.of(
            AdbBootstrapPolicy.Action.AUTHORIZE,
            AdbBootstrapPolicy.Action.NARROW,
            AdbBootstrapPolicy.Action.OPEN),
        RootShell.calls);
    assertEquals(1, DeviceBridgeService.applies);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.persistedPending());
    assertFalse(rig.controller.snapshot().evidence.isEmpty());
  }

  /** 同一竞态也覆盖 standalone OPEN：核验后的成功判定必须服从提交时捕获的用户意图。 */
  @Test
  public void standaloneOpenRejectsAVerificationThatFinishedAfterTheUserRevoked() throws Exception {
    Rig rig = rig();
    rig.verifyHook = () -> AdbSwitch.userSet(rig.app, false);

    assertTrue(rig.controller.submit(AdbBootstrapPolicy.Action.OPEN));
    rig.executor.run();

    assertNotEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("UNKNOWN", rig.controller.snapshot().outcome);
    assertEquals("ADB_SWITCH_INTENT_CHANGED", rig.controller.snapshot().detail);
    assertFalse(AdbSwitch.enabled(rig.app));
    assertEquals(1L, AdbSwitch.revision(rig.app));
    assertEquals(1, AdbBridge.verifyCalls);
    assertEquals(List.of(AdbBootstrapPolicy.Action.OPEN), RootShell.calls);
    assertEquals(1, DeviceBridgeService.applies);
    assertFalse(rig.controller.chaining());
    assertTrue(rig.controller.needsObservation());
    assertTrue(rig.persistedPending());
  }

  /**
   * 反例护栏：成功已经落盘之后用户再关闭，属于他自己的新状态，不能把历史成功追改成失败。
   *
   * <p>这条也是线性化点的选择依据：意图回查必须发生在成功快照**产生/落盘之前**（核验结果被
   * 接受的那一刻），而不是在 {@code advanceChain} 里回写已经保存的结果。
   */
  @Test
  public void aUserChangeAfterTheChainPublishedSuccessDoesNotRewriteHistory() throws Exception {
    Rig rig = rig();

    assertTrue(rig.controller.submitBootstrapChain());
    rig.executor.run();
    assertEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("ADB_SESSION_ROOT", rig.controller.snapshot().detail);
    assertFalse(rig.controller.needsObservation());

    assertTrue(AdbSwitch.userSet(rig.app, false));
    assertEquals(1L, AdbSwitch.revision(rig.app));

    assertEquals("OK", rig.controller.snapshot().outcome);
    assertEquals("ADB_SESSION_ROOT", rig.controller.snapshot().detail);
    assertFalse(rig.controller.needsObservation());
  }
}
