package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * 桥端点发现文件 {@code .bridge_port}：与 {@code .bridge_token} / {@code .bridge_headers}
 * 同目录（数据根，容器内即 {@code /root/.dsh}）。
 *
 * <p><b>契约</b>：ASCII 十进制端口 + {@code \n}。
 * <ul>
 *   <li>{@code 0} —— 本实例桥不可用（启动、绑定失败、停止时都会这样发布一次）；</li>
 *   <li>{@code 1..65535} —— 实际监听端口，仅在 bind 成功且凭据/读回校验通过后发布；</li>
 *   <li>文件缺失 —— 只对旧版本消费者兼容缺省 {@link #LEGACY_DEFAULT}（3090）。</li>
 * </ul>
 *
 * <p><b>这不是凭据，也不参与鉴权</b>：端口只决定请求打到哪个桥，token 仍决定能不能用。
 * 本类不提供「读不到就回退 3090」的行为；读到 0 或坏内容必须按不可用处理，
 * 否则同机第二个实例会把请求打到第一个实例的桥上（然后被 token 拒绝）。
 * 原子写 + 读回核验与凭据文件同一套纪律；旧代次不得擦除新代次已发布的端口。
 *
 * <p>就绪前的发布必须用 {@link #publishReady}：端口契约只有在同一数据根的
 * {@code .bridge_token} / {@code .bridge_headers} 已在位时才算数，否则「就绪」只是一个
 * 没有凭据可用的假成功（消费者随后会误回退到缺省端口）。{@link #publishIfCurrent} 仍用于
 * 启动占位（发布 {@code 0}）与停止清理这类不声明就绪的场合。
 */
public final class BridgeEndpointFiles {
  /** 与 {@code .bridge_token} 同目录的发现文件名（名字由凭据路径表统一生成）。 */
  public static final String FILE_NAME = CredentialPaths.BRIDGE_PORT;

  /** 已发布但本实例桥不可用。 */
  public static final int UNAVAILABLE = 0;

  /** 文件缺失时的返回码（只有旧版本消费者才把它当 3090）。 */
  public static final int MISSING = -1;

  /** 旧版本消费者的兼容缺省；新原生与契约消费者都不把它当回退结果。 */
  public static final int LEGACY_DEFAULT = Constants.SHELL_BRIDGE_PORT;

  private static final int READ_LIMIT = 16;

  private BridgeEndpointFiles() {}

  /** 端口 -> 文件内容；范围外拒绝，避免发布一个打不通或误导的端口。 */
  public static String encode(int port) throws IOException {
    if (port < 0 || port > 65535) throw new IOException("BRIDGE_PORT_RANGE");
    return Integer.toString(port) + "\n";
  }

  /** 严格解析已发布内容：只有 {@code 0} 或 {@code 1..65535} 合法，其它都是契约错误。 */
  public static int parse(String raw) throws IOException {
    if (raw == null || !raw.matches("[0-9]{1,5}\\n?")) throw new IOException("BRIDGE_PORT_FORMAT");
    String value = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
    int port = Integer.parseInt(value);
    if (port < 0 || port > 65535) throw new IOException("BRIDGE_PORT_RANGE");
    return port;
  }

  /**
   * 读契约文件：文件缺失返回 {@link #MISSING}；非普通文件、坏内容、超大内容一律抛错。
   * 任何情况下都不返回 {@link #LEGACY_DEFAULT} 作为回退。
   */
  public static int read(BackupFileSystem fs, File file) throws IOException {
    BackupFileSystem.Node node = fs.stat(file);
    if (node.type.equals("MISSING")) return MISSING;
    if (!node.type.equals("FILE")) throw new IOException("BRIDGE_PORT_FILE_TYPE");
    try {
      return parse(new String(fs.small(file, READ_LIMIT), StandardCharsets.UTF_8));
    } catch (IOException malformed) {
      if (malformed.getMessage() != null && malformed.getMessage().startsWith("BRIDGE_PORT_"))
        throw malformed;
      throw new IOException("BRIDGE_PORT_FORMAT", malformed);
    }
  }

  /** 原子发布 + 读回校验；任一步失败都抛错，调用方据此不发布就绪。 */
  public static void publish(BackupFileSystem fs, File file, int port) throws IOException {
    byte[] bytes = encode(port).getBytes(StandardCharsets.UTF_8);
    File root = file.getParentFile();
    if (root == null || !fs.stat(root).type.equals("DIRECTORY"))
      throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
    BackupFileSystem.Node old = fs.stat(file);
    if (!old.type.equals("MISSING") && !old.type.equals("FILE"))
      throw new IOException("BRIDGE_PORT_FILE_TYPE");
    fs.atomic(root, file.getName(), bytes);
    fs.mode(file, 0600);
    if (read(fs, file) != port || !java.util.Arrays.equals(bytes, fs.small(file, READ_LIMIT)))
      throw new IOException("BRIDGE_PORT_READBACK");
  }

  /**
   * 只有当前代次才允许改端点文件：迟到的旧代次 stop/finish 不得擦除新代次已发布的端口。
   *
   * <p>调用方必须持有与代次判定同一把锁，让「检查代次」与「写文件」之间代次不能变化。
   *
   * @return false 表示代次已过期，文件一个字节都没动
   */
  public static boolean publishIfCurrent(
      BackupFileSystem fs, File file, int port, BooleanSupplier current) throws IOException {
    if (current != null && !current.getAsBoolean()) return false;
    publish(fs, file, port);
    return true;
  }

  /**
   * 就绪门槛：本实例的 token/header 必须已经落在**同一个数据根**里，端口才有资格被发布。
   *
   * <p>{@code publish()} 只保证端口文件可读回；若数据根还没选出来（例如 proot 尚未就绪）或
   * 只存在内存 token，端口文件就会成为「指得通、却没人能鉴权」的假契约。因此就绪前的发布
   * 一律走这里：根不可用报 {@code BRIDGE_DATA_ROOT_UNAVAILABLE}，token/header 不在位报
   * {@code BRIDGE_TOKEN_UNAVAILABLE} / {@code BRIDGE_HEADERS_UNAVAILABLE}，任一项失败都不写端口。
   *
   * @return false 表示代次已过期，文件和凭据一个字节都没动
   */
  public static boolean publishReady(
      BackupFileSystem fs, File file, int port, String token, BooleanSupplier current)
      throws IOException {
    if (current != null && !current.getAsBoolean()) return false;
    requireCredentials(fs, file == null ? null : file.getParentFile(), token);
    publish(fs, file, port);
    return true;
  }

  /** 同一数据根内的 token/header 核验；任何缺失或不一致都按不可就绪处理。 */
  private static void requireCredentials(BackupFileSystem fs, File root, String token)
      throws IOException {
    if (token == null || token.isEmpty()) throw new IOException("BRIDGE_TOKEN_UNAVAILABLE");
    if (root == null || !fs.stat(root).type.equals("DIRECTORY"))
      throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
    if (!token.equals(BridgeCredentialFiles.read(fs, fs.child(root, CredentialPaths.BRIDGE_TOKEN))))
      throw new IOException("BRIDGE_TOKEN_UNAVAILABLE");
    byte[] expected = ("X-Token: " + token + "\n").getBytes(StandardCharsets.UTF_8);
    File headers = fs.child(root, CredentialPaths.BRIDGE_HEADERS);
    if (!fs.stat(headers).type.equals("FILE")) throw new IOException("BRIDGE_HEADERS_UNAVAILABLE");
    byte[] actual;
    try {
      actual = fs.small(headers, 512);
    } catch (IOException unreadable) {
      throw new IOException("BRIDGE_HEADERS_UNAVAILABLE", unreadable);
    }
    if (!java.util.Arrays.equals(expected, actual))
      throw new IOException("BRIDGE_HEADERS_UNAVAILABLE");
  }
}
