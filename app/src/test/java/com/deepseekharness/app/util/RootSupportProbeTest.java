package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class RootSupportProbeTest {
  @Test
  public void acceptsExecutorTrailerAndNumericUid() {
    RootSupportProbe probe =
        RootSupportProbe.parse(
            "uid=0(root) gid=0(root) groups=0(root),1004(input) context=u:r:ksu:s0\n\n[EXIT=0]\n");
    assertTrue(probe.succeeded());
    assertEquals(AdbdIdentity.Level.ROOT, probe.executionLevel());
    assertEquals(
        AdbdIdentity.Level.SHELL,
        RootSupportProbe.parse("uid=2000 gid=2000\r\n[EXIT=0]\r\n").executionLevel());
  }

  @Test
  public void rejectsIncompleteTruncatedAndMultipleTrailers() {
    for (String output :
        new String[] {
          null,
          "",
          "uid=0(root) gid=0(root)",
          "uid=0(root) gid=0(root)[EXIT=0]",
          "uid=0(root) gid=0(root)\n[EXIT=256]",
          "uid=0(root) gid=0(root)\n[EXIT=timeout]",
          "uid=0(root) gid=0(root)\n[EXIT=interrupted]",
          "uid=0(root) gid=0(root)\n[OUTPUT_TRUNCATED] x\n[EXIT=0]",
          "uid=0(root) gid=0(root)\n[EXECUTION_UNKNOWN] x\n[EXIT=0]",
          "uid=0(root) gid=0(root)\n[POLICY_BLOCKED] x\n[EXIT=0]",
          "uid=0(root) gid=0(root)\n[EXIT=1]\n[EXIT=0]",
          "uid=0(root) gid=0(root)\n[EXIT=0]\nextra"
        }) {
      RootSupportProbe probe = RootSupportProbe.parse(output);
      assertFalse(String.valueOf(output), probe.succeeded());
      assertEquals(-1, probe.exitCode);
      assertEquals("", probe.body);
    }
  }

  @Test
  public void preservesNonzeroExitWithoutTrustingBody() {
    RootSupportProbe probe = RootSupportProbe.parse("uid=0(root) gid=0(root)\n[EXIT=126]");
    assertEquals(126, probe.exitCode);
    assertEquals(AdbdIdentity.Level.UNKNOWN, probe.executionLevel());
  }

  @Test
  public void rejectsAmbiguousIdAndErrorText() {
    for (String body :
        new String[] {
          "error uid=0(root) gid=0(root)",
          "uid=2000(shell) gid=2000(shell)\nuid=0(root) gid=0(root)",
          "euid=0(root) gid=0(root)",
          "uid=0(root)",
          "uid=1234 gid=1234",
          "uid=0(root) gid=0(root)\rerror",
          "uid=0(root) gid=0(root)\u007f",
          "uid=0(root) gid=0(root)\u0085"
        }) {
      assertEquals(
          body,
          AdbdIdentity.Level.UNKNOWN,
          RootSupportProbe.parse(body + "\n[EXIT=0]").executionLevel());
    }
  }

  @Test
  public void rejectsOversizedOutput() {
    assertFalse(RootSupportProbe.parse("x".repeat(270001) + "\n[EXIT=0]").succeeded());
  }

  @Test
  public void incompleteIdentityCannotBecomeOverallPass() {
    for (String state : new String[] {null, "UNKNOWN", "UNAVAILABLE", "UNVERIFIED", "FAIL"}) {
      assertEquals("INCOMPLETE", RootSupportProbe.auditResult(true, state));
      assertEquals("PASS", RootSupportProbe.auditResult(false, state));
    }
    assertEquals("PASS", RootSupportProbe.auditResult(true, "PASS"));
  }
}
