package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.DeviceAuthorizationReader;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AdbKeyTable;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;

/**
 * Root 自举 ADB 授权页：四个来源里唯一能拿到**系统级** ADB 授权的那条。
 *
 * <p>除了自举操作本身，这一页也把"看得见的状态"补齐：设备 ADB 授权表（含每把钥匙的指纹与本应用
 * 归属）、5555 监听与双栈防护、以及**只读**的 ADB 会话核验。核验只跑 `id`，不写任何东西。
 */
public final class BootstrapAuthorizationActivity extends androidx.appcompat.app.AppCompatActivity {
  private static final int KEYS_REFRESH_TICKS = 5;

  private TextView line;
  private TextView requirements;
  private TextView keysLine;
  private TextView keysDetail;
  private TextView sessionLine;
  private RootBootstrapCard rootBootstrap;
  private boolean keysBusy;
  private boolean sessionBusy;
  private int tick;

  /** 每次只读核验的请求代次：页面销毁 / 再次发起都让旧回调的 UI 交付失效（F07）。 */
  private int keysGeneration;

  private int sessionGeneration;
  private androidx.appcompat.app.AlertDialog sessionDialog;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          if (++tick % KEYS_REFRESH_TICKS == 0) refreshKeys();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    CardPage page =
        new CardPage(
            this,
            t("Root 自举 ADB 授权", "ADB setup via root"),
            t(
                "四个来源里唯一能建立系统级 ADB 授权的一条：借 root 写入本应用公钥并开启监听。",
                "The only path that establishes system-level ADB authorization: via root it registers this app's key and opens a listener."));
    UiNavigation.addHeader(
        this, page.root, t("Root 自举 ADB 授权", "ADB setup via root"), this::finish);
    LinearLayout head = page.card();
    line = page.text("", 13, R.color.text);
    head.addView(line);
    requirements = page.text("", 12, R.color.text_secondary);
    requirements.setPadding(0, dp(10), 0, 0);
    head.addView(requirements);
    page.button(
        head, t("去 Root 授权页", "Open Root authorization"), false, this::openRootAuthorization);

    page.label(t("自举操作", "Bootstrap actions"));
    LinearLayout card =
        (LinearLayout)
            getLayoutInflater().inflate(R.layout.card_root_bootstrap, page.content, false);
    page.content.addView(card);
    card.setBackgroundResource(R.drawable.bg_card);
    rootBootstrap = new RootBootstrapCard(card);

    page.label(t("设备 ADB 授权表（只读）", "Device ADB authorization table (read-only)"));
    LinearLayout keysCard = page.card();
    keysLine = page.text("", 13, R.color.text);
    keysCard.addView(keysLine);
    keysDetail = page.text("", 12, R.color.text_secondary);
    keysDetail.setPadding(0, dp(8), 0, 0);
    keysCard.addView(keysDetail);
    page.button(keysCard, t("刷新授权表", "Refresh key table"), false, this::refreshKeys);

    page.label(t("ADB 会话核验（只读）", "ADB session check (read-only)"));
    LinearLayout sessionCard = page.card();
    sessionLine = page.text("", 12, R.color.text_secondary);
    sessionCard.addView(sessionLine);
    page.button(
        sessionCard,
        t("核验当前 ADB 会话（只跑一次 id，不写设备）", "Check the current ADB session (one read-only id)"),
        false,
        this::verifySession);
    setContentView(page.root);
    render();
  }

  private void render() {
    if (line == null) return;
    DeviceAuthorization.Snapshot snapshot = DeviceAuthorizationReader.snapshot(this);
    line.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(
                snapshot, DeviceAuthorization.Pipe.ADB_BOOTSTRAP)));
    boolean root = RootShell.enabled(this);
    requirements.setText(
        root
            ? t(
                "Root 授权已开启。自举与系统无线调试互斥：需要先在开发者选项关闭无线调试。",
                "Root authorization is on. Bootstrap and system wireless debugging are mutually exclusive: turn wireless debugging off first.")
            : t(
                "自举需要 Root 授权：请先到「Root 授权」页开启「允许 Root Shell」。",
                "Bootstrap needs root authorization: enable Allow root shell on the Root authorization page first."));
    if (rootBootstrap != null) rootBootstrap.render();
  }

  /** 只读读取系统 ADB 授权表；不写、不改、不删。 */
  private void refreshKeys() {
    if (keysBusy || keysLine == null) return;
    keysBusy = true;
    final int generation = ++keysGeneration;
    new Thread(
            () -> {
              final String output;
              if (!RootShell.enabled(getApplicationContext())) {
                output = "";
              } else {
                output = RootShell.exec(getApplicationContext(), "cat /data/misc/adb/adb_keys", -1);
              }
              final java.util.List<AdbKeyTable.Entry> entries =
                  AdbKeyTable.parse(output, getPackageName());
              final boolean root = RootShell.enabled(getApplicationContext());
              main.post(
                  () -> {
                    keysBusy = false;
                    // 页面暂停 / 销毁或已重新发起：旧回调不得继续渲染。
                    if (generation != keysGeneration || !live()) return;
                    if (keysLine == null) return;
                    if (!root) {
                      keysLine.setText(
                          t(
                              "需要 Root 授权才能只读查看授权表",
                              "Root authorization is required to read the table"));
                      keysDetail.setText("");
                      return;
                    }
                    boolean appKey = AdbKeyTable.containsAppKey(entries);
                    keysLine.setText(
                        UiText.format(
                            t("共 %d 把钥匙 · 本应用公钥：%s", "%d keys · this app's key: %s"),
                            entries.size(),
                            appKey ? t("在表内", "present") : t("不在表内", "absent")));
                    StringBuilder builder = new StringBuilder();
                    for (AdbKeyTable.Entry entry : entries) {
                      if (builder.length() > 0) builder.append('\n');
                      String fingerprint =
                          entry.fingerprint.isEmpty()
                              ? t("指纹未知", "unknown fingerprint")
                              : entry.fingerprint;
                      builder.append(fingerprint);
                      builder.append(" · ");
                      builder.append(
                          entry.comment.isEmpty() ? t("无备注", "no comment") : entry.comment);
                      if (entry.appOwned) builder.append(t("（本应用）", " (this app)"));
                    }
                    keysDetail.setText(
                        builder.length() == 0
                            ? t("授权表为空", "The table is empty")
                            : builder.toString());
                  });
            },
            "adb-key-table")
        .start();
  }

  /** 只读核验：跑一次固定端点的 `id`，把原文与退出码摆出来；不发现、不重放、不换通道。 */
  private void verifySession() {
    if (sessionBusy || sessionLine == null) return;
    sessionBusy = true;
    final int generation = ++sessionGeneration;
    sessionLine.setText(
        t("正在核验 ADB 会话（只跑一次 id）…", "Checking the ADB session (single read-only id)…"));
    new Thread(
            () -> {
              String output;
              try {
                output = AdbBridge.verifyBootstrap(HarnessController.get(getApplication()).proot());
              } catch (Throwable error) {
                output = SensitiveData.redact(String.valueOf(error));
              }
              final String result = output == null ? "" : output;
              main.post(
                  () -> {
                    sessionBusy = false;
                    // 核验途中返回 / 旋转 / 再次发起：旧结果不允许在已销毁页面上弹窗。
                    if (generation != sessionGeneration || !live()) return;
                    String first = result.trim();
                    int newline = first.indexOf('\n');
                    String summary = newline > 0 ? first.substring(0, newline) : first;
                    if (summary.length() > 90) summary = summary.substring(0, 90) + "…";
                    sessionLine.setText(
                        UiText.format(
                            t("上次核验：%s", "Last check: %s"),
                            summary.isEmpty() ? t("空结果", "empty") : summary));
                    androidx.appcompat.app.AlertDialog dialog =
                        new DshaDialogBuilder(this)
                            .setTitle(t("ADB 会话核验原文", "ADB session check output"))
                            .setMessage(UiText.raw(result))
                            .setPositiveButton(t("知道了", "OK"), null)
                            .create();
                    dialog.setOnDismissListener(
                        ignored -> {
                          if (sessionDialog == dialog) sessionDialog = null;
                        });
                    sessionDialog = dialog;
                    dialog.show();
                  });
            },
            "adb-session-check")
        .start();
  }

  /** 页面是否还能接收 UI 交付：已结束 / 已销毁都不行。 */
  private boolean live() {
    return !isFinishing() && !isDestroyed();
  }

  private void openRootAuthorization() {
    try {
      startActivity(new Intent(this, RootAuthorizationActivity.class));
    } catch (RuntimeException unavailable) {
      // 明确降级：至少告诉用户在哪里。
      android.widget.Toast.makeText(
              this,
              t(
                  "请在设置 → 设备能力授权 → Root 授权里操作",
                  "Use Settings → Device capabilities → Root authorization"),
              android.widget.Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (rootBootstrap != null) rootBootstrap.resume();
    main.removeCallbacks(refresh);
    main.post(refresh);
    refreshKeys();
  }

  @Override
  protected void onPause() {
    if (rootBootstrap != null) rootBootstrap.pause();
    main.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    // 使所有在途只读核验的 UI 交付失效，并关闭归属本页的对话框。
    keysGeneration++;
    sessionGeneration++;
    if (sessionDialog != null) sessionDialog.dismiss();
    sessionDialog = null;
    if (rootBootstrap != null) rootBootstrap.detach();
    rootBootstrap = null;
    keysLine = null;
    keysDetail = null;
    sessionLine = null;
    requirements = null;
    line = null;
    super.onDestroy();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
