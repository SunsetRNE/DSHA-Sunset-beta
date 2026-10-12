package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 控制器主机测试用的最小 Android Context。
 *
 * <p>只提供控制器与授权/开关存储真正读取的东西：应用上下文、偏好设置与私有文件目录。
 * 不模拟 Activity、服务或系统设置；每个测试用到的偏好都留在进程内存里。
 */
public class Context {
  public static final int MODE_PRIVATE = 0;

  private final Map<String, FakeSharedPreferences> stores = new HashMap<>();
  private final File filesDir;

  public Context(File filesDir) {
    this.filesDir = filesDir;
  }

  public Context getApplicationContext() {
    return this;
  }

  public synchronized FakeSharedPreferences getSharedPreferences(String name, int mode) {
    return stores.computeIfAbsent(name, key -> new FakeSharedPreferences());
  }

  public File getFilesDir() {
    return filesDir;
  }
}
