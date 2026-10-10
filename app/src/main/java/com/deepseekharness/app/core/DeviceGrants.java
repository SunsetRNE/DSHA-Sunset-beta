package com.deepseekharness.app.core;

import android.content.Context;

/** 独立原生授权存储，不从环境、插件文件或备份自动恢复授权。 */
public final class DeviceGrants {
  private final android.content.SharedPreferences preferences;
  private static final Object BOOTSTRAP_LOCK = new Object();

  public DeviceGrants(Context context) {
    preferences = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
  }

  public boolean smsReadAllowed() {
    return preferences.getBoolean("sms_read", false);
  }

  public boolean setSmsReadAllowed(boolean allowed) {
    return preferences.edit().putBoolean("sms_read", allowed).commit();
  }

  public boolean adbBootstrapAllowed() {
    return preferences.getBoolean("adb_bootstrap", false);
  }

  public long adbBootstrapRevision() {
    return preferences.getLong("adb_bootstrap_revision", 0);
  }

  public boolean setAdbBootstrapAllowed(boolean allowed) {
    synchronized (BOOTSTRAP_LOCK) {
      long revision = adbBootstrapRevision();
      if (revision == Long.MAX_VALUE) return false;
      return preferences
          .edit()
          .putBoolean("adb_bootstrap", allowed)
          .putLong("adb_bootstrap_revision", revision + 1)
          .commit();
    }
  }
}
