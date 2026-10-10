package com.deepseekharness.app.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** root 评估专用的完整输出解析；不执行命令，也不把失败后的残留输出当作身份。 */
public final class RootSupportProbe {
  private static final Pattern RESULT =
      Pattern.compile("(?s)(.*)\\r?\\n\\[EXIT=([0-9]{1,3})\\][ \\t]*(?:\\r?\\n)?");
  private static final Pattern ID =
      Pattern.compile(
          "uid=([0-9]+)(?:\\([A-Za-z0-9_-]+\\))? gid=[0-9]+(?:\\([A-Za-z0-9_-]+\\))?"
              + "(?: groups=[0-9]+(?:\\([A-Za-z0-9_-]+\\))?(?:,[0-9]+(?:\\([A-Za-z0-9_-]+\\))?)*)?"
              + "(?: context=u:r:[A-Za-z0-9_]+:[^ \\t\\r\\n]+)?");

  public final String body;
  public final int exitCode;

  private RootSupportProbe(String body, int exitCode) {
    this.body = body;
    this.exitCode = exitCode;
  }

  public boolean succeeded() {
    return exitCode == 0;
  }

  public static RootSupportProbe parse(String output) {
    if (output == null || output.length() > 270000) return unknown();
    for (int i = 0; i < output.length(); i++) {
      char c = output.charAt(i);
      if ((Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r')
          || (c == '\r' && (i + 1 == output.length() || output.charAt(i + 1) != '\n')))
        return unknown();
    }
    Matcher match = RESULT.matcher(output);
    if (!match.matches()) return unknown();
    String body = match.group(1);
    if (body.contains("[EXIT=")
        || body.contains("[OUTPUT_TRUNCATED]")
        || body.contains("[EXECUTION_UNKNOWN]")
        || body.contains("[POLICY_BLOCKED]")) return unknown();
    int exit = Integer.parseInt(match.group(2));
    return exit <= 255 ? new RootSupportProbe(body.trim(), exit) : unknown();
  }

  /** 只接受一行完整 id；euid、错误文本或相互冲突的多行均不充当真实 UID。 */
  public AdbdIdentity.Level executionLevel() {
    if (!succeeded() || body.length() > 8192) return AdbdIdentity.Level.UNKNOWN;
    Matcher match = ID.matcher(body);
    if (!match.matches()) return AdbdIdentity.Level.UNKNOWN;
    return switch (match.group(1)) {
      case "0" -> AdbdIdentity.Level.ROOT;
      case "2000" -> AdbdIdentity.Level.SHELL;
      default -> AdbdIdentity.Level.UNKNOWN;
    };
  }

  /** 策略测试通过与身份采集完整性分别报告；未取证不能冒充整体通过。 */
  public static String auditResult(boolean identityRequested, String identityResult) {
    return !identityRequested || "PASS".equals(identityResult) ? "PASS" : "INCOMPLETE";
  }

  private static RootSupportProbe unknown() {
    return new RootSupportProbe("", -1);
  }
}
