package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.LanListenerStatus;
import java.io.IOException;
import java.net.SocketException;
import org.junit.Test;

/**
 * 局域网监听状态与发布逻辑的纯逻辑回归（{@code LanListenerStatus} / {@code Registry}）。
 *
 * <p>这些用例不引用任何 Android API，也不经过 {@code LanProxyService}：后者的
 * {@code android.util.Log} 在普通 JVM 单测里是 not-mocked，一调就报错。生产
 * {@code LanProxyService} 的所有状态发布都委托给 {@link LanListenerStatus.Registry}，
 * 因此这里执行的就是生产状态机本身；真实 acceptLoop / start / stop 接线由
 * {@code tools/test-lan-listener-status.py} 的宿主 fixture 覆盖。</p>
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>IDLE/WAITING/FAILED 都不可复制链接，只有 BOUND 可以；</li>
 *   <li>快照同时携带轮次 epoch 与 dsh generation，二者不互相比较；</li>
 *   <li>旧轮次的迟到失败不能覆盖新轮次；</li>
 *   <li>按代次停止能清掉绑定失败（即使轮次已退出），也不会因数值巧合误清别的代次。</li>
 * </ol>
 */
public class LanListenerStatusTest {

  private static final int PORT = 3081;

  @Test
  public void idleIsNotCopyableAndCarriesNoIdentity() {
    LanListenerStatus.Snapshot idle = LanListenerStatus.idle();
    assertEquals(LanListenerStatus.State.IDLE, idle.state());
    assertEquals(0L, idle.epoch());
    assertEquals(0L, idle.generation());
    assertEquals(0, idle.port());
    assertFalse(idle.bound());
    assertFalse(idle.waitingAuth());
    assertFalse(idle.failed());
    assertEquals(null, idle.causeCode());
    assertEquals(null, idle.causeDetail());
  }

  @Test
  public void waitingIsNotBoundAndBindsBothEpochAndGeneration() {
    LanListenerStatus.Snapshot waiting = LanListenerStatus.waiting(7L, 3L, PORT);
    assertTrue(waiting.waitingAuth());
    assertFalse("等待绑定不能被当成已绑定", waiting.bound());
    assertFalse(waiting.failed());
    assertEquals(7L, waiting.epoch());
    assertEquals(3L, waiting.generation());
    assertEquals(PORT, waiting.port());
  }

  @Test
  public void boundIsTheOnlyCopyableState() {
    LanListenerStatus.Snapshot bound = LanListenerStatus.bound(9L, 4L, PORT);
    assertTrue(bound.bound());
    assertFalse(bound.waitingAuth());
    assertFalse(bound.failed());

    assertFalse(LanListenerStatus.waiting(9L, 4L, PORT).bound());
    assertFalse(LanListenerStatus.failed(9L, 4L, PORT, new SocketException("EADDRINUSE")).bound());
  }

  @Test
  public void portBoundsRejectZero() {
    assertFalse(LanListenerStatus.waiting(1L, 1L, 0).waitingAuth());
    assertFalse(LanListenerStatus.bound(1L, 1L, 0).bound());
    assertFalse(LanListenerStatus.failed(1L, 1L, 0, new SocketException("x")).failed());
    assertTrue(LanListenerStatus.waiting(1L, 1L, PORT).waitingAuth());
  }

  @Test
  public void classificationNeverEchoesRawMessages() {
    LanListenerStatus.Snapshot permission =
        LanListenerStatus.failed(
            1L,
            1L,
            PORT,
            new IOException("EACCES (Permission denied) /data/local/dsh-lan.log token=SECRET-XYZ"));
    assertEquals(LanListenerStatus.CAUSE_PERMISSION_DENIED, permission.causeCode());
    assertEquals("EACCES", permission.causeDetail());
    assertFalse(permission.toString().contains("token="));
    assertFalse(permission.toString().contains("SECRET-XYZ"));
    assertFalse(permission.toString().contains("/data/local"));

    LanListenerStatus.Snapshot unknown =
        LanListenerStatus.failed(2L, 1L, PORT, new IOException("/root/.dsh/secret-path failed"));
    assertEquals("未知失败不得冒充端口占用", LanListenerStatus.CAUSE_UNKNOWN, unknown.causeCode());
    assertEquals("java.io.IOException", unknown.causeDetail());
    assertFalse(unknown.toString().contains("secret-path"));
    assertFalse(unknown.toString().contains("failed"));

    LanListenerStatus.Snapshot inUse =
        LanListenerStatus.failed(3L, 1L, PORT, new SocketException("EADDRINUSE"));
    assertEquals(LanListenerStatus.CAUSE_PORT_IN_USE, inUse.causeCode());
    assertEquals("EADDRINUSE", inUse.causeDetail());
    assertEquals(
        LanListenerStatus.CAUSE_PORT_IN_USE,
        LanListenerStatus.classify(
            new java.net.BindException("bind failed: EADDRINUSE (Address already in use)")));
    assertEquals(
        LanListenerStatus.CAUSE_UNAVAILABLE,
        LanListenerStatus.classify(new java.net.BindException("bind failed: EADDRNOTAVAIL")));
  }

  @Test
  public void registryPublishesWaitingThenBoundAndRejectsStaleBound() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    assertNotNull(registry.snapshot());

    registry.begin(11L, 5L, PORT);
    LanListenerStatus.Snapshot waiting = registry.snapshot();
    assertTrue(waiting.waitingAuth());
    assertEquals(11L, waiting.epoch());
    assertEquals(5L, waiting.generation());
    assertTrue(registry.isCurrent(11L));

    assertFalse("旧轮次不能发布 BOUND", registry.bound(10L, PORT));
    assertEquals("旧轮次的 BOUND 被拒绝后状态不变", waiting, registry.snapshot());

    assertTrue(registry.bound(11L, PORT));
    LanListenerStatus.Snapshot bound = registry.snapshot();
    assertTrue(bound.bound());
    assertEquals(5L, bound.generation());
    assertFalse(bound.failed());
  }

  @Test
  public void registryRejectsLateFailureFromOldEpoch() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(20L, 6L, PORT);
    LanListenerStatus.Snapshot waiting = registry.snapshot();

    assertFalse(registry.fail(19L, PORT, new SocketException("EADDRINUSE")));
    assertEquals("旧轮次的迟到失败不得覆盖当前状态", waiting, registry.snapshot());
    assertTrue(registry.snapshot().waitingAuth());

    assertTrue(registry.fail(20L, PORT, new SocketException("EADDRINUSE")));
    LanListenerStatus.Snapshot failed = registry.snapshot();
    assertTrue(failed.failed());
    assertEquals(6L, failed.generation());
    assertFalse(failed.waitingAuth());
    assertFalse(failed.bound());
  }

  @Test
  public void stopByGenerationClearsFailureEvenAfterRoundEnded() {
    // 绑定失败后真实 acceptLoop 会退出（activeRun 清空）：按代次停止仍须清掉 FAILED。
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(30L, 7L, PORT);
    assertTrue(registry.fail(30L, PORT, new SocketException("EADDRINUSE")));
    assertTrue(registry.snapshot().failed());
    assertFalse("轮次已退出", registry.isCurrent(30L));

    registry.stopGeneration(7L);
    assertEquals(LanListenerStatus.State.IDLE, registry.snapshot().state());
    assertFalse(registry.snapshot().failed());
  }

  @Test
  public void stopByGenerationDoesNotClearUnrelatedGenerationOrEpoch() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(40L, 8L, PORT);
    assertTrue(registry.fail(40L, PORT, new SocketException("EADDRINUSE")));

    registry.stopGeneration(99L);
    assertTrue("别的代次停止不得误伤当前失败状态", registry.snapshot().failed());

    // 轮次号与代次号数值相同也不能互相比较：40 是 epoch，不是 generation。
    registry.stopGeneration(40L);
    assertTrue("epoch 数值不得被当作 generation 匹配", registry.snapshot().failed());

    registry.stopGeneration(8L);
    assertEquals(LanListenerStatus.State.IDLE, registry.snapshot().state());
  }

  @Test
  public void newRoundReplacesStaleFailureAndOldFailCannotReviveIt() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(60L, 10L, PORT);
    assertTrue(registry.fail(60L, PORT, new SocketException("EADDRINUSE")));

    registry.begin(61L, 10L, PORT);
    LanListenerStatus.Snapshot waiting = registry.snapshot();
    assertTrue(waiting.waitingAuth());
    assertFalse(waiting.failed());
    assertEquals(61L, waiting.epoch());

    assertFalse(registry.fail(60L, PORT, new SocketException("EADDRINUSE")));
    assertEquals("新轮次状态必须原样保留", waiting, registry.snapshot());
  }

  @Test
  public void endedOnlyClearsItsOwnEpoch() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(50L, 9L, PORT);
    registry.begin(51L, 9L, PORT);

    registry.ended(50L);
    assertEquals("旧轮次结束不得清掉新轮次", 51L, registry.snapshot().epoch());
    assertTrue(registry.snapshot().waitingAuth());

    registry.ended(51L);
    assertEquals(LanListenerStatus.State.IDLE, registry.snapshot().state());
    assertFalse(registry.isCurrent(51L));
  }

  @Test
  public void stopAllClearsWaitingBoundAndFailed() {
    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(70L, 11L, PORT);
    registry.stopAll();
    assertEquals(LanListenerStatus.State.IDLE, registry.snapshot().state());
    assertFalse(registry.isCurrent(70L));

    registry.begin(71L, 11L, PORT);
    assertTrue(registry.bound(71L, PORT));
    registry.stopAll();
    assertFalse(registry.snapshot().bound());

    registry.begin(72L, 11L, PORT);
    assertTrue(registry.fail(72L, PORT, new SocketException("EADDRINUSE")));
    registry.stopAll();
    assertFalse(registry.snapshot().failed());
  }
}
