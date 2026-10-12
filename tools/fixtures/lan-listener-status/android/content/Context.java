package android.content;

/**
 * 宿主 fixture 的最小 {@code Context} 替身：只提供 LAN 代理实际用到的读取与权限接口，
 * 不启动 Android、不访问真实存储（{@link #getSharedPreferences} 返回 null，凭据生成
 * 的持久化写入由生产代码的 try/catch 吞掉）。
 */
public class Context {
  public static final int MODE_PRIVATE = 0;

  public Context getApplicationContext() {
    return this;
  }

  public SharedPreferences getSharedPreferences(String name, int mode) {
    return null;
  }

  public int checkSelfPermission(String permission) {
    return 0;
  }
}
