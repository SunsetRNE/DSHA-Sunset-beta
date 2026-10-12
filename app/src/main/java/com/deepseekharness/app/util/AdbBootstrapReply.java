package com.deepseekharness.app.util;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 自举原生响应的唯一编码 / 解码接缝。
 *
 * <p>原生侧与宿主侧过去各自按需读字段：原生的 {@code PENDING_ACTION} 只对 STATUS 外传，
 * 于是「无操作 REVOKE 成功」会把一个仍然持久存在的网络未决事务洗成已确认（F03）。
 * 这里把两件事拆开、并且**每个动作都返回**：
 *
 * <ul>
 *   <li>{@code pendingVerified}：本次响应是否真的核实了持久未决状态；
 *   <li>{@code networkPending} / {@code keyPending}：网络事务与公钥事务各自的未决阶段。
 * </ul>
 *
 * <p>解码是保守的：退出码与状态不一致、缺字段、字段类型不符、阶段值不在允许集合内，一律
 * 返回 {@code trusted=false}，由宿主保持原来的未知提示，绝不当成「已经确认清理完」。
 * 旧版原生响应（没有这些字段）同样解码为不可信 —— 兼容方向是「不解除屏障」，不是相反。
 */
public final class AdbBootstrapReply {
  /** 不可信时使用的失败码：响应根本不是可解析的自举 JSON。 */
  public static final String UNPARSEABLE = "RESULT_UNPARSEABLE";

  /** 不可信时使用的失败码：解析成功但状态 / 退出码 / 字段组合不符合协议。 */
  public static final String PROTOCOL_INVALID = "RESULT_PROTOCOL_INVALID";

  public final boolean trusted;
  public final String status;
  public final String code;
  public final String keyState;
  public final String listenerState;
  public final String guardState;
  public final String evidence;

  /** 持久未决状态是否已核实；false 表示未知，不能据此解除屏障。 */
  public final boolean pendingVerified;

  /** 网络事务未决阶段：""、NARROW、OPEN、CLOSE、CLOSE_RESTART_SENT。 */
  public final String networkPending;

  /** 公钥事务未决阶段：""、AUTHORIZE、REVOKE、REVOKE_RESTART_SENT。 */
  public final String keyPending;

  private AdbBootstrapReply(
      boolean trusted,
      String status,
      String code,
      String keyState,
      String listenerState,
      String guardState,
      String evidence,
      boolean pendingVerified,
      String networkPending,
      String keyPending) {
    this.trusted = trusted;
    this.status = status == null ? "" : status;
    this.code = code == null ? "" : code;
    this.keyState = keyState == null ? "" : keyState;
    this.listenerState = listenerState == null ? "" : listenerState;
    this.guardState = guardState == null ? "" : guardState;
    this.evidence = evidence == null ? "" : evidence;
    this.pendingVerified = pendingVerified;
    this.networkPending = networkPending == null ? "" : networkPending;
    this.keyPending = keyPending == null ? "" : keyPending;
  }

  /**
   * 原生适配器实际调用的编码入口；退出码只由状态推导，调用方不能自带。
   *
   * @param pendingVerified 是否真的读到了持久记录（读不到必须传 false）
   */
  public static String encode(
      String status,
      String code,
      String keyState,
      String listenerState,
      String guardState,
      String evidence,
      boolean pendingVerified,
      String networkPending,
      String keyPending) {
    String normalized = status == null ? "" : status;
    int exit = normalized.equals("OK") ? 0 : normalized.equals("REFUSED") ? 126 : 125;
    // 手工拼装（值只用 JSONObject.quote 转义）而不是链式 put：Android 的 org.json 把
    // JSONException 声明为受检异常，链式 put 会一路把异常签名带进原生响应路径。
    StringBuilder json = new StringBuilder(256).append('{');
    field(json, "status", normalized);
    field(json, "code", code);
    field(json, "keyState", keyState);
    field(json, "listenerState", listenerState);
    field(json, "guardState", guardState);
    field(json, "evidence", evidence);
    field(json, "pendingVerified", pendingVerified ? "true" : "false", true);
    if (pendingVerified) {
      field(json, "networkPending", networkPending);
      field(json, "keyPending", keyPending);
    }
    return json.append('}').append("\n[EXIT=").append(exit).append(']').toString();
  }

  private static void field(StringBuilder json, String name, String value) {
    field(json, name, value, false);
  }

  private static void field(StringBuilder json, String name, String value, boolean raw) {
    if (json.length() > 1) json.append(',');
    json.append(JSONObject.quote(name)).append(':');
    json.append(raw ? value : JSONObject.quote(value == null ? "" : value));
  }

  /**
   * 宿主侧解码。任何无法确认的组合都返回 {@code trusted=false}，并以 {@link #code} 说明原因；
   * {@link #evidence} 是中性化后的原文开头，只用于显示与诊断。
   */
  public static AdbBootstrapReply parse(String output) {
    String raw = output == null ? "" : output;
    String head = AdbFirewallTool.head(raw);
    RootSupportProbe probe = RootSupportProbe.parse(raw);
    if (probe.exitCode < 0 || probe.body.isEmpty()) return untrusted(UNPARSEABLE, head);
    JSONObject json;
    try {
      json = new JSONObject(probe.body);
    } catch (JSONException unparseable) {
      return untrusted(UNPARSEABLE, head);
    }
    String status = string(json, "status");
    String code = string(json, "code");
    String keyState = string(json, "keyState");
    String listenerState = string(json, "listenerState");
    String guardState = string(json, "guardState");
    if (status == null
        || code == null
        || keyState == null
        || listenerState == null
        || guardState == null) return untrusted(PROTOCOL_INVALID, head);
    int expected = status.equals("OK") ? 0 : status.equals("REFUSED") ? 126 : 125;
    if (!(status.equals("OK")
        || status.equals("REFUSED")
        || status.equals("UNKNOWN")
        || status.equals("FAILED"))) return untrusted(PROTOCOL_INVALID, head);
    if (probe.exitCode != expected) return untrusted(PROTOCOL_INVALID, head);
    // 未决字段是新协议的核心：缺失或类型不符就保持未知，而不是退回旧判据。
    if (!json.has("pendingVerified") || !(json.opt("pendingVerified") instanceof Boolean))
      return untrusted(PROTOCOL_INVALID, head);
    boolean pendingVerified = json.optBoolean("pendingVerified", false);
    String networkPending = "";
    String keyPending = "";
    if (pendingVerified) {
      networkPending = string(json, "networkPending");
      keyPending = string(json, "keyPending");
      if (networkPending == null || keyPending == null) return untrusted(PROTOCOL_INVALID, head);
      if (!AdbBootstrapJournal.validNetworkPhase(networkPending)
          || !AdbBootstrapJournal.validKeyPhase(keyPending))
        return untrusted(PROTOCOL_INVALID, head);
    }
    return new AdbBootstrapReply(
        true,
        status,
        code,
        keyState,
        listenerState,
        guardState,
        string(json, "evidence"),
        pendingVerified,
        networkPending,
        keyPending);
  }

  /** 三项观测是否都在可判定集合内（与引擎的完成判据同一份定义）。 */
  public boolean observationComplete() {
    return new AdbBootstrapEngine.Snapshot(keyState, listenerState, guardState).complete();
  }

  /** 两个未决阶段都已被可信确认结束。 */
  public boolean pendingCleared() {
    return trusted && pendingVerified && networkPending.isEmpty() && keyPending.isEmpty();
  }

  private static AdbBootstrapReply untrusted(String code, String evidence) {
    return new AdbBootstrapReply(
        false, "FAILED", code, "UNKNOWN", "UNKNOWN", "UNKNOWN", evidence, false, "", "");
  }

  private static String string(JSONObject json, String field) {
    if (!json.has(field)) return null;
    Object value = json.opt(field);
    return value instanceof String ? (String) value : null;
  }
}
