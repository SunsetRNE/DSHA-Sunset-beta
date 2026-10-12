package com.deepseekharness.app;

import android.content.Context;
import com.deepseekharness.app.util.AdbBootstrapPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 主机测试替身：按剧本返回原生自举输出并记录派发顺序。
 *
 * <p>真实 RootShell 需要 su / 绑定服务；这里只替代设备 I/O，让生产控制器的组合逻辑
 * （谁在跑、派发了哪一步、失败后停不停）可以在普通 JVM 上被真实执行。
 */
public final class RootShell {
  public static boolean present = true;
  public static Function<AdbBootstrapPolicy.Action, String> responder = action -> "";
  public static final List<AdbBootstrapPolicy.Action> calls = new ArrayList<>();

  private RootShell() {}

  public static boolean present() {
    return present;
  }

  public static String bootstrap(
      Context ctx, AdbBootstrapPolicy.Action action, String authority) {
    calls.add(action);
    return responder.apply(action);
  }

  public static void reset() {
    present = true;
    calls.clear();
    responder = action -> "";
  }
}
