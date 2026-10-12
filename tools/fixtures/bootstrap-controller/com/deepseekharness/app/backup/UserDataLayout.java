package com.deepseekharness.app.backup;

import java.io.File;

/** 主机测试替身：当前用户数据根的版本切换不在本轮范围内，直接指向测试私有目录。 */
public final class UserDataLayout {
  private final File current;

  public UserDataLayout(AndroidBackupFileSystem fs, File filesDir) {
    current = filesDir;
  }

  public File current() {
    return current;
  }
}
