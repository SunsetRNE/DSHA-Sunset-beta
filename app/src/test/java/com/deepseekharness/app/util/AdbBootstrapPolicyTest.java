package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class AdbBootstrapPolicyTest {
  // 显式测试夹具，不是设备的实际 UID 或 APK 路径。
  private static final String PACKAGE = "com.dsh.client";
  private static final int UID = 10281;
  private static final String APK = "/data/app/com.dsh.client-fixture/base.apk";

  private static AdbBootstrapPolicy.Result inspect(String action, int port, int uid) {
    return AdbBootstrapPolicy.inspect("adb-bootstrap", action, port, PACKAGE, uid, PACKAGE, uid);
  }

  private static Map<String, Object> request() {
    Map<String, Object> fields = new HashMap<>();
    fields.put("operation", "adb-bootstrap");
    fields.put("action", "authorize");
    fields.put("selfPackage", PACKAGE);
    fields.put("selfUid", UID);
    return fields;
  }

  private static SelfPackageIdentity identity() throws IOException {
    return SelfPackageIdentity.launch(PACKAGE, UID, APK, APK);
  }

  private static void denied(AdbBootstrapPolicy.Result result) {
    assertFalse(result.allowed);
    assertNull(result.request);
    assertTrue(result.reason.startsWith("[POLICY_BLOCKED] "));
  }

  @Test
  public void acceptsFixedActionsAndDefaultPort() {
    for (AdbBootstrapPolicy.Action action : AdbBootstrapPolicy.Action.values()) {
      AdbBootstrapPolicy.Result result =
          inspect(action.name().toLowerCase(java.util.Locale.ROOT), 0, UID);
      assertTrue(action.name(), result.allowed);
      assertEquals(action, result.request.action);
      assertEquals(5555, result.request.port);
      assertEquals("", result.reason);
    }
    assertTrue(inspect("open", 5555, UID).allowed);
  }

  @Test
  public void rejectsNonCanonicalAndInjectedActions() {
    for (String action :
        new String[] {
          null,
          "",
          "OPEN",
          " open",
          "open ",
          "open\n",
          "open;id",
          "authorize --key=other",
          "setprop service.adb.tcp.port 5555"
        }) denied(inspect(action, 0, UID));
  }

  @Test
  public void rejectsArbitraryPortAndWrongOperation() {
    for (int port : new int[] {-1, 1, 1024, 5556, 65535, Integer.MAX_VALUE})
      denied(inspect("open", port, UID));
    denied(AdbBootstrapPolicy.inspect("generic", "open", 0, PACKAGE, UID, PACKAGE, UID));
    denied(AdbBootstrapPolicy.inspect(null, "open", 0, PACKAGE, UID, PACKAGE, UID));
  }

  @Test
  public void bindsRequestedPackageAndUidToInstalledIdentity() {
    denied(
        AdbBootstrapPolicy.inspect(
            "adb-bootstrap", "open", 0, "com.example.other", UID, PACKAGE, UID));
    denied(AdbBootstrapPolicy.inspect("adb-bootstrap", "open", 0, PACKAGE, UID + 1, PACKAGE, UID));
    denied(AdbBootstrapPolicy.inspect("adb-bootstrap", "open", 0, null, UID, PACKAGE, UID));
    denied(AdbBootstrapPolicy.inspect("adb-bootstrap", "open", 0, PACKAGE, UID, null, UID));
    denied(AdbBootstrapPolicy.inspect("adb-bootstrap", "open", 0, "bad", UID, "bad", UID));
  }

  @Test
  public void checksAppIdForSecondaryUsersAndRejectsIsolatedUid() {
    assertTrue(inspect("authorize", 0, 10000).allowed);
    assertTrue(inspect("authorize", 0, 19999).allowed);
    assertTrue(inspect("authorize", 0, 110281).allowed);
    for (int uid :
        new int[] {-1, 0, 2000, 9999, 20000, 99000, 100000, 102000, 199000, Integer.MAX_VALUE})
      denied(inspect("authorize", 0, uid));
  }

  @Test
  public void acceptsStrictMapWithOptionalPort() throws IOException {
    Map<String, Object> fields = request();
    assertTrue(AdbBootstrapPolicy.inspect(fields, identity()).allowed);
    fields.put("port", 5555);
    assertTrue(AdbBootstrapPolicy.inspect(fields, identity()).allowed);
    fields.put("port", 0);
    assertTrue(AdbBootstrapPolicy.inspect(fields, identity()).allowed);
  }

  @Test
  public void rejectsExtraAndMissingFields() throws IOException {
    for (String name : new String[] {"command", "path", "property", "pubkey", "expectedUid"}) {
      Map<String, Object> fields = request();
      fields.put(name, "id");
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
    Map<String, Object> withNullKey = request();
    withNullKey.put(null, "value");
    denied(AdbBootstrapPolicy.inspect(withNullKey, identity()));
    for (String name : request().keySet()) {
      Map<String, Object> fields = request();
      fields.remove(name);
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
    denied(AdbBootstrapPolicy.inspect(null, identity()));
    denied(AdbBootstrapPolicy.inspect(request(), null));
  }

  @Test
  public void rejectsCoercedNumbersAndNullFields() throws IOException {
    for (Object uid : new Object[] {null, "10281", 10281L, 10281.0, true}) {
      Map<String, Object> fields = request();
      fields.put("selfUid", uid);
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
    for (Object port : new Object[] {null, "5555", 5555L, 5555.0, true}) {
      Map<String, Object> fields = request();
      fields.put("port", port);
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
    for (String name : new String[] {"operation", "action", "selfPackage"}) {
      Map<String, Object> fields = request();
      fields.put(name, null);
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
    Map<String, Object> other = request();
    other.put("selfUid", UID + 1);
    denied(AdbBootstrapPolicy.inspect(other, identity()));
  }

  @Test
  public void openRequiresDualStackProtectionAndNativeGrants() {
    var requirements = AdbBootstrapPolicy.requirements(AdbBootstrapPolicy.Action.OPEN);
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.IPV4_AND_IPV6_LOOPBACK_GUARD));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.ADB_NATIVE_GRANT));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.BOOTSTRAP_NATIVE_GRANT));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.ROOT_NATIVE_GRANT));
  }

  @Test
  public void cleanupRemainsPossibleAfterDisablingAdbBootstrap() {
    var revoke = AdbBootstrapPolicy.requirements(AdbBootstrapPolicy.Action.REVOKE);
    assertTrue(revoke.contains(AdbBootstrapPolicy.Requirement.OWNED_KEY_ONLY));
    assertTrue(revoke.contains(AdbBootstrapPolicy.Requirement.KEY_TABLE_SNAPSHOT));
    for (var action :
        new AdbBootstrapPolicy.Action[] {
          AdbBootstrapPolicy.Action.CLOSE, AdbBootstrapPolicy.Action.REVOKE
        }) {
      var requirements = AdbBootstrapPolicy.requirements(action);
      assertFalse(requirements.contains(AdbBootstrapPolicy.Requirement.ADB_NATIVE_GRANT));
      assertFalse(requirements.contains(AdbBootstrapPolicy.Requirement.BOOTSTRAP_NATIVE_GRANT));
      assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.VERIFIED_APK_IDENTITY));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingActionIsNotAnEmptyPreconditionSet() {
    AdbBootstrapPolicy.requirements(null);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void requirementsCannotBeChangedByCaller() {
    AdbBootstrapPolicy.requirements(AdbBootstrapPolicy.Action.OPEN).clear();
  }

  @Test
  public void authorizeProtectsExistingListenerBeforeGrantingAccess() {
    var requirements = AdbBootstrapPolicy.requirements(AdbBootstrapPolicy.Action.AUTHORIZE);
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.IPV4_AND_IPV6_LOOPBACK_GUARD));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.OBSERVED_LISTENER_STATE));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.VERIFIED_CLIENT_PUBLIC_KEY));
    assertTrue(requirements.contains(AdbBootstrapPolicy.Requirement.KEY_TABLE_SNAPSHOT));
  }

  @Test
  public void allActionsRejectNonFixedPortsAndBindMapIdentity() throws IOException {
    for (var action : AdbBootstrapPolicy.Action.values()) {
      Map<String, Object> fields = request();
      fields.put("action", action.name().toLowerCase(java.util.Locale.ROOT));
      for (int port : new int[] {-1, 1024, 5556, 65535}) {
        fields.put("port", port);
        denied(AdbBootstrapPolicy.inspect(fields, identity()));
      }
      fields.put("port", 5555);
      fields.put("selfPackage", "com.example.other");
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
      fields.put("selfPackage", PACKAGE);
      fields.put("selfUid", UID + 100000);
      denied(AdbBootstrapPolicy.inspect(fields, identity()));
    }
  }
}
