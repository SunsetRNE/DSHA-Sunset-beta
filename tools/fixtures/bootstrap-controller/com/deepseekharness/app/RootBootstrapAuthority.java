package com.deepseekharness.app;

import android.content.Context;
import com.deepseekharness.app.util.AdbBootstrapGrant;
import com.deepseekharness.app.util.AdbBootstrapPolicy;

/**
 * 主机测试替身：短期原生授权代理。
 *
 * <p>保留生产类的形状（票据、逐次 valid 回查、关闭即失效），但把"当前是否被撤销/被允许"
 * 交给测试控制，从而能在步骤之间的确切时点模拟用户撤销，而不是依赖真实的 120 秒许可。
 */
public final class RootBootstrapAuthority implements AutoCloseable {
  public static boolean allowed = true;
  /** 最近一次构造的代理，测试用它模拟执行途中撤销。 */
  public static RootBootstrapAuthority current;

  public final String ticket;
  private volatile boolean revoked;

  public RootBootstrapAuthority(Context app, AdbBootstrapPolicy.Action action, String key) {
    ticket = "ticket-" + action.name().toLowerCase(java.util.Locale.ROOT);
    current = this;
  }

  public static boolean needsBootstrap(AdbBootstrapPolicy.Action action) {
    return AdbBootstrapGrant.needsBootstrap(action);
  }

  public static boolean needsClientKey(AdbBootstrapPolicy.Action action) {
    return AdbBootstrapGrant.needsClientKey(action);
  }

  public static boolean allowed(Context app, AdbBootstrapPolicy.Action action) {
    return allowed;
  }

  public void prepareKey(String key) {}

  public void revoke() {
    revoked = true;
  }

  public boolean valid() {
    return allowed && !revoked;
  }

  @Override
  public void close() {
    revoked = true;
  }

  public static void reset() {
    allowed = true;
    current = null;
  }
}
