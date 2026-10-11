package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.AdbBootstrapController;
import com.deepseekharness.app.core.AdbSwitch;
import com.deepseekharness.app.core.DeviceAuthorizationReader;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.UiStateText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设备能力授权首页（ADR 0005）。
 *
 * <p>只保留两个入口：**ADB 全局授权**（入口行带总开关，开=全局授权、关=全局无 ADB 授权）与
 * **Root 授权**（独立权威，进独立页面）。四来源铺开、配对、Shizuku、自举都在各自的页面里，
 * 首页不再出现第二个真相。
 */
public final class DeviceGrantsFragment extends Fragment {
  private MaterialSwitch sms;
  private TextView smsStatus;
  private boolean updating;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshEntries =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshEntryRows();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_grants, container, false);
    Context ctx = requireContext();
    view.findViewById(R.id.entry_adb_authorization)
        .setOnClickListener(
            button -> startActivity(new Intent(ctx, AdbAuthorizationActivity.class)));
    view.findViewById(R.id.entry_root_authorization)
        .setOnClickListener(
            button -> startActivity(new Intent(ctx, RootAuthorizationActivity.class)));
    ((TextView) view.findViewById(R.id.computer_use_hint))
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "让助手读屏、点击和输入。先完成屏幕操作设置，再在对话中描述任务。",
                "Let the assistant observe, tap and type. Set up screen control, then describe the task in a conversation."));
    view.findViewById(R.id.computer_use_accessibility)
        .setOnClickListener(
            button ->
                startActivity(new Intent(requireContext(), AccessibilitySetupActivity.class)));
    android.widget.LinearLayout screenCard =
        (android.widget.LinearLayout) view.findViewById(R.id.computer_use_hint).getParent();
    android.widget.Button revoke = new androidx.appcompat.widget.AppCompatButton(ctx);
    revoke.setText(
        com.deepseekharness.app.util.UiText.choose(
            "撤销本次读屏与操作授权", "Revoke screen access for this run"));
    revoke.setTextSize(12);
    revoke.setAllCaps(false);
    revoke.setBackgroundResource(R.drawable.bg_btn);
    screenCard.addView(revoke, new android.widget.LinearLayout.LayoutParams(-1, -2));
    revoke.setOnClickListener(
        button -> {
          com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
          toast(
              com.deepseekharness.app.util.UiText.choose(
                  "已撤销，后续屏幕操作需要重新确认", "Revoked. Future screen actions require confirmation"));
        });
    android.widget.Button virtualScreen = new androidx.appcompat.widget.AppCompatButton(ctx);
    virtualScreen.setText(
        com.deepseekharness.app.util.UiText.choose("打开虚拟屏", "Open virtual screen"));
    virtualScreen.setAllCaps(false);
    virtualScreen.setTextSize(12);
    virtualScreen.setBackgroundResource(R.drawable.bg_btn);
    virtualScreen.setOnClickListener(
        button ->
            startActivity(
                new Intent(ctx, com.deepseekharness.app.vscreen.VirtualScreenActivity.class)));
    if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx)) {
      android.widget.TextView virtualHint = new android.widget.TextView(ctx);
      virtualHint.setText(
          com.deepseekharness.app.util.UiText.choose(
              "虚拟屏需要已连接的设备通道。无障碍不会自动创建，请打开虚拟屏后点“创建竖屏”或“创建横屏”；页内有使用说明。",
              "A virtual screen requires a connected device channel. Accessibility does not create it automatically. Open virtual screen, then tap Create portrait or Create landscape. Instructions are available on that page."));
      virtualHint.setTextSize(12);
      virtualHint.setTextColor(ctx.getColor(R.color.text_secondary));
      screenCard.addView(virtualHint, new android.widget.LinearLayout.LayoutParams(-1, -2));
      screenCard.addView(virtualScreen, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    // ADB 全局授权总开关：开 = 允许使用 ADB 全局授权（含 Shizuku 来源）；关 = 全局无 ADB 授权。
    CompoundButton adb = view.findViewById(R.id.config_adb_enable);
    adb.setSaveEnabled(false);
    adb.setChecked(AdbSwitch.enabled(ctx));
    adb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return; // 渲染写回不构成用户意图
          if (!AdbSwitch.userSet(ctx, enabled)) {
            toast(com.deepseekharness.app.util.UiText.text("设置保存失败，请重试"));
            syncAdbSwitch();
            return;
          }
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          if (enabled) {
            // 用户明确选择恢复 ADB 使用 = 使用模式切换；已有可信清理结果时解除自举互斥。
            AdbBootstrapController.get(ctx).onUserModeChosen();
            DeviceBridgeService.apply(ctx);
            if (getActivity() instanceof MainActivity)
              ((MainActivity) getActivity()).requestLocalNetwork();
          } else ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
          syncSettings();
          refreshEntryRows();
        });
    view.findViewById(R.id.config_all_files).setOnClickListener(button -> openAllFilesAccess(ctx));
    view.findViewById(R.id.config_battery_opt).setOnClickListener(button -> openBatteryOpt(ctx));
    sms = view.findViewById(R.id.device_sms);
    smsStatus = view.findViewById(R.id.device_sms_status);
    sms.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          updateSms();
          if (!enabled) {
            saveSms(false);
            return;
          }
          new com.deepseekharness.app.ui.DshaDialogBuilder(ctx)
              .setTitle(com.deepseekharness.app.util.UiText.text("持续允许读取短信？"))
              .setMessage(
                  com.deepseekharness.app.util.UiText.text(
                      "当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。关闭后立即阻止新查询。"))
              .setPositiveButton(
                  com.deepseekharness.app.util.UiText.text("允许短信读取"),
                  (dialog, which) -> saveSms(true))
              .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
              .show();
        });
    CompoundButton sensors = view.findViewById(R.id.config_cap_sensors);
    sensors.setChecked(pref(ctx, "cap_sensors", false));
    sensors.setOnCheckedChangeListener((button, enabled) -> savePreference("cap_sensors", enabled));
    CompoundButton location = view.findViewById(R.id.config_cap_location);
    location.setChecked(pref(ctx, "cap_location", false));
    location.setOnCheckedChangeListener(
        (button, enabled) -> {
          savePreference("cap_location", enabled);
          if (enabled
              && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {
                  android.Manifest.permission.ACCESS_FINE_LOCATION,
                  android.Manifest.permission.ACCESS_COARSE_LOCATION
                },
                104);
        });
    updateSms();
    return view;
  }

  /** 两个入口行只显示唯一结论：ADB 全局授权状态 + Root 通道状态。 */
  private void refreshEntryRows() {
    View page = getView();
    if (page == null || !isAdded()) return;
    DeviceAuthorization.Snapshot snapshot = DeviceAuthorizationReader.snapshot(requireContext());
    TextView adb = page.findViewById(R.id.device_auth_summary);
    if (adb != null)
      adb.setText(UiStateText.render(DeviceAuthorizationReader.adbEntrySummary(snapshot)));
    TextView root = page.findViewById(R.id.device_root_status);
    if (root != null) root.setText(UiStateText.render(RootShell.status(requireContext())));
  }

  private void savePreference(String key, boolean enabled) {
    boolean success =
        requireContext()
            .getSharedPreferences(Constants.PREFS, 0)
            .edit()
            .putBoolean(key, enabled)
            .commit();
    if (success && !enabled) com.deepseekharness.app.DeviceSense.revoke(requireContext(), key);
    if (!success) toast(com.deepseekharness.app.util.UiText.text("设置保存失败，请重试"));
  }

  private void syncSettings() {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    new Thread(
            () -> {
              String result = AdbBridge.applySettings(app, HarnessController.get(app).proot());
              main.post(
                  () -> {
                    if (!isAdded() || page == null || getView() != page) return;
                    TextView status = page.findViewById(R.id.config_guard_status);
                    if (status == null) return;
                    status.setVisibility(View.VISIBLE);
                    status.setText(
                        com.deepseekharness.app.util.UiText.format(
                            "原生授权即时生效；%s", result.replaceFirst("^SETTINGS_[A-Z]+: ", "")));
                  });
            },
            "device-settings")
        .start();
  }

  private void saveSms(boolean enabled) {
    boolean saved = new DeviceGrants(requireContext()).setSmsReadAllowed(enabled);
    updateSms();
    toast(
        !saved
            ? com.deepseekharness.app.util.UiText.text("授权保存失败，请重试")
            : enabled
                ? com.deepseekharness.app.util.UiText.text("短信查询已预授权")
                : com.deepseekharness.app.util.UiText.choose("已关闭短信读取", "SMS reading disabled"));
  }

  private void updateSms() {
    if (sms == null || !isAdded()) return;
    boolean allowed = new DeviceGrants(requireContext()).smsReadAllowed();
    updating = true;
    sms.setChecked(allowed);
    updating = false;
    smsStatus.setText(
        allowed
            ? com.deepseekharness.app.util.UiText.text("已预授权 · 关闭可撤销")
            : com.deepseekharness.app.util.UiText.choose(
                "已关闭 · 查询会被阻止", "Off · queries are blocked"));
    smsStatus.setTextColor(
        requireContext().getColor(allowed ? R.color.primary : R.color.text_muted));
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != 104 || !isAdded()) return;
    boolean granted =
        requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            || requireContext()
                    .checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    if (!granted) {
      savePreference("cap_location", false);
      if (getView() != null)
        ((CompoundButton) getView().findViewById(R.id.config_cap_location)).setChecked(false);
      new DshaDialogBuilder(requireContext())
          .setTitle(
              com.deepseekharness.app.util.UiText.choose(
                  "需要系统定位权限", "Location permission required"))
          .setMessage(
              com.deepseekharness.app.util.UiText.choose(
                  "位置读取保持关闭。请在系统应用权限中允许定位，再返回这里开启；如果系统定位服务关闭，也需要先开启系统定位。",
                  "Location reading remains off. Allow location in system app permissions, then return and enable it here. System location services must also be enabled."))
          .setNegativeButton(com.deepseekharness.app.util.UiText.choose("暂不开启", "Not now"), null)
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("打开系统权限设置", "Open app permissions"),
              (dialog, which) -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException missing) {
                  toast(
                      com.deepseekharness.app.util.UiText.choose(
                          "请在系统设置中打开 DSHA 应用权限", "Open DSHA permissions in system settings"));
                }
              })
          .show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    // F08：自举自动启用、配对页关闭等路径都会改总开关；每次回到前台从真实存储同步显示值。
    syncAdbSwitch();
    updateSms();
    main.removeCallbacks(refreshEntries);
    main.post(refreshEntries);
    View page = getView();
    if (page != null) {
      refreshAllFilesStatus(page.findViewById(R.id.config_all_files_status));
      refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
    }
  }

  /**
   * 从真实授权存储同步 ADB 总开关。
   *
   * <p>状态渲染只能读：用 {@code updating} 抑制监听器，写回不得触发再次授权、停止服务或弹窗。
   */
  private void syncAdbSwitch() {
    View page = getView();
    if (page == null) return;
    CompoundButton adb = page.findViewById(R.id.config_adb_enable);
    if (adb == null) return;
    boolean enabled = AdbSwitch.enabled(requireContext());
    updating = true;
    try {
      adb.setChecked(enabled);
    } finally {
      updating = false;
    }
  }

  @Override
  public void onPause() {
    main.removeCallbacks(refreshEntries);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    main.removeCallbacks(refreshEntries);
    sms = null;
    smsStatus = null;
    super.onDestroyView();
  }

  private boolean pref(Context ctx, String key, boolean fallback) {
    return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(key, fallback);
  }

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  private void openBatteryOpt(Context ctx) {
    try {
      Intent i =
          new Intent(
              Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
              Uri.parse("package:" + ctx.getPackageName()));
      startActivity(i);
    } catch (Exception e) {
      toast(com.deepseekharness.app.util.UiText.text("无法打开电池优化设置"));
    }
  }

  /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
  private void openAllFilesAccess(Context ctx) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      try {
        Intent i =
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        startActivity(i);
      } catch (Throwable e) {
        try {
          startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable e2) {
          toast(com.deepseekharness.app.util.UiText.format("打开设置失败：%s", e2.getMessage()));
        }
      }
      return;
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
      if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
      } else {
        toast(com.deepseekharness.app.util.UiText.text("存储权限已授予，容器可访问手机存储"));
      }
      return;
    }
    toast(com.deepseekharness.app.util.UiText.text("当前系统无需存储权限"));
  }

  /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
  private void refreshAllFilesStatus(TextView status) {
    if (status == null) return;
    boolean granted;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      granted = Environment.isExternalStorageManager();
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      granted =
          requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } else {
      granted = true;
    }
    status.setText(
        granted
            ? com.deepseekharness.app.util.UiText.text("已开启：容器可读写手机存储任意文件（含 DSHA 目录外）")
            : com.deepseekharness.app.util.UiText.text("未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件"));
    try {
      status.setTextColor(
          granted
              ? getResources().getColor(R.color.primary, null)
              : getResources().getColor(R.color.err, null));
    } catch (Throwable ignored) {
    }
  }

  /** 分别显示系统开关与本次实际连接；开启开关不代表服务已经绑定。 */
  private void refreshA11yStatus(TextView status) {
    if (status == null) return;
    String st = DshaAccessibilityService.enabledState(requireContext());
    boolean enabled = "YES".equals(st);
    boolean connected = enabled && DshaAccessibilityService.connected();
    if (enabled) {
      status.setText(
          connected
              ? com.deepseekharness.app.util.UiText.choose(
                  "已连接，可以在对话中使用 Computer Use。",
                  "Connected. Computer Use is available in conversations.")
              : com.deepseekharness.app.util.UiText.choose(
                  "开关已开启，正在等待系统连接；可重新开关服务。",
                  "Enabled, waiting for the system to connect. Try toggling the service."));
    } else if ("NO".equals(st)) {
      status.setText(com.deepseekharness.app.util.UiText.text("未开启 · 点上方按两步完成设置"));
    } else {
      status.setText(com.deepseekharness.app.util.UiText.text("尚未连接 · 点上方查看设置步骤"));
    }
    try {
      status.setTextColor(
          getResources()
              .getColor(
                  connected ? R.color.primary : enabled ? R.color.text_muted : R.color.err, null));
    } catch (Throwable ignored) {
    }
  }
}
