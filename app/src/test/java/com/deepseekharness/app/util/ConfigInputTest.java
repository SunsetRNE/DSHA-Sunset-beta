package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigInputTest {
  @Test
  public void usablePorts() {
    assertEquals(3080, ConfigInput.port(" 3080 "));
    assertEquals(1, ConfigInput.port("1"));
    assertEquals(1024, ConfigInput.port("1024"));
    assertEquals(65535, ConfigInput.port("65535"));
  }

  @Test
  public void reservedAndMalformedPortsNeverSilentlyFallback() {
    for (String value :
        new String[] {"3081", "3090", "0", "-1", "65536", "", "x", "999999999999", "30.80"})
      assertThrows(value, IllegalArgumentException.class, () -> ConfigInput.port(value));
  }

  /**
   * 保留端口判据只有一份实现：UI 保存（{@link ConfigInput#port}）、备份导入与主机设置
   * （{@code ConfigStore.portForWrite}）、读取兜底（{@code ConfigStore.getPortInt}）都引用它。
   * ADR 0003 §1 记录的漂移就是「同一条事实被抄成多份，然后各自漂移」。
   */
  @Test
  public void reservedPortRuleHasOneImplementation() {
    assertEquals(3081, Constants.LAN_BRIDGE_PORT);
    assertEquals(3090, Constants.SHELL_BRIDGE_PORT);
    assertTrue(ConfigInput.reservedPort(Constants.LAN_BRIDGE_PORT));
    assertTrue(ConfigInput.reservedPort(Constants.SHELL_BRIDGE_PORT));
    for (int port : new int[] {1, 1024, 3080, 3089, 3091, 65535})
      assertFalse(String.valueOf(port), ConfigInput.reservedPort(port));
    assertNotEquals("", ConfigInput.reservedPortMessage());
  }
}
