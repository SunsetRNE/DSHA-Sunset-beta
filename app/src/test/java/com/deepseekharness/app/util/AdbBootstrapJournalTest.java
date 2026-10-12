package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class AdbBootstrapJournalTest {
  private Map<String, Object> record() {
    Map<String, Object> value = new HashMap<>();
    value.put("schema", 1);
    value.put("package", "com.dsh.client");
    value.put("uid", 10281);
    return value;
  }

  private void rejects(Map<String, Object> value) {
    try {
      AdbBootstrapJournal.validate(value, "com.dsh.client", 10281);
      fail("expected invalid journal");
    } catch (IOException expected) {
      assertEquals("BOOTSTRAP_JOURNAL_INVALID", expected.getMessage());
    }
  }

  @Test
  public void acceptsEmptyOwnedStateWithoutInferringResources() throws Exception {
    AdbBootstrapJournal.validate(record(), "com.dsh.client", 10281);
    var value = record();
    value.put("keyOwned", false);
    value.put("portOwned", false);
    AdbBootstrapJournal.validate(value, "com.dsh.client", 10281);
  }

  @Test
  public void rejectsCoercionForeignOwnerAndUnknownFields() {
    for (String field :
        new String[] {"schema", "uid", "keyOwned", "portOwned", "ipv4Owned", "ipv6Owned"}) {
      var value = record();
      value.put(field, "1");
      rejects(value);
    }
    var value = record();
    value.put("uid", 10281.0);
    rejects(value);
    value = record();
    value.put("package", "com.example.other");
    rejects(value);
    value = record();
    value.put("arbitraryCommand", "id");
    rejects(value);
    value = record();
    value.put("keyOwned", true);
    rejects(value);
  }

  @Test
  public void rejectsUnprovenPendingAndBootOwnership() {
    for (String phase : new String[] {"AUTHORIZE", "REVOKE", "REVOKE_RESTART_SENT", "EXEC_ANY"}) {
      var value = record();
      value.put("keyPending", phase);
      rejects(value);
    }
    var value = record();
    value.put("portOwned", true);
    rejects(value);
    value = record();
    value.put("portBoot", "------------------------------------");
    rejects(value);
    value = record();
    value.put("keyBackup", "../adb_keys");
    rejects(value);
  }

  @Test
  public void removedKeyDoesNotTriggerDeletionAfterInterruption() {
    assertTrue(AdbBootstrapJournal.keyAlreadyRemoved("REVOKE", 0));
    assertTrue(AdbBootstrapJournal.keyAlreadyRemoved("REVOKE_RESTART_SENT", 0));
    assertFalse(AdbBootstrapJournal.keyAlreadyRemoved("AUTHORIZE", 0));
    assertFalse(AdbBootstrapJournal.keyAlreadyRemoved("REVOKE", 1));
    assertFalse(AdbBootstrapJournal.keyAlreadyRemoved("REVOKE", 2));
  }

  @Test
  public void unknownRestartNeverBecomesASecondSend() {
    assertTrue(AdbBootstrapJournal.restartMayBeSent("REVOKE"));
    for (String phase : new String[] {null, "", "REVOKE_RESTART_SENT", "AUTHORIZE", "NARROW"})
      assertFalse(AdbBootstrapJournal.restartMayBeSent(phase));
  }

  private static AdbBootstrapReply reply(
      String status,
      String code,
      String key,
      String listener,
      String guard,
      boolean pendingVerified,
      String networkPending,
      String keyPending) {
    return AdbBootstrapReply.parse(
        AdbBootstrapReply.encode(
            status, code, key, listener, guard, "", pendingVerified, networkPending, keyPending));
  }

  @Test
  public void onlyATrustedClearedObservationReleasesThePendingBarrier() {
    // 可信 + 两阶段都已结束 + 观测完整 + 成功：解除。
    assertFalse(
        AdbBootstrapJournal.unresolved(
            true,
            reply("OK", "COMPLETE", "ABSENT", "CLOSED", "UNPROTECTED", true, "", ""),
            "OK",
            "COMPLETE"));
    // F03：无操作 REVOKE 成功，但持久记录里网络阶段仍未结束 —— 不得解除。
    assertTrue(
        AdbBootstrapJournal.unresolved(
            false,
            reply("OK", "COMPLETE", "NOT_REGISTERED", "CLOSED", "UNPROTECTED", true, "NARROW", ""),
            "OK",
            "COMPLETE"));
    assertTrue(
        AdbBootstrapJournal.unresolved(
            false,
            reply("OK", "COMPLETE", "OWNED", "CLOSED", "UNPROTECTED", true, "", "AUTHORIZE"),
            "OK",
            "COMPLETE"));
    // 观测不完整或未核实时也不解除。
    assertTrue(
        AdbBootstrapJournal.unresolved(
            false,
            reply("OK", "OBSERVATION_INCOMPLETE", "UNKNOWN", "CLOSED", "UNPROTECTED", true, "", ""),
            "OK",
            "OBSERVATION_INCOMPLETE"));
    assertTrue(
        AdbBootstrapJournal.unresolved(
            true,
            reply("OK", "COMPLETE", "ABSENT", "CLOSED", "UNPROTECTED", false, "", ""),
            "OK",
            "COMPLETE"));
    assertTrue(
        AdbBootstrapJournal.unresolved(
            false,
            reply(
                "OK",
                "PENDING_ACTION",
                "NOT_REGISTERED",
                "CLOSED",
                "UNPROTECTED",
                true,
                "NARROW",
                ""),
            "OK",
            "PENDING_ACTION"));
  }

  @Test
  public void failedOrIncompleteRefreshRetainsUnresolvedRequest() {
    // 原生异常（不可信响应）：沿用结果码判据，可能已写入的未知结果保持未决。
    for (String result : new String[] {"FAILED", "REFUSED", "UNKNOWN"})
      assertTrue(AdbBootstrapJournal.unresolved(true, null, result, "BOOTSTRAP_BUSY"));
    assertFalse(AdbBootstrapJournal.unresolved(false, null, "REFUSED", "NATIVE_GRANT_REVOKED"));
    assertTrue(AdbBootstrapJournal.unresolved(false, null, "UNKNOWN", "REQUEST_OUTCOME_UNKNOWN"));
    // 旧格式 / 缺字段响应不得解除已有未决。
    assertTrue(
        AdbBootstrapJournal.unresolved(
            true,
            reply("OK", "COMPLETE", "ABSENT", "CLOSED", "UNPROTECTED", false, "", ""),
            "OK",
            "COMPLETE"));
    // 可信但失败：保留原状态，不新增也不清除。
    assertTrue(
        AdbBootstrapJournal.unresolved(
            true,
            reply(
                "REFUSED", "WIRELESS_DEBUG_IN_USE", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, "", ""),
            "REFUSED",
            "WIRELESS_DEBUG_IN_USE"));
    assertFalse(
        AdbBootstrapJournal.unresolved(
            false,
            reply(
                "REFUSED", "WIRELESS_DEBUG_IN_USE", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, "", ""),
            "REFUSED",
            "WIRELESS_DEBUG_IN_USE"));
  }

  @Test
  public void reappearingKeyDoesNotResetRestartSentPhase() throws Exception {
    AdbBootstrapJournal.checkRevokeRecovery("REVOKE_RESTART_SENT", 0);
    for (int count : new int[] {1, 2}) {
      try {
        AdbBootstrapJournal.checkRevokeRecovery("REVOKE_RESTART_SENT", count);
        fail();
      } catch (IOException expected) {
        assertEquals("KEY_REAPPEARED_AFTER_REVOKE", expected.getMessage());
      }
    }
  }
}
