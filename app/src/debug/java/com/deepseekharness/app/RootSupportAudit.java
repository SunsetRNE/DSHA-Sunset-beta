package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import com.deepseekharness.app.util.AdbBootstrapPolicy;
import com.deepseekharness.app.util.AdbdIdentity;
import com.deepseekharness.app.util.RootSupportProbe;
import com.deepseekharness.app.util.SelfPackageIdentity;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 调试专用 root 支持评估插桩：验证 M1 纯逻辑契约，并在 root 已开启时只读采集 root/adbd 证据。
 *
 * <p>本类不写 adb_keys、不改端口或防火墙、不启动 ADB bootstrap，也不持久化原始 ps/id 输出。 identity/all
 * 模式的“不可用”或未核验出生身份报告 INCOMPLETE；策略结果独立保留，daemon 身份不推导会话权限。
 */
public final class RootSupportAudit extends Instrumentation {
  private Bundle arguments;
  private int checks;

  private void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args == null ? new Bundle() : new Bundle(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      check(BuildConfig.DEBUG, "仅限调试包");
      Context app = getTargetContext().getApplicationContext();
      String mode = arguments.getString("mode", "policy").toLowerCase(Locale.ROOT);
      check(
          mode.equals("policy") || mode.equals("identity") || mode.equals("all"),
          "mode 仅支持 policy/identity/all");
      runPolicyChecks(app, result);
      boolean identityRequested = !mode.equals("policy");
      if (identityRequested) runIdentityProbe(app, result);
      result.putString(
          "result",
          RootSupportProbe.auditResult(identityRequested, result.getString("identityResult")));
    } catch (Throwable error) {
      result.putString("result", "FAIL");
      result.putString(
          "failure", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
    }
    result.putInt("checks", checks);
    finish(
        "PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
        result);
  }

  private void runPolicyChecks(Context app, Bundle result) throws Exception {
    String source = new File(app.getApplicationInfo().sourceDir).getCanonicalPath();
    SelfPackageIdentity installed =
        SelfPackageIdentity.launch(
            app.getPackageName(), app.getApplicationInfo().uid, source, source);
    Map<String, Object> request = new HashMap<>();
    request.put("operation", AdbBootstrapPolicy.OPERATION);
    request.put("action", "authorize");
    request.put("selfPackage", installed.name);
    request.put("selfUid", installed.uid);

    ArrayList<String> actions = new ArrayList<>();
    for (AdbBootstrapPolicy.Action action : AdbBootstrapPolicy.Action.values()) {
      request.put("action", action.name().toLowerCase(Locale.ROOT));
      AdbBootstrapPolicy.Result accepted = AdbBootstrapPolicy.inspect(request, installed);
      check(accepted.allowed && accepted.request.action == action, "允许动作校验失败: " + action);
      check(accepted.request.port == AdbBootstrapPolicy.DEFAULT_PORT, "固定端口校验失败: " + action);
      check(!AdbBootstrapPolicy.requirements(action).isEmpty(), "动作缺少执行条件: " + action);
      actions.add(action.name());
    }

    request.put("unexpected", "ignored");
    check(!AdbBootstrapPolicy.inspect(request, installed).allowed, "附加字段未拒绝");
    request.remove("unexpected");
    request.put("selfPackage", "com.example.not-installed");
    check(!AdbBootstrapPolicy.inspect(request, installed).allowed, "错误包名未拒绝");
    request.put("selfPackage", installed.name);
    request.put("selfUid", installed.uid + 1);
    check(!AdbBootstrapPolicy.inspect(request, installed).allowed, "错误 UID 未拒绝");

    result.putString("policy", "PASS");
    result.putStringArrayList("policyActions", actions);
    result.putInt("policyPort", AdbBootstrapPolicy.DEFAULT_PORT);
    result.putString("installedPackage", installed.name);
    result.putInt("installedUid", installed.uid);
  }

  private void runIdentityProbe(Context app, Bundle result) {
    boolean enabled = RootShell.enabled(app);
    boolean present = RootShell.present();
    result.putBoolean("rootEnabled", enabled);
    result.putBoolean("suPresent", present);
    if (!enabled || !present) {
      result.putString("identityResult", "UNAVAILABLE");
      result.putString("identityReason", !enabled ? "ROOT_SWITCH_OFF" : "SU_NOT_FOUND");
      result.putString("sessionEvidence", "NOT_COLLECTED");
      return;
    }

    RootSupportProbe rootId = probe(app, "id");
    result.putString("rootExecutionLevel", rootId.executionLevel().name());
    result.putInt("rootExitCode", rootId.exitCode);
    result.putString("sessionEvidence", "NOT_COLLECTED");
    if (rootId.executionLevel() != AdbdIdentity.Level.ROOT) {
      result.putString("identityResult", "UNKNOWN");
      result.putString("identityReason", "ROOT_ID_NOT_VERIFIED");
      return;
    }
    RootSupportProbe uid = probe(app, String.join(" ", AdbdIdentity.UID_ARGV));
    result.putInt("uidExitCode", uid.exitCode);
    if (!uid.succeeded()) {
      result.putString("identityResult", "UNKNOWN");
      result.putString("identityReason", "UID_PROBE_INCOMPLETE");
      return;
    }
    RootSupportProbe context = probe(app, String.join(" ", AdbdIdentity.CONTEXT_ARGV));
    result.putInt("contextExitCode", context.exitCode);
    AdbdIdentity.Evidence evidence =
        AdbdIdentity.assess(uid.body, uid.exitCode, context.body, context.exitCode);
    result.putString("daemonLevel", evidence.level.name());
    result.putInt("daemonPid", evidence.pid);
    result.putInt("daemonUid", evidence.uid);
    result.putString("daemonContext", evidence.selinuxContext);
    result.putString(
        "identityResult", evidence.level == AdbdIdentity.Level.UNKNOWN ? "UNKNOWN" : "UNVERIFIED");
    result.putString("sessionEvidence", "NOT_COLLECTED");
    result.putBoolean("daemonBirthVerified", false);
    result.putString("identityNote", "两份进程快照尚未核验 PID 出生身份，只作观测；强制 ADB 会话须另行取证");
  }

  private static RootSupportProbe probe(Context app, String command) {
    return RootSupportProbe.parse(RootShell.exec(app, command, -1));
  }
}
