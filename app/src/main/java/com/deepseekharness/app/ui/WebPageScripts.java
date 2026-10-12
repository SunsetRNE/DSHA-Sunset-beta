package com.deepseekharness.app.ui;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class WebPageScripts {
  private WebPageScripts() {}

  /** 应急页只注入语言与语法兼容，不注册正式设备/插件桥。 */
  public static String emergencyCompatibility(Context context) {
    return language(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js");
  }

  public static String compatibility(Context context) {
    return "window.__DSHA_NATIVE_PLUGINS__=true;\n"
        + language(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js")
        + "\n"
        + read(context, "web-integration/files.js")
        + "\n"
        + bridgePort()
        + "\n"
        + bridgePortRequired()
        + "\n"
        + read(context, "bridge-token-compat.cjs")
        + "\n"
        + read(context, "web-integration/startup.js");
  }

  public static String compatibility(
      Context context, com.deepseekharness.app.util.StartupPageGate gate) {
    return "window.__DSHA_PAGE_BINDING__={nonce:'"
        + gate.nonce()
        + "'};\n"
        + compatibility(context)
        + "\n//# sourceURL="
        + gate.source();
  }

  public static String language(Context context) {
    String id = new com.deepseekharness.app.core.ConfigStore(context).getUiLanguage();
    return "window.__DSHA_LANGUAGE__='"
        + id
        + "';window.dispatchEvent(new CustomEvent('dsha-language'));"
        + "if(!window.__dshaLanguageSelectionBound){window.__dshaLanguageSelectionBound=true;window.addEventListener('dsha-language-selected',e=>{if(e.detail==='en'||e.detail==='zh')window.DshaLanguage?.postMessage(e.detail);});}";
  }

  /**
   * 桥实际端口的静态快照，在 compat shim 之前执行；每次生成都重新读取，不用首选端口冒充。
   *
   * <p>这只是给「没有 provider 的旧 App / 旧内嵌页」保留的旧兼容来源，<b>不再是安全依据</b>：
   * 长寿命文档在前台期间桥换了端口时，这一行不会刷新。正式 App 页另外声明
   * {@link #bridgePortRequired()}，由 startup.js 安装的 provider 在<b>每次已知桥请求</b>前现取
   * {@code activePort()}，拿不到就拒绝，绝不回退这里的静态值或 3090。
   *
   * <p>桥不可用（未绑定 / 已停止 / 回退范围耗尽）时 {@code activePort()} 返回 0，这里就按 0 注入。
   * 应急页与外部来源页面不生成这一行，也不加载 shim。
   */
  public static String bridgePort() {
    return "window.__DSHA_BRIDGE_PORT__="
        + com.deepseekharness.app.HttpShellService.activePort()
        + ";";
  }

  /**
   * 声明「桥端口必须每次现取」：shim 见到这一行后，带 token 的已知桥请求一律先 await
   * {@code __DSHA_BRIDGE_PORT_PROVIDER__}，0 / 坏值 / 超时一律拒绝，不回退静态值与 3090。
   *
   * <p>由本方法（而不是 provider 脚本）在正式页里声明，是为了即使 startup.js 资产读不出来，
   * 也保持 fail-closed 而不是悄悄退回旧默认端口。应急页走
   * {@link #emergencyCompatibility(Context)}，不写这一行、不加载 shim。
   */
  public static String bridgePortRequired() {
    return "window.__DSHA_BRIDGE_PORT_REQUIRED__=true;";
  }

  private static String read(Context context, String path) {
    try (InputStream in = context.getAssets().open(path)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "";
    }
  }

  public static String back(Context context) {
    try (InputStream in = context.getAssets().open("web-integration/page.js")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return "(function(){"
          + new String(out.toByteArray(), StandardCharsets.UTF_8)
          + ";return window.__dshaPageBack();})()";
    } catch (IOException error) {
      return "false";
    }
  }
}
