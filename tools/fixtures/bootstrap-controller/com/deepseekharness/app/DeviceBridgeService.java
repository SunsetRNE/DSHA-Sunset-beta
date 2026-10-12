package com.deepseekharness.app;

import android.content.Context;

/** 主机测试替身：只统计设备通道是否被要求开启，不启动任何 Android 服务。 */
public final class DeviceBridgeService {
  public static int applies;
  /** 模拟启动服务失败；生产里由 Android API 抛出的 RuntimeException。 */
  public static boolean failApply;

  private DeviceBridgeService() {}

  public static boolean apply(Context ctx) {
    applies++;
    if (failApply) throw new IllegalStateException("DEVICE_BRIDGE_UNAVAILABLE");
    return true;
  }

  public static void reset() {
    applies = 0;
    failApply = false;
  }
}
