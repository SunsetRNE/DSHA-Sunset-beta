package com.deepseekharness.app.util;

/**
 * 首次写入系统 ADB 授权表之前的无副作用预检（F06 / D1）。
 *
 * <p>可信系统目录存在、但授权表文件不存在的设备上，旧实现会先写防火墙、再在读取授权表时
 * 以 ENOENT 失败并把它报成未知结果 —— 设备已经被改，却既没建表也没能说清原因。D1 只做
 * 止损：在**任何**设备写入之前，把「可信缺失、目录 / 链接 / 元数据异常、标签不可读」区分
 * 出来，并返回明确的未初始化 / 不支持原因；防火墙、公钥、监听写入保持为零。
 *
 * <p>这里**不**提供首次建表能力。真正完成首次建表（无覆盖发布、标签来源、提交前外部建表
 * 与提交中断处理）属于 D2，尚未实现；因此 READY 只表示「沿用既有 inode / 标签的写入路径
 * 可以安全继续」，不表示首次自举已受支持。
 */
public final class AdbKeyTablePreflight {
  public static final String DIRECTORY_UNTRUSTED = "ADB_KEY_DIRECTORY_UNTRUSTED";
  public static final String MISSING_UNSUPPORTED = "ADB_KEY_TABLE_MISSING_UNSUPPORTED";
  public static final String IDENTITY_UNVERIFIED = "ADB_KEY_TABLE_IDENTITY_UNVERIFIED";
  public static final String LABEL_UNVERIFIED = "ADB_KEY_TABLE_LABEL_UNVERIFIED";

  private AdbKeyTablePreflight() {}

  /**
   * @return 空串表示可以继续进行既有的写入路径；否则是明确的拒绝码（零设备写入）
   */
  public static String reason(
      boolean directoryTrusted,
      boolean filePresent,
      boolean regularFile,
      boolean singleLink,
      boolean ownerTrusted,
      boolean modeSafe,
      boolean pathCanonical,
      boolean labelReadable) {
    if (!directoryTrusted) return DIRECTORY_UNTRUSTED;
    if (!filePresent) return MISSING_UNSUPPORTED;
    if (!regularFile || !singleLink || !pathCanonical) return IDENTITY_UNVERIFIED;
    if (!ownerTrusted || !modeSafe) return IDENTITY_UNVERIFIED;
    if (!labelReadable) return LABEL_UNVERIFIED;
    return "";
  }
}
