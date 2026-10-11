package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.AdbBootstrapChainPlan;
import com.deepseekharness.app.util.Constants;

/**
 * ADB 总开关的唯一受管写入口（F01）。
 *
 * <p>「用户看到的值」和「用户意图代次」分开保存：
 *
 * <ul>
 *   <li>用户在界面上的每一次显式选择都经 {@link #userSet}，写值并递增代次；因此关闭再开启
 *       也是两次新的意图。
 *   <li>后台自动启用（自举成功后打开通道）经 {@link #autoEnable}，只在最初确认捕获的代次
 *       仍然有效时提交，且**不递增代次** —— 自动写入不是用户的新选择，不能被后续步骤误认成
 *       「用户又撤销了一次」。
 * </ul>
 *
 * <p>因此「核对意图 → 写值」是一个原子段；调用方随后必须重新读取真实值再决定是否启动服务。
 */
public final class AdbSwitch {
  public static final String VALUE = "adb_enabled";
  public static final String REVISION = "adb_enabled_revision";
  private static final Object LOCK = new Object();

  private AdbSwitch() {}

  public static boolean enabled(Context ctx) {
    return prefs(ctx).getBoolean(VALUE, false);
  }

  public static long revision(Context ctx) {
    return prefs(ctx).getLong(REVISION, 0L);
  }

  /** 用户显式选择：写值并递增意图代次。 */
  public static boolean userSet(Context ctx, boolean value) {
    synchronized (LOCK) {
      long revision = revision(ctx);
      if (revision == Long.MAX_VALUE) return false;
      return prefs(ctx)
          .edit()
          .putBoolean(VALUE, value)
          .putLong(REVISION, revision + 1)
          .commit();
    }
  }

  /**
   * 后台自动启用：仅当意图代次仍等于确认时捕获的代次才写入。
   *
   * @return 是否已（或本来就是）符合本次确认的开启状态；被用户的新意图拒绝时返回 false
   */
  public static boolean autoEnable(Context ctx, long capturedRevision) {
    synchronized (LOCK) {
      if (!AdbBootstrapChainPlan.autoEnableAllowed(capturedRevision, revision(ctx)))
        return false;
      if (enabled(ctx)) return true;
      return prefs(ctx).edit().putBoolean(VALUE, true).commit();
    }
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
  }
}
