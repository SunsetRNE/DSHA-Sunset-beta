package com.deepseekharness.app.util;

/**
 * 自举与系统「无线调试」互斥的共享判据（F02）。
 *
 * <p>自举要接管 ADB 监听，而系统无线调试是另一个独立的 ADB TLS 入口，两者互斥。任何
 * 「自动开启无线调试」的入口（保活探测、开机广播、覆盖升级广播）都必须先过这里的
 * **无副作用**判据：它只读本机证据，不申请 Root、不执行自举、不写系统设置。
 *
 * <p>互斥记录是**保守意图**，不是永久有效的设备健康证明：
 *
 * <ul>
 *   <li>运行中、在途链、上次结果未确认、已知最近一次正因无线调试被拒 —— 一律拦住。
 *   <li>进程死亡 / 升级后没有内存快照，靠持久化的保守意图恢复判据；旧数据里存在自举
 *       动作记录但无法确定是否清理时，同样保持禁止并提示显式观察。
 *   <li>解除需要两个条件同时成立：可信清理结果（两个未决阶段都已结束）**以及**用户明确
 *       切换到别的使用模式。一次成功的 STATUS 或无操作清理本身不构成「用户选择了无线调试」。
 * </ul>
 */
public final class WirelessReopenPolicy {
  private WirelessReopenPolicy() {}

  /**
   * @param running 自举任务正在执行
   * @param chainActive 一键自举链在途
   * @param unresolved 上次结果尚未确认
   * @param lastCode 最近一次结果码（可为空）
   * @param persistedBlock 持久化的保守互斥意图（含旧数据迁移判据）
   */
  public static boolean blocked(
      boolean running,
      boolean chainActive,
      boolean unresolved,
      String lastCode,
      boolean persistedBlock) {
    if (running || chainActive || unresolved || persistedBlock) return true;
    return "WIRELESS_DEBUG_IN_USE".equals(lastCode == null ? "" : lastCode);
  }

  /**
   * 持久化的保守互斥意图（F02 的迁移判据）。
   *
   * <p>新数据有显式记录就照记录；**旧数据没有显式记录**时，按「是否真的确认过会改设备状态的自举
   * 动作」保守迁移：只做过只读刷新（STATUS）或清理（CLOSE / REVOKE）的普通无线配对用户不应被拦住，
   * 而确认过 AUTHORIZE / NARROW / OPEN 的记录无法确定是否清理完，保持禁止自动重开并提示显式观察。
   *
   * @param hasExplicitRecord 是否已有显式的互斥记录
   * @param explicitValue 显式记录的值（仅当 {@code hasExplicitRecord} 为 true 时有意义）
   * @param lastBootstrapAction 最近一次提交的自举动作名（可为空）
   */
  public static boolean persistedBlock(
      boolean hasExplicitRecord, boolean explicitValue, String lastBootstrapAction) {
    if (hasExplicitRecord) return explicitValue;
    return "AUTHORIZE".equals(lastBootstrapAction)
        || "NARROW".equals(lastBootstrapAction)
        || "OPEN".equals(lastBootstrapAction);
  }

  /** 持久互斥意图何时可以解除：可信清理与用户显式选择，两者缺一不可。 */
  public static boolean releasable(
      boolean unresolved, boolean trustedCleanupConfirmed, boolean explicitModeChoice) {
    return !unresolved && trustedCleanupConfirmed && explicitModeChoice;
  }
}
