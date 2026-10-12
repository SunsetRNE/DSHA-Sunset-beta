package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CredentialPathsTest {
  @Test
  public void realMachineNamesAndDerivedAtomicFilesArePrivate() {
    for (String name :
        new String[] {
          ".bridge_token",
          ".bridge_headers",
          ".bridge_token.retained-fixture",
          ".bridge_token.previous",
          ".bridge_status.tmp-fixture",
          ".anonymous-user-id"
        }) {
      assertTrue(name, CredentialPaths.machine(name));
      assertTrue(BridgePathPolicy.denied("/root/work/" + name));
    }
  }

  @Test
  public void bridgePortEndpointIsMachineStateAndDerivedAtomicsStayPrivate() {
    assertEquals(".bridge_port", CredentialPaths.BRIDGE_PORT);
    for (String name :
        new String[] {".bridge_port", ".bridge_port.atomic-9", ".bridge_port.previous"}) {
      assertTrue(name, CredentialPaths.machine(name));
      assertTrue(name, CredentialPaths.backupMachine(name));
      assertTrue(name, BridgePathPolicy.denied("/root/work/" + name));
    }
    // 端口端点是机器状态（因此凭据判据也拒绝导出），但不是 externalCredentials
    // 里的用户凭据记录；也不能把同名项目内路径误判成机器状态。
    assertTrue(CredentialPaths.credentialName(".bridge_port"));
    assertFalse(CredentialPaths.credentialName(".bridge-port"));
    assertFalse(CredentialPaths.machine(".bridge-port"));
    assertFalse(CredentialPaths.backupMachine(".bridge-port"));
    assertFalse(CredentialPaths.machine("my-project/.bridge_port"));
  }

  @Test
  public void consumerScopesRemainDistinctAfterGeneratedTableAdoption() {
    assertTrue(CredentialPaths.backupMachine(".dsha-web.identity"));
    assertFalse(CredentialPaths.machine(".dsha-web.identity"));
    assertTrue(CredentialPaths.credentialName(".env"));
    assertFalse(CredentialPaths.backupMachine(".env"));
    assertTrue(com.deepseekharness.app.backup.DataRootPolicy.directData(".env"));
    assertEquals(
        "RETAINED_SESSION_TRASH",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "sessions", ".sessions-trash/file"));
    assertEquals(
        "",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "storages", ".sessions-trash/file"));
    assertEquals(
        "",
        com.deepseekharness.app.backup.DataRootPolicy.exclusion(
            "sessions", "project/.sessions-trash/file"));
  }

  @Test
  public void credentialBasenamesAndRuntimeNamePrefixDoNotUseDirectoryPrefixMatching() {
    for (String path :
        new String[] {
          "/root/.dsha-web.pid",
          "/root/.dsha-web.identity",
          "/root/.dsha-report.txt",
          "/root/work/.env",
          "/sdcard/project/.env.local",
          "/root/work/.credentials.yaml"
        }) assertTrue(path, BridgePathPolicy.denied(path));
    assertFalse(BridgePathPolicy.denied("/root/.dsh-backup-note.md"));
    assertFalse(BridgePathPolicy.denied("/root/report.md"));
  }
}
