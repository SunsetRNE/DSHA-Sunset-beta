package com.deepseekharness.app.util;

/**
 * Shizuku 的纯逻辑状态判定：它是**中间层管道**，自身仍需由 ADB 或 root 启动。
 *
 * <p>抽出来是为了两件事：一是让「装了 Shizuku ≠ 授权了 ADB」这条结论有可测的单一出处；
 * 二是给 Shizuku 补上此前完全缺失的专属单测（旧实现把判定和 Binder 访问搅在一起，
 * 单测跑不动）。
 */
public final class ShizukuStatus {
  private ShizukuStatus() {}

  public enum Level {
    NOT_INSTALLED,
    MANAGER_INSTALLED,
    PERMISSION_REQUIRED,
    CONNECTING,
    CONNECT_FAILED,
    READY
  }

  /** Shizuku 服务是谁拉起来的 —— UI 必须显示它，否则用户会误以为它是独立权威。 */
  public enum Source {
    ROOT,
    ADB,
    UNKNOWN
  }

  public static Level level(
      boolean available, boolean installed, boolean hasPermission, boolean ready, String failure) {
    if (!available) return installed ? Level.MANAGER_INSTALLED : Level.NOT_INSTALLED;
    if (!hasPermission) return Level.PERMISSION_REQUIRED;
    if (ready) return Level.READY;
    return failure == null || failure.isEmpty() ? Level.CONNECTING : Level.CONNECT_FAILED;
  }

  public static String describe(Level level, String failure) {
    switch (level) {
      case NOT_INSTALLED:
        return UiText.text("尚未安装 Shizuku。安装并启动后可在这里授权。");
      case MANAGER_INSTALLED:
        return UiText.text("管理器已安装，服务尚未连接。点击授权重新连接；若仍未连接，请启动管理器服务，并检查隐藏模式是否允许 DSHA。");
      case PERMISSION_REQUIRED:
        return UiText.text("服务已连接，尚未授权 DSHA。点击下方授权。");
      case READY:
        return UiText.text("已授权 · 设备 Shell 已连接，可直接使用，无需 ADB 配对");
      case CONNECT_FAILED:
        return UiText.format("已授权，连接失败：%s。点击下方重试。", failure);
      default:
        return UiText.text("已授权，正在连接设备 Shell…");
    }
  }

  public static Source source(boolean rootEnabled, boolean adbConnected) {
    if (rootEnabled) return Source.ROOT;
    if (adbConnected) return Source.ADB;
    return Source.UNKNOWN;
  }

  /** 无论处于哪个 Level，都必须如实说明它是中间层而不是权威。 */
  public static String sourceLabel(Source source) {
    switch (source) {
      case ROOT:
        return UiText.text("中间层 · 由 Root 启动，不是授权本身");
      case ADB:
        return UiText.text("中间层 · 由 ADB 启动，不是授权本身");
      default:
        return UiText.text("中间层 · 尚未观测到启动来源，不是授权本身");
    }
  }

  /** 一条管道只需一行，避免用户把「已装 Shizuku」读成「已授权设备命令」。 */
  public static String summary(Level level, String failure, Source source) {
    return describe(level, failure) + "；" + sourceLabel(source);
  }
}
