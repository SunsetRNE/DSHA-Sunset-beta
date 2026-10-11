package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceAuthorizationReader;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;

/** USB 调试授权（只读检测）。ADR 0005 §2.1：App 里没有 USB 调试代码路径，不替用户开关。 */
public final class UsbAuthorizationActivity extends androidx.appcompat.app.AppCompatActivity {
  private TextView line;
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
            t("USB 调试授权", "USB debugging"),
            t("系统调试授权的一条取得路径；本应用只做只读检测。", "One path to system debugging authorization; this app only reads it."));
    UiNavigation.addHeader(this, page.root, t("USB 调试授权", "USB debugging"), this::finish);
    LinearLayout card = page.card();
    line = page.text("", 13, R.color.text);
    card.addView(line);
    TextView hint =
        page.text(
            t(
                "App 里没有 USB 调试的代码路径，只能读系统 ADB 调试总开关；它开着时无法区分是 USB 还是无线调试，所以这里只展示事实、不替你开关。",
                "This app has no USB-debugging code path; it can only read the system ADB switch, which does not distinguish USB from wireless. So this page only reports the fact."),
            12,
            R.color.text_secondary);
    hint.setPadding(0, dp(10), 0, 0);
    card.addView(hint);
    page.button(
        card, t("打开开发者选项", "Open developer options"), false, this::openDeveloperOptions);
    setContentView(page.root);
    render();
  }

  private void render() {
    if (line == null) return;
    DeviceAuthorization.Snapshot snapshot = DeviceAuthorizationReader.snapshot(this);
    line.setText(
        UiStateText.render(
            DeviceAuthorizationReader.sourceLine(snapshot, DeviceAuthorization.Pipe.ADB_USB)));
  }

  private void openDeveloperOptions() {
    try {
      startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    } catch (RuntimeException error) {
      Toast.makeText(
              this,
              t("无法打开开发者选项，请在系统设置里手动进入", "Cannot open developer options; open it in system settings"),
              Toast.LENGTH_LONG)
          .show();
    }
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
