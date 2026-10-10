package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class AdbdIdentityTest {
  @Test
  public void identifiesRootAdbdFromTheSamePidInBothViews() {
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess(
            "  PID UID NAME\n  9883 0 adbd\n",
            "u:r:ksu:s0 root 9883 1 11020396 9016 do_epoll_wait 0 S adbd\n");
    assertEquals(AdbdIdentity.Level.ROOT, evidence.level);
    assertEquals(9883, evidence.pid);
    assertEquals(0, evidence.uid);
    assertEquals("u:r:ksu:s0", evidence.selinuxContext);
  }

  @Test
  public void acceptsExplicitPidContextNameRows() {
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess("4012 2000 adbd\n", "4012 u:r:adbd:s0 adbd\n");
    assertEquals(AdbdIdentity.Level.SHELL, evidence.level);
    assertEquals(4012, evidence.pid);
    assertEquals(2000, evidence.uid);
    assertEquals("u:r:adbd:s0", evidence.selinuxContext);
  }

  @Test
  public void identifiesRegularShellAdbdFromHistoricalPsZShape() {
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess("  4012 2000 adbd\n", "u:r:adbd:s0 shell 4012 1 0 0 epoll 0 S adbd\n");
    assertEquals(AdbdIdentity.Level.SHELL, evidence.level);
    assertEquals(4012, evidence.pid);
    assertEquals(2000, evidence.uid);
  }

  @Test
  public void preservesUnknownForUnexpectedAdbdUid() {
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess("4012 1234 adbd\n", "4012 u:r:vendor_adbd:s0 adbd\n");
    assertEquals(AdbdIdentity.Level.UNKNOWN, evidence.level);
    assertEquals(4012, evidence.pid);
    assertEquals(1234, evidence.uid);
    assertEquals("u:r:vendor_adbd:s0", evidence.selinuxContext);
  }

  @Test
  public void requiresSuccessfulEvidenceCommands() {
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd\n", 1, "4012 u:r:ksu:s0 adbd\n", 0).level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd\n", 0, "4012 u:r:ksu:s0 adbd\n", 124).level);
    assertEquals(
        AdbdIdentity.Level.ROOT,
        AdbdIdentity.assess("4012 0 adbd\n", 0, "4012 u:r:ksu:s0 adbd\n", 0).level);
  }

  @Test
  public void unknownIsStickyForMissingMismatchedOrDuplicateEvidence() {
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd", "u:r:ksu:s0 root 9999 1 x adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd", "4012 not-a-context adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd\n4013 0 adbd", "4012 u:r:ksu:s0 adbd\n").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 other", "4012 u:r:ksu:s0 adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd", "4012 u:r:ksu:s0 adbd\n4012 u:r:adbd:s0 adbd").level);
  }

  @Test
  public void doesNotTreatCommandArgumentsContainingAdbdAsTheProcessName() {
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 sh\n", "u:r:ksu:s0 root 4012 1 0 0 sh -c adbd\n").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd extra\n", "4012 u:r:ksu:s0 adbd\n").level);
  }

  @Test
  public void acceptsMlsRangeAndCategoryListContexts() {
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess("4012 0 adbd\n", "4012 u:r:ksu:s0-s0:c512,c768 adbd\n");
    assertEquals(AdbdIdentity.Level.ROOT, evidence.level);
    assertEquals("u:r:ksu:s0-s0:c512,c768", evidence.selinuxContext);
  }

  @Test
  public void rejectsClientIdOutputAndMalformedControlData() {
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("uid=0(root) gid=0(root)", "uid=0(root) gid=0(root)").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd\u0000", "4012 u:r:ksu:s0 adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 0 adbd\u007f", "4012 u:r:ksu:s0 adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN, AdbdIdentity.assess(null, "4012 u:r:ksu:s0 adbd").level);
    assertEquals(AdbdIdentity.Level.UNKNOWN, AdbdIdentity.assess("4012 0 adbd\n", "").level);
  }

  @Test
  public void rejectsTruncationFailureMarkersAndUnsupportedHeaders() {
    for (String uid :
        new String[] {
          "4012 2000 adbd\n[OUTPUT_TRUNCATED] 输出截断",
          "PID PPID NAME\n4012 2000 adbd",
          "PID GID NAME\n4012 0 adbd",
          "4012 2000 adbd\n[ROOT_EXEC_FAILED] failed",
          "4012 2000 adbd\nps: bad option",
          "4012 2000 adbd\n[EXIT=0]",
          "4012 2000 adbd\n4013 0 adbd\u007f",
          "4012 2000 adbd\n\u0085",
          "4012 2000 adbd\rhidden",
          "PID UID NAME\nPID UID NAME\n4012 2000 adbd"
        }) {
      assertEquals(
          uid,
          AdbdIdentity.Level.UNKNOWN,
          AdbdIdentity.assess(uid, 0, "4012 u:r:adbd:s0 adbd", 0).level);
    }
    for (String context :
        new String[] {
          "PID UID NAME\n4012 u:r:adbd:s0 adbd",
          "4012 u:r:adbd:s0 adbd\n[OUTPUT_TRUNCATED]",
          "4012 u:r:adbd:s0 adbd\n4013 u:r:ksu:s0 adbd\u007f"
        }) {
      assertEquals(
          context,
          AdbdIdentity.Level.UNKNOWN,
          AdbdIdentity.assess("4012 2000 adbd", 0, context, 0).level);
    }
  }

  @Test
  public void rejectsConflictingHistoricalUserAndDuplicatePid() {
    for (String user : new String[] {"root", "0", "unknown"}) {
      assertEquals(
          AdbdIdentity.Level.UNKNOWN,
          AdbdIdentity.assess("4012 2000 adbd", "u:r:ksu:s0 " + user + " 4012 1 0 0 epoll 0 S adbd")
              .level);
    }
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 2000 adbd\n4012 0 sh", "4012 u:r:adbd:s0 adbd").level);
    assertEquals(
        AdbdIdentity.Level.UNKNOWN,
        AdbdIdentity.assess("4012 2000 adbd", "4012 u:r:adbd:s0 adbd\n4012 u:r:shell:s0 sh").level);
  }

  @Test
  public void acceptsCanonicalMlsBothEndpointsAndOtherProcesses() {
    String context = "u:r:ksu:s0:c0.c1023-s1:c0.c1023";
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess(
            "PID UID NAME\r\n1 0 init\r\n4012 0 adbd\r\n",
            "PID CONTEXT NAME\r\n1 u:r:init:s0 init\r\n4012 " + context + " adbd\r\n");
    assertEquals(AdbdIdentity.Level.ROOT, evidence.level);
    assertEquals(context, evidence.selinuxContext);
  }

  @Test
  public void rejectsOversizeOverflowAndZeroPidEvidence() {
    for (String uid :
        new String[] {
          " ".repeat(262145),
          "0 0 adbd",
          "2147483648 0 adbd",
          "4012 2147483648 adbd",
          "4012 -1 adbd",
          "-1 0 adbd"
        }) {
      assertEquals(
          AdbdIdentity.Level.UNKNOWN, AdbdIdentity.assess(uid, "4012 u:r:ksu:s0 adbd").level);
    }
    assertEquals(
        AdbdIdentity.Level.UNKNOWN, AdbdIdentity.assess("4012 0 adbd", " ".repeat(262145)).level);
  }

  @Test
  public void absenceRequiresCompleteFixedTables() {
    String uid = "PID UID NAME\n1 0 init\n";
    String label = "PID CONTEXT NAME\n1 u:r:init:s0 init\n";
    assertTrue(AdbdIdentity.absent(uid, label));
    for (String bad :
        new String[] {"", "permission denied", "PID UID NAME\ntruncated", uid + "321 0 adbd\n"})
      assertFalse(AdbdIdentity.absent(bad, label));
    assertFalse(AdbdIdentity.absent(uid, label + "321 u:r:adbd:s0 adbd\n"));
  }
}
