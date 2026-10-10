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

  @Test
  public void failedOrIncompleteRefreshRetainsUnresolvedRequest() {
    for (String result : new String[] {"FAILED", "REFUSED", "UNKNOWN"})
      assertTrue(
          AdbBootstrapJournal.unresolved(
              true, result, "BOOTSTRAP_BUSY", "UNKNOWN", "UNKNOWN", "UNVERIFIED"));
    assertTrue(
        AdbBootstrapJournal.unresolved(
            true, "OK", "PENDING_ACTION", "ABSENT", "CLOSED", "UNPROTECTED"));
    assertTrue(
        AdbBootstrapJournal.unresolved(
            true, "OK", "OBSERVATION_INCOMPLETE", "UNKNOWN", "CLOSED", "UNPROTECTED"));
    assertFalse(
        AdbBootstrapJournal.unresolved(true, "OK", "COMPLETE", "ABSENT", "CLOSED", "UNPROTECTED"));
    assertFalse(
        AdbBootstrapJournal.unresolved(
            false, "REFUSED", "NATIVE_GRANT_REVOKED", "UNKNOWN", "UNKNOWN", "UNKNOWN"));
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
