package com.deepseekharness.app.util;

/** 配置输入先整体校验，再写入，避免一半保存或静默改成另一个值。 */
public final class ConfigInput {
  private ConfigInput() {}

  public static int port(String value) {
    int port = number(value, com.deepseekharness.app.util.UiText.text("端口请输入 1—65535 的整数"));
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException(
          com.deepseekharness.app.util.UiText.text("端口请输入 1—65535 的整数"));
    if (reservedPort(port)) throw new IllegalArgumentException(reservedPortMessage());
    return port;
  }

  /**
   * 保留端口判据的**唯一实现**：3081（LAN 代理）与 3090（设备桥）由 App 自己占用。
   *
   * <p>UI 保存走 {@link #port(String)}，备份导入与主机设置走 {@code ConfigStore.portForWrite}，
   * 读取兜底走 {@code ConfigStore.getPortInt()} —— 三处都必须引用这里，否则同一条事实会被抄成
   * 多份再各自漂移（ADR 0003 §1 记录过这种漂移）。
   */
  public static boolean reservedPort(int port) {
    return port == Constants.LAN_BRIDGE_PORT || port == Constants.SHELL_BRIDGE_PORT;
  }

  /** 保留端口的统一文案：两条路径给出同一句可读错误。 */
  public static String reservedPortMessage() {
    return com.deepseekharness.app.util.UiText.text("3081 和 3090 已用于 App 桥接，请换一个端口");
  }

  private static int number(String value, String error) {
    try {
      return Integer.parseInt(value == null ? "" : value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(error);
    }
  }
}
