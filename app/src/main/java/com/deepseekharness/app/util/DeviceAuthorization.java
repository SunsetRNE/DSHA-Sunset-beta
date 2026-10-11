package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备命令授权的唯一模型：把混在一起的三件事拆成类型。
 *
 * <p>权威 Authority（谁批准）／凭据 Grant（哪次授权、代次）／管道 Transport（argv 真正走哪条通道）。
 * 本类零 Android import，只做决策与状态重算；真实观测由 App 层塞进 {@link Builder}。
 *
 * <p>执行期语义与 {@code HttpShellService.deviceExecute} 原来的 if 链逐项等价 —— 顺序仍是
 * Root → Shizuku → ADB，{@code forceRoot} / {@code forceAdb} / 短信敏感读各自旁路；区别只是
 * 现在返回决策**和理由**，UI 与 /app/device 不必再各自猜。
 */
public final class DeviceAuthorization {
  private DeviceAuthorization() {}

  /** 谁批准本应用执行特权操作。Shizuku 不是权威，只是管道，其来源由 {@link Pipe} 表达。 */
  public enum Authority {
    NONE,
    ROOT_UID0,
    ADB_SHELL,
    SHIZUKU_ADB,
    SHIZUKU_ROOT
  }

  /** 真正把 argv 送进设备 shell 的通道。 */
  public enum Pipe {
    ROOT_SU,
    SHIZUKU_BINDER,
    ADB_USB,
    ADB_TCP,
    ADB_BOOTSTRAP,
    VS_LAUNCH
  }

  public enum State {
    UNKNOWN,
    ABSENT,
    GRANTABLE,
    PENDING,
    GRANTED,
    STALE,
    BLOCKED
  }

  /** 不可用的具体原因；UI、诊断页与 /app/device 都按它渲染，不再各自拼字符串。 */
  public enum Reason {
    READY,
    ROOT_SWITCH_OFF,
    ROOT_SU_NOT_FOUND,
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NO_PERMISSION,
    SHIZUKU_NOT_CONNECTED,
    SMS_NEEDS_ROOT_OR_ADB,
    ADB_CHANNEL_OFF,
    ADB_GLOBAL_OFF,
    ADB_NOT_PAIRED,
    ADB_RECONNECTING,
    ADB_ENVIRONMENT_BUSY,
    BOOTSTRAP_ALLOWED,
    BOOTSTRAP_SWITCH_OFF,
    BOOTSTRAP_NEEDS_ROOT,
    WIRELESS_DEBUG_IN_USE,
    WIRELESS_REOPEN_BLOCKED,
    NOT_OBSERVED
  }

  /** 一次授权观测的不可变快照。只装事实，不装结论。 */
  public static final class Snapshot {
    public final boolean rootEnabled;
    public final boolean rootPresent;
    public final long rootRevision;
    public final boolean shizukuAvailable;
    public final boolean shizukuPermission;
    public final boolean shizukuReady;
    public final String shizukuFailure;
    public final boolean adbEnabled;
    public final String adbState;
    public final boolean adbKeyPresent;
    public final boolean adbConnected;
    public final boolean bootstrapAllowed;
    public final long bootstrapRevision;
    public final boolean bootstrapActive;
    public final boolean wirelessDebugInUse;
    public final boolean wirelessReopenBlocked;
    public final boolean usbDebugDetected;
    public final boolean writeSecureSettings;
    public final boolean screenGrant;

    private Snapshot(Builder value) {
      rootEnabled = value.rootEnabled;
      rootPresent = value.rootPresent;
      rootRevision = value.rootRevision;
      shizukuAvailable = value.shizukuAvailable;
      shizukuPermission = value.shizukuPermission;
      shizukuReady = value.shizukuReady;
      shizukuFailure = value.shizukuFailure == null ? "" : value.shizukuFailure;
      adbEnabled = value.adbEnabled;
      adbState = value.adbState == null ? "" : value.adbState;
      adbKeyPresent = value.adbKeyPresent;
      adbConnected = value.adbConnected;
      bootstrapAllowed = value.bootstrapAllowed;
      bootstrapRevision = value.bootstrapRevision;
      bootstrapActive = value.bootstrapActive;
      wirelessDebugInUse = value.wirelessDebugInUse;
      wirelessReopenBlocked = value.wirelessReopenBlocked;
      usbDebugDetected = value.usbDebugDetected;
      writeSecureSettings = value.writeSecureSettings;
      screenGrant = value.screenGrant;
    }

    /**
     * 授权状态的稳定指纹：任何受管事实变化都会改变它，用于判断快照是否过期。
     *
     * <p>它刻意不是全局单调序号 —— 单调序号需要有人写入；本类保持纯函数，单调性由调用方
     * 自己的在途请求计数负责（{@code RootShell.authorizationRevision()} 等）。
     */
    public long fingerprint() {
      long h = 1125899906842597L;
      h = h * 31 + (rootEnabled ? 1 : 0);
      h = h * 31 + (rootPresent ? 1 : 0);
      h = h * 31 + rootRevision;
      h = h * 31 + (shizukuAvailable ? 1 : 0);
      h = h * 31 + (shizukuPermission ? 1 : 0);
      h = h * 31 + (shizukuReady ? 1 : 0);
      h = h * 31 + shizukuFailure.hashCode();
      h = h * 31 + (adbEnabled ? 1 : 0);
      h = h * 31 + adbState.hashCode();
      h = h * 31 + (adbKeyPresent ? 1 : 0);
      h = h * 31 + (adbConnected ? 1 : 0);
      h = h * 31 + (bootstrapAllowed ? 1 : 0);
      h = h * 31 + bootstrapRevision;
      h = h * 31 + (bootstrapActive ? 1 : 0);
      h = h * 31 + (wirelessDebugInUse ? 1 : 0);
      h = h * 31 + (wirelessReopenBlocked ? 1 : 0);
      h = h * 31 + (usbDebugDetected ? 1 : 0);
      h = h * 31 + (writeSecureSettings ? 1 : 0);
      h = h * 31 + (screenGrant ? 1 : 0);
      return h;
    }

    public boolean equals(Object other) {
      return other instanceof Snapshot && ((Snapshot) other).fingerprint() == fingerprint();
    }

    public int hashCode() {
      return Long.hashCode(fingerprint());
    }
  }

  /** 快照装配器；每个字段都对应一处真实观测，缺省即「未观测」。 */
  public static final class Builder {
    private boolean rootEnabled;
    private boolean rootPresent;
    private long rootRevision;
    private boolean shizukuAvailable;
    private boolean shizukuPermission;
    private boolean shizukuReady;
    private String shizukuFailure = "";
    private boolean adbEnabled;
    private String adbState = "";
    private boolean adbKeyPresent;
    private boolean adbConnected;
    private boolean bootstrapAllowed;
    private long bootstrapRevision;
    private boolean bootstrapActive;
    private boolean wirelessDebugInUse;
    private boolean wirelessReopenBlocked;
    private boolean usbDebugDetected;
    private boolean writeSecureSettings;
    private boolean screenGrant;

    public Builder root(boolean enabled, boolean present, long revision) {
      rootEnabled = enabled;
      rootPresent = present;
      rootRevision = revision;
      return this;
    }

    public Builder shizuku(boolean available, boolean permission, boolean ready, String failure) {
      shizukuAvailable = available;
      shizukuPermission = permission;
      shizukuReady = ready;
      shizukuFailure = failure;
      return this;
    }

    public Builder adb(boolean enabled, String state, boolean keyPresent, boolean connected) {
      adbEnabled = enabled;
      adbState = state;
      adbKeyPresent = keyPresent;
      adbConnected = connected;
      return this;
    }

    public Builder bootstrap(boolean allowed, long revision, boolean active) {
      bootstrapAllowed = allowed;
      bootstrapRevision = revision;
      bootstrapActive = active;
      return this;
    }

    public Builder wireless(boolean debugInUse, boolean reopenBlocked, boolean secureSettings) {
      wirelessDebugInUse = debugInUse;
      wirelessReopenBlocked = reopenBlocked;
      writeSecureSettings = secureSettings;
      return this;
    }

    public Builder usbDebugDetected(boolean value) {
      usbDebugDetected = value;
      return this;
    }

    public Builder screenGrant(boolean value) {
      screenGrant = value;
      return this;
    }

    public Snapshot build() {
      return new Snapshot(this);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** 执行期决策：走哪条管道、属于哪个权威、为什么、是否要限制到 App 自己的用户。 */
  public static final class Decision {
    public final Pipe pipe;
    public final Authority authority;
    public final Reason reason;
    public final boolean executable;
    /** 与 {@code deviceExecute} 既有 transport 字符串完全一致：root / shizuku / adb / none。 */
    public final String transport;
    /** 短信读这类敏感能力在 root 通道上必须限制到当前 Android 用户。 */
    public final boolean restrictToAppUser;

    Decision(
        Pipe pipe,
        Authority authority,
        Reason reason,
        boolean executable,
        String transport,
        boolean restrictToAppUser) {
      this.pipe = pipe;
      this.authority = authority;
      this.reason = reason;
      this.executable = executable;
      this.transport = transport;
      this.restrictToAppUser = restrictToAppUser;
    }
  }

  /**
   * 发送前选一次通道。语义（ADR 0005）：
   *
   * <ol>
   *   <li>{@code !forceAdb && rootEnabled && rootPresent} → root（独立权威，不受 ADB 总开关影响；
   *       短信读限制到 App 用户）
   *   <li>ADB 总开关关（{@code !adbEnabled}）→ 无通道，连 Shizuku 也不得用
   *   <li>{@code !forceRoot && !敏感读 && shizukuPermission} → shizuku（中间层，属于 ADB 授权来源）
   *   <li>否则 → ADB 计划（自举监听 / 无线 / USB 之一）
   * </ol>
   */
  public static Decision plan(
      Snapshot snapshot, boolean forceRoot, boolean forceAdb, String capability) {
    boolean sensitiveRead = "sms.read".equals(capability);
    if (!forceAdb && snapshot.rootEnabled && snapshot.rootPresent)
      return new Decision(
          Pipe.ROOT_SU,
          Authority.ROOT_UID0,
          Reason.READY,
          true,
          "root",
          sensitiveRead);
    if (!snapshot.adbEnabled)
      return new Decision(
          null,
          Authority.NONE,
          sensitiveRead ? Reason.SMS_NEEDS_ROOT_OR_ADB : Reason.ADB_GLOBAL_OFF,
          false,
          "none",
          false);
    if (!forceAdb && !forceRoot && !sensitiveRead && snapshot.shizukuPermission)
      return new Decision(
          Pipe.SHIZUKU_BINDER,
          snapshot.rootEnabled ? Authority.SHIZUKU_ROOT : Authority.SHIZUKU_ADB,
          Reason.READY,
          true,
          "shizuku",
          false);
    return new Decision(
        adbPipe(snapshot), Authority.ADB_SHELL, adbReason(snapshot), true, "adb", false);
  }

  /** 当前生效的权威：与执行顺序一致，且尊重 ADB 总开关。 */
  public static Authority authority(Snapshot snapshot) {
    if (snapshot.rootEnabled && snapshot.rootPresent) return Authority.ROOT_UID0;
    if (!snapshot.adbEnabled) return Authority.NONE;
    if (snapshot.adbConnected) return Authority.ADB_SHELL;
    if (snapshot.shizukuPermission && snapshot.shizukuReady)
      return snapshot.rootEnabled ? Authority.SHIZUKU_ROOT : Authority.SHIZUKU_ADB;
    return Authority.NONE;
  }

  /** ADB 授权来源的四个成员，顺序固定为 UI 的四节顺序。 */
  public static List<PipeStatus> adbSources(Snapshot s) {
    List<PipeStatus> sources = new ArrayList<>();
    sources.add(status(s, Pipe.ADB_USB));
    sources.add(status(s, Pipe.ADB_TCP));
    sources.add(status(s, Pipe.SHIZUKU_BINDER));
    sources.add(status(s, Pipe.ADB_BOOTSTRAP));
    return Collections.unmodifiableList(sources);
  }

  /** 全局授权状态：总开关开 **且** 某一来源已取得才算已授权 —— UI 只显示这一个结论。 */
  public static State globalState(Snapshot s) {
    if (!s.adbEnabled) return State.ABSENT;
    return grantedSource(s) != null ? State.GRANTED : State.ABSENT;
  }

  public static Reason globalReason(Snapshot s) {
    if (!s.adbEnabled) return Reason.ADB_GLOBAL_OFF;
    for (PipeStatus status : adbSources(s))
      if (status.state == State.GRANTED) return status.reason;
    // 还没取得时，给出「最接近可用」那条来源的原因。
    for (PipeStatus status : adbSources(s))
      if (status.state != State.ABSENT && status.state != State.UNKNOWN) return status.reason;
    return Reason.NOT_OBSERVED;
  }

  /** 已取得的来源（用于「来源=…」这一行）；没有则返回 null。 */
  public static Pipe grantedSource(Snapshot s) {
    if (!s.adbEnabled) return null;
    for (PipeStatus status : adbSources(s))
      if (status.state == State.GRANTED) return status.pipe;
    return null;
  }

  /** 每条管道的可用性与阻塞原因（不是布尔）。 */
  public static final class PipeStatus {
    public final Pipe pipe;
    public final State state;
    public final Reason reason;

    PipeStatus(Pipe pipe, State state, Reason reason) {
      this.pipe = pipe;
      this.state = state;
      this.reason = reason;
    }
  }

  /** 全部管道：Root 在前，随后是 ADB 四来源（UI 顺序），最后虚拟屏。 */
  public static List<PipeStatus> pipes(Snapshot s) {
    List<PipeStatus> values = new ArrayList<>();
    values.add(status(s, Pipe.ROOT_SU));
    values.add(status(s, Pipe.ADB_USB));
    values.add(status(s, Pipe.ADB_TCP));
    values.add(status(s, Pipe.SHIZUKU_BINDER));
    values.add(status(s, Pipe.ADB_BOOTSTRAP));
    values.add(status(s, Pipe.VS_LAUNCH));
    return Collections.unmodifiableList(values);
  }

  private static PipeStatus status(Snapshot s, Pipe pipe) {
    switch (pipe) {
      case ROOT_SU:
        return new PipeStatus(
            Pipe.ROOT_SU,
            !s.rootEnabled ? State.ABSENT : s.rootPresent ? State.GRANTED : State.BLOCKED,
            !s.rootEnabled
                ? Reason.ROOT_SWITCH_OFF
                : s.rootPresent ? Reason.READY : Reason.ROOT_SU_NOT_FOUND);
      case ADB_USB:
        return new PipeStatus(
            Pipe.ADB_USB,
            s.usbDebugDetected ? State.GRANTED : State.UNKNOWN,
            s.usbDebugDetected ? Reason.READY : Reason.NOT_OBSERVED);
      case ADB_TCP:
        return new PipeStatus(
            Pipe.ADB_TCP,
            !s.adbEnabled ? State.ABSENT : s.adbConnected ? State.GRANTED : State.PENDING,
            !s.adbEnabled ? Reason.ADB_GLOBAL_OFF : adbReason(s));
      case SHIZUKU_BINDER:
        return new PipeStatus(
            Pipe.SHIZUKU_BINDER,
            !s.shizukuAvailable
                ? State.ABSENT
                : !s.shizukuPermission
                    ? State.GRANTABLE
                    : s.shizukuReady ? State.GRANTED : State.PENDING,
            !s.shizukuAvailable
                ? Reason.SHIZUKU_NOT_INSTALLED
                : !s.shizukuPermission
                    ? Reason.SHIZUKU_NO_PERMISSION
                    : s.shizukuReady ? Reason.READY : Reason.SHIZUKU_NOT_CONNECTED);
      case ADB_BOOTSTRAP:
        return new PipeStatus(
            Pipe.ADB_BOOTSTRAP,
            !s.bootstrapAllowed
                ? State.ABSENT
                : s.wirelessDebugInUse
                    ? State.BLOCKED
                    : s.bootstrapActive ? State.GRANTED : State.GRANTABLE,
            !s.bootstrapAllowed
                ? Reason.BOOTSTRAP_SWITCH_OFF
                : !s.rootEnabled
                    ? Reason.BOOTSTRAP_NEEDS_ROOT
                    : s.wirelessDebugInUse
                        ? Reason.WIRELESS_DEBUG_IN_USE
                        : s.bootstrapActive ? Reason.READY : Reason.BOOTSTRAP_ALLOWED);
      default:
        return new PipeStatus(
            Pipe.VS_LAUNCH,
            s.screenGrant ? State.GRANTED : State.ABSENT,
            s.screenGrant ? Reason.READY : Reason.NOT_OBSERVED);
    }
  }

  /**
   * 无线调试派生出的系统权限（WRITE_SECURE_SETTINGS）是独立凭据：它只能用于维持无线调试自身，
   * 自举生效期间必须被互斥规则拦停。统一层只表达，不放宽。
   */
  public static PipeStatus keepalive(Snapshot s) {
    if (!s.adbEnabled) return new PipeStatus(Pipe.ADB_TCP, State.ABSENT, Reason.ADB_CHANNEL_OFF);
    if (s.wirelessReopenBlocked)
      return new PipeStatus(Pipe.ADB_TCP, State.BLOCKED, Reason.WIRELESS_REOPEN_BLOCKED);
    if (s.wirelessDebugInUse)
      return new PipeStatus(Pipe.ADB_TCP, State.BLOCKED, Reason.WIRELESS_DEBUG_IN_USE);
    if (!s.writeSecureSettings)
      return new PipeStatus(Pipe.ADB_TCP, State.ABSENT, Reason.NOT_OBSERVED);
    return new PipeStatus(Pipe.ADB_TCP, State.GRANTED, Reason.READY);
  }

  private static Pipe adbPipe(Snapshot snapshot) {
    return snapshot.bootstrapActive ? Pipe.ADB_BOOTSTRAP : Pipe.ADB_TCP;
  }

  private static Reason adbReason(Snapshot snapshot) {
    if (snapshot.adbConnected) return Reason.READY;
    if ("environment_busy".equals(snapshot.adbState)) return Reason.ADB_ENVIRONMENT_BUSY;
    if ("reconnecting".equals(snapshot.adbState)) return Reason.ADB_RECONNECTING;
    if ("need_pair".equals(snapshot.adbState)) return Reason.ADB_NOT_PAIRED;
    if (snapshot.bootstrapActive) return Reason.READY;
    return snapshot.adbKeyPresent ? Reason.NOT_OBSERVED : Reason.ADB_NOT_PAIRED;
  }
}
