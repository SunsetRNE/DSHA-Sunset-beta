package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 防火墙调用形式适配：候选顺序、回退、未知结果不重放，以及失败证据的形状。 */
public final class AdbFirewallToolTest {
  private static final String TOOL = "/system/bin/iptables";

  /** 记录调用顺序的假设备：只有 accepted 形式成功，第一次调用可选地返回未知结果。 */
  private static final class Fake implements AdbFirewallTool.Runner {
    final List<List<String>> calls = new ArrayList<>();
    final List<String> accepted;
    final boolean unknownFirst;

    Fake(List<String> accepted) {
      this(accepted, false);
    }

    Fake(List<String> accepted, boolean unknownFirst) {
      this.accepted = accepted;
      this.unknownFirst = unknownFirst;
    }

    public RootSupportProbe run(List<String> argv) {
      calls.add(argv);
      if (unknownFirst && calls.size() == 1)
        return RootSupportProbe.parse("[EXECUTION_UNKNOWN] 超时\n[EXIT=125]");
      List<String> wait = argv.subList(1, argv.size() - 2);
      if (wait.equals(accepted))
        return RootSupportProbe.parse("-P INPUT ACCEPT\n-A INPUT -j DROP\n[EXIT=0]");
      return RootSupportProbe.parse(
          "iptables v1.8.9 (legacy): unrecognized option '-w'\n[EXIT=2]");
    }
  }

  @Test
  public void resolvePrefersTheShortestWaitThatWorks() {
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, new Fake(List.of("-w", "2")));
    assertTrue(resolved.ok());
    assertEquals(List.of("-w", "2"), resolved.form.wait);
    assertEquals(TOOL, resolved.form.tool);
    assertEquals("", resolved.evidence);
    assertEquals(
        List.of(TOOL, "-w", "2", "-S", "INPUT"), resolved.form.listing());
  }

  @Test
  public void resolveFallsBackWhenSecondsFormIsRejected() {
    Fake fake = new Fake(List.of("-w"));
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, fake);
    assertTrue(resolved.ok());
    assertEquals(List.of("-w"), resolved.form.wait);
    // 先试了 -w 2，被拒后才退到 -w。
    assertEquals(2, fake.calls.size());
    assertEquals(List.of(TOOL, "-w", "2", "-S", "INPUT"), fake.calls.get(0));
    assertEquals(List.of(TOOL, "-w", "-S", "INPUT"), fake.calls.get(1));
  }

  @Test
  public void resolveFallsBackToNoWaitUntilNothingIsLeft() {
    Fake fake = new Fake(List.of());
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, fake);
    assertTrue(resolved.ok());
    assertEquals(List.of(), resolved.form.wait);
    assertEquals(3, fake.calls.size());
    assertEquals(List.of(TOOL, "-S", "INPUT"), resolved.form.listing());
  }

  @Test
  public void everyFormFailingKeepsTheCommandExitCodeAndFirstLine() {
    Fake fake = new Fake(List.of("never"));
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, fake);
    assertFalse(resolved.ok());
    assertTrue(resolved.evidence.contains(TOOL + " -w 2 -S INPUT (exit=2)"));
    assertTrue(resolved.evidence.contains("unrecognized option"));
    assertTrue(resolved.evidence.contains(TOOL + " -S INPUT (exit=2)"));
    assertEquals(3, fake.calls.size());
  }

  @Test
  public void unknownOutcomeStopsInsteadOfTryingAnotherForm() {
    // 第一次调用就是未知结果：不能再换形式重放。
    Fake fake = new Fake(List.of("-w"), true);
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, fake);
    assertFalse(resolved.ok());
    assertEquals(1, fake.calls.size());
    assertTrue(resolved.evidence.contains("(exit=-1)"));
  }

  @Test
  public void evidenceNeverCarriesTheReservedProtocolTokens() {
    // 实机踩过：证据里出现 [EXIT=…] 会让 RootSupportProbe.parse 把整包结果判成不可信，
    // 于是「命令失败」被伪装成「结果未知」。证据生成与展示两处都必须中性化。
    String evidence = AdbFirewallTool.head("[EXECUTION_UNKNOWN] su 超时\n[EXIT=125]");
    assertFalse(evidence.contains("[EXIT="));
    assertFalse(evidence.contains("[EXECUTION_UNKNOWN]"));
    assertTrue(evidence.contains("(exit=125)"));
    // 放进自举 JSON 之后，协议解析仍必须成功。
    String json =
        "{\"status\":\"FAILED\",\"code\":\"COMMAND_FAILED\",\"evidence\":"
            + org.json.JSONObject.quote(evidence)
            + "}";
    RootSupportProbe parsed = RootSupportProbe.parse(json + "\n[EXIT=125]");
    assertEquals(125, parsed.exitCode);
    assertTrue(parsed.body.contains("COMMAND_FAILED"));
  }

  @Test
  public void headKeepsReadableTextAndDropsControlCharacters() {
    String head =
        AdbFirewallTool.head("iptables: Permission denied\u0007\u0000\n\nsecond line\nthird line");
    assertEquals("iptables: Permission denied / second line", head);
    String longLine = "x".repeat(1000);
    assertTrue(AdbFirewallTool.head(longLine).length() <= 201);
  }

  @Test
  public void formCommandPrefixesTheResolvedWaitForm() {
    AdbFirewallTool.Resolved resolved = AdbFirewallTool.resolve(TOOL, new Fake(List.of("-w")));
    List<String> insert = resolved.form.command("-I", "INPUT", "1", "-j", "DROP");
    assertEquals(List.of(TOOL, "-w", "-I", "INPUT", "1", "-j", "DROP"), insert);
  }
}
