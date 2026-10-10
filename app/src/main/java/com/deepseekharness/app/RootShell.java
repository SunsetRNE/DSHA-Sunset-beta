package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.RootShellCandidates;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Root 直接通道；仅在用户开启开关并执行设备操作时请求 su，不需要 ADB。 */
public final class RootShell {
  private enum Status {
    WAITING,
    RESPONDED,
    TIMED_OUT,
    FAILED
  }

  private static final class LastResult {
    final Status status;
    final String executable, detail;

    LastResult(Status status, String executable, String detail) {
      this.status = status;
      this.executable = executable;
      this.detail = detail;
    }

    String render(String current) {
      if (!executable.equals(current) || status == Status.WAITING)
        return com.deepseekharness.app.util.UiText.format("su：%s\n尚未验证 root 授权", current);
      return switch (status) {
        case RESPONDED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 通道已响应 · 设备命令仍受策略保护", current);
        case TIMED_OUT ->
            com.deepseekharness.app.util.UiText.format(
                "su：%s\nroot 授权或执行超时，请在 root 管理器查看 DSHA 授权", current);
        case FAILED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 授权或执行失败：%s", current, detail);
        case WAITING -> throw new IllegalStateException("ROOT_STATUS_WAITING");
      };
    }
  }

  private static final java.util.concurrent.atomic.AtomicLong AUTHORIZATION =
      new java.util.concurrent.atomic.AtomicLong();

  public static long authorizationRevision() {
    return AUTHORIZATION.get();
  }

  public static void authorizationChanged() {
    AUTHORIZATION.incrementAndGet();
    lastResult = new LastResult(Status.WAITING, "", "");
  }

  private static volatile LastResult lastResult = new LastResult(Status.WAITING, "", "");

  private RootShell() {}

  public static boolean enabled(Context ctx) {
    return ctx.getSharedPreferences(Constants.PREFS, 0)
        .getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
  }

  private static String executable() {
    // Only inspect known system/root-manager locations. Discovery never invokes
    // su or trusts an app-controlled PATH; authorization happens on execution.
    return RootShellCandidates.firstVerified(
        path -> {
          try {
            File file = new File(path);
            android.system.StructStat stat = android.system.Os.stat(path);
            int writable = android.system.OsConstants.S_IWGRP | android.system.OsConstants.S_IWOTH;
            return stat.st_uid == 0
                && android.system.OsConstants.S_ISREG(stat.st_mode)
                && (stat.st_mode & writable) == 0
                && file.canExecute();
          } catch (android.system.ErrnoException | SecurityException ignored) {
            return false;
          }
        });
  }

  public static boolean present() {
    return executable() != null;
  }

  public static String status(Context ctx) {
    if (!enabled(ctx)) return com.deepseekharness.app.util.UiText.text("未启用 · 开启后可直接申请 root 授权");
    String su = executable();
    return su == null
        ? com.deepseekharness.app.util.UiText.text("未找到 su，可使用 Shizuku 或 ADB 通道")
        : lastResult.render(su);
  }

  public static String exec(Context ctx, String command, int authorizedSmsUser) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
    if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
    if (plan.kind == DeviceShellPolicy.Kind.SENSITIVE_READ
        && (authorizedSmsUser < 0
            || !new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed()))
      return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 短信读取尚未经过原生授权\n[EXIT=126]");
    return runPrivileged(ctx, command, authorizedSmsUser, "");
  }

  /** Native-only launcher path. Generic RootShell.exec rejects app_process unconditionally. */
  public static String execVirtualScreen(Context ctx, String command) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    try {
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new File(source).getCanonicalPath();
      if (!source.equals(canonical)
          || !DeviceShellPolicy.inspectVirtualScreenLaunch(command, canonical, ctx.getPackageName())
              .allowed())
        return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 虚拟屏启动参数无法核验\n[EXIT=126]");
    } catch (Exception invalid) {
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 无法核验 DSHA 安装包路径\n[EXIT=126]");
    }
    return runPrivileged(ctx, command, -1, "virtual-screen-start");
  }

  /** 原生 UI 的固定自举入口；不接收命令字符串，也不经过通用执行白名单。 */
  public static String bootstrap(
      Context ctx,
      com.deepseekharness.app.util.AdbBootstrapPolicy.Action action,
      String authority) {
    if (action == null || !RootBootstrapAuthority.allowed(ctx, action))
      return "[POLICY_BLOCKED] 原生自举授权未开启\n[EXIT=126]";
    try {
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", com.deepseekharness.app.util.AdbBootstrapPolicy.OPERATION)
              .put("action", action.name().toLowerCase(java.util.Locale.ROOT))
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid)
              .put("authority", authority);
      return runPrivileged(ctx, request);
    } catch (org.json.JSONException invalid) {
      return "[POLICY_BLOCKED] 自举请求无法构造\n[EXIT=126]";
    }
  }

  private static String runPrivileged(
      Context ctx, String command, int authorizedSmsUser, String operation) {
    try {
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("command", command)
              .put("smsUser", authorizedSmsUser)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      if (!operation.isEmpty()) request.put("operation", operation);
      return runPrivileged(ctx, request);
    } catch (org.json.JSONException invalid) {
      return "[POLICY_BLOCKED] 设备请求无法构造\n[EXIT=126]";
    }
  }

  private static String runPrivileged(Context ctx, org.json.JSONObject request) {
    String su = executable();
    if (su == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    long authorization = authorizationRevision();
    Process process = null;
    try {
      // 命令通过标准输入传递；su 的 shell 参数只含固定入口与已安装 APK 路径。
      String entry =
          "CLASSPATH="
              + ShellQuote.arg(ctx.getApplicationInfo().sourceDir)
              + " /system/bin/app_process /system/bin "
              + RootShellMain.class.getName();
      process = new ProcessBuilder(su, "-c", entry).redirectErrorStream(true).start();
      try (java.io.OutputStream input = process.getOutputStream()) {
        input.write(request.toString().getBytes(StandardCharsets.UTF_8));
      }
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, 65_000, 400_000, Compat::destroy);
      int marker = result.output.lastIndexOf(RootShellMain.RESULT);
      if (!result.timedOut && !result.truncated && marker >= 0) {
        String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
        String output = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        if (authorization == authorizationRevision() && enabled(ctx))
          lastResult = new LastResult(Status.RESPONDED, su, "");
        return output;
      }
      LastResult failed =
          new LastResult(
              result.timedOut ? Status.TIMED_OUT : Status.FAILED,
              su,
              SensitiveData.redact(result.output.trim()));
      if (authorization == authorizationRevision() && enabled(ctx)) lastResult = failed;
      return "[EXECUTION_UNKNOWN] "
          + failed.render(su)
          + com.deepseekharness.app.util.UiText.text("。未取得完整结果，不会自动重试\n[EXIT=125]");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return com.deepseekharness.app.util.UiText.text(
          "[EXECUTION_UNKNOWN] root 请求已中断，不会自动重试\n[EXIT=125]");
    } catch (Throwable e) {
      LastResult failed =
          new LastResult(Status.FAILED, su, SensitiveData.redact(String.valueOf(e)));
      if (authorization == authorizationRevision() && enabled(ctx)) lastResult = failed;
      return "[EXECUTION_UNKNOWN] " + failed.render(su) + "\n[EXIT=125]";
    } finally {
      if (process != null) {
        try {
          process.exitValue();
        } catch (IllegalThreadStateException running) {
          Compat.destroy(process);
        }
        try {
          process.getOutputStream().close();
        } catch (java.io.IOException ignored) {
        }
        try {
          process.getInputStream().close();
        } catch (java.io.IOException ignored) {
        }
        try {
          process.getErrorStream().close();
        } catch (java.io.IOException ignored) {
        }
      }
    }
  }
}
