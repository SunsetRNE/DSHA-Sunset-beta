package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.MaintenanceCoordinator;

/**
 * 环境维护门禁的唯一判据（2026-10-12 实机反馈修正）。
 *
 * <p>门禁过去只写在 {@link MainActivity#onCreate} 里，于是**任何没有新建 MainActivity 的入口**
 * 都会整段跳过它：覆盖安装后从文件管理器「打开」、任务被系统恢复到别的 Activity（例如自举页）、
 * 已有任务回到前台、克隆 / 快捷方式打开 —— 用户看不到维护页，退出重开才会触发。
 *
 * <p>现在 MainActivity 与应用级 Activity 生命周期回调共用这一份判据，任何入口都先过它。
 * 安全性不依赖这个跳转：即使某个入口漏过 UI 重定向，Web 启动仍由
 * {@code WebLifecycleController} 用同一运行时门禁挡住，不会拿旧环境跑起来。
 */
public final class EnvironmentGate {
  private EnvironmentGate() {}

  /** 是否必须先进入环境维护页（未决事务优先，其次是运行身份 / 就绪状态）。 */
  public static boolean blocksEntry(Context context, HarnessController controller) {
    try {
      if (MaintenanceCoordinator.pending(controller)) return true;
      if (!controller.isEnvironmentReady()) return true;
      return EnvironmentAccess.requiresMaintenanceBeforeStart(controller);
    } catch (RuntimeException unavailable) {
      // 判据本身不可用时不强制跳转：宁可让 Web 门禁去挡，也不要让用户卡在进不去的界面。
      return false;
    }
  }

  /** 用户已经明确选择「受限主界面」时不再强制跳转；维护仍可从横幅进入。 */
  public static boolean limitedAllowed(Context context, HarnessController controller) {
    try {
      return new ConfigStore(context).allowsLimitedEntry(controller.proot().environmentIdentity());
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * 不需要重定向的页面：维护 / 首次设置 / 应急链路 / 维护相关任务页，以及显式要求跳过门禁的入口。
   * 重定向它们会和这些流程自己打架（例如维护页跳到带 {@code limited_entry} 的主界面后再被弹回来）。
   */
  private static boolean exempt(Activity activity) {
    Intent intent = activity.getIntent();
    if (intent != null
        && (intent.getBooleanExtra("limited_entry", false)
            || intent.getBooleanExtra("skip_extract", false)
            || intent.getBooleanExtra("review_only", false))) return true;
    if (activity instanceof ExtractActivity
        || activity instanceof WelcomeActivity
        || activity instanceof StartupRecoveryActivity
        || activity instanceof ModelSetupActivity
        || activity instanceof OnboardingPrepareActivity
        || activity instanceof OnboardingReadyActivity
        || activity instanceof ColdSetupSpeedActivity
        || activity instanceof RecoveryActivity
        || activity instanceof RecoveryWebActivity
        || activity instanceof NativeDataActivity
        || activity instanceof RetainedDataActivity
        || activity instanceof BackgroundTasksActivity
        || activity instanceof UpdateActivity
        || activity instanceof WebPreviewActivity
        || activity instanceof PictureInPictureActivity) return true;
    return activity.getClass().getName().startsWith("com.deepseekharness.app.recovery.");
  }

  /** 任意 Activity 回到前台时的门禁；必要且可跳转时进入维护页。 */
  public static void redirectIfNeeded(Activity activity) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    if (exempt(activity)) return;
    HarnessController controller;
    try {
      controller = HarnessController.get(activity);
    } catch (RuntimeException unavailable) {
      return;
    }
    if (!blocksEntry(activity, controller) || limitedAllowed(activity, controller)) return;
    try {
      activity.startActivity(
          new Intent(activity, ExtractActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      activity.finish();
    } catch (RuntimeException unavailable) {
      // 跳转失败不改变门禁结论；Web 侧仍有同一判据。
    }
  }
}
