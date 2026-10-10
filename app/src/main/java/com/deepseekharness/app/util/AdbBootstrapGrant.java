package com.deepseekharness.app.util;

/** 一次动作从提交到完成的许可；撤销、代次变化、过期都不能靠重新开启复活。 */
public final class AdbBootstrapGrant {
  private final AdbBootstrapPolicy.Action action;
  private final long rootRevision, bootstrapRevision, expires;
  private volatile boolean revoked;

  public AdbBootstrapGrant(
      AdbBootstrapPolicy.Action action, long rootRevision, long bootstrapRevision, long now) {
    if (action == null || now < 0 || now > Long.MAX_VALUE - 120000)
      throw new IllegalArgumentException("NATIVE_AUTHORITY_INVALID");
    this.action = action;
    this.rootRevision = rootRevision;
    this.bootstrapRevision = bootstrapRevision;
    expires = now + 120000;
  }

  public void revoke() {
    revoked = true;
  }

  /**
   * 门禁只校验 root 许可与自举许可。
   *
   * <p>自举的目的正是在「尚无 ADB 授权」时把它建立起来（root 写 adb_keys，免系统配对），
   * 所以这里不再要求「ADB 设备通道」先行开启；否则会形成「要先有 ADB 才能自举 ADB」的循环，
   * 与原始目标（不经配对弹窗获得 ADB 通道）相反。
   */
  public boolean valid(
      boolean root, boolean bootstrap, long currentRoot, long currentBootstrap, long now) {
    return !revoked
        && root
        && now >= 0
        && now < expires
        && currentRoot == rootRevision
        && (!needsBootstrap(action) || bootstrap && currentBootstrap == bootstrapRevision);
  }

  public static boolean needsBootstrap(AdbBootstrapPolicy.Action action) {
    return action == AdbBootstrapPolicy.Action.AUTHORIZE
        || action == AdbBootstrapPolicy.Action.OPEN
        || action == AdbBootstrapPolicy.Action.NARROW;
  }

  /**
   * 执行路径判断：这两个动作需要从环境读取并核验客户端公钥。
   *
   * <p>它只决定「要不要准备公钥/环境」，不再承担任何门禁含义。
   */
  public static boolean needsClientKey(AdbBootstrapPolicy.Action action) {
    return action == AdbBootstrapPolicy.Action.AUTHORIZE
        || action == AdbBootstrapPolicy.Action.OPEN;
  }
}
