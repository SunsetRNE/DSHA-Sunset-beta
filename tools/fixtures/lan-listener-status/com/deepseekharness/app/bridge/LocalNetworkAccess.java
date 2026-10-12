package com.deepseekharness.app.bridge;

import android.content.Context;

/**
 * 宿主 fixture 的 {@code LocalNetworkAccess} 替身：只让探针决定「授权通过/撤销」，
 * 不触碰 Android 权限系统，也不启动任何设备或网络功能。
 */
public final class LocalNetworkAccess {
  public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

  /** 探针可控的授权开关；默认通过。 */
  public static volatile boolean grantedForHost = true;

  private LocalNetworkAccess() {}

  public static boolean granted(Context context) {
    return grantedForHost;
  }
}
