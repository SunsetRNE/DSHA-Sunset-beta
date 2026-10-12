package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 桥端口回退的真实行为：首选 3090，冲突依次到 3109，只在回环上绑定。 */
public class BridgePortPolicyTest {
  private static ServerSocket bound(int port) throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
    return socket;
  }

  /** 首选端口被另一个实例占用时，仍要在有界范围内选到后面的空闲回环端口。 */
  @Test
  public void preferredPortOccupiedStillBindsNextFreeLoopbackPort() throws Exception {
    List<ServerSocket> occupied = new ArrayList<>();
    try {
      for (int port = BridgePortPolicy.PREFERRED; port <= 3093; port++) {
        try {
          occupied.add(bound(port));
        } catch (IOException busy) {
          // 本机已被别的监听占用，对该端口而言同样是「不可用」。
        }
      }
      try (ServerSocket chosen = BridgePortPolicy.bindLoopbackFirstAvailable()) {
        int port = chosen.getLocalPort();
        assertTrue(chosen.isBound());
        assertTrue(
            "必须绑在本机回环: " + chosen.getInetAddress(), chosen.getInetAddress().isLoopbackAddress());
        assertNotEquals("首选端口被占用时不能复用同一个端口", BridgePortPolicy.PREFERRED, port);
        assertTrue(
            "端口要在有界回退范围内: " + port,
            port > BridgePortPolicy.PREFERRED && port <= BridgePortPolicy.LAST);
        for (ServerSocket taken : occupied)
          assertNotEquals("不能选一个已知被占用的端口: " + port, taken.getLocalPort(), port);
      }
    } finally {
      for (ServerSocket socket : occupied) socket.close();
    }
  }

  @Test
  public void binderStopsAtFirstSuccessAndNeverProbesPastIt() throws Exception {
    List<Integer> attempts = new ArrayList<>();
    try (ServerSocket available = bound(0)) {
      int free = available.getLocalPort();
      ServerSocket chosen =
          BridgePortPolicy.bindFirstAvailable(
              port -> {
                attempts.add(port);
                if (port != free) throw new BindException("Address already in use");
                return available;
              },
              free,
              free + 3);
      assertSame(available, chosen);
      assertEquals(List.of(free), attempts);
    }
  }

  @Test
  public void exhaustedRangeReportsUnavailableInsteadOfBindingElsewhere() {
    List<Integer> attempts = new ArrayList<>();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                BridgePortPolicy.bindFirstAvailable(
                    port -> {
                      attempts.add(port);
                      throw new BindException("Address already in use");
                    },
                    BridgePortPolicy.PREFERRED,
                    BridgePortPolicy.LAST));
    assertEquals("BRIDGE_PORT_EXHAUSTED", error.getMessage());
    assertEquals(20, attempts.size());
    assertEquals(Integer.valueOf(3090), attempts.get(0));
    assertEquals(Integer.valueOf(3109), attempts.get(19));
  }

  @Test
  public void unexpectedBindFailureIsNotMaskedAsPortConflict() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                BridgePortPolicy.bindFirstAvailable(
                    port -> {
                      throw new IOException("EACCES");
                    },
                    BridgePortPolicy.PREFERRED,
                    BridgePortPolicy.LAST));
    assertEquals("EACCES", error.getMessage());
  }

  @Test
  public void invalidRangeIsRejectedBeforeAnyBind() {
    BridgePortPolicy.Binder never =
        port -> {
          throw new AssertionError("不能尝试绑定");
        };
    assertThrows(
        IllegalArgumentException.class, () -> BridgePortPolicy.bindFirstAvailable(never, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> BridgePortPolicy.bindFirstAvailable(never, 65536, 70000));
    assertThrows(
        IllegalArgumentException.class,
        () -> BridgePortPolicy.bindFirstAvailable(never, 3100, 3090));
  }

  @Test
  public void contractRangeStaysOffPublicKnownPorts() {
    assertEquals(3090, BridgePortPolicy.PREFERRED);
    assertEquals(3109, BridgePortPolicy.LAST);
    assertEquals(
        com.deepseekharness.app.util.Constants.SHELL_BRIDGE_PORT, BridgePortPolicy.PREFERRED);
  }
}
