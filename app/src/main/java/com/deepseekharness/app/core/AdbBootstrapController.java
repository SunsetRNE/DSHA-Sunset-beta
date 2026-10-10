package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.RootBootstrapAuthority;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 应用级单飞控制器。仅保存 Application 与结构化结果，页面销毁不会取消或重放设备动作。 */
public final class AdbBootstrapController {
  private static AdbBootstrapController instance;
  private final Context app;
  private final SharedPreferences records;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "adb-bootstrap");
            thread.setDaemon(true);
            return thread;
          });
  private volatile Snapshot current;
  private volatile boolean rootRequestSent;
  private volatile boolean unresolved;
  /** 一键自举剩余动作；固定顺序，不重排、不重放。 */
  private final java.util.ArrayDeque<AdbBootstrapPolicy.Action> chain =
      new java.util.ArrayDeque<>();
  private volatile boolean chainActive;

  public static final class Snapshot {
    public final boolean busy;
    public final AdbBootstrapPolicy.Action action;
    public final String outcome, detail, keyState, listenerState, guardState;
    public final long observedAt;

    Snapshot(
        boolean busy,
        AdbBootstrapPolicy.Action action,
        String outcome,
        String detail,
        String key,
        String listener,
        String guard,
        long observedAt) {
      this.busy = busy;
      this.action = action;
      this.outcome = outcome;
      this.detail = detail;
      keyState = key;
      listenerState = listener;
      guardState = guard;
      this.observedAt = observedAt;
    }
  }

  private AdbBootstrapController(Context application) {
    app = application.getApplicationContext();
    records = app.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    unresolved = records.getBoolean("bootstrap_request_pending", false);
    current =
        unknown(
            null,
            records.getBoolean("bootstrap_request_pending", false) ? "UNKNOWN" : "NOT_CHECKED",
            records.getBoolean("bootstrap_request_pending", false)
                ? "PREVIOUS_REQUEST_OUTCOME_UNKNOWN"
                : "");
  }

  public static synchronized AdbBootstrapController get(Context app) {
    if (instance == null) instance = new AdbBootstrapController(app);
    return instance;
  }

  public boolean allowed() {
    return new DeviceGrants(app).adbBootstrapAllowed();
  }

  public boolean setAllowed(boolean value) {
    return new DeviceGrants(app).setAdbBootstrapAllowed(value);
  }

  public boolean needsObservation() {
    return unresolved;
  }

  /**
   * ADB 保活能否自动重开无线调试。
   *
   * <p>自举与无线调试互斥：自举会拒绝接管系统的 ADB TLS 入口，而保活每次探活失败都想把
   * 无线调试打开。两者同时生效就会出现「用户刚关掉、应用又开回来」的死循环，所以这里在
   * 自举执行期间、以及最近一次结果正因无线调试被拒时，明确拦住保活的重开动作。
   * 用户任何后续动作（只读刷新、重跑自举、成功或其它失败）都会换掉这个结果，拦停自然解除。
   */
  public boolean blocksWirelessReopen() {
    if (running.get() || chainActive) return true;
    Snapshot latest = current;
    return latest != null && "WIRELESS_DEBUG_IN_USE".equals(latest.detail);
  }

  public Snapshot snapshot() {
    return current;
  }

  /** busy 时拒绝排队；不会把旧页面操作留到授权变化后才执行。 */
  public boolean submit(AdbBootstrapPolicy.Action action) {
    if (action == null || !running.compareAndSet(false, true)) return false;
    RootBootstrapAuthority prepared = null;
    boolean submitted = false;
    try {
      if (unresolved && RootBootstrapAuthority.needsBootstrap(action)) return false;
      if (!RootBootstrapAuthority.allowed(app, action) || !RootShell.present()) {
        current = unknown(action, "REFUSED", "NATIVE_GRANT_OR_SU_UNAVAILABLE");
        return false;
      }
      prepared = new RootBootstrapAuthority(app, action, "");
      if (!records
          .edit()
          .putBoolean("bootstrap_request_pending", true)
          .putString("bootstrap_action", action.name())
          .commit()) {
        current = unknown(action, "REFUSED", "REQUEST_RECORD_FAILED");
        return false;
      }
      Snapshot before = current;
      current =
          new Snapshot(
              true,
              action,
              "RUNNING",
              "",
              before.keyState,
              before.listenerState,
              before.guardState,
              before.observedAt);
      final RootBootstrapAuthority authority = prepared;
      worker.execute(() -> perform(action, authority));
      submitted = true;
      return true;
    } catch (Exception unavailable) {
      current = unknown(action, "FAILED", "REQUEST_PREPARATION_FAILED");
      return false;
    } finally {
      if (!submitted) {
        try {
          if (prepared != null) prepared.close();
          // 尚未派发可撤去本次意图，但不能清除此前的未知动作。
          if (!records.edit().putBoolean("bootstrap_request_pending", unresolved).commit())
            unresolved = true;
        } catch (Exception unavailable) {
          unresolved = true;
        } finally {
          running.set(false);
        }
      }
    }
  }

  /**
   * 一键自举：一次确认后按固定顺序执行 AUTHORIZE → NARROW → OPEN。
   *
   * <p>三步复用与单动作完全相同的门禁、票据与结果记录路径；任一步不成功即停止，
   * 保留该步现场并要求先只读刷新，不自动重试。三步全部成功才自动打开 ADB 设备通道。
   */
  public boolean submitBootstrapChain() {
    if (running.get()) return false;
    if (unresolved) return false;
    if (!RootBootstrapAuthority.allowed(app, AdbBootstrapPolicy.Action.AUTHORIZE)
        || !RootShell.present()) {
      current =
          unknown(AdbBootstrapPolicy.Action.AUTHORIZE, "REFUSED", "NATIVE_GRANT_OR_SU_UNAVAILABLE");
      return false;
    }
    chain.clear();
    chain.add(AdbBootstrapPolicy.Action.NARROW);
    chain.add(AdbBootstrapPolicy.Action.OPEN);
    chainActive = true;
    if (submit(AdbBootstrapPolicy.Action.AUTHORIZE)) return true;
    chainActive = false;
    chain.clear();
    return false;
  }

  /** 链是否正在执行；UI 据此显示进度而不是逐次确认。 */
  public boolean chaining() {
    return chainActive;
  }

  private void perform(AdbBootstrapPolicy.Action action, RootBootstrapAuthority authority) {
    rootRequestSent = false;
    Snapshot result;
    try (RootBootstrapAuthority lease = authority) {
      // 公钥读取/准备与环境维护共用任务凭据；清理不依赖 Ubuntu 环境仍健康。
      String output =
          RootBootstrapAuthority.needsClientKey(action)
              ? AdbBridge.runEnvironmentTask(
                  app, "root-adb-bootstrap", () -> execute(action, clientPublicKey(action), lease))
              : execute(action, "", lease);
      RootSupportProbe probe = RootSupportProbe.parse(output);
      JSONObject reply = new JSONObject(probe.body);
      String outcome = reply.getString("status");
      if (!(outcome.equals("OK") && probe.exitCode == 0
          || outcome.equals("REFUSED") && probe.exitCode == 126
          || outcome.equals("UNKNOWN") && probe.exitCode == 125
          || outcome.equals("FAILED") && probe.exitCode == 125))
        throw new IOException("RESULT_PROTOCOL_INVALID");
      result =
          new Snapshot(
              false,
              action,
              outcome,
              reply.getString("code"),
              reply.getString("keyState"),
              reply.getString("listenerState"),
              reply.getString("guardState"),
              System.currentTimeMillis());
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      // 不能由异常推断“未执行”；保留未知并要求显式刷新/清理。
      String code = error.getMessage();
      if (code == null || !code.matches("[A-Z0-9_]{1,96}")) code = "REQUEST_OUTCOME_UNKNOWN";
      result = unknown(action, rootRequestSent ? "UNKNOWN" : "FAILED", code);
    }
    try {
      unresolved =
          AdbBootstrapJournal.unresolved(
              unresolved,
              result.outcome,
              result.detail,
              result.keyState,
              result.listenerState,
              result.guardState);
      boolean saved =
          records
              .edit()
              .putBoolean("bootstrap_request_pending", unresolved)
              .putString("bootstrap_last_outcome", result.outcome)
              .putString("bootstrap_last_code", result.detail)
              .commit();
      if (!saved) unresolved = true;
      current = saved ? result : unknown(action, "UNKNOWN", "RESULT_RECORD_FAILED");
    } catch (Throwable failure) {
      unresolved = true;
      current = unknown(action, "UNKNOWN", "RESULT_RECORD_FAILED");
    } finally {
      running.set(false);
      advanceChain(result);
    }
  }

  /**
   * 一键自举的下一步：仅当当前步成功、且仍有待执行动作时继续。
   *
   * <p>失败、未知或授权变化都终止整条链并清空队列，不重放、不换通道；
   * 只有三步全部成功才自动打开 ADB 设备通道，使自举结果真正可用。
   */
  private void advanceChain(Snapshot result) {
    if (!chainActive) return;
    boolean ok = result != null && "OK".equals(result.outcome);
    if (ok && !chain.isEmpty() && submit(chain.poll())) return;
    boolean completed = ok && chain.isEmpty();
    chainActive = false;
    chain.clear();
    if (completed) enableAdbChannel();
  }

  /** 自举成功后自动开启 ADB 设备通道；失败保持关闭，交由用户按需处理。 */
  private void enableAdbChannel() {
    try {
      app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
          .edit()
          .putBoolean("adb_enabled", true)
          .commit();
      DeviceBridgeService.apply(app);
    } catch (RuntimeException unavailable) {
      // 自举结果已经落盘；通道开启失败不改变本次结论，也不重放设备动作。
    }
  }

  private String execute(
      AdbBootstrapPolicy.Action action, String key, RootBootstrapAuthority authority)
      throws Exception {
    authority.prepareKey(key);
    {
      rootRequestSent = true;
      String output = RootShell.bootstrap(app, action, authority.ticket);
      if (action == AdbBootstrapPolicy.Action.OPEN && RootSupportProbe.parse(output).succeeded()) {
        if (!authority.valid()) throw new IOException("NATIVE_GRANT_REVOKED");
        String session = AdbBridge.verifyBootstrap(HarnessController.get(app).proot());
        if (!authority.valid()) throw new IOException("NATIVE_GRANT_REVOKED");
        RootSupportProbe identity = RootSupportProbe.parse(session);
        if (!identity.succeeded() || identity.executionLevel() == AdbdIdentity.Level.UNKNOWN)
          throw new IOException("ADB_SESSION_UNVERIFIED");
        JSONObject verified = new JSONObject(RootSupportProbe.parse(output).body);
        verified.put(
            "code",
            identity.executionLevel() == AdbdIdentity.Level.ROOT
                ? "ADB_SESSION_ROOT"
                : "ADB_SESSION_SHELL");
        output = verified.toString() + "\n[EXIT=0]";
      }
      return output;
    }
  }

  private String clientPublicKey(AdbBootstrapPolicy.Action action) throws Exception {
    if (action == AdbBootstrapPolicy.Action.AUTHORIZE) {
      String ready = AdbBridge.ensureReady(app, HarnessController.get(app).proot());
      if (!AdbResult.marker(ready, "SETUP_DONE"))
        throw new IOException("ADB_ENVIRONMENT_NOT_READY");
    }
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File data = new UserDataLayout(fs, app.getFilesDir()).current().getCanonicalFile();
    File key = new File(data, "adbkeys/adbkey.pub");
    if (!key.getAbsolutePath().equals(key.getCanonicalPath()) || !fs.stat(key).type.equals("FILE"))
      throw new IOException("PUBLIC_KEY_PATH_UNVERIFIED");
    return AdbAuthorizedKeys.publicKey(new String(fs.small(key, 2048), StandardCharsets.US_ASCII));
  }

  private static Snapshot unknown(AdbBootstrapPolicy.Action action, String outcome, String detail) {
    return new Snapshot(false, action, outcome, detail, "UNKNOWN", "UNKNOWN", "UNKNOWN", 0);
  }
}
