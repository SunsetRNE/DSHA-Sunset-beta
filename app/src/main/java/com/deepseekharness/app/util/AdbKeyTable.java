package com.deepseekharness.app.util;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 设备 ADB 授权表（`/data/misc/adb/adb_keys`）的只读解析。
 *
 * <p>纯逻辑：每行是 `<base64 公钥> <备注>`。指纹取解码后字节的 SHA-256 前 8 字节，
 * 用冒号分组显示 —— 足够在界面上区分"哪把钥匙"，又不把整把公钥铺在屏幕上。
 */
public final class AdbKeyTable {
  private AdbKeyTable() {}

  public static final class Entry {
    public final String fingerprint;
    public final String comment;
    /** 备注里带本应用包名或 `dsha:` 前缀，说明是本应用自举写进去的那把。 */
    public final boolean appOwned;

    Entry(String fingerprint, String comment, boolean appOwned) {
      this.fingerprint = fingerprint;
      this.comment = comment;
      this.appOwned = appOwned;
    }
  }

  public static List<Entry> parse(String output, String packageName) {
    if (output == null || output.isEmpty()) return Collections.emptyList();
    List<Entry> entries = new ArrayList<>();
    for (String raw : output.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      // 设备 shell 会在输出末尾附协议标记（`[EXIT=0]` 等）；它不是授权表的一行。
      if (line.startsWith("[") && line.endsWith("]")) continue;
      int space = line.indexOf(' ');
      String blob = space < 0 ? line : line.substring(0, space);
      String comment = space < 0 ? "" : line.substring(space + 1).trim();
      boolean owned =
          !comment.isEmpty()
              && (comment.startsWith("dsha:")
                  || (packageName != null
                      && !packageName.isEmpty()
                      && comment.contains(packageName)));
      entries.add(new Entry(fingerprint(blob), comment, owned));
    }
    return Collections.unmodifiableList(entries);
  }

  /** 解码失败不猜：返回空串，界面显示「未知」。 */
  public static String fingerprint(String base64) {
    if (base64 == null || base64.isEmpty()) return "";
    try {
      byte[] decoded = Base64.getDecoder().decode(base64);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(decoded);
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        if (i > 0) text.append(':');
        text.append(String.format(java.util.Locale.ROOT, "%02X", digest[i]));
      }
      return text.toString();
    } catch (Throwable unsupported) {
      return "";
    }
  }

  public static boolean containsAppKey(List<Entry> entries) {
    for (Entry entry : entries) if (entry.appOwned) return true;
    return false;
  }
}
