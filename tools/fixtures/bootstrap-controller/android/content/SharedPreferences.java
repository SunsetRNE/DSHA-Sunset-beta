package android.content;

/** 控制器主机测试用的最小偏好接口：只声明生产代码实际调用的读写方法。 */
public interface SharedPreferences {
  boolean getBoolean(String key, boolean fallback);

  long getLong(String key, long fallback);

  String getString(String key, String fallback);

  boolean contains(String key);

  Editor edit();

  interface Editor {
    Editor putBoolean(String key, boolean value);

    Editor putLong(String key, long value);

    Editor putString(String key, String value);

    boolean commit();
  }
}
