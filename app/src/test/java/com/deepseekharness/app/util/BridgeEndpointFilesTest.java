package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** `.bridge_port` 端点发现的真实文件行为：原子发布、读回校验、旧代次不得擦除新代次。 */
public class BridgeEndpointFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private File root() throws IOException {
    return temporary.newFolder();
  }

  private static File endpoint(File root) {
    return new File(root, BridgeEndpointFiles.FILE_NAME);
  }

  @Test
  public void unavailableAndActualPortsArePublishedAsExactDecimalBytes() throws Exception {
    File file = endpoint(root());
    BridgeEndpointFiles.publish(fs, file, BridgeEndpointFiles.UNAVAILABLE);
    assertEquals("0\n", Files.readString(file.toPath()));
    assertEquals(BridgeEndpointFiles.UNAVAILABLE, BridgeEndpointFiles.read(fs, file));

    BridgeEndpointFiles.publish(fs, file, 3091);
    assertEquals("3091\n", Files.readString(file.toPath()));
    assertEquals(3091, BridgeEndpointFiles.read(fs, file));
  }

  @Test
  public void missingFileIsDistinctFromUnavailableAndLegacyDefaultIsNotANewFallback()
      throws Exception {
    File file = endpoint(root());
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, file));
    assertEquals(0, BridgeEndpointFiles.UNAVAILABLE);
    assertNotEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.UNAVAILABLE);
    assertEquals(3090, BridgeEndpointFiles.LEGACY_DEFAULT);
  }

  @Test
  public void zeroAndMalformedContentNeverBecomeAPort() throws Exception {
    File file = endpoint(root());
    for (String bad :
        new String[] {
          "",
          "abc\n",
          "-1\n",
          "65536\n",
          "3090x\n",
          " 3090 3091\n",
          " 3091",
          "3091 ",
          "3091\n\n",
          "3091\t",
          "3091\u00a0"
        }) {
      Files.writeString(file.toPath(), bad);
      IOException error = assertThrows(IOException.class, () -> BridgeEndpointFiles.read(fs, file));
      assertTrue(
          "坏内容必须报契约错误: " + error.getMessage(), error.getMessage().startsWith("BRIDGE_PORT_"));
    }
    Files.writeString(file.toPath(), "9".repeat(64) + "\n");
    assertThrows(IOException.class, () -> BridgeEndpointFiles.read(fs, file));
  }

  @Test
  public void directoryAtEndpointPathIsRejectedAndNeverOverwritten() throws Exception {
    File file = endpoint(root());
    Files.createDirectory(file.toPath());
    assertThrows(IOException.class, () -> BridgeEndpointFiles.publish(fs, file, 3090));
    assertTrue(Files.isDirectory(file.toPath()));
  }

  @Test
  public void missingDataRootPublishesNothing() throws Exception {
    File absent = new File(temporary.getRoot(), "absent");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                BridgeEndpointFiles.publish(
                    fs, new File(absent, BridgeEndpointFiles.FILE_NAME), 3090));
    assertEquals("BRIDGE_DATA_ROOT_UNAVAILABLE", error.getMessage());
    assertFalse(absent.exists());
  }

  @Test
  public void writeIoFailureAndReadbackMismatchBothFailInsteadOfPublishing() throws Exception {
    File file = endpoint(root());
    BackupFileSystem brokenWrite =
        new JvmBackupFileSystem() {
          @Override
          public void atomic(File directory, String name, byte[] bytes) throws IOException {
            throw new IOException("EIO");
          }
        };
    assertEquals(
        "EIO",
        assertThrows(IOException.class, () -> BridgeEndpointFiles.publish(brokenWrite, file, 3090))
            .getMessage());
    assertFalse(file.exists());

    BackupFileSystem wrongReadback =
        new JvmBackupFileSystem() {
          @Override
          public byte[] small(File target, int limit) {
            return "0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          }
        };
    assertEquals(
        "BRIDGE_PORT_READBACK",
        assertThrows(
                IOException.class, () -> BridgeEndpointFiles.publish(wrongReadback, file, 3091))
            .getMessage());
  }

  @Test
  public void staleGenerationCannotEraseTheNewerEndpoint() throws Exception {
    File file = endpoint(root());
    BridgeLifecycle lifecycle = new BridgeLifecycle();
    long old = lifecycle.beginStart();
    assertTrue(lifecycle.finish(old));
    long current = lifecycle.beginStart();
    assertTrue(
        BridgeEndpointFiles.publishIfCurrent(fs, file, 3091, () -> lifecycle.isCurrent(current)));
    assertFalse(
        BridgeEndpointFiles.publishIfCurrent(
            fs, file, BridgeEndpointFiles.UNAVAILABLE, () -> lifecycle.isCurrent(old)));
    assertEquals(3091, BridgeEndpointFiles.read(fs, file));
    assertTrue(lifecycle.publish(current));
    assertFalse(
        BridgeEndpointFiles.publishIfCurrent(
            fs, file, BridgeEndpointFiles.UNAVAILABLE, () -> lifecycle.isCurrent(old)));
    assertEquals(3091, BridgeEndpointFiles.read(fs, file));
  }

  @Test
  public void publishIfCurrentWritesNothingWhenGenerationIsStale() throws Exception {
    File file = endpoint(root());
    BridgeEndpointFiles.publish(fs, file, 3090);
    assertFalse(BridgeEndpointFiles.publishIfCurrent(fs, file, 0, () -> false));
    assertEquals("3090\n", Files.readString(file.toPath()));
  }

  /** 端口契约不是鉴权：两个实例各有自己的端口与 token，对方 token 一律拒绝。 */
  @Test
  public void twoInstancesShareNeitherPortContractNorToken() throws Exception {
    File rootA = root(), rootB = root();
    String tokenA = "A".repeat(32), tokenB = "B".repeat(32);
    BridgeCredentialFiles.publish(fs, new File(rootA, CredentialPaths.BRIDGE_TOKEN), tokenA, false);
    BridgeCredentialFiles.publish(fs, new File(rootB, CredentialPaths.BRIDGE_TOKEN), tokenB, false);
    BridgeEndpointFiles.publish(fs, endpoint(rootA), 3090);
    BridgeEndpointFiles.publish(fs, endpoint(rootB), 3091);

    assertEquals(3090, BridgeEndpointFiles.read(fs, endpoint(rootA)));
    assertEquals(3091, BridgeEndpointFiles.read(fs, endpoint(rootB)));
    assertNotEquals(
        BridgeCredentialFiles.read(fs, new File(rootA, CredentialPaths.BRIDGE_TOKEN)),
        BridgeCredentialFiles.read(fs, new File(rootB, CredentialPaths.BRIDGE_TOKEN)));
    assertTrue(BridgeCredentialAuth.authorized("/app/version", List.of(tokenA), tokenA));
    assertFalse(BridgeCredentialAuth.authorized("/app/version", List.of(tokenA), tokenB));
    assertFalse(BridgeCredentialAuth.authorized("/app/version", List.of(tokenB), tokenA));
  }

  /**
   * 就绪门槛：只有同一数据根里的 token/header 已在位，端口才允许被发布。
   *
   * <p>这正是旧缺陷的红灯：没有数据根时会生成内存 token，桥却照样 ready —— 容器随后读不到
   * {@code .bridge_port}，只能回退到别人的 3090。因此「只有内存 token / 只有 token 没有 header /
   * 文件与内存不一致」都必须报错且**不写端口**。
   */
  @Test
  public void readyPublicationRequiresSameRootTokenAndHeaders() throws Exception {
    File root = root();
    File file = endpoint(root);
    String token = "A".repeat(32);

    assertEquals(
        "BRIDGE_TOKEN_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, file, 3091, token, () -> true))
            .getMessage());
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, file));

    Files.writeString(new File(root, CredentialPaths.BRIDGE_TOKEN).toPath(), token);
    assertEquals(
        "BRIDGE_HEADERS_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, file, 3091, token, () -> true))
            .getMessage());
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, file));

    Files.writeString(
        new File(root, CredentialPaths.BRIDGE_HEADERS).toPath(), "X-Token: someone-else\n");
    assertEquals(
        "BRIDGE_HEADERS_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, file, 3091, token, () -> true))
            .getMessage());
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, file));

    // 文件里是另一个实例/旧代次的 token：内存 token 不能算就绪。
    BridgeCredentialFiles.publish(
        fs, new File(root, CredentialPaths.BRIDGE_TOKEN), "B".repeat(32), false);
    assertEquals(
        "BRIDGE_TOKEN_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, file, 3091, token, () -> true))
            .getMessage());
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, file));

    // 同一根内 token/header 一致，才发布实际端口并读回。
    BridgeCredentialFiles.publish(fs, new File(root, CredentialPaths.BRIDGE_TOKEN), token, false);
    assertTrue(BridgeEndpointFiles.publishReady(fs, file, 3091, token, () -> true));
    assertEquals("3091\n", Files.readString(file.toPath()));
  }

  @Test
  public void readyPublicationWithoutARootReportsUnavailableAndWritesNothing() throws Exception {
    File absent = new File(temporary.getRoot(), "absent");
    File file = new File(absent, BridgeEndpointFiles.FILE_NAME);
    assertEquals(
        "BRIDGE_DATA_ROOT_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, file, 3091, "A".repeat(32), () -> true))
            .getMessage());
    assertFalse(absent.exists());
    assertEquals(
        "BRIDGE_DATA_ROOT_UNAVAILABLE",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(fs, null, 3091, "A".repeat(32), () -> true))
            .getMessage());
  }

  @Test
  public void readyPublicationFailsClosedOnWriteOrReadbackFailure() throws Exception {
    File root = root();
    File file = endpoint(root);
    String token = "A".repeat(32);
    BridgeCredentialFiles.publish(fs, new File(root, CredentialPaths.BRIDGE_TOKEN), token, false);

    BackupFileSystem brokenWrite =
        new JvmBackupFileSystem() {
          @Override
          public void atomic(File directory, String name, byte[] bytes) throws IOException {
            if (BridgeEndpointFiles.FILE_NAME.equals(name)) throw new IOException("EIO");
            super.atomic(directory, name, bytes);
          }
        };
    assertEquals(
        "EIO",
        assertThrows(
                IOException.class,
                () -> BridgeEndpointFiles.publishReady(brokenWrite, file, 3091, token, () -> true))
            .getMessage());
    assertFalse(file.exists());

    BackupFileSystem wrongReadback =
        new JvmBackupFileSystem() {
          @Override
          public byte[] small(File target, int limit) throws IOException {
            if (BridgeEndpointFiles.FILE_NAME.equals(target.getName()))
              return "0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return super.small(target, limit);
          }
        };
    assertEquals(
        "BRIDGE_PORT_READBACK",
        assertThrows(
                IOException.class,
                () ->
                    BridgeEndpointFiles.publishReady(wrongReadback, file, 3091, token, () -> true))
            .getMessage());
  }

  @Test
  public void readyPublicationForStaleGenerationWritesNothingAndChecksNothing() throws Exception {
    File root = root();
    File file = endpoint(root);
    BridgeEndpointFiles.publish(fs, file, 3090);
    // 代次已过期：不校验凭据（这里根本没有凭据）、也不写端口，更不能抛错。
    assertFalse(BridgeEndpointFiles.publishReady(fs, file, 3091, "", () -> false));
    assertEquals("3090\n", Files.readString(file.toPath()));
  }

  /**
   * 恢复切换数据根的行为回归：新根只有轮换后的凭据、还没有 {@code .port} 时，必须把活跃 run 的
   * 实际端口重发进新根；否则消费者会把它当成「缺文件」并回退到缺省 3090（很可能是别人的桥）。
   * 旧根已发布的端口不能被旧代次擦掉。
   */
  @Test
  public void rootSwitchRepublishesTheActivePortIntoTheNewRootInsteadOfLeavingItMissing()
      throws Exception {
    String oldToken = "A".repeat(32), newToken = "B".repeat(32);
    File oldRoot = root(), newRoot = root();
    BridgeCredentialFiles.publish(
        fs, new File(oldRoot, CredentialPaths.BRIDGE_TOKEN), oldToken, false);
    BridgeLifecycle lifecycle = new BridgeLifecycle();
    long generation = lifecycle.beginStart();
    assertTrue(
        BridgeEndpointFiles.publishReady(
            fs, endpoint(oldRoot), 3091, oldToken, () -> lifecycle.isCurrent(generation)));
    assertTrue(lifecycle.publish(generation));

    // 恢复提交把当前数据根切到新根：新根凭据已轮换，但机器端点不会被备份带上，于是没有 .port。
    BridgeCredentialFiles.publish(
        fs, new File(newRoot, CredentialPaths.BRIDGE_TOKEN), newToken, false);
    assertEquals(BridgeEndpointFiles.MISSING, BridgeEndpointFiles.read(fs, endpoint(newRoot)));

    // resetTokenAfterRestore 的修复路径：同一新根、正确代次下重发仍就绪的实际端口。
    assertTrue(
        BridgeEndpointFiles.publishReady(
            fs, endpoint(newRoot), 3091, newToken, () -> lifecycle.isCurrent(generation)));
    assertEquals(3091, BridgeEndpointFiles.read(fs, endpoint(newRoot)));
    assertNotEquals(
        BridgeEndpointFiles.LEGACY_DEFAULT, BridgeEndpointFiles.read(fs, endpoint(newRoot)));
    // 旧根保留自己的端口，没有被旧代次擦成 0。
    assertEquals(3091, BridgeEndpointFiles.read(fs, endpoint(oldRoot)));

    // 旧代次随后到达也不能擦掉新根刚重发的端口。
    long stale = generation;
    assertTrue(lifecycle.finish(generation));
    long newer = lifecycle.beginStart();
    assertFalse(
        BridgeEndpointFiles.publishReady(
            fs, endpoint(newRoot), 0, newToken, () -> lifecycle.isCurrent(stale)));
    assertEquals(3091, BridgeEndpointFiles.read(fs, endpoint(newRoot)));
    assertTrue(lifecycle.isCurrent(newer));
  }
}
