package com.deepseekharness.app.util;

/**
 * 宿主 fixture 的 {@code UiText} 替身。真实 {@code UiText} 依赖构建期生成的
 * {@code UiMessages}，而这里只测监听状态与绑定，不测文案翻译；替身保持中文直出，
 * 语义与被替换层一致（{@code choose} 按语言取分支）。
 */
public final class UiText {
  private static volatile String language = "zh";

  private UiText() {}

  public static void setLanguage(String value) {
    language = "en".equals(value) ? "en" : "zh";
  }

  public static String language() {
    return language;
  }

  public static String choose(String chinese, String english) {
    return "en".equals(language) ? english : chinese;
  }

  public static String raw(String value) {
    return value;
  }

  public static CharSequence raw(CharSequence value) {
    return value;
  }

  public static String format(String template, Object... values) {
    return String.format(java.util.Locale.ROOT, template, values);
  }

  public static String text(String value) {
    return value;
  }

  public static CharSequence text(CharSequence value) {
    return value;
  }

  public static int text(int resource) {
    return resource;
  }

  public static String[] text(String[] values) {
    return values;
  }

  public static String status(String value) {
    return value;
  }

  public static String toolStatus(String value) {
    return value;
  }
}
