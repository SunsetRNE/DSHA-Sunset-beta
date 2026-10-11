package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.AdbBootstrapPolicy.Action;
import org.junit.Test;

/** 自举链状态机的步骤归属与队列语义；纯逻辑，能替控制器回答「下一步该不该发」。 */
public final class AdbBootstrapChainSessionTest {
  @Test
  public void chainRunsAuthorizeThenNarrowThenOpen() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    assertTrue(session.start(5L));
    assertTrue(session.active());
    assertEquals(Action.AUTHORIZE, session.step());
    assertEquals(java.util.List.of(Action.NARROW, Action.OPEN), session.remaining());
    assertTrue(session.beginNext());
    assertEquals(Action.NARROW, session.step());
    assertEquals(java.util.List.of(Action.OPEN), session.remaining());
    assertTrue(session.beginNext());
    assertEquals(Action.OPEN, session.step());
    assertFalse("队列取空即整链结束", session.beginNext());
    assertFalse(session.active());
    assertEquals(java.util.List.of(), session.remaining());
  }

  @Test
  public void chainOnlyAcceptsItsOwnCurrentStep() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    session.start(5L);
    // 链自己的当前步骤可以派发。
    assertTrue(session.accepts(Action.AUTHORIZE, true));
    // 还没轮到的步骤不行；外部动作（另一个 STATUS / 另一条链）也不行。
    assertFalse(session.accepts(Action.NARROW, true));
    assertFalse(session.accepts(Action.OPEN, true));
    assertFalse(session.accepts(Action.STATUS, false));
    assertFalse(session.accepts(null, true));
    // 链结束后外部动作恢复可用。
    session.end();
    assertTrue(session.accepts(Action.STATUS, false));
  }

  @Test
  public void standaloneActionsDoNotDisturbARunningChain() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    assertTrue(session.accepts(Action.STATUS, false));
    session.captureStandalone(11L);
    assertEquals(11L, session.intentRevision());
    // 单动作不激活链。
    assertFalse(session.active());
  }

  @Test
  public void chainCapturesTheUserIntentOnlyOnce() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    session.start(7L);
    assertEquals(7L, session.intentRevision());
    session.beginNext();
    // 链内步进不允许重新捕获：用户在步骤之间改开关不会「借新票据复活旧链」。
    session.captureStandalone(8L);
    assertEquals(7L, session.intentRevision());
  }

  @Test
  public void secondChainCannotStartWhileOneIsRunning() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    assertTrue(session.start(1L));
    assertFalse(session.start(2L));
    assertEquals(1L, session.intentRevision());
  }

  @Test
  public void endClearsEverythingForTheNextConfirmation() {
    AdbBootstrapChainSession session = new AdbBootstrapChainSession();
    session.start(3L);
    session.beginNext();
    session.end();
    assertFalse(session.active());
    assertFalse(session.hasNext());
    assertEquals(java.util.List.of(), session.remaining());
    assertTrue(session.start(4L));
    assertEquals(Action.AUTHORIZE, session.step());
    assertEquals(4L, session.intentRevision());
  }
}
