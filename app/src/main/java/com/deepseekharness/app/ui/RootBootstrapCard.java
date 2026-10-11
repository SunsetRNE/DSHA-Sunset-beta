package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.core.AdbBootstrapController;
import com.deepseekharness.app.util.AdbBootstrapPolicy.Action;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.text.DateFormat;
import java.util.Date;

/** 原生自举操作的审阅与状态展示；页面生命周期不拥有后台任务。 */
public final class RootBootstrapCard {
  private static final Action[] ACTIONS = {
    Action.AUTHORIZE, Action.NARROW, Action.OPEN, Action.CLOSE, Action.REVOKE, Action.STATUS
  };
  private static final int[] BUTTONS = {
    R.id.root_bootstrap_authorize,
    R.id.root_bootstrap_narrow,
    R.id.root_bootstrap_open,
    R.id.root_bootstrap_close,
    R.id.root_bootstrap_revoke,
    R.id.root_bootstrap_status
  };

  private final View card;
  private final Context app;
  private final AdbBootstrapController controller;
  private final MaterialSwitch allowed;
  private final Button chain;
  private final Button devOptions;
  private final Button evidenceButton;
  private android.widget.LinearLayout advanced;
  private Button advancedToggle;
  private boolean updating;
  private boolean detached;
  private boolean resumed;
  private boolean pendingAfterRefresh;
  private int reviewGeneration;
  private AlertDialog reviewDialog;

  public RootBootstrapCard(View card) {
    this.card = card;
    app = card.getContext().getApplicationContext();
    controller = AdbBootstrapController.get(app);
    allowed = card.findViewById(R.id.root_bootstrap_allowed);
    allowed.setSaveEnabled(false);
    allowed.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating || detached) return;
          // 点击开启先恢复真实开关值；只有原生审阅的正按钮可以保存授权。
          render();
          dismissReview();
          if (enabled) reviewGrant();
          else {
            if (!controller.setAllowed(false))
              toast(UiText.choose("授权保存失败，请重试", "Could not save permission. Try again."));
            render();
          }
        });
    for (int i = 0; i < ACTIONS.length; i++) {
      Action action = ACTIONS[i];
      card.findViewById(BUTTONS[i]).setOnClickListener(button -> reviewAction(action));
    }
    chain = card.findViewById(R.id.root_bootstrap_all);
    chain.setOnClickListener(button -> startOneButton());
    advanced = card.findViewById(R.id.root_bootstrap_advanced);
    advancedToggle = card.findViewById(R.id.root_bootstrap_advanced_toggle);
    advanced.setVisibility(android.view.View.GONE);
    advancedToggle.setOnClickListener(button -> toggleAdvanced());
    devOptions = card.findViewById(R.id.root_bootstrap_dev_options);
    devOptions.setVisibility(android.view.View.GONE);
    devOptions.setOnClickListener(button -> openDeveloperOptions());
    evidenceButton = card.findViewById(R.id.root_bootstrap_evidence);
    evidenceButton.setVisibility(android.view.View.GONE);
    evidenceButton.setOnClickListener(button -> showEvidence());
    render();
  }

  /** 只重绘控制器快照和本机授权，不请求 su、探测端口或自动提交 STATUS。 */
  public void render() {
    if (detached) return;
    AdbBootstrapController.Snapshot snapshot = controller.snapshot();
    boolean grant = controller.allowed();
    boolean root = RootShell.enabled(app);
    updating = true;
    allowed.setChecked(grant);
    updating = false;
    allowed.setText(UiText.choose("允许 Root 自举 ADB", "Allow ADB setup via root"));
    text(R.id.root_bootstrap_title, UiText.choose("Root 自举 ADB", "ADB setup via root"));
    text(
        R.id.root_bootstrap_hint,
        UiText.choose(
            "点「一键完成 Root 自举」即可：先只读刷新状态，再自动跑完授权公钥 → 收窄双栈 → 开启监听并核验；缺前置条件时它会带你去处理，而不是让你猜。单独执行与清理收在「高级操作」里，每个动作都会先请你确认。",
            "Tap Set up ADB via root in one step: it refreshes read-only first, then runs key authorization → loopback narrowing → listener with verification. Missing prerequisites are handled for you instead of left to guess. One-by-one actions and cleanup live under Advanced, each asking for confirmation."));
    text(
        R.id.root_bootstrap_grant_state,
        grant
            ? UiText.choose(
                "独立授权已开启 · 不代表 ADB 已开启或已受保护", "Permission on · ADB may still be off or unprotected")
            : UiText.choose(
                "独立授权已关闭 · 已有设备状态不会自动清理",
                "Permission off · Existing device state is not cleaned up automatically"));
    text(
        R.id.root_bootstrap_requirements,
        !root
            ? UiText.choose(
                "请先在「Root 授权」页开启「允许 Root Shell」，再执行检查或清理。",
                "Enable Allow Root Shell above before checking or cleaning up.")
            : !grant
                ? UiText.choose(
                    "可只读刷新、关闭监听或撤销公钥；新增授权、收窄和开启需打开此开关。",
                    "Refresh, close or revoke remain available. Enable this permission to authorize, narrow or open.")
                : UiText.choose(
                    "可一键自举 ADB（免配对），也可按需单独执行；实际 root 许可和设备状态会在执行时核验。",
                    "Set up ADB via root in one step, or run a single action. Root permission and device state are checked on execution."));
    text(
        R.id.root_bootstrap_outcome,
        String.format(
            UiText.choose("请求：%s\n%s", "Request: %s\n%s"),
            snapshot.action == null ? UiText.choose("无", "None") : actionLabel(snapshot.action),
            snapshot.busy
                ? UiText.choose("正在执行，请等待结果", "Running. Wait for the result.")
                : outcomeLabel(snapshot.outcome)));
    String observed =
        snapshot.observedAt <= 0
            ? UiText.choose("尚未观测", "Not observed")
            : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(snapshot.observedAt));
    text(
        R.id.root_bootstrap_observed,
        String.format(
            UiText.choose(
                "上次观测：%s\n以下仅是上次观测值，不保证持续有效。",
                "Last observed: %s\nThe values below describe that observation, not a continuing guarantee."),
            observed));
    text(
        R.id.root_bootstrap_key_state,
        String.format(UiText.choose("公钥：%s", "Key: %s"), state(snapshot.keyState)));
    text(
        R.id.root_bootstrap_listener_state,
        String.format(UiText.choose("监听：%s", "Listener: %s"), state(snapshot.listenerState)));
    text(
        R.id.root_bootstrap_guard_state,
        String.format(
            UiText.choose("双栈防护：%s", "IPv4/IPv6 protection: %s"), state(snapshot.guardState)));
    text(
        R.id.root_bootstrap_detail,
        snapshot.detail == null || snapshot.detail.isEmpty() ? "" : detailLabel(snapshot.detail));
    // 只有「被系统无线调试挡住」这一种拒绝给出直达入口：其它拒绝没有对应的单个开关。
    boolean wirelessBlocked =
        !snapshot.busy && "WIRELESS_DEBUG_IN_USE".equals(snapshot.detail == null ? "" : snapshot.detail);
    devOptions.setVisibility(wirelessBlocked ? android.view.View.VISIBLE : android.view.View.GONE);
    if (wirelessBlocked)
      text(
          R.id.root_bootstrap_dev_options,
          UiText.choose("打开开发者选项关闭无线调试", "Open developer options to turn it off"));
    // 失败原文只在有证据时出现；门禁与事务日志仍只看错误码。
    boolean hasEvidence = snapshot.evidence != null && !snapshot.evidence.isEmpty();
    evidenceButton.setVisibility(hasEvidence ? android.view.View.VISIBLE : android.view.View.GONE);
    if (hasEvidence)
      text(
          R.id.root_bootstrap_evidence,
          UiText.choose("查看失败原文", "View failure details"));
    text(
        R.id.root_bootstrap_cleanup,
        UiText.choose(
            "关闭授权开关不会撤销公钥或关闭端口。请按需单独清理，再只读刷新核对；未知结果不要重放。设备重启或厂商网络刷新后，双栈规则可能失效。",
            "Turning permission off does not revoke keys or close ports. Clean up explicitly, then refresh to check. Do not replay an unknown result. A reboot or vendor network reset may invalidate IPv4/IPv6 rules."));
    for (int i = 0; i < ACTIONS.length; i++) {
      Action action = ACTIONS[i];
      TextView button = card.findViewById(BUTTONS[i]);
      String label = actionLabel(action);
      if (!label.contentEquals(button.getText())) button.setText(label);
      // ADB 通道不再是自举门禁：自举本就用于免配对地建立 ADB 授权。
      button.setEnabled(!snapshot.busy && root && (!needsBootstrap(action) || grant));
    }
    // 一键自举：一次确认后按固定顺序跑完 AUTHORIZE → NARROW → OPEN；前置条件由程序处理。
    chain.setText(chainLabel(snapshot, root, grant));
    chain.setEnabled(!snapshot.busy);
    advancedToggle.setText(
        advanced.getVisibility() == android.view.View.VISIBLE
            ? UiText.choose("收起高级操作", "Hide advanced actions")
            : UiText.choose("高级操作（单独执行与清理）", "Advanced actions (run one by one / clean up)"));
    // 忙时仍允许撤销独立授权；阻止后续受控写入，任务继续收集结果且不重放。
    allowed.setEnabled(true);
    // 一键流程的第二步：只读刷新落地后才继续（不拿旧观测执行）。
    if (pendingAfterRefresh && !snapshot.busy) {
      pendingAfterRefresh = false;
      continueOneButton();
    }
  }

  /** 主按钮文案要能回答"现在到哪了"。 */
  private String chainLabel(
      AdbBootstrapController.Snapshot snapshot, boolean root, boolean grant) {
    if (snapshot.busy)
      return UiText.format(
          "正在执行：%s…",
          snapshot.action == null
              ? UiText.choose("自举", "Setup")
              : actionLabel(snapshot.action));
    return UiText.choose("一键完成 Root 自举", "Set up ADB via root in one step");
  }

  private void toggleAdvanced() {
    boolean show = advanced.getVisibility() != android.view.View.VISIBLE;
    advanced.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
    advancedToggle.setText(
        show
            ? UiText.choose("收起高级操作", "Hide advanced actions")
            : UiText.choose("高级操作（单独执行与清理）", "Advanced actions (run one by one / clean up)"));
  }

  /**
   * 主按钮：按顺序处理前置条件，不让用户记两个开关，也不让用户先手动刷新。
   *
   * <p>第一步永远是**只读刷新**（收进同一个按钮），拿到新观测后才决定：无线调试占用 → 说清并给入口；
   * 缺 Root 授权 → 给入口；缺独立自举授权 → 在同一次确认里开启并继续；否则直接确认跑链。
   *
   * <p>原生门禁一点没放松 —— 所有写入仍要求 Root 授权 + 独立自举授权，且都经用户确认。
   */
  private void startOneButton() {
    if (!active()) return;
    AdbBootstrapController.Snapshot snapshot = controller.snapshot();
    if (snapshot.busy) {
      toast(UiText.choose("已有自举任务执行中，请等待结果。", "A setup task is running. Wait for its result."));
      return;
    }
    // 环境任务在跑（例如刚更新完运行环境）时，自举的 pre-flight 拿不到环境，只会变成一次
    // 无码失败（REQUEST_OUTCOME_UNKNOWN）。这里先说清，不让用户白点。
    if (com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy()) {
      reviewEnvironmentBusy();
      return;
    }
    // 实机踩过：快照里的 detail 只有在**被拒之后**才会带上 WIRELESS_DEBUG_IN_USE，
    // 而保活可能在这之前又把无线调试开回来（另一个同源包也会）。所以这里读实时系统设置。
    if (wirelessDebuggingLive()) {
      reviewWirelessBlocked();
      return;
    }
    if (!RootShell.enabled(app)) {
      reviewRootRequired();
      return;
    }
    // 只读刷新不写设备，先跑它，避免拿旧观测去执行。
    pendingAfterRefresh = true;
    if (!controller.submit(Action.STATUS)) {
      pendingAfterRefresh = false;
      toast(
          UiText.choose(
              "无法刷新状态：已有任务执行中或授权已变化。", "Cannot refresh: another task is running or permission changed."));
    }
    render();
  }

  /** 只读刷新落地后，用新观测继续一键流程。 */
  private void continueOneButton() {
    AdbBootstrapController.Snapshot snapshot = controller.snapshot();
    if (snapshot.busy) return;
    if ("WIRELESS_DEBUG_IN_USE".equals(snapshot.detail == null ? "" : snapshot.detail)) {
      reviewWirelessBlocked();
      return;
    }
    if (controller.needsObservation()) {
      toast(
          UiText.choose(
              "上次结果仍未确认：请稍后再点一次，或到高级操作里单独只读刷新。",
              "The previous result is still unconfirmed. Tap again later, or refresh read-only under Advanced."));
      render();
      return;
    }
    if (!RootShell.present()) {
      toast(UiText.choose("未找到 su，请检查 root 管理器。", "su was not found. Check your root manager."));
      render();
      return;
    }
    if (!controller.allowed()) {
      reviewGrantAndChain();
      return;
    }
    reviewChain();
  }

  /** 只读实时系统设置：无线调试此刻是否开着（保活可能把它自动开回来，快照会滞后）。 */
  private boolean wirelessDebuggingLive() {
    try {
      return android.provider.Settings.Global.getInt(
              app.getContentResolver(), "adb_wifi_enabled", 0)
          == 1;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 无线调试占用的说明与入口；**不在本页改设置**，只把用户指到开发者选项。 */
  private void reviewWirelessBlocked() {
    if (!active() || reviewDialog != null) return;
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("系统「无线调试」正在运行", "System wireless debugging is running"))
            .setMessage(
                UiText.choose(
                    "自举要接管 ADB 监听，而系统无线调试是另一个独立的 ADB TLS 入口，两者互斥：DSHA 按设计拒绝接管，也不会把它自动关掉。\n\n请先在开发者选项里关闭「无线调试」，再回来点一键自举。\n\n注意：如果它**自己又开了**，通常是「ADB 全局授权 / 设备桥保活」在重连失败后把它开回来的（本机装了两个同源 DSHA 包时，另一个包的保活也会这么做）。这种情况下请先关掉那个包的 ADB 全局授权总开关，再关无线调试。",
                    "Setup needs to own the ADB listener, but system wireless debugging is a separate ADB TLS entry point. They are mutually exclusive: DSHA refuses to take over and will not switch it off for you.\n\nTurn off Wireless debugging in Developer options, then tap the one-step setup again.\n\nNote: if it switches itself back on, the ADB keepalive usually did it after a failed reconnect (when two same-origin DSHA packages are installed, the other package's keepalive does the same). Turn off that package's ADB global authorization switch first, then turn wireless debugging off."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("打开开发者选项", "Open developer options"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration) return;
                  openDeveloperOptions();
                })
            .create();
    showReview(reviewDialog);
  }

  /** 环境任务进行中（维护 / 更新运行环境）：先把原因说清，等环境空闲再自举。 */
  private void reviewEnvironmentBusy() {
    if (!active() || reviewDialog != null) return;
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("环境任务进行中", "Environment task running"))
            .setMessage(
                UiText.choose(
                    "自举前要先准备 ADB 环境，而此刻环境正在维护（例如刚更新过运行环境）。现在提交只会得到一个无法归因的失败。\n\n请等维护结束、页面不再显示「环境维护进行中」后再点一键自举。",
                    "Setup needs to prepare the ADB environment first, but the environment is being maintained right now (for example, right after a runtime update). Submitting now only produces an unattributable failure.\n\nWait until maintenance finishes and the page no longer shows an in-progress notice, then tap the one-step setup."))
            .setPositiveButton(UiText.choose("知道了", "OK"), null)
            .create();
    showReview(reviewDialog);
  }

  /** Root 没开时只给入口，绝不代开：`allow_root_shell` 归 Root 授权页。 */
  private void reviewRootRequired() {
    if (!active() || reviewDialog != null) return;
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("需要先开启 Root 授权", "Root authorization required"))
            .setMessage(
                UiText.choose(
                    "自举需要 uid 0 的 root 通道。请在「Root 授权」页开启「允许 Root Shell」并完成验证，再回来点这个按钮。",
                    "Setup needs the uid 0 root channel. Enable Allow root shell on the Root authorization page, verify it, then come back."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("去 Root 授权页", "Open Root authorization"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration) return;
                  try {
                    app.startActivity(
                        new android.content.Intent(app, RootAuthorizationActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                  } catch (Throwable unavailable) {
                    toast(
                        UiText.choose(
                            "打不开 Root 授权页：请到设置 → 设备能力授权 → Root 授权。",
                            "Cannot open the Root authorization page; use Settings → Device capabilities → Root authorization."));
                  }
                })
            .create();
    showReview(reviewDialog);
  }

  /** 独立自举授权没开时：在**同一次确认**里问清并开启，然后直接跑链，不再让用户点第二次。 */
  private void reviewGrantAndChain() {
    if (!active() || reviewDialog != null) return;
    final AdbBootstrapController.Snapshot reviewed = controller.snapshot();
    final long rootRevision = RootShell.authorizationRevision();
    final long grantRevision =
        new com.deepseekharness.app.core.DeviceGrants(app).adbBootstrapRevision();
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("一键完成 Root 自举？", "Set up ADB via root in one step?"))
            .setMessage(
                UiText.choose(
                    "这一步需要先允许独立的系统 ADB 自举操作（只保存授权，不执行设备动作），随后自动按固定顺序执行：\n\n1. 授权本应用公钥（写入系统 ADB 授权表）\n2. 收窄双栈访问（仅本机回环）\n3. 开启 5555 监听\n\n三步全部成功后自动打开 ADB 设备通道，无需系统配对弹窗。任一步失败即停止并保留现场，不重试、不换通道。关闭自举授权不会删除已有公钥或关闭端口，清理需要到高级操作里单独执行。",
                    "This needs the separate system-ADB setup permission first (saves permission only, no device action), then runs in a fixed order:\n\n1. Authorize this app's key\n2. Narrow to loopback only\n3. Open the listener\n\nOn success the ADB channel opens automatically, with no system pairing dialog. Any failure stops the chain and keeps the state; nothing is retried or rerouted. Turning the permission off does not remove keys or close ports; cleanup is a separate advanced action."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("允许并开始", "Allow and start"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration) return;
                  if (!sameObservation(reviewed, controller.snapshot())
                      || rootRevision != RootShell.authorizationRevision()
                      || grantRevision
                          != new com.deepseekharness.app.core.DeviceGrants(app)
                              .adbBootstrapRevision()) {
                    toast(
                        UiText.choose(
                            "状态已变化，请重新审阅本次操作。", "State changed. Review the action again."));
                    render();
                    return;
                  }
                  if (!controller.setAllowed(true)) {
                    toast(UiText.choose("授权保存失败，请重试", "Could not save permission. Try again."));
                    render();
                    return;
                  }
                  render();
                  // 授权刚保存：直接继续跑链，用户不需要再点第二次。
                  if (!controller.submitBootstrapChain())
                    toast(
                        UiText.choose(
                            "未提交：已有任务执行中或授权已变化。",
                            "Not submitted: another task is running or permission changed."));
                  render();
                })
            .create();
    showReview(reviewDialog);
  }

  public void grantsChanged() {
    dismissReview();
    render();
  }

  public void resume() {
    resumed = true;
    render();
  }

  public void pause() {
    resumed = false;
    dismissReview();
  }

  public void detach() {
    detached = true;
    dismissReview();
    allowed.setOnCheckedChangeListener(null);
    for (int id : BUTTONS) card.findViewById(id).setOnClickListener(null);
    devOptions.setOnClickListener(null);
    evidenceButton.setOnClickListener(null);
    if (advancedToggle != null) advancedToggle.setOnClickListener(null);
    chain.setOnClickListener(null);
  }

  /** 只打开系统开发者选项，不改任何设置；关掉无线调试仍由用户自己确认。 */
  private void openDeveloperOptions() {
    try {
      app.startActivity(
          new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (Throwable unavailable) {
      toast(
          UiText.choose(
              "打不开开发者选项：请在 设置 → 系统 → 开发者选项 里关闭无线调试。",
              "Could not open developer options: turn wireless debugging off in Settings → System → Developer options."));
    }
  }

  /**
   * 展示本次失败原文（哪条命令、退出码、输出首行）。
   *
   * <p>只读展示，不重放动作；文本可选可滚，短屏与大字体下不会截断关键信息。
   */
  private void showEvidence() {
    AdbBootstrapController.Snapshot snapshot = controller.snapshot();
    String evidence = snapshot.evidence == null ? "" : snapshot.evidence.trim();
    if (evidence.isEmpty()) return;
    new DshaDialogBuilder(card.getContext())
        .setTitle(UiText.choose("失败原文", "Failure details"))
        .setMessage(evidence + crossInstanceNote(evidence))
        .setPositiveButton(UiText.choose("知道了", "Close"), null)
        .show();
  }

  /**
   * 把"另一个实例占着 3090 桥"这一条单独解释清楚。
   *
   * <p>它不是自举失败：前三步已经执行，只有最后一步「会话核验」必须经容器脚本向 3090 桥申请
   * ADB 计划，而同机装了同源第二个包时，3090 属于启动它的那个实例（见上一轮审计的真因 4）。
   */
  private static String crossInstanceNote(String evidence) {
    if (evidence == null) return "";
    if (!evidence.contains("桥拒绝了这个 token") && !evidence.contains("DSHA 实例占用着桥"))
      return "";
    return UiText.choose(
        "\n\n说明：这不是自举失败。授权公钥 / 收窄双栈 / 开启监听三步已经执行，本页的「设备 ADB 授权表」也能核对。\n只有最后一步「会话核验」必须向本机 3090 桥申请 ADB 计划，而同机装了同源的第二个 DSHA 包时，3090 属于启动它的那个实例 —— 本包拿自己的 token 去问必然被拒。这是双实例环境的限制，需要单实例（正式包自己）才能把这一步跑绿。",
        "\n\nNote: this is not a setup failure. Key authorization, loopback narrowing and the listener were executed, and you can confirm them in the device ADB key table below.\nOnly the final session check must ask the local 3090 bridge for an ADB plan; with a second same-origin DSHA package installed, port 3090 belongs to the instance that started it, so this package's token will always be refused. That is a two-instance environment limit; only a single instance (the release package itself) can turn this step green.");
  }

  private void reviewGrant() {
    if (!active()) return;
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("允许独立的系统 ADB 自举操作？", "Allow separate system ADB setup?"))
            .setMessage(
                UiText.choose(
                    "这项授权独立于 Root Shell 和 ADB 通道开关。它允许 DSHA 在逐次确认后管理系统 ADB 公钥授权、TCP 监听端口及 IPv4/IPv6 防火墙防护。\n\n此处仅保存授权，不执行设备动作。关闭开关也不会删除已有公钥或关闭端口；仍需单独执行清理。离开页面不取消任务。撤销授权会阻止后续写入；已经生效的变更需单独清理。",
                    "This permission is separate from Root Shell and the ADB channel switch. It lets DSHA manage system ADB keys, the TCP listener and IPv4/IPv6 firewall protection after you review each action.\n\nThis switch only saves permission; it performs no device action. Turning it off does not remove existing keys or close ports. Cleanup is separate. Leaving the page does not cancel work. Revoking permission blocks further writes; changes already applied require separate cleanup."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("允许自举操作", "Allow setup actions"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration) return;
                  if (!controller.setAllowed(true))
                    toast(UiText.choose("授权保存失败，请重试", "Could not save permission. Try again."));
                  render();
                })
            .create();
    showReview(reviewDialog);
  }

  private void reviewAction(Action action) {
    if (!active() || reviewDialog != null || !ready(action)) return;
    final AdbBootstrapController.Snapshot reviewed = controller.snapshot();
    final long rootRevision = RootShell.authorizationRevision();
    final long grantRevision =
        new com.deepseekharness.app.core.DeviceGrants(app).adbBootstrapRevision();
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(String.format(UiText.choose("%s？", "%s?"), actionLabel(action)))
            .setMessage(reviewMessage(action))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("确认执行", "Confirm action"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration || !ready(action)) return;
                  if (!sameObservation(reviewed, controller.snapshot())
                      || rootRevision != RootShell.authorizationRevision()
                      || grantRevision
                          != new com.deepseekharness.app.core.DeviceGrants(app)
                              .adbBootstrapRevision()) {
                    toast(
                        UiText.choose(
                            "状态已变化，请重新审阅本次操作。", "State changed. Review the action again."));
                    render();
                    return;
                  }
                  // submit 只调用一次；拒绝或未知结果不触发其他通道和自动重试。
                  if (!controller.submit(action))
                    toast(
                        UiText.choose(
                            "未提交：已有任务执行中或授权已变化。",
                            "Not submitted: another task is running or permission changed."));
                  render();
                })
            .create();
    showReview(reviewDialog);
  }

  private boolean ready(Action action) {
    String reason = null;
    if (controller.snapshot().busy)
      reason = UiText.choose("已有自举任务执行中，请等待结果。", "A setup task is running. Wait for its result.");
    else if (needsBootstrap(action) && controller.needsObservation())
      reason =
          UiText.choose(
              "上次结果未知，请先只读刷新状态。", "The previous result is unknown. Refresh read-only first.");
    else if (!RootShell.enabled(app))
      reason = UiText.choose("请先开启「允许 Root Shell」。", "Enable Allow Root Shell first.");
    else if (!RootShell.present())
      reason = UiText.choose("未找到 su，请检查 root 管理器。", "su was not found. Check your root manager.");
    else if (needsBootstrap(action) && !controller.allowed())
      reason = UiText.choose("请先允许独立的 Root 自举 ADB 操作。", "Allow separate ADB setup via root first.");
    if (reason == null) return true;
    toast(reason);
    render();
    return false;
  }

  /**
   * 一键自举：一次确认后按固定顺序跑完 AUTHORIZE → NARROW → OPEN。
   *
   * <p>确认前核验快照身份与两处授权代次；提交后不再逐次询问，任一步失败即停止。
   */
  private void reviewChain() {
    if (!active() || reviewDialog != null || !readyChain()) return;
    final AdbBootstrapController.Snapshot reviewed = controller.snapshot();
    final long rootRevision = RootShell.authorizationRevision();
    final long grantRevision =
        new com.deepseekharness.app.core.DeviceGrants(app).adbBootstrapRevision();
    final int generation = ++reviewGeneration;
    reviewDialog =
        new DshaDialogBuilder(card.getContext())
            .setTitle(UiText.choose("一键自举 ADB？", "Set up ADB via root?"))
            .setMessage(
                UiText.choose(
                    "将按固定顺序执行：\n\n1. 授权本应用公钥（写入系统 ADB 授权表）\n2. 收窄双栈访问（仅本机回环）\n3. 开启 5555 监听\n\n三步全部成功后自动打开 ADB 设备通道，无需系统配对弹窗。任一步失败即停止并保留现场，不重试、不换通道。",
                    "Runs in a fixed order:\n\n1. Authorize this app's key\n2. Narrow to loopback only\n3. Open the listener\n\nOn success the ADB channel is enabled automatically, with no system pairing dialog. Any failure stops the chain and keeps the state; nothing is retried or rerouted."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(
                UiText.choose("确认自举", "Run setup"),
                (dialog, which) -> {
                  if (!active() || generation != reviewGeneration || !readyChain()) return;
                  if (!sameObservation(reviewed, controller.snapshot())
                      || rootRevision != RootShell.authorizationRevision()
                      || grantRevision
                          != new com.deepseekharness.app.core.DeviceGrants(app)
                              .adbBootstrapRevision()) {
                    toast(
                        UiText.choose(
                            "状态已变化，请重新审阅本次操作。", "State changed. Review the action again."));
                    render();
                    return;
                  }
                  if (!controller.submitBootstrapChain())
                    toast(
                        UiText.choose(
                            "未提交：已有任务执行中或授权已变化。",
                            "Not submitted: another task is running or permission changed."));
                  render();
                })
            .create();
    showReview(reviewDialog);
  }

  private boolean readyChain() {
    String reason = null;
    if (controller.snapshot().busy)
      reason = UiText.choose("已有自举任务执行中，请等待结果。", "A setup task is running. Wait for its result.");
    else if (controller.needsObservation())
      reason =
          UiText.choose(
              "上次结果未知，请先只读刷新状态。", "The previous result is unknown. Refresh read-only first.");
    else if (!RootShell.enabled(app))
      reason = UiText.choose("请先开启「允许 Root Shell」。", "Enable Allow Root Shell first.");
    else if (!RootShell.present())
      reason = UiText.choose("未找到 su，请检查 root 管理器。", "su was not found. Check your root manager.");
    else if (!controller.allowed())
      reason = UiText.choose("请先允许独立的 Root 自举 ADB 操作。", "Allow separate ADB setup via root first.");
    if (reason == null) return true;
    toast(reason);
    render();
    return false;
  }

  private void showReview(AlertDialog dialog) {
    dialog.setOnDismissListener(
        ignored -> {
          if (reviewDialog == dialog) reviewDialog = null;
        });
    dialog.show();
  }

  private void dismissReview() {
    reviewGeneration++;
    AlertDialog dialog = reviewDialog;
    reviewDialog = null;
    if (dialog != null) dialog.dismiss();
  }

  private boolean active() {
    return resumed && !detached && card.isAttachedToWindow();
  }

  private void text(int id, String value) {
    TextView label = card.findViewById(id);
    if (!value.contentEquals(label.getText())) label.setText(value);
  }

  private void toast(String message) {
    if (active()) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
  }

  private static boolean needsBootstrap(Action action) {
    return action == Action.AUTHORIZE || action == Action.NARROW || action == Action.OPEN;
  }

  private static boolean sameObservation(
      AdbBootstrapController.Snapshot before, AdbBootstrapController.Snapshot after) {
    // 快照不可变；对象身份同时拒绝“变更后值又相同”的旧确认。
    return before == after;
  }

  private static String state(String value) {
    if (value == null) return UiText.choose("未知", "Unknown");
    return switch (value) {
      case "OWNED" -> UiText.choose("本应用新增的公钥", "Key added by this app");
      case "EXISTING" -> UiText.choose("原已存在，不归本应用撤销", "Pre-existing key; not owned by this app");
      case "NOT_REGISTERED" -> UiText.choose("尚无本机公钥事务记录", "No local key transaction recorded");
      case "UNPROTECTED" -> UiText.choose("未发现完整双栈防护", "Full IPv4/IPv6 protection is absent");
      case "ABSENT" -> UiText.choose("未发现本应用公钥", "This app's key is absent");
      case "OPEN" -> UiText.choose("5555 端口正在监听", "Port 5555 is listening");
      case "CLOSED" ->
          UiText.choose("5555 无监听及存活连接", "No listener or live connections on port 5555");
      case "CONNECTIONS_REMAIN" ->
          UiText.choose("监听已关，连接仍存", "Listener closed; connections remain");
      case "PROTECTED" ->
          UiText.choose("上次核验双栈规则位于首位", "IPv4/IPv6 rules were first when last checked");
      case "UNVERIFIED" -> UiText.choose("双栈防护未确认", "IPv4/IPv6 protection is unverified");
      case "UNKNOWN", "NOT_CHECKED", "" -> UiText.choose("未知", "Unknown");
      default -> UiStateText.render(value);
    };
  }

  private static String detailLabel(String value) {
    return switch (value) {
      case "COMPLETE" ->
          UiText.choose("请结合各项上次观测值检查结果。", "Check the individual last-observed values.");
      case "ADB_SESSION_ROOT" ->
          UiText.choose(
              "本次强制 ADB 会话已验证为 UID 0；不代表所有会话均同权。",
              "This forced ADB session was verified as UID 0; other sessions may differ.");
      case "ADB_SESSION_SHELL" ->
          UiText.choose(
              "本次强制 ADB 会话已验证为 UID 2000。", "This forced ADB session was verified as UID 2000.");
      case "ADB_SESSION_UNVERIFIED" ->
          UiText.choose(
              "公钥与监听已经建立，但 ADB 会话这一次没核验成功：adbd 可能还没读到刚写入的公钥，而核验按设计只做一次。请按本页的「核验当前 ADB 会话（只读）」再核一次 —— 它只跑 id，不会再写任何东西。",
              "Key and listener are in place, but the ADB session did not verify this time: adbd may not have picked up the newly written key yet, and the check runs only once by design. Use Check the current ADB session (read-only) below; it runs a single id and writes nothing.");
      case "OBSERVATION_INCOMPLETE" ->
          UiText.choose(
              "部分状态尚未确认，请保留现场并再次只读检查。",
              "Some state is unverified. Preserve it and inspect read-only again.");
      case "KEY_REAPPEARED_AFTER_REVOKE",
              "ADBD_RESTART_RESULT_UNKNOWN",
              "PORT_CLOSE_RESULT_UNKNOWN" ->
          UiText.choose(
              "清理结果尚未确认，已保留事务且不会重发。请只读检查或在原系统调试入口核对。",
              "Cleanup is unverified. The transaction is retained without replay. Inspect read-only or check the original system debugging controls.");
      case "PENDING_ACTION", "PENDING_ACTION_OBSERVE_AND_CLEANUP" ->
          UiText.choose(
              "有未完成的设备事务。先只读刷新，再根据状态分别关闭监听或撤销公钥。",
              "A device transaction is unfinished. Refresh read-only, then close the listener or revoke the key as appropriate.");
      case "PREEXISTING_KEY_NOT_OWNED" ->
          UiText.choose(
              "这条公钥授权原已存在，本应用不会删除它。",
              "This key authorization predated setup; this app will not delete it.");
      case "WIRELESS_DEBUG_IN_USE" ->
          UiText.choose(
              "系统「无线调试」正在运行（ADB TLS 入口），DSHA 不会接管它。请先关闭无线调试，再执行自举；在你处理期间 DSHA 不会自动重开它。",
              "System wireless debugging is active (an ADB TLS endpoint); this app will not take it over. Turn wireless debugging off before running setup. The app will not reopen it while this block is shown.");
      case "ADB_LISTENER_ALREADY_IN_USE",
              "ADB_LISTENER_NOT_OWNED",
              "OTHER_ADB_PORT_IN_USE",
              "PERSISTENT_ADB_PORT_IN_USE" ->
          UiText.choose(
              "已有其他来源或端口的 ADB 监听，本次不会接管。请先在原调试入口核对。",
              "An ADB listener is owned elsewhere or uses another port. This request will not take it over; check its original debugging controls.");
      case "NATIVE_GRANT_UNAVAILABLE", "NATIVE_GRANT_REVOKED", "NATIVE_GRANT_OR_SU_UNAVAILABLE" ->
          UiText.choose(
              "原生授权已变化或 su 不可用，后续写入已停止；已有变更需单独核对。",
              "Native permission changed or su is unavailable. Further writes stopped; verify any changes already applied.");
      case "ADB_SWITCH_INTENT_CHANGED" ->
          UiText.choose(
              "你在自举过程中关闭或重新选择过 ADB 总开关：旧链已停止，不会再写入设备，也不会把总开关写回来。设备可能停在中间状态（例如公钥已授权但监听未开），请先只读刷新核对，需要时再单独清理。",
              "You turned the ADB master switch off or re-selected it during setup: the old chain stopped, performs no further device writes and will not flip the switch back. The device may be left mid-chain (for example key authorized but listener closed); refresh read-only and clean up separately if needed.");
      case "KEY_TABLE_CHANGED", "KEY_OWNERSHIP_CONFLICT", "CLIENT_KEY_CHANGED_REVOKE_FIRST" ->
          UiText.choose(
              "公钥或授权表发生变化，不能确认安全修改范围；原件与事务记录已保留。",
              "The key or authorization table changed. The safe edit scope is uncertain; originals and transaction records are retained.");
      case "BOOTSTRAP_OTHER_OWNER" ->
          UiText.choose(
              "自举记录属于另一应用安装或 Android 用户，本次不会修改。",
              "Setup records belong to another installation or Android user and will not be modified.");
      default -> UiStateText.render(value);
    };
  }

  private static String outcomeLabel(String value) {
    if (value == null) return UiText.choose("结果未知", "Result unknown");
    return switch (value) {
      case "NOT_CHECKED" -> UiText.choose("尚未检查", "Not checked");
      case "RUNNING" -> UiText.choose("正在执行", "Running");
      case "OK" -> UiText.choose("本次操作已完成", "This action completed");
      case "REFUSED" -> UiText.choose("本次请求被拒绝", "This request was refused");
      case "FAILED" -> UiText.choose("本次操作失败，请查看详情", "This action failed. See details.");
      default ->
          UiText.choose("结果未知 · 请先只读刷新，不要重放", "Result unknown · Refresh first; do not replay");
    };
  }

  private static String actionLabel(Action action) {
    return switch (action) {
      case AUTHORIZE -> UiText.choose("授权本应用公钥", "Authorize this app's key");
      case NARROW -> UiText.choose("收窄双栈访问", "Restrict IPv4/IPv6 access");
      case OPEN -> UiText.choose("开启 ADB 监听", "Open ADB listener");
      case CLOSE -> UiText.choose("关闭 ADB 监听", "Close ADB listener");
      case REVOKE -> UiText.choose("撤销本应用公钥", "Revoke this app's key");
      case STATUS -> UiText.choose("只读刷新状态", "Refresh status (read-only)");
    };
  }

  private static String reviewMessage(Action action) {
    String message =
        switch (action) {
          case AUTHORIZE ->
              UiText.choose(
                  "把本应用的 ADB 公钥加入系统授权表。执行前需核验密钥所有权与双栈防护。",
                  "Add this app's ADB key to the system authorization table. Key ownership and IPv4/IPv6 protection must be verified first.");
          case NARROW ->
              UiText.choose(
                  "按自举计划调整 IPv4/IPv6 防火墙防护，限制 ADB 端口的访问范围。",
                  "Apply the setup plan's IPv4/IPv6 firewall protection to restrict access to the ADB port.");
          case OPEN ->
              UiText.choose(
                  "在双栈防护检查通过后开启 ADB TCP 监听。",
                  "Open the ADB TCP listener after IPv4/IPv6 protection checks pass.");
          case CLOSE ->
              UiText.choose(
                  "关闭自举的 ADB TCP 监听。关闭监听不等于撤销公钥授权，请分别核对状态。",
                  "Close the setup ADB TCP listener. This is separate from revoking key authorization; check both states.");
          case REVOKE ->
              UiText.choose(
                  "仅撤销有本应用所有权记录的公钥；保留其他客户端的授权。请根据结果核对残留状态。",
                  "Revoke only keys recorded as owned by this app, keeping other clients' authorizations. Check the result for remaining state.");
          case STATUS ->
              UiText.choose(
                  "通过已授权的 root 只读检查公钥、监听和双栈防护。不会执行授权或清理，未能确认的字段保持未知。",
                  "Use authorized root access to inspect keys, the listener and IPv4/IPv6 protection. No authorization or cleanup is performed. Unconfirmed fields remain unknown.");
        };
    if (action == Action.AUTHORIZE
        || action == Action.OPEN
        || action == Action.REVOKE
        || action == Action.CLOSE)
      message +=
          UiText.choose(
              "\n\n可能重启系统 ADB 服务（adbd），导致其他正在使用的调试连接断开。",
              "\n\nThis may restart the system ADB service (adbd) and disconnect other active debugging sessions.");
    if (action != Action.STATUS)
      message +=
          UiText.choose(
              "\n\n双栈防护规则不保证跨设备重启或厂商网络刷新持续有效。执行结果未知时请先只读刷新，不要重复执行。",
              "\n\nIPv4/IPv6 rules are not guaranteed to survive a reboot or vendor network reset. If the result is unknown, refresh read-only first; do not repeat the action.");
    return message;
  }
}
