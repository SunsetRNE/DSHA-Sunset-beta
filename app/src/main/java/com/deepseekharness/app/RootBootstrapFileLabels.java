package com.deepseekharness.app;

import android.os.Build;
import android.system.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** API 23–25 复用系统 SELinux 文件标签接口，API 26+ 使用公开 xattr；失败时停止事务。 */
final class RootBootstrapFileLabels {
  private RootBootstrapFileLabels() {}

  static byte[] read(String path) throws Exception {
    if (Build.VERSION.SDK_INT >= 26) return Os.getxattr(path, "security.selinux");
    Object value =
        Class.forName("android.os.SELinux")
            .getMethod("getFileContext", String.class)
            .invoke(null, path);
    if (!(value instanceof String) || ((String) value).isEmpty())
      throw new IOException("FILE_LABEL_UNAVAILABLE");
    return ((String) value).getBytes(StandardCharsets.UTF_8);
  }

  static void copyTo(String path, byte[] label) throws Exception {
    if (Build.VERSION.SDK_INT >= 26) {
      Os.setxattr(path, "security.selinux", label, 0);
      return;
    }
    boolean done =
        Boolean.TRUE.equals(
            Class.forName("android.os.SELinux")
                .getMethod("setFileContext", String.class, String.class)
                .invoke(null, path, new String(label, StandardCharsets.UTF_8)));
    if (!done) throw new IOException("FILE_LABEL_PRESERVATION_FAILED");
  }
}
