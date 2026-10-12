package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 开机自启（参考 thedjchi/Shizuku 增强版的核心机制）：
 * 1. 若已获得 WRITE_SECURE_SETTINGS 权限（首次 ADB 配对成功后自动 pm grant）：
 *    → 开机广播直接 Settings.Global.adb_wifi_enabled=1 自动开启无线调试
 *      （无需用户手动去开发者选项开！这正是 thedjchi fork 的卖点）
 * 2. 拉起 DeviceBridgeService → 连接看门狗自动发现端口并重连已配对设备
 */
public class BootReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    try {
      String action = intent == null ? "" : intent.getAction();
      if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
          && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
          && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
        return;
      }
      if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action))
        com.deepseekharness.app.backup.AutomaticBackups.schedule(context);
      if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
        com.deepseekharness.app.backup.PostUpgradeCleanupService.schedule(context);
      // 用户没开 ADB 就不拉起（尊重开关）
      if (!DeviceBridgeService.isAdbEnabled(context)) return;
      android.util.Log.i(
          "DSHA-ADB", com.deepseekharness.app.util.UiText.text("开机/升级自启：尝试自动开启无线调试 + 拉起设备桥"));

      // 关键（thedjchi/Shizuku 机制）：有 WRITE_SECURE_SETTINGS 权限 → 直接开无线调试
      boolean hasSecure = false;
      try {
        hasSecure =
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
      } catch (Throwable ignored) {
      }
      // F02：所有自动开启无线调试的入口共用同一无副作用判据。开机/升级时进程可能是刚起来的，
      // 判据从持久化的保守互斥意图恢复；被拦住时只在日志里说明，不写系统设置。
      // 直接启动（LOCKED_BOOT_COMPLETED）下凭据加密存储可能还不可读：读不到本机证据时保守禁止。
      boolean reopenBlocked;
      try {
        reopenBlocked =
            com.deepseekharness.app.core.AdbBootstrapController.wirelessReopenBlocked(context);
      } catch (Throwable unavailable) {
        reopenBlocked = true;
      }
      boolean alreadyOn = false;
      if (hasSecure && !reopenBlocked) {
        try {
          alreadyOn =
              Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable unreadable) {
          alreadyOn = false;
        }
      }
      com.deepseekharness.app.util.WirelessBootAction.Decision decision =
          com.deepseekharness.app.util.WirelessBootAction.decide(
              hasSecure, reopenBlocked, alreadyOn);
      if (com.deepseekharness.app.util.WirelessBootAction.writesSettings(decision)) {
        try {
          Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
          android.util.Log.i(
              "DSHA-ADB",
              com.deepseekharness.app.util.UiText.text("已自动开启无线调试（WRITE_SECURE_SETTINGS）"));
        } catch (Throwable e) {
          android.util.Log.w(
              "DSHA-ADB",
              com.deepseekharness.app.util.UiText.format(
                  "自动开无线调试失败: %s", SensitiveData.redact(String.valueOf(e))));
        }
      } else if (decision == com.deepseekharness.app.util.WirelessBootAction.Decision.SKIP_MUTEX) {
        android.util.Log.i(
            "DSHA-ADB",
            com.deepseekharness.app.util.UiText.text("自举互斥意图生效：开机/升级不自动开启无线调试（自举未决或最近一次正因它被拒）"));
      } else if (decision
          == com.deepseekharness.app.util.WirelessBootAction.Decision.SKIP_NO_PERMISSION) {
        android.util.Log.i(
            "DSHA-ADB",
            com.deepseekharness.app.util.UiText.text("无 WRITE_SECURE_SETTINGS 权限，靠 Shizuku/看门狗兜底"));
      }

      // 拉起设备桥（看门狗会自动发现端口重连）
      DeviceBridgeService.apply(context);
      // 重启后系统会清掉所有闹钟，Doze 兜底得重新排一次
      AdbKeepAliveReceiver.schedule(context);
    } catch (Throwable ignored) {
    }
  }
}
