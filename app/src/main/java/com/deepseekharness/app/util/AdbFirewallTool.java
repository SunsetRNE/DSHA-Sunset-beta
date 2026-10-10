package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 防火墙调用形式适配。
 *
 * <p>不同 Android/ROM 的 iptables 对等待参数的支持不同（`-w 2` / 仅 `-w` / 完全不支持），
 * 参数不被接受时命令以非 0 退出，原来的实现只报一个 `COMMAND_FAILED`，在设备上无从排查。
 * 这里先按候选形式探测一次**只读**的 `-S INPUT`，选定可用形式后复用到后续所有查询与增删，
 * 并在全部失败时把「哪条命令、退出码、stderr 首行」作为证据交给界面。
 *
 * <p>只读探测可以换形式重试；一旦某次探测的结果本身未知（超时、无法解析），立即停止，
 * 不再换形式重放，避免把未知结果当成「这个形式不可用」。
 */
public final class AdbFirewallTool {
  /** 候选等待形式，按优先级排列。 */
  public static final List<List<String>> WAIT_FORMS =
      Collections.unmodifiableList(
          new ArrayList<>(
              List.of(List.of("-w", "2"), List.of("-w"), Collections.<String>emptyList())));

  private static final int LINE_LIMIT = 200;
  private static final int LINES = 2;
  private static final int EVIDENCE_LIMIT = 420;

  private AdbFirewallTool() {}

  /** 执行一条设备命令；由适配器提供真实 I/O，测试提供假设备。 */
  public interface Runner {
    RootSupportProbe run(List<String> argv) throws Exception;
  }

  /** 一次成功探测出来的调用形式；同一事务内所有该工具的命令都必须复用它。 */
  public static final class Form {
    public final String tool;
    public final List<String> wait;

    private Form(String tool, List<String> wait) {
      this.tool = tool;
      this.wait = Collections.unmodifiableList(new ArrayList<>(wait));
    }

    /** 拼装 `[tool, 等待形式…, args…]`。 */
    public List<String> command(String... args) {
      List<String> argv = new ArrayList<>();
      argv.add(tool);
      argv.addAll(wait);
      argv.addAll(Arrays.asList(args));
      return argv;
    }

    /** 只读列出 INPUT 链。 */
    public List<String> listing() {
      return command("-S", "INPUT");
    }

    /** 探测时选用的那份列表，可直接交给 {@link AdbBootstrapEngine#firstRuleProtects}。 */
    public String listingText(RootSupportProbe probe) {
      return probe == null ? "" : probe.body;
    }
  }

  /** 探测结果：成功带 Form，失败带证据。 */
  public static final class Resolved {
    public final Form form;
    public final String evidence;

    private Resolved(Form form, String evidence) {
      this.form = form;
      this.evidence = evidence == null ? "" : evidence;
    }

    public boolean ok() {
      return form != null;
    }
  }

  /**
   * 探测可用的调用形式。
   *
   * @throws Exception 仅当 {@link Runner#run} 抛出中断等异常需要向上传递时；
   *     命令自身的非 0 退出不会抛出，而是作为证据累积。
   */
  public static Resolved resolve(String tool, Runner runner) throws Exception {
    List<String> evidence = new ArrayList<>();
    for (List<String> wait : WAIT_FORMS) {
      List<String> argv = argv(tool, wait, "-S", "INPUT");
      RootSupportProbe probe;
      try {
        probe = runner.run(argv);
      } catch (InterruptedException interrupted) {
        throw interrupted;
      } catch (Exception failed) {
        // 抛异常通常代表执行结果未知（超时、进程异常）：不换形式重放。
        evidence.add(describe(argv, -1, failed.getClass().getSimpleName()));
        return new Resolved(null, join(evidence));
      }
      if (probe == null || probe.exitCode < 0) {
        evidence.add(describe(argv, -1, probe == null ? "" : probe.body));
        return new Resolved(null, join(evidence));
      }
      if (probe.succeeded()) return new Resolved(new Form(tool, wait), "");
      evidence.add(describe(argv, probe.exitCode, probe.body));
    }
    return new Resolved(null, join(evidence));
  }

  public static List<String> argv(String tool, List<String> wait, String... args) {
    List<String> argv = new ArrayList<>();
    argv.add(tool);
    if (wait != null) argv.addAll(wait);
    argv.addAll(Arrays.asList(args));
    return argv;
  }

  /** `tool -w 2 -S INPUT (exit=2) <stderr 首行>`；退出码刻意不用 `[EXIT=` 形式。 */
  public static String describe(List<String> argv, int exitCode, String output) {
    StringBuilder text = new StringBuilder(quote(argv));
    text.append(" (exit=").append(exitCode).append(')');
    String head = head(output);
    if (!head.isEmpty()) text.append(' ').append(head);
    return text.length() > EVIDENCE_LIMIT ? text.substring(0, EVIDENCE_LIMIT) : text.toString();
  }

  /** 失败命令的证据行：命令 + 退出码 + 输出首两行（脱敏、去控制字符、截断）。 */
  public static String evidence(List<String> argv, RootSupportProbe probe) {
    if (probe == null) return describe(argv, -1, "");
    return describe(argv, probe.exitCode, probe.body);
  }

  /** 只保留可读的输出开头：脱敏、中性化保留标记、压掉控制字符、限制行数与长度。 */
  public static String head(String output) {
    if (output == null || output.isEmpty()) return "";
    String safe = SensitiveData.redact(output);
    StringBuilder text = new StringBuilder();
    int lines = 0;
    for (String raw : safe.split("\\r?\\n")) {
      String line = neutralize(raw.replaceAll("[\\p{Cntrl}&&[^\\t]]", "").trim());
      if (line.isEmpty()) continue;
      if (text.length() > 0) text.append(" / ");
      text.append(line.length() > LINE_LIMIT ? line.substring(0, LINE_LIMIT) + "…" : line);
      if (++lines >= LINES) break;
    }
    return text.toString();
  }

  /**
   * 中性化协议保留标记。
   *
   * <p>`RootSupportProbe.parse` 会把含 `[EXIT=`、`[OUTPUT_TRUNCATED]`、`[EXECUTION_UNKNOWN]`、
   * `[POLICY_BLOCKED]` 的输出判为不可信并整包丢弃——证据文本里出现这些字样，就会把本来
   * 可以解析的结果变成「结果未知」（实机踩过）。因此证据在生成与展示两处都必须中性化。
   */
  public static String neutralize(String value) {
    if (value == null || value.isEmpty()) return "";
    return value
        .replace("[EXIT=", "(exit=")
        .replace("[EXIT", "(exit")
        .replace("[OUTPUT_TRUNCATED]", "(output truncated)")
        .replace("[EXECUTION_UNKNOWN]", "(execution unknown)")
        .replace("[POLICY_BLOCKED]", "(policy blocked)");
  }

  /** 展示用：命令按空格连接；参数是我们自己构造的固定值，不含用户输入。 */
  public static String quote(List<String> argv) {
    if (argv == null || argv.isEmpty()) return "";
    StringBuilder text = new StringBuilder();
    for (String part : argv) {
      if (text.length() > 0) text.append(' ');
      text.append(part);
    }
    return text.toString();
  }

  private static String join(List<String> lines) {
    StringBuilder text = new StringBuilder();
    for (String line : lines) {
      if (line == null || line.isEmpty()) continue;
      if (text.length() > 0) text.append('\n');
      text.append(line);
    }
    return text.length() > EVIDENCE_LIMIT * 2 ? text.substring(0, EVIDENCE_LIMIT * 2) : text.toString();
  }
}
