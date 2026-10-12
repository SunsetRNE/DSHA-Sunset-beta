package com.deepseekharness.app.util;

/**
 * Shizuku 用户服务销毁事务的真实契约。抽出来是为了让「事务码」和「谁可以自我终止」有单一出处，
 * 并且能被主机测试对着真实 Shizuku 库常量核验。
 *
 * <p>Shizuku 服务端（{@code UserServiceRecord.destroy()}）用事务码 16777115 通知用户服务退出，这是
 * 用户服务进程**唯一**的正常终止入口 —— 官方文档原文：「the user service process will NOT be killed
 * automatically. You need to implement a "destroy" method in your service. The transaction code for that
 * method is 16777115 (use 16777114 in aidl).」声明 ID 会被生成的 {@code Stub} 再加上
 * {@code FIRST_CALL_TRANSACTION}(1)，所以声明成 16777114 才等于该事务码；声明成 16777113 会得到
 * 16777114，销毁事务送不到，服务端以为已删除、进程却永远留在设备上。
 */
public final class ShizukuUserServiceProtocol {
  /** Shizuku 服务端实际发送的 Binder 事务码（{@code ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy}）。 */
  public static final int DESTROY_TRANSACTION = 16777115;

  /** AIDL 若要与该事务码一致，必须声明的 ID；生成代码会再叠加 {@code FIRST_CALL_TRANSACTION}。 */
  public static final int DESTROY_AIDL_DECLARED_ID = 16777114;

  private ShizukuUserServiceProtocol() {}

  public static boolean isDestroyTransaction(int code) {
    return code == DESTROY_TRANSACTION;
  }

  /**
   * 只有以 Shizuku 特权身份（root 0 或 shell 2000）运行的用户服务进程才允许自我终止；进程内测试实例不是
   * 特权进程，不能把 DSHA 本身带走。
   */
  public static boolean ownsServiceProcess(int uid) {
    return uid == 0 || uid == 2000;
  }
}
