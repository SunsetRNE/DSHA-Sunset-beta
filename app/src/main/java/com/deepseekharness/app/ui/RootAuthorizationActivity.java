package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Root 授权页（独立权威）。
 *
 * <p>ADR 0005：Root 是 uid=0 的独立权威，与「ADB 全局授权」正交 —— 本页没有 ADB 相关开关，
 * 关掉 ADB 总开关不影响这里，关掉这里也不影响 ADB 总开关。
 */
public final class RootAuthorizationActivity extends androidx.appcompat.app.AppCompatActivity {
  private MaterialSwitch toggle;
  private TextView status;
  private androidx.appcompat.app.AlertDialog dialog;

  /** 每次验证的请求代次：页面销毁或再次发起都让旧回调的 UI 交付失效（F07）。 */
  private int verifyGeneration;

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
            t("Root 授权", "Root authorization"),
            t(
                "uid 0 的独立权威：开关只决定 root 通道，与 ADB 全局授权互不影响。",
                "An independent uid 0 authority. This switch controls the root channel only and is independent of ADB authorization."));
    UiNavigation.addHeader(this, page.root, t("Root 授权", "Root authorization"), this::finish);
    LinearLayout card = page.card();
    toggle = new MaterialSwitch(this);
    toggle.setText(t("允许 Root Shell", "Allow root shell"));
    toggle.setMinHeight(dp(48));
    toggle.setSaveEnabled(false);
    toggle.setChecked(RootShell.enabled(this));
    toggle.setOnCheckedChangeListener(
        (button, enabled) -> {
          new ConfigStore(this).setRootShellAllowed(enabled);
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(this).revoke();
          render();
        });
    card.addView(toggle);
    status = page.text("", 13, R.color.text);
    status.setPadding(0, dp(10), 0, 0);
    card.addView(status);
    TextView hint =
        page.text(
            t(
                "已 Root 的手机：开启开关，在 Magisk / KernelSU 等管理器允许 DSHA，再点验证。",
                "Rooted device: enable the switch, authorize DSHA in Magisk / KernelSU, then verify."),
            12,
            R.color.text_secondary);
    hint.setPadding(0, dp(10), 0, dp(4));
    card.addView(hint);
    page.button(card, t("申请授权并验证 root", "Request and verify root"), true, this::verify);
    TextView note =
        page.text(
            t(
                "设备命令仍受白名单保护：root 只提供通道，不放开被拒绝的命令。",
                "Device commands stay behind the whitelist: root provides a channel, not extra commands."),
            12,
            R.color.text_secondary);
    note.setPadding(0, dp(12), 0, 0);
    card.addView(note);
    // CardPage 只负责搭视图树，必须显式挂成内容视图；漏掉这一行就是白屏。
    setContentView(page.root);
    render();
  }

  private void render() {
    if (status == null) return;
    boolean enabled = RootShell.enabled(this);
    if (toggle.isChecked() != enabled) toggle.setChecked(enabled);
    status.setText(UiStateText.render(RootShell.status(this)));
  }

  private void verify() {
    if (!RootShell.enabled(this)) {
      toast(t("请先开启「允许 Root Shell」", "Enable Allow root shell first"));
      return;
    }
    if (!RootShell.present()) {
      toast(
          t(
              "未找到 su；请确认手机已 root，且 root 管理器允许 DSHA",
              "No su found. Confirm the device is rooted and the root manager allows DSHA."));
      return;
    }
    status.setText(t("正在验证 root 通道…", "Verifying the root channel…"));
    final int generation = ++verifyGeneration;
    new Thread(
            () -> {
              String output;
              try {
                output = RootShell.exec(getApplicationContext(), "id", -1);
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              main.post(
                  () -> {
                    // 验证途中返回 / 旋转：旧结果不得在已销毁页面上渲染或弹窗。
                    if (generation != verifyGeneration || isFinishing() || isDestroyed()) return;
                    render();
                    if (dialog != null) dialog.dismiss();
                    dialog =
                        new DshaDialogBuilder(this)
                            .setTitle(t("Root 通道验证", "Root channel verification"))
                            .setMessage(UiText.raw(result))
                            .setPositiveButton(t("知道了", "OK"), null)
                            .create();
                    dialog.setOnDismissListener(ignored -> dialog = null);
                    dialog.show();
                  });
            },
            "root-verify")
        .start();
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

  @Override
  protected void onDestroy() {
    // 使在途验证的 UI 交付失效，并关闭归属本页的对话框。
    verifyGeneration++;
    if (dialog != null) dialog.dismiss();
    dialog = null;
    status = null;
    super.onDestroy();
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
