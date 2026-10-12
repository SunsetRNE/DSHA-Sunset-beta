package com.deepseekharness.app.core;

/** 主机测试桩：语言只用来验证脚本生成顺序，不读真实偏好。 */
public class ConfigStore {
  public static String language = "zh";

  private final Object context;

  public ConfigStore(android.content.Context context) {
    this.context = context;
  }

  public String getUiLanguage() {
    return language;
  }
}
