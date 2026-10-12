package android.content;

import android.content.res.AssetManager;

/** 主机测试桩：只提供 WebPageScripts 生成脚本所需的资产入口，不模拟 Android 其它行为。 */
public class Context {
  public final AssetManager assets = new AssetManager();

  public AssetManager getAssets() {
    return assets;
  }
}
