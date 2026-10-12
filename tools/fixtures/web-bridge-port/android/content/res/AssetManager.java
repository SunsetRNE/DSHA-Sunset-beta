package android.content.res;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 主机测试桩：默认记录被读取的资产路径并为每个路径生成可辨识标记；设置系统属性
 * {@code dsha.web.assets} 指向真实 {@code app/src/main/assets} 时改读真实资产字节，
 * 供「真实 JS/Java 生成路径」的语法/内容核对使用。
 */
public class AssetManager {
  public final List<String> opened = new ArrayList<>();

  public InputStream open(String path) throws IOException {
    if (path == null || path.isEmpty()) throw new IOException("ASSET_PATH_EMPTY");
    opened.add(path);
    String root = System.getProperty("dsha.web.assets");
    if (root == null || root.isEmpty())
      return new ByteArrayInputStream(("/*ASSET:" + path + "*/").getBytes(StandardCharsets.UTF_8));
    return new FileInputStream(new File(root, path));
  }
}
