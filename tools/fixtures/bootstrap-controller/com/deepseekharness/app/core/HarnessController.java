package com.deepseekharness.app.core;

import android.content.Context;

/** 主机测试替身：控制器只经它取得 proot 句柄；适配器由 AdbBridge 替身接收。 */
public final class HarnessController {
  private static final HarnessController INSTANCE = new HarnessController();

  private HarnessController() {}

  public static HarnessController get(Context context) {
    return INSTANCE;
  }

  public Object proot() {
    return null;
  }
}
