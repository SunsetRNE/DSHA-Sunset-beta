package android.content;

/** 宿主 fixture 的最小 {@code SharedPreferences} 替身，不落盘。 */
public interface SharedPreferences {

  Editor edit();

  interface Editor {
    Editor putString(String key, String value);

    Editor putBoolean(String key, boolean value);

    void apply();
  }
}
