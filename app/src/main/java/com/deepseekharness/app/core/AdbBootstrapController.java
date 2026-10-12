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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 应用级单飞控制器。仅保存 Application 与结构化结果，页面销毁不会取消或重放设备动作。 */
public final class AdbBootstrapController {
  /** 会话核验前的固定等待（毫秒）：只延后时点，不增加核验次数（ADR 0007 方案 C）。 */
  private static final long SESSION_SETTLE_MS = 2500L;

  private static AdbBootstrapController instance;
  private final Context app;
  private final SharedPreferences records;
  private final AtomicBoolean running = new AtomicBoolean();
  private final Executor worker;

  /** 核验前等待的可控接缝（ADR 0007 方案 C）；生产固定 2.5 秒且只等一次。 */
  private final Settle settle;

  private volatile Snapshot current;
  private volatile boolean rootRequestSent;
  private volatile boolean unresolved;

  /** 本轮进程内的保守互斥拦截；持久化失败时仍生效（F02）。 */
  private volatile boolean wirelessReopenLatched;

  /** 一键自举链的状态机：当前步骤 / 剩余步骤 / 最初确认的总开关意图（F01/F04）。 */
  private final AdbBootstrapChainSession session = new AdbBootstrapChainSession();

  public static final class Snapshot {
    public final boolean busy;
    public final AdbBootstrapPolicy.Action action;
    public final String outcome, detail, keyState, listenerState, guardState;

    /** 失败时设备命令的原文（argv / 退出码 / 输出首行）；只用于显示与诊断记录。 */
    public final String evidence;

    public final long observedAt;

    Snapshot(
        boolean busy,
        AdbBootstrapPolicy.Action action,
        String outcome,
        String detail,
        String key,
        String listener,
        String guard,
        String evidence,
        long observedAt) {
      this.busy = busy;
      this.action = action;
      this.outcome = outcome;
      this.detail = detail;
      keyState = key;
      listenerState = listener;
      guardState = guard;
      this.evidence = evidence == null ? "" : evidence;
      this.observedAt = observedAt;
    }
  }

  /**
   * 会话核验前的固定等待（ADR 0007 方案 C）。
   *
   * <p>只为让刚写入的公钥有机会被 adbd / 系统侧看到；**不轮询、不重试**，等待结束后仍然只核验一次。
   * 被中断（页面 / 进程要求停止）时按打断处理，由上层保留现场。生产固定 2.5 秒；主机测试
   * 注入自己的实现以可控重现「等待期间撤销」，不改变生产时长、次数或端点。
   */
  interface Settle {
    void await() throws InterruptedException;
  }

  private AdbBootstrapController(Context application) {
    this(application, defaultWorker(), () -> Thread.sleep(SESSION_SETTLE_MS));
  }

  /**
   * 主机测试接缝：注入可控执行器与等待时点。
   *
   * <p>生产路径只走上面的单参构造；这里注入的仍是同一个生产控制器类，组合逻辑（派发判据、
   * 结果保存、续链、屏障）不复制一套只给测试用的实现。
   */
  AdbBootstrapController(Context application, Executor worker, Settle settle) {
    app = application.getApplicationContext();
    this.worker = worker;
    this.settle = settle;
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

  private static Executor defaultWorker() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread thread = new Thread(r, "adb-bootstrap");
          thread.setDaemon(true);
          return thread;
        });
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
   * 自举执行期间、最近一次结果正因无线调试被拒时，**以及上次结果尚未确认时**，明确拦住
   * 保活的重开动作。
   *
   * <p>实机踩过（2026-10-11）：自举链在最后一步以「结果未知」收场后，链里已经打开了 ADB
   * 通道，保活随即探活失败、把用户刚关掉的无线调试又打开 —— 下一次自举必然被
   * `WIRELESS_DEBUG_IN_USE` 拒绝。未确认的结果同样属于"不该动设备状态"的窗口。
   *
   * <p>解除条件：用户显式只读刷新（STATUS）把结果确认下来，或重跑自举。
   */
  public boolean blocksWirelessReopen() {
    return WirelessReopenPolicy.blocked(
        running.get() || session.active(),
        session.active(),
        unresolved,
        records.getString("bootstrap_last_code", ""),
        wirelessReopenLatched || persistedReopenBlock());
  }

  /**
   * 无副作用判据，供开机 / 覆盖升级广播使用。
   *
   * <p>进程可能是刚起来的：控制器实例只用持久记录恢复保守意图，不申请 Root、不执行自举、
   * 也不在广播里补做任何设备写动作。
   */
  public static boolean wirelessReopenBlocked(Context context) {
    return get(context).blocksWirelessReopen();
  }

  /**
   * 持久互斥意图：旧数据没有显式记录时，按「是否真的确认过自举动作」保守迁移。
   *
   * <p>只读刷新（STATUS）不写设备、不需要无线调试关闭，因此不构成自举选择；只有
   * AUTHORIZE / NARROW / OPEN 这类会改设备状态的确认才算。
   */
  private boolean persistedReopenBlock() {
    // 显式记录优先；旧数据按「确认过哪个动作」保守迁移（判据与测试都在 util 里）。
    return WirelessReopenPolicy.persistedBlock(
        records.contains("wireless_reopen_block"),
        records.getBoolean("wireless_reopen_block", true),
        records.getString("bootstrap_action", ""));
  }

  /** 自举确认时先持久化「本轮选择自举」的保守意图；持久化失败也保持内存拦截。 */
  private void markBootstrapChosen() {
    wirelessReopenLatched = true;
    try {
      records
          .edit()
          .putBoolean("wireless_reopen_block", true)
          .putBoolean("wireless_reopen_clean", false)
          .commit();
    } catch (RuntimeException unavailable) {
      // 内存拦截已生效；下次确认会再尝试持久化。
    }
  }

  /**
   * 用户明确选择了别的使用模式（打开 ADB 全局授权、完成无线配对）。
   *
   * <p>只有在已经拿到**可信清理结果**时才解除互斥；否则保持禁止并提示显式观察。
   */
  public boolean onUserModeChosen() {
    boolean clean = records.getBoolean("wireless_reopen_clean", false);
    if (!WirelessReopenPolicy.releasable(unresolved, clean, true)) return false;
    try {
      if (records.edit().putBoolean("wireless_reopen_block", false).commit()) {
        wirelessReopenLatched = false;
        return true;
      }
    } catch (RuntimeException unavailable) {
      // 保持拦截。
    }
    return false;
  }

  public Snapshot snapshot() {
    return current;
  }

  /** busy 时拒绝排队；不会把旧页面操作留到授权变化后才执行。 */
  public boolean submit(AdbBootstrapPolicy.Action action) {
    return submit(action, false);
  }

  /**
   * @param fromChain 该动作属于在途的整链；链进行中只接受链自己的下一步，外部动作不能
   *     趁步骤之间的空档插入并改变归属（F01/F04）。
   */
  private boolean submit(AdbBootstrapPolicy.Action action, boolean fromChain) {
    // 链进行中只接受链自己的当前步骤；外部动作不能趁步骤之间的空档插入（F01/F04）。
    if (!session.accepts(action, fromChain)) return false;
    if (!running.compareAndSet(false, true)) return false;
    RootBootstrapAuthority prepared = null;
    boolean submitted = false;
    try {
      if (unresolved && RootBootstrapAuthority.needsBootstrap(action)) return false;
      if (!RootBootstrapAuthority.allowed(app, action) || !RootShell.present()) {
        current = unknown(action, "REFUSED", "NATIVE_GRANT_OR_SU_UNAVAILABLE");
        return false;
      }
      prepared = new RootBootstrapAuthority(app, action, "");
      // 单动作各自捕获总开关用户意图；链内步进沿用最初确认，不能借新票据复活旧链。
      if (!fromChain) session.captureStandalone(AdbSwitch.revision(app));
      // 选择自举 = 本轮禁止自动重开无线调试；先落盘，进程死亡/升级后判据仍保守。
      if (RootBootstrapAuthority.needsBootstrap(action)) markBootstrapChosen();
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
              "",
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
    if (running.get() || session.active()) return false;
    if (unresolved) return false;
    if (!RootBootstrapAuthority.allowed(app, AdbBootstrapPolicy.Action.AUTHORIZE)
        || !RootShell.present()) {
      current =
          unknown(AdbBootstrapPolicy.Action.AUTHORIZE, "REFUSED", "NATIVE_GRANT_OR_SU_UNAVAILABLE");
      return false;
    }
    // 整链只捕获一次总开关用户意图：步骤之间用户改过开关，后面的步骤也不能把它写回来。
    session.start(AdbSwitch.revision(app));
    if (submit(AdbBootstrapPolicy.Action.AUTHORIZE, true)) return true;
    session.end();
    return false;
  }

  /** 链是否正在执行；UI 据此显示进度而不是逐次确认。 */
  public boolean chaining() {
    return session.active();
  }

  private void perform(AdbBootstrapPolicy.Action action, RootBootstrapAuthority authority) {
    rootRequestSent = false;
    Snapshot result;
    // 可信响应只在解析成功后存在；异常路径保持 null，屏障按旧的结果码判据处理。
    AdbBootstrapReply reply = null;
    try (RootBootstrapAuthority lease = authority) {
      // 公钥读取/准备与环境维护共用任务凭据；清理不依赖 Ubuntu 环境仍健康。
      String output =
          RootBootstrapAuthority.needsClientKey(action)
              ? AdbBridge.runEnvironmentTask(
                  app, "root-adb-bootstrap", () -> execute(action, clientPublicKey(action), lease))
              : execute(action, "", lease);
      // 生产响应编码/解码与完成判据都在 AdbBootstrapReply 接缝里；这里不另写一套判断。
      reply = AdbBootstrapReply.parse(output);
      if (!reply.trusted) throw new AdbBootstrapEngine.BootstrapFailure(reply.code, reply.evidence);
      result =
          new Snapshot(
              false,
              action,
              reply.status,
              reply.code,
              reply.keyState,
              reply.listenerState,
              reply.guardState,
              reply.evidence,
              System.currentTimeMillis());
      // 诊断页也留一份：设备上出问题时不必再让它复现一次。
      if (!result.evidence.isEmpty())
        com.deepseekharness.app.runtime.RuntimeHostPorts.fromOwner(app)
            .record("BOOTSTRAP_" + result.detail, AdbFirewallTool.head(result.evidence));
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      // 不能由异常推断“未执行”；保留未知并要求显式刷新/清理。
      String code = error.getMessage();
      if (code == null || !code.matches("[A-Z0-9_]{1,96}")) code = "REQUEST_OUTCOME_UNKNOWN";
      // 没有可识别错误码时，**必须**把异常原文留下来：否则界面只剩一个 REQUEST_OUTCOME_UNKNOWN，
      // 既看不到原因，也没有「查看失败原文」可点（实机踩过：环境任务进行中时点了自举）。
      String evidence =
          error instanceof AdbBootstrapEngine.BootstrapFailure
              ? ((AdbBootstrapEngine.BootstrapFailure) error).evidence
              : AdbFirewallTool.head(
                  com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error)));
      result =
          new Snapshot(
              false,
              action,
              rootRequestSent ? "UNKNOWN" : "FAILED",
              code,
              "UNKNOWN",
              "UNKNOWN",
              "UNKNOWN",
              evidence,
              0);
    }
    boolean persisted = false;
    try {
      // 只有可信响应确认两个未决阶段都已结束、观测完整，才允许解除屏障。
      unresolved = AdbBootstrapJournal.unresolved(unresolved, reply, result.outcome, result.detail);
      boolean saved =
          records
              .edit()
              .putBoolean("bootstrap_request_pending", unresolved)
              .putString("bootstrap_last_outcome", result.outcome)
              .putString("bootstrap_last_code", result.detail)
              .commit();
      if (!saved) unresolved = true;
      persisted = saved;
      current = saved ? result : unknown(action, "UNKNOWN", "RESULT_RECORD_FAILED");
      result = current;
      // 可信清理结果（两阶段都已结束、观测完整）单独记录：它是解除互斥的必要条件之一，
      // 但只有用户明确选择别的使用模式时才真正解除。
      if (saved
          && !unresolved
          && reply != null
          && reply.pendingCleared()
          && reply.observationComplete()) {
        try {
          records.edit().putBoolean("wireless_reopen_clean", true).commit();
        } catch (RuntimeException unavailable) {
          // 清理证据没记上只是保守；主结果不受影响。
        }
      }
    } catch (Throwable failure) {
      unresolved = true;
      current = unknown(action, "UNKNOWN", "RESULT_RECORD_FAILED");
      result = current;
    } finally {
      running.set(false);
      // 续链只看**已持久化**的有效结果：保存失败时 persisted=false，链不会继续。
      advanceChain(persisted, result);
    }
  }

  /**
   * 一键自举的下一步：仅当当前步成功、结果已持久化、未决已清、用户意图未变，且下一步真的
   * 被受理时才继续。
   *
   * <p>失败、未知、未决未清、意图改变或提交被拒都终止整条链并清空队列，不重放、不换通道。
   * 下一步在决定之前**不取出**：旧实现先取走再用「队列为空」判成功，提交失败会被报告成
   * 整链成功（F04）。自动打开 ADB 设备通道已在 OPEN 步骤内完成，这里不再重复写开关。
   */
  private void advanceChain(boolean persisted, Snapshot result) {
    if (!session.active()) return;
    boolean ok = persisted && result != null && "OK".equals(result.outcome) && !unresolved;
    boolean nextExists = session.hasNext();
    // 每次派发下一步**之前**回查最初确认的总开关意图：用户在步骤之间关闭或重新选择后，
    // 旧链不再获得任何新的设备写入（F01）。
    boolean intentUnchanged =
        AdbBootstrapChainPlan.autoEnableAllowed(session.intentRevision(), AdbSwitch.revision(app));
    AdbBootstrapPolicy.Action next = nextExists ? session.remaining().get(0) : null;
    boolean accepted =
        ok && nextExists && intentUnchanged && session.beginNext() && submit(next, true);
    AdbBootstrapChainPlan.Outcome outcome =
        AdbBootstrapChainPlan.after(session.active(), ok, intentUnchanged, nextExists, accepted);
    if (outcome == AdbBootstrapChainPlan.Outcome.CONTINUE) return;
    if (outcome == AdbBootstrapChainPlan.Outcome.ABORT && ok && nextExists && !intentUnchanged) {
      // 链停在中间状态：保留上一步的观测，但把结果标成未知并说明原因，
      // 而不是让卡片继续显示上一个「已完成」。
      unresolved = true;
      current =
          new Snapshot(
              false,
              result.action,
              "UNKNOWN",
              "ADB_SWITCH_INTENT_CHANGED",
              result.keyState,
              result.listenerState,
              result.guardState,
              result.evidence,
              result.observedAt);
      try {
        records
            .edit()
            .putBoolean("bootstrap_request_pending", true)
            .putString("bootstrap_last_outcome", "UNKNOWN")
            .putString("bootstrap_last_code", "ADB_SWITCH_INTENT_CHANGED")
            .commit();
      } catch (RuntimeException unavailable) {
        // 内存里的屏障已经立起来。
      }
    }
    session.end();
  }

  /**
   * 最初确认捕获的 ADB 总开关用户意图是否仍然有效（F01）。
   *
   * <p>{@code session.intentRevision()} 对整链是确认时捕获的那一次，对 standalone OPEN 是提交时
   * 捕获的那一次；{@link AdbSwitch#autoEnable} 自身不递增代次，所以这里只会被用户的新选择
   * （关闭、关闭再开启）改变。核验前后都用同一个判据，避免两处逻辑漂移。
   */
  private boolean userIntentUnchanged() {
    return AdbBootstrapChainPlan.autoEnableAllowed(
        this.session.intentRevision(), AdbSwitch.revision(app));
  }

  /**
   * 自举成功后自动开启 ADB 设备通道（F01）。
   *
   * <p>只有最初确认捕获的总开关用户意图仍然有效时才能提交：用户在途中的关闭或重新选择
   * 都会改变意图代次，旧完成回调不得把它反转回来。被拒时返回明确的失败码，由调用方报告，
   * 不静默吞掉 commit 结果。
   *
   * @return 空串表示通道已开启或本来就是开启；否则是明确失败码。用户改意图与
   *     {@code commit()} 没落盘必须分开报告，不能把写入失败说成"用户关闭了开关"。
   */
  private String openAdbChannel() {
    try {
      if (!AdbSwitch.autoEnable(app, this.session.intentRevision()))
        return AdbBootstrapChainPlan.autoEnableAllowed(
                this.session.intentRevision(), AdbSwitch.revision(app))
            ? "ADB_SWITCH_ENABLE_NOT_PERSISTED"
            : "ADB_SWITCH_INTENT_CHANGED";
      // 服务启动前再读真实开关：写值与启动服务之间的用户关闭优先。
      DeviceBridgeService.apply(app);
      return "";
    } catch (RuntimeException unavailable) {
      // 自举结果已经落盘；通道开启失败不改变本次结论，也不重放设备动作。
      return "ADB_CHANNEL_START_FAILED";
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
        // 监听已确认打开。桥只对「已开启的 ADB 通道」下发 ADB 计划，而「三步全成功才开启」
        // 的顺序会让这里的会话核验永远拿不到计划（实机返回 [DEVICE_CHANNEL_UNAVAILABLE]）。
        // 因此先开启通道再核验；核验失败时保留开关与监听，由卡片提示刷新或按需关闭。
        // 用户在途中关过总开关时这里会失败：不能反转用户意图，也**不能**把整链报成成功。
        String channelFailure = openAdbChannel();
        if (!channelFailure.isEmpty())
          throw new AdbBootstrapEngine.BootstrapFailure(
              channelFailure,
              "ADB_SWITCH_INTENT_CHANGED".equals(channelFailure)
                  ? "监听已确认打开；但用户已关闭 ADB 总开关，本次不再自动启用通道。"
                  : "监听已确认打开；但本次未能开启 ADB 设备通道（" + channelFailure + "），保留现状待只读刷新。");
        // C（ADR 0007）：刚写完 adb_keys，adbd/系统侧可能还没读到新公钥，而核验按设计**只做一次**。
        // 所以把核验时点后移一个固定短延时 —— 次数仍是 1 次，不轮询、不重试、不换端点、不换通道。
        settle.await();
        if (!authority.valid()) throw new IOException("NATIVE_GRANT_REVOKED");
        // 等待期间用户仍可能关闭或重新选择总开关：继续核验之前必须回查最初确认的意图（F01），
        // 否则旧完成回调会把已经撤销的确认报告成"整链完成"。
        if (!userIntentUnchanged())
          throw new AdbBootstrapEngine.BootstrapFailure(
              "ADB_SWITCH_INTENT_CHANGED", "核验前的等待期间用户已改动 ADB 总开关，本次不再继续核验。");
        String session = AdbBridge.verifyBootstrap(HarnessController.get(app).proot());
        if (!authority.valid()) throw new IOException("NATIVE_GRANT_REVOKED");
        // 只读 `id` 本身也要跑一段窗口，核验返回后必须再回查一次用户意图（F01）：核验返回的
        // 真实观测只有在本轮确认的总开关意图仍然有效时，才能被当作整链完成的依据。
        //
        // 线性化点选在「核验结果被接受为本次成功证据」的这一刻，也就是成功快照产生/落盘**之前**，
        // 而不是在 advanceChain 里回写已保存的结果。两个理由：
        //   1) 这里同时覆盖整链的 OPEN 与 standalone OPEN；advanceChain 只处理整链，而且末步
        //      nextExists=false 时按设计不因之后的总开关改动改写已发布的结果 —— 用户在看到成功
        //      之后关闭属于他自己的新状态，不能追改成旧链失败。
        //   2) 每次确认仍只有一次核验：这里只丢弃「已撤销确认下的核验结论」，不重放、不换端点、
        //      不追加等待；核验原文作为证据保留下来。
        if (!userIntentUnchanged())
          throw new AdbBootstrapEngine.BootstrapFailure(
              "ADB_SWITCH_INTENT_CHANGED",
              "只读会话核验期间用户已改动 ADB 总开关，本次核验结果不再作为成功依据。核验原文：" + AdbFirewallTool.head(session));
        RootSupportProbe identity = RootSupportProbe.parse(session);
        if (!identity.succeeded() || identity.executionLevel() == AdbdIdentity.Level.UNKNOWN)
          // 会话核验失败也要留下原文（脚本版本不符、模块缺失、超时、握手被拒都在这里现形）。
          throw new AdbBootstrapEngine.BootstrapFailure(
              "ADB_SESSION_UNVERIFIED", AdbFirewallTool.head(session));
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
        // 环境准备失败的具体原因（SETTINGS_PENDING / WHEELS_* / SETUP_FAILED…）必须可见。
        throw new AdbBootstrapEngine.BootstrapFailure(
            "ADB_ENVIRONMENT_NOT_READY", AdbFirewallTool.head(ready));
    }
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    File data = new UserDataLayout(fs, app.getFilesDir()).current().getCanonicalFile();
    File key = new File(data, "adbkeys/adbkey.pub");
    if (!key.getAbsolutePath().equals(key.getCanonicalPath()) || !fs.stat(key).type.equals("FILE"))
      throw new IOException("PUBLIC_KEY_PATH_UNVERIFIED");
    return AdbAuthorizedKeys.publicKey(new String(fs.small(key, 2048), StandardCharsets.US_ASCII));
  }

  private static Snapshot unknown(AdbBootstrapPolicy.Action action, String outcome, String detail) {
    return new Snapshot(false, action, outcome, detail, "UNKNOWN", "UNKNOWN", "UNKNOWN", "", 0);
  }
}
