# Root 支持完善 + UI 改造 + 内置插件改动：工作量评估

评估对象：把 [design.md](design.md) 的 `adb-bootstrap` 命名特权操作、身份等级判定与暴露面控制真正落地，并回答「UI 要改多少」「动内置插件有多少坑」。
方法：只读清点仓库（本轮实测命令与输出见 §2 与 §7），工时全部标「估」，来自人时模型而非实测。

---

## 1. 结论

| 工作包 | 内容 | 人时（估） | 折合（估） |
|---|---|---|---|
| A | Root 自举功能实现（策略 + 特权动作 + 授权模型 + 判定 + 测试） | 34–40 | 4.5–5 人日 |
| B | UI 改造（设备能力授权页 + 文案 + 布局验收） | 15–18 | 2 人日 |
| C | 相关内置插件改动（guide / app-integration / web-mobile / overlay） | 16–24 | 2–3 人日 |
| D | 文档、资产证明、构建与真机验收 | 9–12 | 1.5 人日 |
| **合计** | 单人串行 | **74–94** | **10–12 人日** |

关键路径不是代码量，而是**两条硬约束**：① 特权动作只能在设备真机验证（本容器内无法复验 `su`/`adbd`/`iptables`）；② 动任何内置插件字节都要重签 `runtime-descriptor.json`，否则 CI 资产校验直接红（§6，已实测）。

三档路线（互斥，选一条开工）：

| 路线 | 范围 | 人时（估） | 代价 |
|---|---|---|---|
| R1 最小可用 | `AdbBootstrapPolicy` + `RootShellMain` 分支 + 特权动作 authorize/revoke；UI 只加只读状态行 | 16–20 | 没有收窄档，5555 长期全接口可达（实测 F1：该通道等价 root） |
| R2 推荐 | §3 全量 A+B + C1/C5/C6 + D | 74–94 | 需一次内核模块发放 + 一次真机验收 |
| R3 含 Web 侧可操作 | R2 再加 C2/C3 全量（App 集成插件把授权开关暴露到 Web UI） | 96–124 | 插件补丁边界要重新审阅，回归面翻倍 |
| R4 只做判定 | 不实现自举，只做 adbd 身份等级判定 + 文档 + UI 文案校正 | 6–8 | 免配对授权仍需人工按 [README.md](README.md) §2 手工跑 |

---

## 2. 现状盘点（本轮读到的字面事实）

| # | 事实 | 锚点 |
|---|---|---|
| 1 | `adb-bootstrap` **不存在**：全仓 Java/Python 命中 0 处 | `grep -rl 'AdbBootstrap' --include=*.java app/src` → 0 |
| 2 | 特权侧只认两个 operation：空串（通用命令）与 `virtual-screen-start`，其余抛 `SecurityException("未知 Root 操作")` | `app/src/main/java/com/deepseekharness/app/RootShellMain.java:44-47` |
| 3 | 命名操作的核验模板已存在、可直接照抄形状 | `DeviceShellPolicy.inspectVirtualScreenLaunch()` 第 237–265 行 |
| 4 | `BLOCK` 面含 `setprop chcon restorecon mount` —— 自举**不能**走通用通道 | `DeviceShellPolicy.java:52-54` |
| 5 | 通用 root 通道入口已有，只需加一个 `execNamedOperation` | `RootShell.execVirtualScreen()` 第 104–120 行 |
| 6 | 授权存储只有一个布尔（`sms_read`），没有按能力分项 | `core/DeviceGrants.java`（全文 20 行） |
| 7 | 桥路由表无 `/app/root*`、无 `/app/grants` | `util/BridgeRoutes.java:58-84` |
| 8 | UI 已有 root 开关 + 验证按钮 + ADB 开关 + 配对入口 + 两条状态行 | `ui/DeviceGrantsFragment.java`（599 行）、`res/layout/fragment_device_grants.xml` |
| 9 | 内置插件资产逐文件记 sha256，成员集与字节都校验 | `app/src/main/assets/runtime-descriptor.json`（`inputs` 字段） |
| 10 | 原型脚本已存在但未进构建：4 个 274 行 shell | `docs/audits/root-adb-bringup-20261009/scripts/` |
| 11 | 当前版本 `0.2.13-20261007.1000` / versionCode **162**（AGENTS.md 写的 0.1.7-rc2 / 147 已过期） | `app/build.gradle:26,28` |

复验命令（把 §2 变成一次可跑的判据）：

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
grep -rl 'AdbBootstrap' --include=*.java app/src | wc -l          # 期望 0
grep -n 'operation.equals' app/src/main/java/com/deepseekharness/app/RootShellMain.java
grep -n 'setprop\|chcon\|restorecon' app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java | head -3
python3 tools/verify-runtime-descriptor-source.py                  # 期望 exit 0
```

实测输出：`grep` 命中 0；`RootShellMain` 只有 `virtual-screen-start` 两处；`BLOCK` 行如 §2 第 4 条；descriptor 校验回
`{"checkedAssets": 108, "checkedLaunchers": 277, "deferredArchives": [...]}`，exit 0。

---

## 3. 工作分解（A 功能 / B UI / C 插件 / D 发布）

### A. Root 自举功能（34–40 人时，估）

| 包 | 落点 | 交付 | 人时（估） |
|---|---|---|---|
| A1 | 新建 `util/AdbBootstrapPolicy.java` | 固定字段核验：`{operation, action∈{authorize,revoke,narrow,open,close}}` + `selfPackage`/`selfUid` 校验 + 常量目标表 | 3.5 |
| A2 | `ShellService`（特权侧） | 五个动作的真实实现 + 幂等前置（grep 去重 / `iptables -C` / 读当前属性）+ 原 `adb_keys` 备份 | 6–8 |
| A3 | `RootShellMain` / `RootShell` | `operation="adb-bootstrap"` 分支；`identityRequired` 列表扩充为「需要自包名 + uid」 | 2 |
| A4 | `core/DeviceGrants` + `ConfigStore` | 新增 `adb_bootstrap` 授权项：默认关、可撤销、不随备份/恢复生效 | 2.5 |
| A5 | 新 `util/AdbdIdentity.java` | adbd 自身 uid/SELinux 域判定 → 等级声明（本机实测为 `uid=0`、`u:r:ksu:s0`）；未知时**不得**按 shell 口径声明 | 3 |
| A6 | `HttpShellService` 白名单读 + `util/BridgePathPolicy` | 把 `/root/.dsh/adbkey.pub` 交给特权侧：只收公钥格式、不进日志与脱敏记录 | 3 |
| A7 | 策略 + 原型脚本 | 三档（常开+收窄 / 按需开 / 撤销）与 `--restore` 回滚路径 | 4 |
| A8 | KernelSU 模块资产 | `module.prop` / `post-fs-data.sh` / `service.sh` / `sepolicy.rule` 模板与发放说明 | 3 |
| A9 | 贯穿 | `[EXECUTION_UNKNOWN]` 不重放、`CONNECT_FAIL`、脱敏日志 | 2.5 |
| A10 | `app/src/test/.../util/AdbBootstrapPolicyTest.java` | 每个 action 的拒绝面（错包名/错 uid/多余字段/越界端口/注入串/重复执行） | 6 |

最小核验（这一步就能把 A1 做完并锁住语义）：

```java
// app/src/main/java/com/deepseekharness/app/util/AdbBootstrapPolicy.java（骨架，与 DeviceShellPolicy 同规格）
package com.deepseekharness.app.util;

import java.util.List;

/** 命名特权操作：设备状态自举。不走通用白名单，也不放宽 BLOCK 面。 */
public final class AdbBootstrapPolicy {
  public enum Action { AUTHORIZE, REVOKE, NARROW, OPEN, CLOSE }

  /** 全是常量：调用方永远给不出任意路径或任意属性名。 */
  public static final String ADB_KEYS = "/data/misc/adb/adb_keys";
  public static final String PORT_PROPERTY = "service.adb.tcp.port";

  public static final class Request {
    public final Action action;
    public final int port;
    Request(Action action, int port) { this.action = action; this.port = port; }
  }

  private AdbBootstrapPolicy() {}

  public static Result inspect(
      String operation, String action, int port, String selfPackage, int selfUid,
      String expectedPackage, int expectedUid) {
    if (!"adb-bootstrap".equals(operation)) return Result.deny("未知 Root 操作");
    if (!SelfPackageIdentity.validName(selfPackage) || !selfPackage.equals(expectedPackage))
      return Result.deny("自举请求的包名无法核验");
    if (selfUid != expectedUid) return Result.deny("自举请求的 uid 无法核验");
    Action parsed;
    try {
      parsed = Action.valueOf(action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return Result.deny("自举动作不在白名单");
    }
    if (parsed == Action.OPEN && (port < 1024 || port > 65535))
      return Result.deny("端口越界");
    if (parsed != Action.OPEN && parsed != Action.NARROW && port != 0 && port != 5555)
      return Result.deny("端口不在固定档位");
    return Result.allow(new Request(parsed, port == 0 ? 5555 : port));
  }

  public static final class Result {
    public final boolean allowed;
    public final String reason;
    public final Request request;
    private Result(boolean allowed, String reason, Request request) {
      this.allowed = allowed; this.reason = reason; this.request = request;
    }
    static Result allow(Request request) { return new Result(true, "", request); }
    static Result deny(String reason) { return new Result(false, "[POLICY_BLOCKED] " + reason, null); }
  }

  /** 只读判据提示：特权侧执行前必须先满足的幂等前置。 */
  public static List<String> preconditions(Action action) {
    return switch (action) {
      case AUTHORIZE -> List.of("grep -qF <pubkey> " + ADB_KEYS);
      case NARROW, CLOSE -> List.of("iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP");
      case OPEN -> List.of("getprop " + PORT_PROPERTY);
      case REVOKE -> List.of("wc -c " + ADB_KEYS);
    };
  }
}
```

验证件（写完 A1/A10 就能跑）：

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
bash build.sh :app:testStandardDebugUnitTest --tests '*AdbBootstrapPolicyTest'
python3 tools/verify-runtime-descriptor-source.py     # 新增 util 不动资产，应仍 exit 0
```

### B. UI 改造（15–18 人时，估）

要加的其实是**一块卡片 + 一个危险确认 + 状态回显**，不是重做页面：

| 包 | 落点 | 内容 | 人时（估） |
|---|---|---|---|
| B1 | `res/layout/fragment_device_grants.xml` | 「ADB 自举」卡片：授权 / 撤销 / 收窄 / 按需开-关 五个按钮 + 状态行 | 3 |
| B2 | `ui/DeviceGrantsFragment.java` | 异步执行、按钮防抖、进行中不可重入、失败原话回显（不自动换通道重试） | 4 |
| B3 | 同上 | 身份等级 + 暴露面档位 + `adb_keys` 字节数 / 5555 LISTEN / iptables 规则三态回显（`UiStateText` 按显示边界重渲染） | 2.5 |
| B4 | `tools/i18n/messages.json` | 中英条目 + `python3 tools/check-ui-i18n.py` 通过 | 1.5 |
| B5 | `ui/DshaDialogBuilder` | 危险确认：讲清「写 adb_keys 等于给持有私钥者一条 root shell」；短屏/大字体可滚动 | 1.5 |
| B6 | `LayoutAuditInstrumentation`（debug 源集） | style 中英文 × 日夜 × 短屏 × 1.3 倍字体 | 3 |
| B7 | 主题一致性 | 复用 `CardPage` / `DshaText` 样式，不自造渐变或硬编码色 | 1 |

复验：

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
python3 tools/check-ui-i18n.py
bash build.sh :app:assembleStandardDebug
# 设备侧布局验收（需真机/模拟器接上）：
# ./gradlew :app:connectedStandardDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.deepseekharness.app.ui.LayoutAuditInstrumentation
```

### C. 相关内置插件（16–24 人时，估）

按「是否真的需要改」排序，只有 C1 是必须的：

| 包 | 插件 | 为什么 | 人时（估） |
|---|---|---|---|
| C1 | `dsh-device-shell-guide` | 它的 `PROMPT` 写死了通道优先级与硬约束（第 8–49 行）。自举落地后必须补：自举是受控能力、身份等级须先判定后声明、`[POLICY_BLOCKED]` 不因 root 放行 | 2 |
| C2 | `dsh-app-integration`（`internal:true`，实体在 `assets/app-integration/`） | 若要让 Web UI 能开关自举授权，得加只读状态 + 受控按钮；只读 4 h，可写 8 h | 4–8 |
| C3 | `dsh-web-mobile` | 上游产物固定 commit `a094288…`，**不能直接改** `lib/*.js`，必须经 `tools/apply-mobile-client-patches.mjs` 的锚点补丁 | 3–8 |
| C4 | `dsh-status-overlay` | 悬浮条要显示 root/ADB 通道状态时才动 | 2 |
| C5 | 资产证明链 | 重生成 `runtime-descriptor.json`、核对成员集、`builtin-plugins.json` 版本递增、重生成 `BuiltinPluginRegistry.java` | 3 |
| C6 | 备份/恢复侧 | 验证系统插件新版本不破坏 `SystemPluginState` / `PluginRestoreGraph` 的「只恢复禁用意图、不恢复实体」判定 | 2 |
| C7 | 加载验证 | profile `bundles` + `link:` 声明 + `node_modules` 链接 + `.disabled` 优先，四条契约各验一次 | 2 |

### D. 文档、构建与验收（9–12 人时，估）

| 包 | 内容 | 人时（估） |
|---|---|---|
| D1 | `docs/security-model.md` 加一行（design.md §7 给的原文）、`docs/plugins.md` 契约、`docs/releases/` 验收记录 | 2 |
| D2 | `CHANGELOG` + 版本递增 + `assembleStandardRelease` / `assembleLowRelease` + 真机验收（rooted 与非 rooted 各一台） | 6 |
| D3 | 全量单测 + 失败排查 | 2 |

### 三档路线之外：交给 AI 自己做

| 档 | 人时（估） | 日历天（估） | 人民币 |
|---|---|---|---|
| 纯 AI（代码 + 文档 + 单测，真机步骤留给你） | 74–94 × 0.35 ≈ 26–33 | 2–3 | 未知（仓库内无单价基线，按你方费率 × 上表人时） |
| 混合（AI 写码，人跑构建与真机验收） | ≈ 40–50 | 3–5 | 同上 |
| 全人工 | 74–94 | 10–12 | 同上 |

金额一律不填数：仓库里没有可引用的费率来源，编一个厂商报价就是造数。

---

## 4. 阶段表（里程碑 / 验收判据 / 回滚点）

| 阶段 | 内容 | 验收判据（可直接跑） | 回滚点 |
|---|---|---|---|
| M1 策略层 | A1 + A10 | `--tests '*AdbBootstrapPolicyTest'` 全绿；`verify-runtime-descriptor-source.py` exit 0 | 只新增文件，`git revert` 即净 |
| M2 特权链 | A2/A3/A6/A9 | 设备 `su -c` 跑 synthesize 的固定动作，返回结构化结果；人为断端口回 `CONNECT_FAIL` 且不换通道 | `RootShellMain` 分支删除后回到「未知 Root 操作」 |
| M3 授权与判定 | A4/A5 | 关掉授权项 → 返回 `[POLICY_BLOCKED]`；adbd 身份判定写入机型档案且不猜等级 | 授权项回退默认关 |
| M4 暴露面 | A7/A8 | 另一台机器 `nc <设备IP> 5555` 超时、本机通；`--restore` 后 `grep dpt:5555` 为空；撤权后 `adb_keys` 0 字节、5555 不 LISTEN | 模块删除 + `iptables -D` |
| M5 UI | B1–B7 | `check-ui-i18n.py` 通过；`LayoutAuditInstrumentation` 中英/日夜/短屏/1.3 倍字体无溢出 | 单页回退，不影响运行时 |
| M6 插件 | C1/C5/C6/C7 | descriptor 复生成后校验通过；插件版本递增后 `SystemPluginState` 判定不变 | 插件资产回退到上一提交字节 |
| M7 发布 | D1–D3 | 双 flavor 构建 + 证书/版本码核对 + 真机验收记录落 `docs/releases/` | 保留上一代 APK，覆盖安装回滚 |

---

## 5. 风险登记

| # | 风险 | 触发迹象 | 处置 |
|---|---|---|---|
| R1 | 把自举做成「给通用通道开后门」 | 有人提议顺手放开 `setprop` / `chcon` | 守住 design.md §1 不变量 1；参数表必须是常量 |
| R2 | 身份等级被想当然声明 | 文档写「ADB 通道 = shell 身份」 | 先查 `ps -A -o PID,UID,NAME \| grep adbd` 再声明；本机为 root 等级 |
| R3 | 收窄规则不持久 | 重启后 5555 又全接口 | 模块的 `service.sh` 每次补；文档写明规则不跨重启 |
| R4 | 插件的 `PROMPT` 两份副本漂移 | 系统提示里那份没同步改 | C1 落地时把两处一起改，并在验收里 diff 文本 |
| R5 | 动了插件字节忘了重签描述符 | CI 报 `DESCRIPTOR_SOURCE_BYTES` | 见 §6 第 1 条，改任何插件文件后必跑 `prepare-runtime-descriptor.py` + 校验 |
| R6 | 真机验证被压缩 | 只有一台机器、且是 rooted 机型 | 非 rooted 退化路径（`[DEVICE_CHANNEL_UNAVAILABLE]`）必须单独验一次 |

---

## 6. 改内置插件的坑（逐条已实测）

1. **改一行注释就打破受管证明**。实测：向 `builtin-plugins/dsh-device-shell-guide/lib/index.js` 追加一行注释后

   ```
   ValueError: DESCRIPTOR_SOURCE_BYTES: builtin-plugins/dsh-device-shell-guide/lib/index.js   （exit 1）
   ```

   还原后 exit 0、`checkedAssets: 108`。就是说插件改动必须连带重生成 `app/src/main/assets/runtime-descriptor.json`，否则 CI 资产校验这一步先红。
2. **成员集也锁死**：`verify-runtime-descriptor-source.py` 用 `assetTrees` 递归枚举，新增/删除插件文件会报 `DESCRIPTOR_SOURCE_MEMBER_SET`，不是只算内容哈希。
3. **注册契约有 4 条**，缺一条插件就不加载：`builtin-plugins.json` 名单 → `profiles/web/package.json` 的 `dsh.profile.bundles` → `dependencies` 里的 `link:/root/dsha-*` → `node_modules/<name>` 符号链接；`.disabled` 存在时禁用意图优先。
4. **`dsh-web-mobile` 不能直接改**：上游产物按 commit + 逐文件 sha256 锚定（`upstreamClientHash` 与三个 server 模块哈希），必须经 `tools/apply-mobile-client-patches.mjs` 的命名锚点替换，直接编辑会在下一次重建时被覆盖或抛锚点异常。
5. **系统插件不进用户备份**：改动靠 APK 资产重装，用户目录里的旧实体与备份里的副本都不作数；`SystemPluginState` 只谈禁用意图。
6. **插件里没有 i18n 通道**：`PROMPT` 是中文硬串，`tools/i18n/messages.json` 只管 App 侧 UI 串 —— 想双语得自己加分支，或接受单语。

---

## 7. 四态

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| `adb-bootstrap` 命名操作尚未实现，全仓命中 0 | 已知 | 2026-10-08（本轮 grep） | `grep -rl 'AdbBootstrap' --include=*.java app/src` → 0 |
| 改内置插件资产会打破 descriptor 校验 | 已知 | 2026-10-08（本轮实测） | 追加注释后 `DESCRIPTOR_SOURCE_BYTES`，exit 1；还原后 exit 0 |
| 当前版本 `0.2.13-20261007.1000` / versionCode 162 | 已知 | 2026-10-08（读 `app/build.gradle:26,28`） | 文件字面值 |
| AGENTS.md 记的 `0.1.7-rc2 / 147` 版本口径 | 过期 | 2026-10-07 | 已被 `0.2.13-20261007.1000 / 162` 取代；以 `app/build.gradle` 为准 |
| 本机 adbd 以 `uid=0`、`u:r:ksu:s0` 运行，ADB 通道等价 root | 已知（限 TB321FU 机型） | 2026-10-09 报告所测窗口 | [README.md](README.md) §3 F1；换 ROM 须重判，不能外推 |
| 工时 74–94 人时 | 推测 | — | 人时模型估，非实测；误差主要来自真机验收与插件补丁边界 |

标签行 ——

- 已知：§2 十一条全部来自本轮 grep/读文件；§6 第 1 条为本轮实测（改文件 → 校验失败 → 还原 → 校验通过）。
- 推测：工时表；A2 特权实现的实际难度随 root 方案（KernelSU / Magisk / ROM 自带）浮动。
- 未知：你方费率基线（所以钱一栏留空）；目标机型的 `su` 行为与厂商链对 iptables 的重建时机；`dsh-web-mobile` 若真要做 UI 改动，补丁锚点是否仍与上游一致。
- 过期：`AGENTS.md` 的 `0.1.7-rc2 / versionCode 147`（有效期到 2026-10-07，依据 `app/build.gradle` 的 `0.2.13-20261007.1000 / 162`），替代口径以构建文件为准。

---

截至 2026 年 10 月 8 日，本文 §2 现状与 §6 实测结论在本地工作树复核过；工时与金额未实测。
适用范围：本仓库（`/root/Documents/deepseek-harness/default-workspace/DSHA`）当前工作树，`standard` 与 `low` 两个 flavor 共用同一份功能代码。
已知推测未知：工时是模型估；真机侧 `su`/`adbd`/`iptables` 行为、以及 Web UI 侧插件改动量都需要在目标设备与真实上游产物上复验。
依赖与边界：特权动作与暴露面验证需要一台已授权的 rooted 设备；插件字节改动依赖 `tools/prepare-runtime-descriptor.py` 重签描述符；容器内无法替代真机复验。
