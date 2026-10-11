package com.deepseekharness.app.core;

import android.content.Context;
import android.provider.Settings;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.UiText;

/**
 * 授权的唯一观测入口：把散落在多处、各自维护的事实收敛成一次 {@link DeviceAuthorization.Snapshot}。
 *
 * <p>设置页与诊断页都只从这里取状态与措辞，避免出现两份报告各说各话（历史事故：自检报
 * 「已重新写入 token」而容器侧同时报「缺 .bridge_token」）。
 *
 * <p>这里只做**廉价观测**：不启动容器命令、不写任何状态。精确的 ADB key/deps 探测仍由设置页
 * 自己的后台状态行负责（它本来就在后台线程跑）。
 */
public final class DeviceAuthorizationReader {
  private DeviceAuthorizationReader() {}

  public static DeviceAuthorization.Snapshot snapshot(Context context) {
    Context app = context.getApplicationContext();
    DeviceGrants grants = new DeviceGrants(app);
    String adbState = DeviceBridgeService.adbState == null ? "" : DeviceBridgeService.adbState;
    AdbBootstrapController bootstrap = AdbBootstrapController.get(app);
    AdbBootstrapController.Snapshot latest = bootstrap.snapshot();
    boolean usb = false;
    try {
      usb = Settings.Global.getInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    } catch (Throwable ignored) {
      // 系统设置读不到就按未观测处理，不猜。
    }
    boolean secureSettings = false;
    try {
      secureSettings =
          app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {
    }
    return DeviceAuthorization.builder()
        .root(RootShell.enabled(app), RootShell.present(), RootShell.authorizationRevision())
        .shizuku(
            ShizukuShell.isAvailable(),
            ShizukuShell.hasPermission(),
            ShizukuShell.isReady(),
            ShizukuShell.failureReason())
        .adb(
            DeviceBridgeService.isAdbEnabled(app),
            adbState,
            !adbState.isEmpty() && !"need_pair".equals(adbState),
            "connected".equals(adbState))
        .bootstrap(
            grants.adbBootstrapAllowed(),
            grants.adbBootstrapRevision(),
            latest != null && "OPEN".equals(latest.listenerState))
        .wireless(
            latest != null && "WIRELESS_DEBUG_IN_USE".equals(latest.detail),
            bootstrap.blocksWirelessReopen(),
            secureSettings)
        .usbDebugDetected(usb)
        .screenGrant(HttpShellService.hasScreenGrant(app))
        .build();
  }

  /** 总结状态：谁在生效、能用它做什么。设置页与诊断页共用同一句话。 */
  public static String summary(Context context) {
    return summary(snapshot(context));
  }

  /** ADB 授权入口行：只给一个结论 + 已取得的来源（ADR 0005 §3）。 */
  public static String adbEntrySummary(DeviceAuthorization.Snapshot snapshot) {
    DeviceAuthorization.State state = DeviceAuthorization.globalState(snapshot);
    DeviceAuthorization.Reason reason = DeviceAuthorization.globalReason(snapshot);
    DeviceAuthorization.Pipe source = DeviceAuthorization.grantedSource(snapshot);
    if (state == DeviceAuthorization.State.GRANTED && source != null)
      return UiText.format(
          "%s · 来源=%s",
          UiText.text("已授权"), pipeLabel(source));
    if (!snapshot.adbEnabled)
      return UiText.format(
          "%s · %s", UiText.text("未授权"), reasonLabel(DeviceAuthorization.Reason.ADB_GLOBAL_OFF));
    return UiText.format("%s · %s", UiText.text("未授权"), reasonLabel(reason));
  }

  public static String summary(DeviceAuthorization.Snapshot snapshot) {
    DeviceAuthorization.Authority authority = DeviceAuthorization.authority(snapshot);
    return UiText.format("%s · %s", authorityLabel(authority), usableLabel(authority));
  }

  /** 每条管道的可用性与阻塞原因（不是布尔）。多行文本，共用。 */
  public static String pipes(Context context) {
    return pipes(snapshot(context));
  }

  public static String pipes(DeviceAuthorization.Snapshot snapshot) {
    StringBuilder out = new StringBuilder();
    for (DeviceAuthorization.PipeStatus status : DeviceAuthorization.pipes(snapshot)) {
      if (out.length() > 0) out.append('\n');
      out.append(UiText.format("%s：%s", pipeLabel(status.pipe), stateLabel(status.state)));
      if (status.state != DeviceAuthorization.State.GRANTED)
        out.append(UiText.format("（%s）", reasonLabel(status.reason)));
    }
    DeviceAuthorization.PipeStatus keepalive = DeviceAuthorization.keepalive(snapshot);
    out.append('\n')
        .append(
            UiText.format(
                "%s：%s",
                UiText.text("无线调试自动恢复凭据"), stateLabel(keepalive.state)));
    if (keepalive.state != DeviceAuthorization.State.GRANTED)
      out.append(UiText.format("（%s）", reasonLabel(keepalive.reason)));
    return out.toString();
  }

  /**
   * 系统调试授权的只读检测。今天 App 里没有 USB 调试的代码路径，只能读系统总开关，
   * 因此措辞必须说清「无法区分 USB 与无线」，不假装检测到了 USB 授权。
   */
  public static String usbLabel(DeviceAuthorization.Snapshot snapshot) {
    return snapshot.usbDebugDetected
        ? UiText.text("只读检测：系统调试授权已开启（无法区分 USB 与无线调试）")
        : UiText.text("只读检测：系统调试授权未开启；USB 调试今天没有独立入口，需在系统里授权");
  }

  /** 单条来源的一行说明：已取得，或「状态（原因）」。四节共用。 */
  public static String sourceLine(
      DeviceAuthorization.Snapshot snapshot, DeviceAuthorization.Pipe pipe) {
    for (DeviceAuthorization.PipeStatus status : DeviceAuthorization.pipes(snapshot))
      if (status.pipe == pipe)
        return status.state == DeviceAuthorization.State.GRANTED
            ? UiText.text("已取得 · 该来源可用")
            : UiText.format("%s（%s）", stateLabel(status.state), reasonLabel(status.reason));
    return UiText.text("未观测");
  }

  public static String authorityLabel(DeviceAuthorization.Authority authority) {
    switch (authority) {
      case ROOT_UID0:
        return UiText.text("已授权 · 来源=Root（uid 0）");
      case ADB_SHELL:
        return UiText.text("已授权 · 来源=ADB（系统 adbd shell）");
      case SHIZUKU_ADB:
        return UiText.text("已授权 · 来源=Shizuku（由 ADB 启动）");
      case SHIZUKU_ROOT:
        return UiText.text("已授权 · 来源=Shizuku（由 Root 启动）");
      default:
        return UiText.text("未授权 · 没有可用的设备通道");
    }
  }

  public static String usableLabel(DeviceAuthorization.Authority authority) {
    switch (authority) {
      case ROOT_UID0:
      case SHIZUKU_ADB:
      case SHIZUKU_ROOT:
      case ADB_SHELL:
        return UiText.text("可用于：设备 shell（短信查询仍需单独授权）");
      default:
        return UiText.text("打开任一条来源后可用");
    }
  }

  public static String pipeLabel(DeviceAuthorization.Pipe pipe) {
    switch (pipe) {
      case ROOT_SU:
        return UiText.text("Root shell");
      case SHIZUKU_BINDER:
        return UiText.text("Shizuku");
      case ADB_BOOTSTRAP:
        return UiText.text("ADB 自举");
      case ADB_TCP:
        return UiText.text("无线调试");
      case ADB_USB:
        return UiText.text("USB 调试");
      default:
        return UiText.text("虚拟屏");
    }
  }

  public static String stateLabel(DeviceAuthorization.State state) {
    switch (state) {
      case GRANTED:
        return UiText.choose("已授权", "Granted");
      case GRANTABLE:
        return UiText.choose("可授权", "Available");
      case PENDING:
        return UiText.choose("等待完成", "Pending");
      case BLOCKED:
        return UiText.choose("被阻塞", "Blocked");
      case ABSENT:
        return UiText.choose("未开启", "Off");
      case STALE:
        return UiText.choose("已过期", "Stale");
      default:
        return UiText.choose("未观测", "Not observed");
    }
  }

  /** 每一条阻塞原因只在这里有措辞；UI、诊断与 /app/device 都复用它。 */
  public static String reasonLabel(DeviceAuthorization.Reason reason) {
    switch (reason) {
      case READY:
        return UiText.choose("可用", "Ready");
      case ROOT_SWITCH_OFF:
        return UiText.choose("「允许 Root Shell」已关闭", "Root shell is off");
      case ROOT_SU_NOT_FOUND:
        return UiText.choose("未找到 su，或 root 管理器未放行", "No su, or root manager denied this app");
      case SHIZUKU_NOT_INSTALLED:
        return UiText.choose("未安装或服务未连接", "Not installed, or service not connected");
      case SHIZUKU_NO_PERMISSION:
        return UiText.choose("尚未授权 DSHA", "DSHA is not authorized yet");
      case SHIZUKU_NOT_CONNECTED:
        return UiText.choose("已授权但未连接设备 Shell", "Authorized but the device shell is not connected");
      case SMS_NEEDS_ROOT_OR_ADB:
        return UiText.choose(
            "短信查询不走 Shizuku，需要 Root 或 ADB", "SMS queries need Root or ADB, not Shizuku");
      case ADB_CHANNEL_OFF:
        return UiText.choose("ADB 设备通道已关闭", "ADB device channel is off");
      case ADB_GLOBAL_OFF:
        return UiText.choose(
            "ADB 全局授权开关已关闭（四来源一律不参与执行）",
            "The ADB global authorization switch is off (no source is used)");
      case ADB_NOT_PAIRED:
        return UiText.choose("尚未配对或未验证", "Not paired or not verified");
      case ADB_RECONNECTING:
        return UiText.choose("正在重连", "Reconnecting");
      case ADB_ENVIRONMENT_BUSY:
        return UiText.choose("环境任务进行中，ADB 暂停检查", "Environment task running; ADB paused");
      case BOOTSTRAP_ALLOWED:
        return UiText.choose("已允许，等待本机执行", "Allowed; waiting for the device action");
      case BOOTSTRAP_SWITCH_OFF:
        return UiText.choose("自举开关已关闭", "Bootstrap switch is off");
      case BOOTSTRAP_NEEDS_ROOT:
        return UiText.choose("自举需要 Root", "Bootstrap requires Root");
      case WIRELESS_DEBUG_IN_USE:
        return UiText.choose(
            "系统无线调试占用中，自举按设计拒绝接管", "System wireless debugging is in use; bootstrap refuses to take over");
      case WIRELESS_REOPEN_BLOCKED:
        return UiText.choose(
            "自举期间禁止自动重开无线调试", "Auto-reopening wireless debugging is blocked during bootstrap");
      default:
        return UiText.choose("尚未观测", "Not observed");
    }
  }
}
