package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * 桥监听端口的唯一选择点：首选 {@link #PREFERRED}（3090），被占用时依次尝试到
 * {@link #LAST}（3109），并且只绑定本机回环。
 *
 * <p><b>为什么第二个实例改端口，而不是共享 3090</b>：loopback 上拿不到调用者 UID，
 * token 是唯一认证；让一个桥接受另一个实例的 token，等于同机任意能读到该 token 的应用
 * 都能驱动设备通道。端口契约只决定「打到哪个桥」，token 仍决定「能不能用」。
 *
 * <p>回退是有界的：范围耗尽时抛 {@code BRIDGE_PORT_EXHAUSTED}，调用方必须如实报告
 * 设备工具不可用，不得改绑公网地址或放宽鉴权。真正 bind 成功的那个 socket 就是结果，
 * 不做「先探测再猜端口」的两段式（探测与绑定之间端口可能被别人拿走）。
 */
public final class BridgePortPolicy {
  /** 首选端口：与历史单实例行为一致。 */
  public static final int PREFERRED = Constants.SHELL_BRIDGE_PORT;

  /** 回退上界（含）：3090..3109，全部只在本机回环。 */
  public static final int LAST = 3109;

  private BridgePortPolicy() {}

  /** 一个候选端口的真实绑定动作；测试用它注入确定性的占用/失败。 */
  public interface Binder {
    ServerSocket bind(int port) throws IOException;
  }

  /** 真实绑定器：IPv4 回环，与桥的主监听同一地址。 */
  public static final Binder LOOPBACK =
      port -> {
        ServerSocket socket = new ServerSocket();
        try {
          socket.setReuseAddress(true);
          socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
          return socket;
        } catch (IOException | RuntimeException error) {
          closeQuietly(socket);
          throw error;
        }
      };

  /** 生产入口：3090 起，逐端口真实绑定到 3109。 */
  public static ServerSocket bindLoopbackFirstAvailable() throws IOException {
    return bindFirstAvailable(LOOPBACK, PREFERRED, LAST);
  }

  /**
   * 从 {@code first} 到 {@code last}（含）逐个真实绑定；首个成功即返回该已绑定 socket。
   * 整个范围都被占用时抛 {@code BRIDGE_PORT_EXHAUSTED}；非「端口被占用」的失败立即上抛，
   * 不能被当成冲突继续换端口。
   */
  public static ServerSocket bindFirstAvailable(Binder binder, int first, int last)
      throws IOException {
    if (binder == null) throw new IllegalArgumentException("BRIDGE_PORT_BINDER_REQUIRED");
    if (first < 1 || last > 65535 || first > last)
      throw new IllegalArgumentException("BRIDGE_PORT_RANGE");
    for (int port = first; port <= last; port++) {
      try {
        return binder.bind(port);
      } catch (BindException inUse) {
        // 端口被本机其它监听占用；继续尝试下一个候选。
      }
    }
    throw new IOException("BRIDGE_PORT_EXHAUSTED");
  }

  /** 该 socket 是否只绑在本机回环（杜绝把桥暴露到公网/局域网）。 */
  public static boolean loopbackOnly(ServerSocket socket) {
    if (socket == null || !socket.isBound()) return false;
    InetAddress address = socket.getInetAddress();
    return address != null && address.isLoopbackAddress();
  }

  static void closeQuietly(java.io.Closeable closeable) {
    if (closeable == null) return;
    try {
      closeable.close();
    } catch (IOException ignored) {
    }
  }
}
