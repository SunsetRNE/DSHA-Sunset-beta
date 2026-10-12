package com.deepseekharness.app;

/**
 * 主机测试桩：只保留浏览器侧唯一来源 {@code activePort()} 的契约
 * （0 = 桥不可用，1..65535 = 实际监听端口；首选端口见 BridgePortPolicy）。
 */
public final class HttpShellService {
  public static int port;
  public static int reads;

  private HttpShellService() {}

  public static int activePort() {
    reads++;
    return port;
  }
}
