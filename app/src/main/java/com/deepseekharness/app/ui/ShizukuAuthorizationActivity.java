package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.core.DeviceAuthorizationReader;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.ShizukuStatus;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;

/**
 * Shizuku 调试授权页（ADR 0005 §2.1）：Shizuku 是四个来源之一，但仍是**中间层**——
 * 它自己也要靠 ADB 或 root 启动，文案必须保留这一点。
 */
public final class ShizukuAuthorizationActivity extends androidx.appcompat.app.AppCompatActivity {
  private TextView line;
  private TextView status;
  private TextView action;
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
            t("Shizuku 调试授权", "Shizuku authorization"),
            t(
                "中间层：它自己由 ADB 或 root 启动，不是授权本身。",
                "A middle layer: Shizuku itself is started by ADB or root, and is not the authorization itself."));
    UiNavigation.addHeader(this, page.root, t("Shizuku 调试授权", "Shizuku"), this::finish);
    LinearLayout card = page.card();
    line = page.text("", 13, R.color.text);
    card.addView(line);
    status = page.text("", 12, R.color.text_secondary);
    status.setPadding(0, dp(10), 0, 0);
    card.addView(status);
    action = page.text("", 12, R.color.text_secondary);
    action.setPadding(0, dp(8), 0, 0);
    card.addView(action);
    page.button(card, t("授权 / 验证 Shizuku", "Authorize or verify Shizuku"), true, this::shizukuAction);
    page.button(card, t("打开 Shizuku 管理器", "Open the Shizuku manager"), false, this::openManager);
    setContentView(page.root);
    render();
  }

  private void render() {
    if (line == null) return;
    DeviceAuthorization.Snapshot snapshot = DeviceAuthorizationReader.snapshot(this);
    line.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(
                snapshot, DeviceAuthorization.Pipe.SHIZUKU_BINDER)));
    status.setText(UiStateText.render(ShizukuShell.userStatus(this)));
    action.setText(
        ShizukuStatus.sourceLabel(
            ShizukuStatus.source(
                RootShell.enabled(this),
                "connected".equals(DeviceBridgeService.adbState))));
  }

  private void shizukuAction() {
    if (!ShizukuShell.isAvailable()) {
      com.deepseekharness.app.ShizukuManagerCompat.reconnect(
          this,
          () ->
              main.post(
                  () -> {
                    if (ShizukuShell.isAvailable() && !ShizukuShell.hasPermission())
                      ShizukuShell.requestPermission((code, result) -> main.post(this::render));
                    else if (!ShizukuShell.isAvailable()) toast(ShizukuShell.userStatus(this));
                    render();
                  }));
      return;
    }
    if (ShizukuShell.hasPermission()) verifyShizuku();
    else ShizukuShell.requestPermission((code, result) -> main.post(this::render));
  }

  private void verifyShizuku() {
    new Thread(
            () -> {
              String output;
              try {
                output = ShizukuShell.exec("id");
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              main.post(
                  () -> {
                    render();
                    new DshaDialogBuilder(this)
                        .setTitle(t("Shizuku 通道验证", "Shizuku channel verification"))
                        .setMessage(UiText.raw(result))
                        .setPositiveButton(t("知道了", "OK"), null)
                        .show();
                  });
            },
            "shizuku-verify")
        .start();
  }

  private void openManager() {
    android.content.Intent launch =
        com.deepseekharness.app.ShizukuManagerCompat.launchIntent(this);
    if (launch == null) {
      toast(t("尚未安装 Shizuku", "Shizuku is not installed"));
      return;
    }
    try {
      startActivity(launch);
    } catch (RuntimeException e) {
      toast(t("无法打开 Shizuku", "Cannot open Shizuku"));
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    ShizukuShell.ensureBound(this);
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

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
