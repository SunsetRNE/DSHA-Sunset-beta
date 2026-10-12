package com.deepseekharness.app.util;

/** 主机测试桩：只提供页面绑定 nonce 与来源标注，不模拟真实门禁判定。 */
public final class StartupPageGate {
  public static final String NONCE = "stubnonce0123456789";
  private final String source;

  public StartupPageGate(Object owner, long generation, String base) {
    if (owner == null || generation <= 0) throw new IllegalArgumentException("STUB_GATE");
    source = base + ".dsha-page-observer/" + NONCE + ".js";
  }

  public String nonce() {
    return NONCE;
  }

  public String source() {
    return source;
  }
}
