package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** 主机测试替身：真实读写测试临时目录里的公钥文件，不接触 Android 私有存储。 */
public final class AndroidBackupFileSystem {
  public static final class Stat {
    public final String type;

    Stat(String type) {
      this.type = type;
    }
  }

  public Stat stat(File file) {
    return new Stat(file.isFile() ? "FILE" : "MISSING");
  }

  public byte[] small(File file, int limit) throws IOException {
    byte[] bytes = Files.readAllBytes(file.toPath());
    return bytes.length <= limit ? bytes : Arrays.copyOf(bytes, limit);
  }
}
