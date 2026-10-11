package com.deepseekharness.app.util;

/**
 * 一键自举链的纯逻辑计划：续链判定与「总开关意图」判定。
 *
 * <p>控制器只负责 I/O 与派发，这两条容易出错的判断放在这里，可以被主机测试直接击中：
 *
 * <ul>
 *   <li>F04：下一步提交失败必须终止整链。旧实现先 {@code poll()} 取走下一步，再用
 *       「队列为空」判成功 —— 提交失败反而报告整链成功并执行成功后的副作用。
 *   <li>F01：链在途时用户改过总开关，旧完成回调不得再把它写回来。自动启用只能在
 *       最初确认捕获的意图代次仍然有效时提交；自动写入本身不递增代次，不会被误认成
 *       用户的新撤销。
 * </ul>
 */
public final class AdbBootstrapChainPlan {
  /** 链的下一步归属。 */
  public enum Outcome {
    /** 链未在运行，无需处理。 */
    IDLE,
    /** 已受理派发下一步。 */
    CONTINUE,
    /** 最后一步已成功且无未决，整链成功。 */
    COMPLETE,
    /** 终止：失败、未决未清、提交被拒或下一步无处可去。 */
    ABORT
  }

  private AdbBootstrapChainPlan() {}

  /**
   * @param chainActive 链是否在运行
   * @param stepSucceeded 当前步骤是否成功且结果已持久化、未决状态已确认清理
   * @param intentUnchanged 用户对 ADB 总开关的最初确认是否仍然有效（F01）
   * @param nextExists 是否还有下一步（决定前**不得**先把它取走）
   * @param nextAccepted 下一步是否真的被受理
   */
  public static Outcome after(
      boolean chainActive,
      boolean stepSucceeded,
      boolean intentUnchanged,
      boolean nextExists,
      boolean nextAccepted) {
    if (!chainActive) return Outcome.IDLE;
    if (!stepSucceeded) return Outcome.ABORT;
    // 最后一步已经完成：此后用户怎么改总开关是他自己的选择，不再算旧链的失败。
    if (!nextExists) return Outcome.COMPLETE;
    // 还有下一步要写设备：用户在步骤之间关闭或重新选择总开关后，旧确认不再允许新的写入。
    if (!intentUnchanged) return Outcome.ABORT;
    return nextAccepted ? Outcome.CONTINUE : Outcome.ABORT;
  }

  /** 后台自动启用是否仍被最初确认的用户意图允许。 */
  public static boolean autoEnableAllowed(long capturedRevision, long currentRevision) {
    return capturedRevision == currentRevision;
  }
}
