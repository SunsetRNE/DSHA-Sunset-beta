package android.util;

/**
 * 宿主 fixture 的 {@code android.util.Log} 替身：普通 JVM 单测里 android.jar 的
 * {@code Log} 是 not-mocked，一调就抛异常。这里只吸收日志，不改变被测逻辑。
 */
public final class Log {
  private Log() {}

  public static int v(String tag, String msg) {
    return 0;
  }

  public static int d(String tag, String msg) {
    return 0;
  }

  public static int i(String tag, String msg) {
    return 0;
  }

  public static int i(String tag, String msg, Throwable tr) {
    return 0;
  }

  public static int w(String tag, String msg) {
    return 0;
  }

  public static int w(String tag, String msg, Throwable tr) {
    return 0;
  }

  public static int e(String tag, String msg) {
    return 0;
  }

  public static int e(String tag, String msg, Throwable tr) {
    return 0;
  }
}
