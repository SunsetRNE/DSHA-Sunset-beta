package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 一键自举链的状态机（F01/F04）：谁在跑、当前步骤、还剩哪些步骤、最初确认的总开关意图。
 *
 * <p>控制器（Android 适配器）只负责设备 I/O 与派发；这里保存的规则可以被主机测试直接击中：
 *
 * <ul>
 *   <li>链进行中**只接受当前步骤**的派发；外部动作（另一个 STATUS / 另一条链）不能趁步骤
 *       之间的空档插入并改变归属。
 *   <li>只捕获**一次**总开关意图：链内步进沿用最初确认；单动作各自捕获自己的代次。
 *   <li>当前步骤成功后才推进到下一步；队列空时结束整链 —— 「队列为空」永远在派发**之前**
 *       判定，不会像旧实现那样先取走下一步、再把取空当成整链成功。
 * </ul>
 *
 * <p>方法都是同步的：控制器可能在主线程（UI 提交）与 worker 线程（续链）同时触碰它。
 */
public final class AdbBootstrapChainSession {
  private final ArrayDeque<AdbBootstrapPolicy.Action> remaining = new ArrayDeque<>();
  private boolean active;
  private AdbBootstrapPolicy.Action step;
  private long intentRevision;

  /** 开始整链（固定顺序 AUTHORIZE → NARROW → OPEN）；已在链中时拒绝。 */
  public synchronized boolean start(long capturedIntentRevision) {
    if (active) return false;
    remaining.clear();
    remaining.add(AdbBootstrapPolicy.Action.NARROW);
    remaining.add(AdbBootstrapPolicy.Action.OPEN);
    active = true;
    step = AdbBootstrapPolicy.Action.AUTHORIZE;
    intentRevision = capturedIntentRevision;
    return true;
  }

  public synchronized boolean active() {
    return active;
  }

  public synchronized AdbBootstrapPolicy.Action step() {
    return step;
  }

  public synchronized long intentRevision() {
    return intentRevision;
  }

  public synchronized boolean hasNext() {
    return active && !remaining.isEmpty();
  }

  /** 剩余步骤（下一步在队首）；决定「是否完成」前不得取走。 */
  public synchronized List<AdbBootstrapPolicy.Action> remaining() {
    return List.copyOf(remaining);
  }

  /** 链进行中只接受链自己的当前步骤；非链动作（单动作）始终可受理。 */
  public synchronized boolean accepts(AdbBootstrapPolicy.Action action, boolean fromChain) {
    if (action == null) return false;
    if (!active) return true;
    return fromChain && action == step;
  }

  /** 单动作在派发时捕获自己的总开关意图代次；链内不重新捕获。 */
  public synchronized void captureStandalone(long currentIntentRevision) {
    if (!active) intentRevision = currentIntentRevision;
  }

  /**
   * 当前步骤成功后推进到下一步。
   *
   * @return false 表示队列已空、整链结束（此时 {@link #active()} 已为 false）
   */
  public synchronized boolean beginNext() {
    if (!active) return false;
    if (remaining.isEmpty()) {
      end();
      return false;
    }
    step = remaining.pollFirst();
    return true;
  }

  /** 终止并清空（失败、未决未清、意图改变或整链结束都走这里）。 */
  public synchronized void end() {
    active = false;
    remaining.clear();
  }
}
