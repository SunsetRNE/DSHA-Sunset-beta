package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 自举响应编码/解码接缝的协议与兼容规则；不启动 Android、不执行设备命令。 */
public final class AdbBootstrapReplyTest {
  private static final String COMPLETE_KEY = "ABSENT";

  @Test
  public void roundTripCarriesBothPendingPhasesAndVerification() {
    AdbBootstrapReply reply =
        AdbBootstrapReply.parse(
            AdbBootstrapReply.encode(
                "OK",
                "PENDING_ACTION",
                "NOT_REGISTERED",
                "CLOSED",
                "UNPROTECTED",
                "evidence",
                true,
                "NARROW",
                "AUTHORIZE"));
    assertTrue(reply.trusted);
    assertEquals("OK", reply.status);
    assertEquals("PENDING_ACTION", reply.code);
    assertTrue(reply.pendingVerified);
    assertEquals("NARROW", reply.networkPending);
    assertEquals("AUTHORIZE", reply.keyPending);
    assertFalse(reply.pendingCleared());
    assertTrue(reply.observationComplete());
  }

  @Test
  public void trustedClearedReplyReportsCompleteButUnprotectedObservation() {
    AdbBootstrapReply reply =
        AdbBootstrapReply.parse(
            AdbBootstrapReply.encode(
                "OK", "COMPLETE", COMPLETE_KEY, "CLOSED", "UNPROTECTED", "", true, "", ""));
    assertTrue(reply.trusted);
    assertTrue(reply.pendingCleared());
    assertTrue(reply.observationComplete());
  }

  @Test
  public void legacyReplyWithoutPendingFieldsIsNeverTrusted() {
    // 旧版原生的 OK 响应：没有 pendingVerified/networkPending/keyPending。
    String legacy =
        "{\"status\":\"OK\",\"code\":\"COMPLETE\",\"keyState\":\"ABSENT\","
            + "\"listenerState\":\"CLOSED\",\"guardState\":\"UNPROTECTED\",\"evidence\":\"\"}\n[EXIT=0]";
    AdbBootstrapReply reply = AdbBootstrapReply.parse(legacy);
    assertFalse(reply.trusted);
    assertEquals(AdbBootstrapReply.PROTOCOL_INVALID, reply.code);
    assertFalse(reply.pendingCleared());
  }

  @Test
  public void missingOrMistypedFieldsAreProtocolInvalid() {
    String[] bodies = {
      // 缺 networkPending / keyPending。
      "{\"status\":\"OK\",\"code\":\"COMPLETE\",\"keyState\":\"ABSENT\",\"listenerState\":\"CLOSED\","
          + "\"guardState\":\"UNPROTECTED\",\"evidence\":\"\",\"pendingVerified\":true}",
      // pendingVerified 类型不符。
      "{\"status\":\"OK\",\"code\":\"COMPLETE\",\"keyState\":\"ABSENT\",\"listenerState\":\"CLOSED\","
          + "\"guardState\":\"UNPROTECTED\",\"evidence\":\"\",\"pendingVerified\":\"yes\"}",
      // 缺 guardState。
      "{\"status\":\"OK\",\"code\":\"COMPLETE\",\"keyState\":\"ABSENT\",\"listenerState\":\"CLOSED\","
          + "\"evidence\":\"\",\"pendingVerified\":false}",
      // 未决阶段超出允许集合。
      "{\"status\":\"OK\",\"code\":\"COMPLETE\",\"keyState\":\"ABSENT\",\"listenerState\":\"CLOSED\","
          + "\"guardState\":\"UNPROTECTED\",\"evidence\":\"\",\"pendingVerified\":true,"
          + "\"networkPending\":\"EXEC_ANY\",\"keyPending\":\"\"}"
    };
    for (String body : bodies) {
      AdbBootstrapReply reply = AdbBootstrapReply.parse(body + "\n[EXIT=0]");
      assertFalse(body, reply.trusted);
      assertEquals(AdbBootstrapReply.PROTOCOL_INVALID, reply.code);
    }
  }

  @Test
  public void statusAndExitCodeMustAgree() {
    // OK 却带 125：旧实现会当成结果未知，这里明确判为协议不可信。
    AdbBootstrapReply reply =
        AdbBootstrapReply.parse(
            AdbBootstrapReply.encode(
                    "OK", "COMPLETE", COMPLETE_KEY, "CLOSED", "UNPROTECTED", "", true, "", "")
                .replace("[EXIT=0]", "[EXIT=125]"));
    assertFalse(reply.trusted);
    assertEquals(AdbBootstrapReply.PROTOCOL_INVALID, reply.code);
  }

  @Test
  public void unknownProtocolMarkersAndGarbageStayUnparseable() {
    for (String output :
        new String[] {
          "",
          "[ROOT_EXEC_FAILED] su 超时\n[EXIT=125]",
          "[EXECUTION_UNKNOWN] 命令可能已执行",
          "{\"status\":\"OK\"}\n[EXIT=0]"
        }) {
      AdbBootstrapReply reply = AdbBootstrapReply.parse(output);
      assertFalse(reply.trusted);
      assertFalse(reply.pendingCleared());
      assertTrue(
          "证据必须中性化保留标记：" + reply.evidence,
          !reply.evidence.contains("[EXIT=") && !reply.evidence.contains("[EXECUTION_UNKNOWN]"));
    }
  }

  @Test
  public void encodeRejectsNoCallerSuppliedExitCode() {
    // 编码只由 status 推导退出码：REFUSED → 126，UNKNOWN → 125。
    assertTrue(
        AdbBootstrapReply.parse(
                AdbBootstrapReply.encode(
                    "REFUSED", "X", "UNKNOWN", "UNKNOWN", "UNKNOWN", "", false, "", ""))
            .trusted);
    assertTrue(
        AdbBootstrapReply.parse(
                AdbBootstrapReply.encode(
                    "UNKNOWN", "X", "UNKNOWN", "UNKNOWN", "UNKNOWN", "", false, "", ""))
            .trusted);
  }
}
