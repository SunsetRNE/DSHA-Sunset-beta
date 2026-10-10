package com.deepseekharness.app.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 解析 adbd 进程的只读证据；进程身份不代表 ADB 会话权限或设备整体能力。 */
public final class AdbdIdentity {
  public enum Level {
    ROOT,
    SHELL,
    UNKNOWN
  }

  public static final class Evidence {
    public final Level level;
    public final int pid;
    public final int uid;
    public final String selinuxContext;

    private Evidence(Level level, int pid, int uid, String selinuxContext) {
      this.level = level;
      this.pid = pid;
      this.uid = uid;
      this.selinuxContext = selinuxContext;
    }
  }

  /** 固定列避免从命令行参数、用户名或 PPID 猜测进程身份；这些常量不会执行命令。 */
  public static final List<String> UID_ARGV = List.of("ps", "-A", "-o", "PID,UID,NAME");

  /**
   * 上下文列的首选与回退形式。
   *
   * <p>Android 的 toybox `ps` 只认 `LABEL`；`CONTEXT` 在部分构建里直接报
   * `ps: bad -o 'PID,CONTEXT,NAME'` 并以 1 退出（真机 Android 16 已复现）。
   * 两种表头形状相同（PID / 上下文 / NAME 三列），解析层必须都接受。
   */
  public static final List<String> LABEL_ARGV = List.of("ps", "-A", "-o", "PID,LABEL,NAME");

  public static final List<String> CONTEXT_ARGV = List.of("ps", "-A", "-o", "PID,CONTEXT,NAME");
  private static final int MAX_LISTING_CHARS = 262144;
  // MLS 的低、高级别各自都可以带类别集合。
  private static final String MLS_LEVEL = "s[0-9]+(?::c[0-9]+(?:[,.]c[0-9]+)*)?";
  private static final Pattern CONTEXT =
      Pattern.compile("u:r:[A-Za-z0-9_]+:" + MLS_LEVEL + "(?:-" + MLS_LEVEL + ")?");

  private AdbdIdentity() {}

  /**
   * 只用于已确认两条只读命令均成功的捕获件。接受固定列和历史十列 LABEL USER PID ... NAME；
   * 任一非空行格式不支持、重复 adbd、身份冲突或捕获不完整时均返回 UNKNOWN。
   * 同一 PID 仍不能排除复用；调用方必须独立核验快照时效和出生身份。
   */
  public static Evidence assess(String uidListing, String selinuxListing) {
    if (!validListing(uidListing) || !validListing(selinuxListing)) return unknown();
    ProcessRow process = findUidProcess(uidListing);
    if (process == null) return unknown();
    String context = contextForPid(selinuxListing, process);
    if (context == null) return unknown();
    Level level = process.uid == 0 ? Level.ROOT : process.uid == 2000 ? Level.SHELL : Level.UNKNOWN;
    return new Evidence(level, process.pid, process.uid, context);
  }

  /** 运行时入口必须传入真实退出码，不能把失败命令的残留 stdout 当作成功证据。 */
  public static Evidence assess(
      String uidListing, int uidExitCode, String selinuxListing, int selinuxExitCode) {
    return uidExitCode == 0 && selinuxExitCode == 0
        ? assess(uidListing, selinuxListing)
        : unknown();
  }

  /** 仅完整固定列表证明 daemon 缺席；不能把格式错误或空输出当作已停止。 */
  public static boolean absent(String uids, String contexts) {
    return absentTable(uids, "PID UID NAME", false)
        && (absentTable(contexts, "PID LABEL NAME", true)
            || absentTable(contexts, "PID CONTEXT NAME", true));
  }

  private static boolean absentTable(String listing, String header, boolean context) {
    if (!validListing(listing)) return false;
    String[] lines = listing.trim().split("\\r?\\n");
    if (!lines[0].trim().replaceAll("\\s+", " ").equals(header)) return false;
    Set<Integer> pids = new HashSet<>();
    for (int i = 1; i < lines.length; i++) {
      String[] cols = row3(lines[i]);
      if (cols == null) return false;
      Integer pid = number(cols[0]);
      if (pid == null
          || pid <= 0
          || !pids.add(pid)
          || cols[2].equals("adbd")
          || (context ? !CONTEXT.matcher(cols[1]).matches() : number(cols[1]) == null))
        return false;
    }
    return true;
  }

  /**
   * 三列固定表（PID / UID 或 LABEL / NAME）的一行。
   *
   * <p>NAME 允许含空格：真机 Android 16 的内核线程是 `[irq/261-q6v5 wdog]` 这类名字，
   * 按空白切满就会变成 4 列，从而把整张表判成不可信（实机踩过，整条 AUTHORIZE 挂在这里）。
   * 因此只固定前两列，其余全部算 NAME。
   */
  private static String[] row3(String line) {
    if (line == null) return null;
    String[] parts = line.trim().split("\\s+", 3);
    return parts.length == 3 ? parts : null;
  }

  private static boolean validListing(String listing) {
    if (listing == null || listing.isEmpty() || listing.length() > MAX_LISTING_CHARS) return false;
    for (int i = 0; i < listing.length(); i++) {
      char c = listing.charAt(i);
      if ((Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r')
          || (c == '\r' && (i + 1 == listing.length() || listing.charAt(i + 1) != '\n')))
        return false;
    }
    return !listing.contains("[EXIT=")
        && !listing.contains("[OUTPUT_TRUNCATED]")
        && !listing.contains("[EXECUTION_UNKNOWN]")
        && !listing.contains("[POLICY_BLOCKED]");
  }

  private static ProcessRow findUidProcess(String listing) {
    ProcessRow found = null;
    boolean seen = false;
    Set<Integer> pids = new HashSet<>();
    for (String line : listing.split("\\r?\\n")) {
      if (line.trim().isEmpty()) continue;
      if (!seen) {
        String[] head = line.trim().split("\\s+");
        if (String.join(" ", head).equals("PID UID NAME")) {
          seen = true;
          continue;
        }
      }
      seen = true;
      String[] columns = row3(line);
      if (columns == null) return null;
      Integer pid = number(columns[0]);
      Integer uid = number(columns[1]);
      if (pid == null || pid == 0 || uid == null || !pids.add(pid)) return null;
      if (!"adbd".equals(columns[2])) continue;
      if (found != null) return null;
      found = new ProcessRow(pid, uid);
    }
    return found;
  }

  private static String contextForPid(String listing, ProcessRow process) {
    String found = null;
    int shape = 0;
    Set<Integer> pids = new HashSet<>();
    for (String line : listing.split("\\r?\\n")) {
      if (line.trim().isEmpty()) continue;
      String[] raw = line.trim().split("\\s+");
      String header = String.join(" ", raw);
      if (shape == 0 && (header.equals("PID CONTEXT NAME") || header.equals("PID LABEL NAME"))) {
        shape = 3;
        continue;
      }
      if (shape == 0 && header.equals("LABEL USER PID PPID VSZ RSS WCHAN ADDR S NAME")) {
        shape = 10;
        continue;
      }
      if (shape == 0) shape = raw.length;
      // 三列表格允许 NAME 带空格（内核线程），十列 ps -Z 保持原样。
      String[] columns = shape == 3 ? row3(line) : raw;
      if (columns == null || columns.length != shape) return null;
      Integer pid;
      String context;
      if (shape == 3) {
        pid = number(columns[0]);
        context = columns[1];
      } else if (shape == 10 && number(columns[3]) != null) {
        pid = number(columns[2]);
        context = columns[0];
      } else {
        return null;
      }
      if (pid == null || pid == 0 || !pids.add(pid)) return null;
      if (!"adbd".equals(columns[columns.length - 1])) continue;
      if (pid != process.pid || !CONTEXT.matcher(context).matches() || found != null) return null;
      if (shape == 10) {
        Integer uid =
            switch (columns[1]) {
              case "root" -> 0;
              case "shell" -> 2000;
              default -> number(columns[1]);
            };
        if (uid == null || uid != process.uid) return null;
      }
      found = context;
    }
    return found;
  }

  private static Integer number(String value) {
    if (!value.matches("[0-9]+")) return null;
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static Evidence unknown() {
    return new Evidence(Level.UNKNOWN, -1, -1, "");
  }

  private static final class ProcessRow {
    final int pid;
    final int uid;

    ProcessRow(int pid, int uid) {
      this.pid = pid;
      this.uid = uid;
    }
  }
}
