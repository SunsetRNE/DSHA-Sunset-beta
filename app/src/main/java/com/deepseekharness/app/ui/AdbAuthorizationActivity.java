package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceAuthorizationReader;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;

/**
 * ADB 全局授权页（ADR 0005 §3）：**四个来源各是一个入口**，点进去是各自的页面
 * （无线调试沿用既有的配对/验证页），本页只给每个来源一行状态，不在这里铺开操作。
 */
public final class AdbAuthorizationActivity extends androidx.appcompat.app.AppCompatActivity {
  private TextView summary;
  private TextView usbRow;
  private TextView wirelessRow;
  private TextView shizukuRow;
  private TextView bootstrapRow;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    CardPage page =
        new CardPage(
            this,
            t("ADB 全局授权", "ADB global authorization"),
            t(
                "USB 调试、无线调试、Shizuku、Root 自举是同一个 ADB 授权的四条取得路径，任一取得即全局生效。",
                "USB debugging, wireless debugging, Shizuku and root setup are four paths to one ADB authorization; any one of them activates it globally."));
    UiNavigation.addHeader(
        this, page.root, t("ADB 全局授权", "ADB global authorization"), this::finish);

    LinearLayout summaryCard = page.card();
    summary = page.text("", 13, R.color.text);
    summary.setPadding(0, dp(4), 0, dp(4));
    summaryCard.addView(summary);

    page.label(t("来源", "Sources"));
    LinearLayout list = page.card();
    usbRow =
        page.entryRow(
            list,
            t("① USB 调试授权", "① USB debugging"),
            "",
            R.drawable.ic_ui_link,
            () -> open(UsbAuthorizationActivity.class));
    wirelessRow =
        page.entryRow(
            list,
            t("② 无线调试授权", "② Wireless debugging"),
            "",
            R.drawable.ic_recovery_refresh,
            () -> open(AdbPairActivity.class));
    shizukuRow =
        page.entryRow(
            list,
            t("③ Shizuku 调试授权", "③ Shizuku"),
            "",
            R.drawable.ic_recovery_shield,
            () -> open(ShizukuAuthorizationActivity.class));
    bootstrapRow =
        page.entryRow(
            list,
            t("④ Root 自举 ADB 授权", "④ ADB setup via root"),
            "",
            R.drawable.ic_recovery_wrench,
            () -> open(BootstrapAuthorizationActivity.class));
    setContentView(page.root);
  }

  private void render() {
    DeviceAuthorization.Snapshot snapshot = DeviceAuthorizationReader.snapshot(this);
    summary.setText(UiStateText.render(DeviceAuthorizationReader.adbEntrySummary(snapshot)));
    usbRow.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(snapshot, DeviceAuthorization.Pipe.ADB_USB)));
    wirelessRow.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(snapshot, DeviceAuthorization.Pipe.ADB_TCP)));
    shizukuRow.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(
                snapshot, DeviceAuthorization.Pipe.SHIZUKU_BINDER)));
    bootstrapRow.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(
                snapshot, DeviceAuthorization.Pipe.ADB_BOOTSTRAP)));
  }

  private void open(Class<?> activity) {
    startActivity(new Intent(this, activity));
  }

  @Override
  protected void onResume() {
    super.onResume();
    main.removeCallbacks(refresh);
    main.post(refresh);
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(refresh);
    super.onPause();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
