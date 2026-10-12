package com.deepseekharness.app.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Shizuku 用户服务销毁事务的专属单测：事务码契约、AIDL 声明关系与「谁能自我终止」都有断言。
 *
 * <p>真实 Shizuku 库常量的交叉核验在 host 测试
 * {@code tools/test-shizuku-lifecycle.py}（对着 {@code rikka.shizuku.ShizukuApiConstants} 编译），
 * 这里只锁纯逻辑，不引第三方依赖。
 */
public class ShizukuUserServiceProtocolTest {

  @Test
  public void destroyTransactionMatchesShizukuServerConstant() {
    // Shizuku 服务端 UserServiceRecord.destroy() 发送的原始事务码。
    Assert.assertEquals(16777115, ShizukuUserServiceProtocol.DESTROY_TRANSACTION);
    // AIDL 声明 ID 会被生成的 Stub 再加 FIRST_CALL_TRANSACTION(1)；声明值必须比事务码小 1。
    Assert.assertEquals(
        1,
        ShizukuUserServiceProtocol.DESTROY_TRANSACTION
            - ShizukuUserServiceProtocol.DESTROY_AIDL_DECLARED_ID);
    Assert.assertEquals(16777114, ShizukuUserServiceProtocol.DESTROY_AIDL_DECLARED_ID);
  }

  @Test
  public void onlyTheShizukuTransactionIsTreatedAsDestroy() {
    Assert.assertTrue(
        ShizukuUserServiceProtocol.isDestroyTransaction(
            ShizukuUserServiceProtocol.DESTROY_TRANSACTION));
    // AIDL 声明 ID 本身不是 Binder 事务码，差一位就必须不认。
    Assert.assertFalse(
        ShizukuUserServiceProtocol.isDestroyTransaction(
            ShizukuUserServiceProtocol.DESTROY_AIDL_DECLARED_ID));
    Assert.assertFalse(ShizukuUserServiceProtocol.isDestroyTransaction(0));
    Assert.assertFalse(ShizukuUserServiceProtocol.isDestroyTransaction(1));
  }

  @Test
  public void onlyPrivilegedShizukuUidsMayTerminateTheServiceProcess() {
    Assert.assertTrue(ShizukuUserServiceProtocol.ownsServiceProcess(0));
    Assert.assertTrue(ShizukuUserServiceProtocol.ownsServiceProcess(2000));
    Assert.assertFalse(ShizukuUserServiceProtocol.ownsServiceProcess(1000));
    Assert.assertFalse(ShizukuUserServiceProtocol.ownsServiceProcess(10123));
    Assert.assertFalse(ShizukuUserServiceProtocol.ownsServiceProcess(-1));
  }
}
