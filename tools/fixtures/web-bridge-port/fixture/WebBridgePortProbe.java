package fixture;

import android.content.Context;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.ui.WebPageScripts;
import com.deepseekharness.app.util.StartupPageGate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主机探针：直接执行生产 WebPageScripts 的脚本生成，核对桥端口的浏览器侧接线。
 *
 * <p>只替换 Android / 桥 / 配置三类外部依赖（桩），脚本拼装与顺序判定都是生产代码本身。
 */
public final class WebBridgePortProbe {
  private static final Pattern PORT_LINE = Pattern.compile("window\\.__DSHA_BRIDGE_PORT__=(\\d+);");
  private static final String REQUIRED = "window.__DSHA_BRIDGE_PORT_REQUIRED__=true;";
  private static final List<String> FORMAL_ASSETS =
      List.of(
          "web-integration/es-compat.js",
          "web-integration/compat.js",
          "web-integration/files.js",
          "bridge-token-compat.cjs",
          "web-integration/startup.js");
  private static final List<String> EMERGENCY_ASSETS =
      List.of("web-integration/es-compat.js", "web-integration/compat.js");

  private static int checks;

  private static void check(boolean ok, String name) {
    checks++;
    if (!ok) throw new IllegalStateException("CHECK_FAILED: " + name);
  }

  /** 解析注入行；重复注入也判失败（同一个文档里出现两条端口来源会掩盖代次）。 */
  private static int injectedPort(String script) {
    Matcher line = PORT_LINE.matcher(script);
    if (!line.find()) throw new IllegalStateException("CHECK_FAILED: 缺少桥端口注入行");
    int value = Integer.parseInt(line.group(1));
    if (line.find()) throw new IllegalStateException("CHECK_FAILED: 桥端口注入行重复");
    return value;
  }

  private static int marker(String script, String needle) {
    int at = script.indexOf(needle);
    if (at < 0) throw new IllegalStateException("CHECK_FAILED: 缺少标记 " + needle);
    return at;
  }

  /** 真实资产模式：把生产生成的两段脚本落盘，交给 node 语法核对与内容判定。 */
  private static void dump(String directory) throws java.io.IOException {
    java.io.File target = new java.io.File(directory);
    if (!target.isDirectory() && !target.mkdirs()) throw new java.io.IOException("DUMP_DIR");
    Context context = new Context();
    StartupPageGate gate = new StartupPageGate(context, 1L, "http://127.0.0.1:3080/");
    HttpShellService.port = 3093;
    write(target, "formal.js", WebPageScripts.compatibility(context, gate));
    write(target, "emergency.js", WebPageScripts.emergencyCompatibility(context));
    System.out.println("WEB_BRIDGE_PORT_DUMP_OK");
  }

  private static void write(java.io.File directory, String name, String content)
      throws java.io.IOException {
    java.nio.file.Files.write(
        new java.io.File(directory, name).toPath(),
        content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && "--dump".equals(args[0])) {
      dump(args[1]);
      return;
    }
    Context context = new Context();
    StartupPageGate gate = new StartupPageGate(context, 1L, "http://127.0.0.1:3080/");

    // 1. 实际端口：每次生成都重新读 activePort()，注入实际值而不是首选 3090。
    HttpShellService.port = 3091;
    context.assets.opened.clear();
    int readsBefore = HttpShellService.reads;
    String formal = WebPageScripts.compatibility(context, gate);
    check(injectedPort(formal) == 3091, "必须注入实际端口 3091");
    check(injectedPort(formal) != 3090, "实际端口 3091 时不能用首选 3090 冒充");
    check(HttpShellService.reads > readsBefore, "生成脚本必须重新读取 activePort()");
    check(FORMAL_ASSETS.equals(context.assets.opened), "正式页资产顺序: " + context.assets.opened);
    check(
        formal.contains("window.__DSHA_PAGE_BINDING__={nonce:'" + StartupPageGate.NONCE + "'}"),
        "页面绑定 nonce 保持");
    check(formal.contains("//# sourceURL=" + gate.source()), "来源标注保持");

    // 2. 既有顺序不变：语言 → 语法/兼容 → 文件 → 端口 → shim → startup。
    int nativeAt = marker(formal, "window.__DSHA_NATIVE_PLUGINS__=true;");
    int languageAt = marker(formal, "window.__DSHA_LANGUAGE__='");
    int esAt = marker(formal, "/*ASSET:web-integration/es-compat.js*/");
    int compatAt = marker(formal, "/*ASSET:web-integration/compat.js*/");
    int filesAt = marker(formal, "/*ASSET:web-integration/files.js*/");
    int portAt = marker(formal, "window.__DSHA_BRIDGE_PORT__=");
    int requiredAt = marker(formal, REQUIRED);
    int shimAt = marker(formal, "/*ASSET:bridge-token-compat.cjs*/");
    int startupAt = marker(formal, "/*ASSET:web-integration/startup.js*/");
    check(
        formal.indexOf(REQUIRED) == formal.lastIndexOf(REQUIRED),
        "REQUIRED 只声明一次");
    check(
        WebPageScripts.bridgePortRequired().equals(REQUIRED),
        "REQUIRED 行与生产方法一致");
    check(
        nativeAt > 0 && formal.startsWith("window.__DSHA_PAGE_BINDING__={nonce:'"),
        "带页面绑定时绑定行在最前、__DSHA_NATIVE_PLUGINS__ 紧随其后");
    check(
        nativeAt < languageAt
            && languageAt < esAt
            && esAt < compatAt
            && compatAt < filesAt
            && filesAt < portAt
            && portAt < requiredAt
            && requiredAt < shimAt
            && shimAt < startupAt,
        "语言 / 兼容 / 文件 / 端口 / REQUIRED / shim / startup 顺序保持");
    check(portAt < shimAt, "端口必须在 compat shim 执行之前注入");
    check(requiredAt < shimAt, "REQUIRED 必须在 compat shim 执行之前声明");

    // 3. 桥不可用：activePort()==0 就注入 0（显式声明端点未知，不能省略、不能退回 3090）。
    HttpShellService.port = 0;
    context.assets.opened.clear();
    String unavailable = WebPageScripts.compatibility(context, gate);
    check(injectedPort(unavailable) == 0, "桥不可用时注入 0");
    check(unavailable.contains("window.__DSHA_BRIDGE_PORT__=0;"), "0 必须是显式注入行");
    check(FORMAL_ASSETS.equals(context.assets.opened), "不可用不改变资产顺序");

    // 4. 重新生成刷新：桥换端口后立刻反映新值，同端口重复生成稳定。
    HttpShellService.port = 3105;
    String rebound = WebPageScripts.compatibility(context, gate);
    check(injectedPort(rebound) == 3105, "重新生成必须读取新端口 3105");
    check(injectedPort(WebPageScripts.compatibility(context, gate)) == 3105, "同端口重复生成一致");
    check(injectedPort(rebound) != 3091, "重新生成不得保留旧端口 3091");

    // 5. 语言注入与顺序不受影响。
    ConfigStore.language = "en";
    String english = WebPageScripts.compatibility(context, gate);
    check(english.contains("window.__DSHA_LANGUAGE__='en';"), "语言注入保持");
    check(injectedPort(english) == 3105, "换语言时端口同样刷新");
    ConfigStore.language = "zh";

    // 6. 旧内核页面完成注入路径（无页面绑定重载）同样带实际端口。
    context.assets.opened.clear();
    String plain = WebPageScripts.compatibility(context);
    check(injectedPort(plain) == 3105, "旧内核路径同样注入实际端口");
    check(plain.startsWith("window.__DSHA_NATIVE_PLUGINS__=true;"), "无绑定时既有第一行保持");
    check(!plain.contains("__DSHA_PAGE_BINDING__"), "旧内核路径不伪造页面绑定");
    check(FORMAL_ASSETS.equals(context.assets.opened), "旧内核路径资产顺序一致");

    // 7. 应急页：只有语言与语法兼容，不读桥端口、不加载 shim、不注册设备/插件桥。
    int readsBeforeEmergency = HttpShellService.reads;
    context.assets.opened.clear();
    String emergency = WebPageScripts.emergencyCompatibility(context);
    check(HttpShellService.reads == readsBeforeEmergency, "应急页生成不得读取桥端口");
    check(!emergency.contains("__DSHA_BRIDGE_PORT__"), "应急页不含桥端口字段");
    check(!emergency.contains("__DSHA_BRIDGE_PORT_REQUIRED__"), "应急页不声明端口必须现取");
    check(!emergency.contains("bridge-token-compat"), "应急页不加载 compat shim");
    check(!emergency.contains("X-Token"), "应急页不含桥凭据改写");
    check(!emergency.contains("__DSHA_NATIVE_PLUGINS__"), "应急页不注册正式设备/插件桥");
    check(EMERGENCY_ASSETS.equals(context.assets.opened), "应急页资产顺序: " + context.assets.opened);
    check(emergency.contains("window.__DSHA_LANGUAGE__='zh';"), "应急页保留语言");

    System.out.println("WEB_BRIDGE_PORT_PROBE_CHECKS=" + checks);
  }
}
