package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AdbBootstrapEngineTest {
  private static final class Device implements AdbBootstrapEngine.Host {
    final List<String> calls = new ArrayList<>();
    String fail = "", pending = "";
    boolean grant = true, denyAfterGuard;

    private void hit(String name) throws Exception {
      calls.add(name);
      if (name.equals(fail)) throw new IOException("TEST_RESULT_UNKNOWN");
    }

    public void checkGrant(AdbBootstrapPolicy.Action action) throws Exception {
      hit("grant");
      if (!grant) throw new AdbBootstrapEngine.Refused("NATIVE_GRANT_REVOKED");
    }

    public void checkPending(AdbBootstrapPolicy.Action action) throws Exception {
      hit("pending");
      if (!pending.isEmpty()) throw new AdbBootstrapEngine.Refused("PENDING");
    }

    public void narrow() throws Exception {
      hit("narrow");
    }

    public void verifyGuard() throws Exception {
      hit("guard");
      if (denyAfterGuard) grant = false;
    }

    public void authorize() throws Exception {
      hit("authorize");
    }

    public void verifyKey() throws Exception {
      hit("key");
    }

    public void open() throws Exception {
      hit("open");
    }

    public void close() throws Exception {
      hit("close");
    }

    public void revoke() throws Exception {
      hit("revoke");
    }

    public AdbBootstrapEngine.Snapshot observe() throws Exception {
      hit("observe");
      return new AdbBootstrapEngine.Snapshot("OWNED", "CLOSED", "PROTECTED");
    }
  }

  @Test
  public void protectionPrecedesAuthorizationAndOpening() {
    Device device = new Device();
    assertEquals(
        "OK", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.AUTHORIZE, device).status);
    assertTrue(device.calls.indexOf("guard") < device.calls.indexOf("authorize"));
    device = new Device();
    assertEquals("OK", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.OPEN, device).status);
    assertTrue(device.calls.indexOf("key") < device.calls.indexOf("open"));
    assertTrue(device.calls.indexOf("guard") < device.calls.indexOf("open"));
    assertTrue(device.calls.lastIndexOf("guard") > device.calls.indexOf("open"));
  }

  @Test
  public void unknownMutationIsNotReplayedOrSilentlyCompensated() {
    for (String failed : new String[] {"narrow", "guard", "open", "observe"}) {
      Device device = new Device();
      device.fail = failed;
      var outcome = AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.OPEN, device);
      assertEquals("UNKNOWN", outcome.status);
      assertEquals("UNKNOWN", outcome.snapshot.listener);
      assertEquals(1, java.util.Collections.frequency(device.calls, failed));
      assertFalse(device.calls.contains("close"));
      assertFalse(device.calls.contains("revoke"));
    }
  }

  @Test
  public void revokedGrantCannotAuthorizeAfterGuardSetup() {
    Device device = new Device();
    device.denyAfterGuard = true;
    assertEquals(
        "UNKNOWN", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.AUTHORIZE, device).status);
    assertFalse(device.calls.contains("authorize"));
  }

  @Test
  public void refusedAndPendingRequestsPerformNoMutation() {
    Device device = new Device();
    device.grant = false;
    assertEquals(
        "REFUSED", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.OPEN, device).status);
    assertEquals(List.of("grant"), device.calls);
    device = new Device();
    device.pending = "OPEN";
    assertEquals(
        "REFUSED", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.OPEN, device).status);
    assertEquals(List.of("grant", "pending"), device.calls);
  }

  @Test
  public void statusIsReadOnlyAndCleanupDoesNotReauthorize() {
    Device device = new Device();
    assertEquals("OK", AdbBootstrapEngine.execute(AdbBootstrapPolicy.Action.STATUS, device).status);
    assertEquals(List.of("grant", "pending", "observe"), device.calls);
    for (var action :
        new AdbBootstrapPolicy.Action[] {
          AdbBootstrapPolicy.Action.CLOSE, AdbBootstrapPolicy.Action.REVOKE
        }) {
      device = new Device();
      assertEquals("OK", AdbBootstrapEngine.execute(action, device).status);
      assertFalse(device.calls.contains("authorize"));
      assertFalse(device.calls.contains("open"));
      assertFalse(device.calls.contains("narrow"));
    }
  }

  @Test
  public void firewallRuleMustBeFirstAndOwnedAcrossBothFamilies() {
    String rule =
        "-A INPUT ! -i lo -p tcp -m tcp --dport 5555 -m comment --comment dsha-bootstrap-10281 -j DROP\n";
    assertTrue(
        AdbBootstrapEngine.firstRuleProtects("-P INPUT ACCEPT\n" + rule, "dsha-bootstrap-10281"));
    assertFalse(
        AdbBootstrapEngine.firstRuleProtects(
            "-A INPUT -j ACCEPT\n" + rule, "dsha-bootstrap-10281"));
    assertFalse(AdbBootstrapEngine.firstRuleProtects(rule, "dsha-bootstrap-10282"));
    assertFalse(
        AdbBootstrapEngine.firstRuleProtects(rule.replace("5555", "5556"), "dsha-bootstrap-10281"));
  }

  private static String header() {
    return "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
  }

  @Test
  public void requiresBothNetworkTablesAndTracksRemainingConnections() throws Exception {
    String row = "0: 00000000:15B3 00000000:0000 0A 0000:0000 00:0000 0000 0 0 321\n";
    assertEquals(
        "OPEN",
        AdbBootstrapEngine.listener(
            header(), header() + row.replace("00000000:", "00000000000000000000000000000000:")));
    assertEquals(
        "CONNECTIONS_REMAIN",
        AdbBootstrapEngine.listener(header() + row.replace(" 0A ", " 01 "), header()));
    assertEquals("CLOSED", AdbBootstrapEngine.listener(header(), header()));
    for (String bad : new String[] {"", "permission denied", header() + "truncated"}) {
      try {
        AdbBootstrapEngine.listener(header() + row, bad);
        fail();
      } catch (IOException expected) {
      }
    }
  }
}
