package com.deepseekharness.app.util;

import java.net.BindException;
import java.net.ServerSocket;
import java.util.Locale;

/**
 * 局域网监听器的可观察状态（纯逻辑，不 import 任何 Android API）。
 *
 * <p><b>为什么需要它</b>：过去「启动页」只看 {@code isBound()} 的布尔值，把
 * 「本轮认证还没就绪 / 绑定还在进行」和「3081 被别的实例占用，实际绑定失败」混成
 * 同一句「正在等待本轮认证」。用户于是永远等一个不会发生的成功。</p>
 *
 * <p><b>状态语义</b>：</p>
 * <ul>
 *   <li>{@link #IDLE}：没有进行中的监听尝试（功能关闭 / 已停止 / dsh 已换代）。</li>
 *   <li>{@link #WAITING}：已通过认证与授权，绑定尚未返回结果。</li>
 *   <li>{@link #BOUND}：绑定成功，正在监听 {@code port}。</li>
 *   <li>{@link #FAILED}：绑定确实失败，{@link #causeCode()} 给出脱敏分类。</li>
 * </ul>
 *
 * <p><b>脱敏边界</b>：状态里绝不出现 token、连接链接或原始异常正文。失败原因只保留
 * 固定分类码，以及底层的 {@code IOException} 类名（Java 标识符，不含数据）。原始
 * {@code getMessage()} 可能带路径或运行时细节，一律丢弃。</p>
 *
 * <p><b>标识绑定</b>：每个快照同时携带 {@code epoch}（监听轮次）与 {@code generation}
 * （dsh 代次）。二者不能互相比较：停止/换代按 {@code generation} 匹配，迟到结果按
 * {@code epoch} 匹配。{@link Registry} 是生产唯一的状态发布点，也是主机测试直接执行的
 * 纯逻辑接缝。</p>
 */
public final class LanListenerStatus {

  /** 状态分类。 */
  public enum State {
    IDLE,
    WAITING,
    BOUND,
    FAILED
  }

  /** 绑定失败分类；未知失败不回显原始正文。 */
  public static final String CAUSE_PORT_IN_USE = "PORT_IN_USE";

  public static final String CAUSE_PERMISSION_DENIED = "PERMISSION_DENIED";
  public static final String CAUSE_UNAVAILABLE = "UNAVAILABLE";
  public static final String CAUSE_UNKNOWN = "UNKNOWN";

  /**
   * 一份不可变快照。{@code epoch} 标识监听轮次，{@code generation} 标识它所属的 dsh
   * 代次；旧轮次的迟到结果不能覆盖新轮次，按代次停止也不能误伤别的代次。
   */
  public static final class Snapshot {
    private final State state;
    private final long epoch;
    private final long generation;
    private final int port;
    private final String causeCode;
    private final String causeDetail;

    Snapshot(
        State state, long epoch, long generation, int port, String causeCode, String causeDetail) {
      this.state = state;
      this.epoch = epoch;
      this.generation = generation;
      this.port = port;
      this.causeCode = causeCode;
      this.causeDetail = causeDetail;
    }

    public State state() {
      return state;
    }

    public long epoch() {
      return epoch;
    }

    /** 本快照所属的 dsh 代次；IDLE 为 0。停止/换代只按它匹配。 */
    public long generation() {
      return generation;
    }

    /** 监听端口；等待/失败时是尝试的端口，生产恒为 3081。 */
    public int port() {
      return port;
    }

    /** 只有失败状态非空；固定分类码之一。 */
    public String causeCode() {
      return causeCode;
    }

    /**
     * 失败原因的安全细节：受信的错误码或底层异常类名，绝不包含异常正文。
     * 非失败状态或无法安全分类时为 {@code null}。
     */
    public String causeDetail() {
      return causeDetail;
    }

    /** 绑定确实成功；只有这一状态允许展示/复制连接链接。 */
    public boolean bound() {
      return state == State.BOUND && port > 0;
    }

    /** 本轮已通过认证且正在尝试绑定，结果尚未返回。 */
    public boolean waitingAuth() {
      return state == State.WAITING && port > 0;
    }

    /** 绑定确实失败。 */
    public boolean failed() {
      return state == State.FAILED && port > 0 && causeCode != null;
    }

    @Override
    public String toString() {
      return "LanListenerStatus["
          + state
          + " epoch="
          + epoch
          + " generation="
          + generation
          + " port="
          + port
          + " cause="
          + (causeCode == null ? "-" : causeCode)
          + (causeDetail == null ? "" : "/" + causeDetail)
          + "]";
    }
  }

  private LanListenerStatus() {}

  public static Snapshot idle() {
    return new Snapshot(State.IDLE, 0, 0, 0, null, null);
  }

  /** 已通过认证与授权，绑定结果未返回。 */
  public static Snapshot waiting(long epoch, long generation, int port) {
    return new Snapshot(State.WAITING, epoch, generation, port, null, null);
  }

  /** 绑定成功。 */
  public static Snapshot bound(long epoch, long generation, int port) {
    return new Snapshot(State.BOUND, epoch, generation, port, null, null);
  }

  /**
   * 分类一次真实的绑定失败，只保留脱敏后的原因。
   *
   * <p>{@link BindException} 只有在消息明确写出 {@code EACCES} 时才升级为权限问题，
   * 否则按「地址/端口被占用」报告；其余 {@code EACCES} 报权限，{@code EADDRNOTAVAIL} /
   * {@code EADDRINUSE} 报不可用，其它异常归入 {@link #CAUSE_UNKNOWN}。</p>
   */
  public static Snapshot failed(long epoch, long generation, int port, Throwable error) {
    return new Snapshot(State.FAILED, epoch, generation, port, classify(error), safeDetail(error));
  }

  /** 按「先匹配权限，再匹配不可用」的顺序分类，避免子类名误导。 */
  public static String classify(Throwable error) {
    String message = error == null ? null : error.getMessage();
    String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
    if (error == null) return CAUSE_UNKNOWN;
    if (upper.contains("EACCES") || upper.contains("PERMISSION DENIED")) {
      return CAUSE_PERMISSION_DENIED;
    }
    if (upper.contains("EADDRINUSE")) return CAUSE_PORT_IN_USE;
    if (upper.contains("EADDRNOTAVAIL")) return CAUSE_UNAVAILABLE;
    if (error instanceof BindException) return CAUSE_PORT_IN_USE;
    if (error instanceof SecurityException) return CAUSE_PERMISSION_DENIED;
    return CAUSE_UNKNOWN;
  }

  /**
   * 底层异常类型中的安全细节：优先取 {@code E…} 错误码，其次取异常类名。
   * 异常正文、地址和路径一律不回显。
   */
  private static String safeDetail(Throwable error) {
    if (error == null) return null;
    String code = errorCode(error.getMessage());
    if (code != null) return code;
    String name = error.getClass().getName();
    return name.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*") ? name : null;
  }

  /** 从异常消息里提取一个受信 errno 标识；不认识的正文不返回。 */
  static String errorCode(String message) {
    if (message == null) return null;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\b(E[A-Z]{3,})\\b")
            .matcher(message.toUpperCase(Locale.ROOT));
    return matcher.find() ? matcher.group(1) : null;
  }

  /** 生产与测试共用的真实绑定器：固定地址，不扫描、不回退到公网。 */
  public static ServerSocket bindTcp(int port) throws java.io.IOException {
    ServerSocket socket = new ServerSocket();
    try {
      socket.setReuseAddress(true);
      socket.bind(new java.net.InetSocketAddress("0.0.0.0", port));
      return socket;
    } catch (java.io.IOException | RuntimeException error) {
      closeQuietly(socket);
      throw error;
    }
  }

  static void closeQuietly(ServerSocket socket) {
    if (socket == null) return;
    try {
      socket.close();
    } catch (java.io.IOException ignored) {
    }
  }

  /**
   * 监听状态的唯一发布点（纯逻辑，生产 {@code LanProxyService} 与主机测试共用）。
   *
   * <p>它把「当前轮次」和「当前快照」绑在一起：{@link #begin} 登记轮次身份，
   * {@link #bound}/{@link #fail} 只接受当前轮次，{@link #ended} 只清自己，
   * {@link #stopGeneration} 只清属于指定 dsh 代次的监听。因此旧轮次的迟到失败不会
   * 覆盖新轮次，按代次停止也不会误伤别的代次。</p>
   */
  public static final class Registry {
    private Snapshot current = idle();
    private long activeEpoch;
    private long activeGeneration;

    /** 当前快照；永不返回 null。 */
    public synchronized Snapshot snapshot() {
      return current;
    }

    /** 认证与授权都已通过：登记本轮身份并发布 WAITING。 */
    public synchronized void begin(long epoch, long generation, int port) {
      activeEpoch = epoch;
      activeGeneration = generation;
      current = waiting(epoch, generation, port);
    }

    /** 该 epoch 是否仍是当前在途轮次（旧轮次的迟到结果必须被拒绝）。 */
    public synchronized boolean isCurrent(long epoch) {
      return epoch > 0 && activeEpoch == epoch;
    }

    /** 绑定成功：只有当前轮次能发布 BOUND。 */
    public synchronized boolean bound(long epoch, int port) {
      if (!isCurrent(epoch)) return false;
      current = LanListenerStatus.bound(epoch, activeGeneration, port);
      return true;
    }

    /**
     * 绑定失败：只有当前轮次能发布 FAILED；旧轮次的迟到失败被拒绝。轮次就此结束，
     * 保留 FAILED 快照供界面显示（按代次停止仍能清掉），但不再接受该轮次的后续发布。
     */
    public synchronized boolean fail(long epoch, int port, Throwable error) {
      if (!isCurrent(epoch)) return false;
      current = failed(epoch, activeGeneration, port, error);
      activeEpoch = 0;
      activeGeneration = 0;
      return true;
    }

    /** 成功轮次结束：只清自己留下的状态与身份。 */
    public synchronized void ended(long epoch) {
      if (activeEpoch != epoch) return;
      activeEpoch = 0;
      activeGeneration = 0;
      if (current.epoch() == epoch) current = idle();
    }

    /**
     * 停止属于 {@code generation} 的监听：按快照里显式绑定的 dsh 代次匹配，既不会
     * 因为轮次号与代次号数值巧合而误清，也不会在轮次已退出（activeRun 为空）时漏清。
     * {@code generation <= 0} 等价于 {@link #stopAll()}。
     */
    public synchronized void stopGeneration(long generation) {
      if (generation <= 0) {
        stopAll();
        return;
      }
      if (activeGeneration != generation && current.generation() != generation) return;
      activeEpoch = 0;
      activeGeneration = 0;
      current = idle();
    }

    /** 无条件清空：用户关闭局域网访问或服务关闭。 */
    public synchronized void stopAll() {
      activeEpoch = 0;
      activeGeneration = 0;
      current = idle();
    }
  }
}
