package com.deepseekharness.app.bridge;

import android.content.Context;
import java.util.concurrent.Callable;

/** 主机测试替身：环境任务直接内联执行，会话核验按剧本返回。 */
public final class AdbBridge {
  public static String verifyResult = "";
  public static int verifyCalls;
  /**
   * 只读会话核验内部、结果返回**之前**的测试钩子。
   *
   * <p>用来在核验真正执行的那段窗口里模拟用户操作（例如关闭总开关），而不是在核验前后才
   * 插入。生产控制器没有这个钩子；它只让主机测试能击中「核验返回后没有回查用户意图」的竞态。
   */
  public static Runnable verifyHook = () -> {};

  private AdbBridge() {}

  public static <T> T runEnvironmentTask(Context ctx, String kind, Callable<T> operation)
      throws Exception {
    return operation.call();
  }

  public static String ensureReady(Context ctx, Object proot) {
    return "SETUP_DONE";
  }

  public static String verifyBootstrap(Object proot) {
    verifyCalls++;
    verifyHook.run();
    return verifyResult;
  }

  public static void reset() {
    verifyResult = "";
    verifyCalls = 0;
    verifyHook = () -> {};
  }
}
