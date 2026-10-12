package com.deepseekharness.app;

import android.content.Context;

import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.util.LanListenerStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宿主探针：在隔离 JVM 里直接执行真实的生产 {@link LanProxyService} 与其状态发布
 * {@link LanListenerStatus.Registry}，不做设备操作、不依赖 Android 运行时、不占真实
 * 3081（端口来自包内注入接缝，默认值仍是生产常量）。
 *
 * <p>它同时演示本轮修复的红→绿：旧实现里绑定失败后轮次已退出，{@code stop(ownerGeneration)}
 * 会因为把轮次 epoch 当作 dsh generation 比较而漏清，界面继续显示「绑定失败」；现在
 * 快照显式绑定 generation，按代次停止必然清空。</p>
 *
 * <p>任何断言失败即非零退出，供 {@code tools/test-lan-listener-status.py} 判定。</p>
 */
public final class LanProxyServiceHostProbe {

  private static int checks;

  public static void main(String[] args) throws Exception {
    pureStatusSemantics();
    fixedPortContract();
    nullContextFailsClosed();
    realServiceReportsPortConflictInsteadOfWaiting();
    stopOfOtherGenerationDoesNotClear();
    newAuthGenerationClearsStaleFailure();
    realServicePublishesBoundThenStopIsIdle();
    reset();
    System.out.println("LAN_LISTENER_STATUS_PROBE_CHECKS " + checks);
  }

  /** 纯逻辑：状态判据、脱敏分类、epoch/generation 不互比。 */
  private static void pureStatusSemantics() {
    LanListenerStatus.Snapshot idle = LanListenerStatus.idle();
    check(!idle.bound() && !idle.waitingAuth() && !idle.failed(), "IDLE 三种判据都为假", idle);
    check(idle.epoch() == 0L && idle.generation() == 0L, "IDLE 不携带身份", idle);

    LanListenerStatus.Snapshot waiting = LanListenerStatus.waiting(7L, 2L, 3081);
    check(waiting.waitingAuth(), "认证后绑定前必须是 WAITING", waiting);
    check(!waiting.bound(), "WAITING 不能被当成已绑定", waiting);
    check(!waiting.failed(), "WAITING 不是失败", waiting);
    check(
        waiting.epoch() == 7L && waiting.generation() == 2L && waiting.port() == 3081,
        "WAITING 显式绑定 epoch/generation/port",
        waiting);

    check(!LanListenerStatus.waiting(7L, 2L, 0).waitingAuth(), "端口 0 不算 WAITING", null);
    check(!LanListenerStatus.bound(7L, 2L, 0).bound(), "端口 0 不算 BOUND", null);
    check(
        !LanListenerStatus.failed(7L, 2L, 0, new SocketException("x")).failed(),
        "端口 0 不算 FAILED",
        null);

    LanListenerStatus.Snapshot permission =
        LanListenerStatus.failed(
            1L,
            1L,
            3081,
            new IOException("EACCES (Permission denied) /data/local/x token=SECRET-XYZ"));
    check(
        LanListenerStatus.CAUSE_PERMISSION_DENIED.equals(permission.causeCode()),
        "EACCES 必须归类 PERMISSION_DENIED",
        permission);
    check("EACCES".equals(permission.causeDetail()), "只保留 EACCES 错误码", permission);
    check(permission.toString().indexOf("SECRET-XYZ") < 0, "不得回显正文", permission);

    LanListenerStatus.Snapshot unknown =
        LanListenerStatus.failed(2L, 1L, 3081, new IOException("/root/.dsh/secret-path failed"));
    check(
        LanListenerStatus.CAUSE_UNKNOWN.equals(unknown.causeCode()),
        "未知失败归类 UNKNOWN，不得冒充端口占用",
        unknown);
    check("java.io.IOException".equals(unknown.causeDetail()), "未知失败只保留异常类名", unknown);
    check(unknown.toString().indexOf("secret-path") < 0, "不得回显路径", unknown);

    LanListenerStatus.Registry registry = new LanListenerStatus.Registry();
    registry.begin(30L, 3L, 3081);
    check(
        !registry.fail(29L, 3081, new SocketException("EADDRINUSE")),
        "旧轮次迟到失败必须被拒绝",
        null);
    check(registry.snapshot().waitingAuth(), "被拒绝后当前状态不变", registry.snapshot());
    check(
        registry.fail(30L, 3081, new SocketException("EADDRINUSE")),
        "当前轮次可以发布失败",
        null);
    check(registry.snapshot().generation() == 3L, "失败快照保留 generation", registry.snapshot());
    registry.stopGeneration(99L);
    check(registry.snapshot().failed(), "别的代次停止不得误伤", registry.snapshot());
    registry.stopGeneration(30L);
    check(
        registry.snapshot().failed(),
        "epoch 数值不得被当作 generation 匹配",
        registry.snapshot());
    registry.stopGeneration(3L);
    check(
        registry.snapshot().state() == LanListenerStatus.State.IDLE,
        "拥有该代次的停止必须清空",
        registry.snapshot());
  }

  /** 固定端口契约：生产仍是 3081，没有公网/扫描回退。 */
  private static void fixedPortContract() {
    reset();
    check(LanProxyService.LAN_PORT == 3081, "固定监听端口仍是 3081", null);
    check(LanProxyService.DEFAULT_BACKEND_PORT == 3080, "默认后端端口仍是 3080", null);
    check(LanProxyService.listenerPort() == 3081, "重置后端口来源默认为 3081", null);
  }

  /** 无上下文的 start 必须 fail-closed，绝不开始监听。 */
  private static void nullContextFailsClosed() {
    reset();
    LanProxyService.start(null, null, LanProxyService.DEFAULT_BACKEND_PORT, 6L);
    check(
        LanProxyService.listenerStatus().state() == LanListenerStatus.State.IDLE,
        "无上下文 start 不得发布任何监听状态",
        LanProxyService.listenerStatus());
    check(!LanProxyService.isBound(), "无上下文 start 不得绑定", null);
  }

  /** 真实 start/acceptLoop：端口冲突发布 FAILED，且按代次停止能清掉。 */
  private static void realServiceReportsPortConflictInsteadOfWaiting() throws Exception {
    ServerSocket occupied = occupyLoopback();
    int port = occupied.getLocalPort();
    try {
      reset();
      LanProxyService.useHostBindPort(port);
      check(
          LanProxyService.setDshAuthCookie("dsh-auth-probe=roundone", 6L),
          "设置本轮 BrowserAuth cookie",
          null);
      LanProxyService.start(null, new Context(), LanProxyService.DEFAULT_BACKEND_PORT, 6L);

      LanListenerStatus.Snapshot failed = awaitTerminal(4000);
      check(failed.failed(), "被占用端口必须发布 FAILED，而不是一直等待认证", failed);
      check(!failed.waitingAuth(), "FAILED 不得再显示「等待本轮认证」", failed);
      check(!failed.bound(), "FAILED 不得可复制链接", failed);
      check(
          LanListenerStatus.CAUSE_PORT_IN_USE.equals(failed.causeCode()),
          "真实绑定冲突必须归类 PORT_IN_USE",
          failed);
      check(failed.port() == port, "失败快照带真实尝试端口", failed);
      check(failed.generation() == 6L, "快照必须绑定本轮 dsh generation", failed);
      check(!LanProxyService.isBound(), "绑定失败时 isBound 必须为假", null);
      String text = failed.toString();
      check(text.indexOf("Address already in use") < 0, "失败状态不得回显异常正文", text);
      check(text.indexOf("token=") < 0, "失败状态不得包含 token", text);

      // 真实 acceptLoop 已退出（activeRun 为空）：按拥有该轮次的 dsh 代次停止仍须清空。
      LanProxyService.stop(6L);
      check(
          LanProxyService.listenerStatus().state() == LanListenerStatus.State.IDLE,
          "stop(ownerGeneration) 后必须 IDLE（旧缺陷：绑定失败后漏清，界面继续显示失败）",
          LanProxyService.listenerStatus());
    } finally {
      LanProxyService.stop();
      LanProxyService.useHostBindPort(LanProxyService.LAN_PORT);
      occupied.close();
    }
  }

  /** 别的代次停止不得误伤当前失败状态。 */
  private static void stopOfOtherGenerationDoesNotClear() throws Exception {
    ServerSocket occupied = occupyLoopback();
    int port = occupied.getLocalPort();
    try {
      reset();
      LanProxyService.useHostBindPort(port);
      check(
          LanProxyService.setDshAuthCookie("dsh-auth-probe=roundtwo", 8L),
          "设置 cookie",
          null);
      LanProxyService.start(null, new Context(), LanProxyService.DEFAULT_BACKEND_PORT, 8L);
      check(awaitTerminal(4000).failed(), "先要有真实 FAILED", null);

      LanProxyService.stop(5L);
      check(
          LanProxyService.listenerStatus().failed(),
          "别的代次停止不得误伤当前失败状态",
          LanProxyService.listenerStatus());

      LanProxyService.stop(8L);
      check(
          LanProxyService.listenerStatus().state() == LanListenerStatus.State.IDLE,
          "拥有该轮次的代次停止必须清空",
          LanProxyService.listenerStatus());
    } finally {
      LanProxyService.stop();
      LanProxyService.useHostBindPort(LanProxyService.LAN_PORT);
      occupied.close();
    }
  }

  /** 换 dsh 代次发布新 cookie：旧代次遗留的失败状态必须作废。 */
  private static void newAuthGenerationClearsStaleFailure() throws Exception {
    ServerSocket occupied = occupyLoopback();
    int port = occupied.getLocalPort();
    try {
      reset();
      LanProxyService.useHostBindPort(port);
      check(LanProxyService.setDshAuthCookie("dsh-auth-probe=roundfour", 12L), "设置 cookie", null);
      LanProxyService.start(null, new Context(), LanProxyService.DEFAULT_BACKEND_PORT, 12L);
      check(awaitTerminal(4000).failed(), "先要有真实 FAILED", null);

      check(
          LanProxyService.setDshAuthCookie("dsh-auth-probe=roundfive", 13L),
          "发布新一代 cookie",
          null);
      check(
          LanProxyService.listenerStatus().state() == LanListenerStatus.State.IDLE,
          "换代必须清掉旧代次遗留的绑定失败状态",
          LanProxyService.listenerStatus());
    } finally {
      LanProxyService.stop();
      LanProxyService.useHostBindPort(LanProxyService.LAN_PORT);
      occupied.close();
    }
  }

  /** 真实 start/acceptLoop 成功绑定 → BOUND，停止后 IDLE。 */  private static void realServicePublishesBoundThenStopIsIdle() throws Exception {
    int port = freeLoopbackPort();
    try {
      reset();
      LanProxyService.useHostBindPort(port);
      check(
          LanProxyService.setDshAuthCookie("dsh-auth-probe=roundthree", 9L),
          "设置 cookie",
          null);
      LanProxyService.start(null, new Context(), LanProxyService.DEFAULT_BACKEND_PORT, 9L);

      LanListenerStatus.Snapshot bound = awaitTerminal(4000);
      check(bound.bound(), "自由端口必须真实绑定成功并发布 BOUND", bound);
      check(bound.port() == port, "BOUND 必须使用真实尝试端口", bound);
      check(bound.generation() == 9L, "BOUND 快照绑定 generation", bound);
      check(!bound.failed(), "BOUND 必须清掉旧错误", bound);
      check(LanProxyService.isBound(), "成功绑定时 isBound 必须为真", null);

      LanProxyService.stop(9L);
      check(
          LanProxyService.listenerStatus().state() == LanListenerStatus.State.IDLE,
          "停止后必须 IDLE，不能继续可复制",
          LanProxyService.listenerStatus());
    } finally {
      LanProxyService.stop();
      LanProxyService.useHostBindPort(LanProxyService.LAN_PORT);
    }
  }

  private static void reset() {
    LocalNetworkAccess.grantedForHost = true;
    LanProxyService.stop();
    LanProxyService.useHostBindPort(LanProxyService.LAN_PORT);
  }

  private static ServerSocket occupyLoopback() throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress("127.0.0.1", 0));
    return socket;
  }

  private static int freeLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress("127.0.0.1", 0));
      return socket.getLocalPort();
    }
  }

  /** 等真实 acceptLoop 发布 BOUND/FAILED；超时返回当前快照，由断言判定。 */
  private static LanListenerStatus.Snapshot awaitTerminal(long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    LanListenerStatus.Snapshot snapshot = LanProxyService.listenerStatus();
    while (System.currentTimeMillis() < deadline) {
      snapshot = LanProxyService.listenerStatus();
      if (snapshot.bound() || snapshot.failed()) return snapshot;
      Thread.sleep(20L);
    }
    return snapshot;
  }

  private static void check(boolean condition, String message, Object detail) {
    checks++;
    if (condition) return;
    List<String> parts = new ArrayList<>();
    parts.add("LAN_LISTENER_STATUS_PROBE_FAILED: " + message);
    if (detail != null) parts.add("detail=" + detail);
    System.err.println(String.join(" | ", parts));
    System.exit(1);
  }
}
