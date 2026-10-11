# ADR 0003：运行时端点的单一来源（端口去写死）

状态：**待确认，尚未实施**。本文只给方案与影响面，不改代码。依据是 2026-10-11 对当前 `main` 的源码核验。

相关：[ADR 0004 统一 ADB 授权权威](0004-unified-adb-authority.md)、[安全模型](../security-model.md)、[模块图](../module-map.md)。

## 1. 问题

「3080 端口写死」不是一处硬编码，而是**同一事实被抄了四遍，只有一份可配**。核验结果：

| 层 | 位置 | 现状 |
|---|---|---|
| 常量 | `util/Constants.java:22-31` | `DSH_WEB_PORT=3080`、`SHELL_BRIDGE_PORT=3090`、`LAN_BRIDGE_PORT=3081`、`ADB_DEFAULT_CONNECT_PORT=5555` |
| 鉴权默认 | `util/DshAuthUrl.java:15-18` | `LOOPBACK_BASE_URL="http://127.0.0.1:3080/"`、`AUTH_URL_PREFIX`（解析器本身已支持任意端口） |
| 端口仲裁 | `util/WebPortPolicy.java:41` | `reserved(port)` 把 `3081/3090` 写成字面量判据 |
| LAN 代理 | `LanProxyService.java:38-39` | `LAN_PORT=3081`、`DEFAULT_BACKEND_PORT=3080` |
| 桥本体 | `HttpShellService.java:47` | `PORT=3090`；IPv4 主监听 + `::1` 附加监听（`serveIpv6`） |
| 配置校验 | `core/ConfigStore.java:167-186`、`util/ConfigInput.java:12-15` | 回落到 3080；拒收 3081/3090，错误文案也是字面量 |
| guest 客户端（端口闸） | `app/src/main/assets/bridge-token-compat.cjs:8` | `url.port!=='3090'` → 不重写、直接拒绝；**这是安全边界**，非 3090 一律不注入 `X-Token` |
| guest 客户端（脚本） | `app/src/main/assets/adb-shell.py:188,306,333,413` | 四处 `http://127.0.0.1:3090/...` |
| guest 客户端（内置插件） | `builtin-plugins/dsh-tool-vscreen/lib/server.cjs:59`、`dsh-computer-use-android/lib/server.cjs:31`、`dsh-task-notifier/lib/index.js:36`、`dsh-status-overlay/lib/index.js:32,390` | 五处 `http://127.0.0.1:3090/...` —— 本轮盘点补出，上一稿漏了这 4 个插件 |
| guest 包装 | `app/src/main/assets/adb-setup.sh:110-115`、`dsha-device-shell.sh` | **上一稿此处写错**：生成的 `/root/dsh-bin/adb-shell` **不含端口**，只 `exec python3 /root/.dsh/adb-shell.py "$@"`；端口在它 exec 的 `adb-shell.py` 里 |
| agent 提示词 | `HttpShellService.java:1280` | `/app/help` 正文里 3090 出现 **12 次** |
| agent 提示词 | `app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js:10,16` | 内置插件的系统提示词里 3090 出现 2 次（老 App 兜底清单） |
| 文案 | `tools/i18n/messages.json:276,802,6905,6913,6922` | 「3080（1—65535，避开 3081 / 3090）」等都是字面量 |
| 审计脚本 | `tools/device-policy-device-audit.py:95` | 断言 `BRIDGE_PROTOCOL=2`（已与代码漂移，见下） |
| 其它固定端口 | `vscreen/VirtualScreenCore.java:59-60` | 虚拟屏默认 `8998`（范围 8000–8999）；实际总是被 `VirtualScreenManager.java:338` 的随机 8800–8899 覆盖，所以它只是缺省值 |
| 已参数化的对照 | `recovery/RecoveryRuntime.java:455,489` | recovery 用 `--port 0` + `DSHA_RECOVERY_BROKER_PORT` env —— **全库唯一把动态端口注入 guest 的链路**，见 §2 |

**合计：guest 侧 7 个文件 / 12 个字面量读取点**（外加 Host 侧 `/app/help` 的 12 次）。

**已经在做对的部分**：Web 端口其实已经从宿主参数化到容器 —— `util/WebLaunchCommand.java` 明确把 `--port <listenPort>` 传给 guest，`listenPort` 来自 `WebPortPolicy.choose()`，所以 `dsh web` 本身不依赖 3080。真正没打通的是**桥 3090 与 LAN 3081 这两条不可协商的常量**，以及默认值/保留判据的四处抄写。

**但 Web 端口有 8 个替换点，其中两个行为不一致**（盘点结果）：非数字/越界 → 3080（`core/ConfigStore.java:179-186`）；等于 3081/3090 → **静默改成 3080**（`ConfigStore.java:169-171`）或 **保存直接抛错**（`util/ConfigInput.java:12-14`）—— 同一个非法值，一条路径改写、一条路径拒绝。其余是端口占用与动态分配的正常兜底：`WebPortPolicy.java:30`（越界）、`:31/41`（保留跳过）、`:32-36`（上次 fallback）、`:37`（listen=0）、`:60-63`（鉴权拒绝非本轮端口）。**真正需要统一的是前两个**。

**附带发现（本轮核验，与本 ADR 独立）**：`HttpShellService.java:1263` 的 `BRIDGE_PROTOCOL = 3`，而 `AGENTS.md:131` 与 `tools/device-policy-device-audit.py:95` 都还写 `2`。文档与真机审计脚本已经和代码漂移，那条审计今天应当失败。这正是同一个病：**版本/端口这种单一事实被抄进多个地方，然后各自漂移**。

## 2. 已有可复用的机制

不要新造轮子，仓库里这两条已经是正确形态：

- `util/BridgeCredentialFiles.publish()`：在选定数据根里 **原子写入 + 读回核验**，同时落 `.bridge_token` 与 `.bridge_headers`，权限 0600。
- `HttpShellService.noteBindOk()` / `writeBridgeStatus()`：把 `ok port=<实际端口>` 写进 `/root/.dsh/.bridge_status`。**但盘点确认：guest 侧没有任何脚本读它**（全库 grep 只命中 debug/审计代码）—— 它是**纯诊断，不是契约**。要把它变成契约，除了加写入，还必须补上读取端（见 §3.3）。
- `bridge/AdbBridge`：无线调试的 `connect_port` 走 `/root/.dsh/adbkeys/connect_port` 文件 + `connect_port_history`（`adb-shell.py:147-158`）。动态端口在 ADB 这条链上本来就是文件驱动的，而且 **host 与 guest 读同一个文件**（`DeviceBridgeService.readConnectPort:529-541`）。
- **`recovery/RecoveryRuntime.java:489` 是全库唯一「动态端口经 env 进 guest」的先例**：写 `DSHA_RECOVERY_BROKER_PORT`，读取端是 guest 资产 `recovery-agent.js:50,54`。§3.2 的 env 注入直接照抄这条已跑通的路径，不发明新机制。

## 3. 决策

### 3.1 唯一真相：`util/RuntimeEndpoints`

新增纯逻辑类（零 Android import，必须配单测），承载一次运行期的全部端点事实：

```
webPreferred  用户配置，默认 3080
webListen     WebPortPolicy 选定值；0 表示交给 dsh/内核分配
bridge        桥端口，默认 3090；0 表示由内核分配
lan           LAN 代理端口，默认 3081
generation    本次启动代次
```

- `reserved(port)` 改为 `reserved(port, endpoints)`：保留判据来自**实际的 bridge/lan 值**，不再出现 3081/3090 字面量。
- `WebPortPolicy.choose()`、`ConfigStore.getPortInt()`、`ConfigInput.port()` 的回落与拒收都改为引用同一对象。
- `DshAuthUrl.LOOPBACK_BASE_URL` 降级为「测试与文档用默认值」，生产路径只使用解析出来的实际端口（`Parsed.loopbackBaseUrl` 已经是这个语义）。

### 3.2 启动器是契约的生成点

虚拟环境启动器（`runtime/RuntimeLauncher` + `core/WebLifecycleController`）在拉起 web 之前，把**实际端点**写成一份受校验的端点描述，与凭据同目录、但语义分开：

- 文件：`/root/.dsh/.bridge_endpoint`（或与 `.bridge_status` 合并，二选一，见 §5 开放问题）
- 内容：schema 版本、`bridgeProtocol`、`webListen`、`bridge`、`lan`、`generation`、写入时间
- 纪律：复用 `BridgeCredentialFiles` 的 atomic + 读回核验；**不是凭据**，因此不参与凭据排除规则，但要随受管运行时一起重建
- 同时以 env 注入一次（`DSHA_BRIDGE_PORT` / `DSHA_WEB_PORT`），供进程最早的消费者（Node preload、被 exec 的 bash）使用

顺序要求：桥必须**先绑定成功再写端点、再启动 web**，否则 guest 会拿到一个还没生效的端口。这与现有「3090 桥就绪后再拉 web」的顺序一致（`core/WebLifecycleController.java:379-387`），不改变生命周期。

### 3.3 guest 侧一律读契约，不再写常量

| 消费者 | 改法 | 缺省行为 |
|---|---|---|
| `bridge-token-compat.cjs:8` | 端口闸从 `!=='3090'` 改为读注入值 | 读不到时回退 3090（**有意的兼容回退，必须显式注释**） |
| `adb-shell.py`（188/306/333/413） | 从端点描述读 | 同上 |
| `dsh-tool-vscreen/lib/server.cjs:59` | 从端点描述读 | 同上 |
| `dsh-computer-use-android/lib/server.cjs:31` | 从端点描述读 | 同上 |
| `dsh-task-notifier/lib/index.js:36` | 从端点描述读 | 同上 |
| `dsh-status-overlay/lib/index.js:32,390` | 从端点描述读 | 同上 |
| `dsh-device-shell-guide/lib/index.js:10,16` | 端口改为运行时读取 + 兜底文案 | 老 App 兜底清单保留字面量（它本来就是给老版本用的） |
| `/app/help` 正文（`HttpShellService.java:1280`） | 模板插值（12 处） | 无 |
| `/root/dsh-bin/adb-shell` 包装 | **不用改** —— 盘点确认它不含端口，只 exec `adb-shell.py` | — |

> 4 个内置插件（vscreen / computer-use / task-notifier / status-overlay）是上一稿漏掉的最大一块：它们都是 `managed-runtime-inputs.json` 的受管资产，改它们**同样会改 `runtimeId`**，必须与阶段 1 一起打包。

**为什么不简单删掉 3090 直连**：同机可能存在第二个 DSHA 实例，桥属于启动它的那个实例（`adb-shell.py:420-423` 已有这条注释）。契约必须带 `generation`，让 guest 能判断「端点描述是否属于我这一轮」，而不是盲信一个数字。

### 3.4 帮助文案模板化

`/app/help` 与内置插件的提示词是**发给 agent 的**，写错端口等于让 agent 打不通桥。两者都改为从端点事实生成；`BRIDGE_PROTOCOL` 一并从 `RuntimeEndpoints`（或 `BuildConfig`）取，消灭 §1 的版本漂移。

## 4. 影响面清单

### 4.1 按「是否改变受管运行时身份」分组（关键）

`app/src/main/assets/runtime-descriptor.json` 是**生成物且已提交**，其 `runtimeId` = 对 `managed-runtime-inputs.json` 里 141 个 Java 源 + 113 个资产 + launcher 树 + generator 源的 SHA-256。**改这些文件就会改 `runtimeId`，从而走受管运行时更新/试用链**；改其余文件不影响身份。

| 分组 | 文件 | 影响 |
|---|---|---|
| **改 = 变 runtimeId** | `HttpShellService.java`（绑端口、`.bridge_status`、`/app/help`）、`bridge/AdbBridge.java`、`util/DshAuthUrl.java`、`util/WebLaunchCommand.java`、`core/ConfigStore.java`、`core/HarnessController.java`、`core/WebLifecycleController.java`、`util/BridgeCredentialFiles.java`、`util/BridgeRoutes.java`、`util/WebProcSel.java`、`runtime/*`（整目录）| 必须 `tools/prepare-runtime-descriptor.py --write` 并审阅 `runtimeId` 变化；按 `ManagedRuntimeTransaction` / `HostDataTransaction` 走一次真实 `RuntimeTrial`（含**实际端口与鉴权**核验）才能提交 |
| **改 = 变 runtimeId（guest 资产）** | `adb-shell.py`、`bridge-token-compat.cjs`、`device-shell-policy.py`、`builtin-plugins/dsh-device-shell-guide/**`、**另加 4 个内置插件**（`dsh-tool-vscreen/**`、`dsh-computer-use-android/**`、`dsh-task-notifier/**`、`dsh-status-overlay/**`）、`managed-runtime-inputs.json` 本身 | 同上。注意 `adb-setup.sh` 生成的包装**不含端口**，本项改动不触发 `DeviceScriptProof` 锚点与 `RuntimeTools.matchesSignedFile` 的连带修改 |
| **改 ≠ 变 runtimeId（可先做）** | `util/WebPortPolicy.java`、`LanProxyService.java`、`util/ConfigInput.java`、`ui/*`、`res/layout/*`、`tools/i18n/messages.json`、`app/src/debug/*` | 普通改动 + 单测 |

> 注意 `managed-runtime-inputs.json` 自身也在 `inputs` 里，改它的清单本身就会改 `runtimeId`。

### 4.2 测试与工具

- 单测（现值硬编码在测试里，需同步）：`app/src/test/.../util/WebPortPolicyTest.java`、`ConfigInputTest.java`、`DshAuthUrlTest.java`、`MicrophonePolicyTest.java`、`WebProcSelTest.java`、`core/HarnessControllerRunTest.java`、`runtime/RuntimeHostPortsTest.java`
- 台账测试：`tools/test-bridge-token-compat.mjs`（含 3090 端口闸用例）、`tools/test-runtime-trial.mjs`、`tools/test-mobile-auth.mjs`
- 审计：`tools/device-policy-device-audit.py:95`（顺带修协议版本）、`app/src/debug/.../WebPortAudit.java`、`BridgeAuditInstrumentation.java`
- 描述符核验：`tools/prepare-runtime-descriptor.py --check`、`tools/verify-runtime-descriptor-source.py`

### 4.3 文档与文案

`AGENTS.md`（「Web / 桥 / 终端」段的 `BRIDGE_PROTOCOL=2`）、`README.md` / `README.en.md`、`docs/security-model.md`、`CHANGELOG.md`、`docs/releases/*`（历史记录不改）、`tools/i18n/messages.json` 的五条端口文案。

## 5. 开放问题（需要你拍板）

1. **端点契约放哪**：扩展 `/root/.dsh/.bridge_status`（改动最小，但它现在是「诊断」语义），还是新增 `.bridge_endpoint`（语义干净，多一个文件与一条凭据排除规则）？
2. **是否允许 bridge=0 动态分配**：最彻底，但会让「同机第二个实例」「保活重连」「LAN 转发」都要重新推导；建议先做「可配置固定端口」，动态分配留到第二阶段。
3. **缺省回退策略**：guest 读不到契约时回退 3090（向后兼容、但可能打错实例）还是直接拒绝（更安全、但会让「App 降级/契约未写」变成硬故障）？我倾向**回退 + 在 `/app/help` 与 `.bridge_status` 里显式标注「端口来自缺省值」**。
4. **3080 是否也进契约**：它已经被 `--port` 参数化，进契约主要是为了消灭 `DshAuthUrl` 的默认常量与文案字面量，收益中等。

## 6. 建议的迁移顺序

- **阶段 0（不碰 runtimeId）**：抽 `RuntimeEndpoints`，收敛 5 处默认值与 `reserved()` 判据，`LanProxyService` / `ConfigInput` / `WebPortPolicy` 改用它。行为不变，补单测。可以独立合并。
- **阶段 1（碰 runtimeId，一次性打包）**：端点描述落盘 + env 注入 + guest 四个消费者改读契约；保持 3090/3081 默认值。必须重跑描述符并走一次受管试用。
- **阶段 2（碰 runtimeId）**：`/app/help` 与内置插件提示词模板化，顺带修 `BRIDGE_PROTOCOL` 漂移。
- **阶段 3**：开放非默认端口（配置项 + `reserved` 动态判据），再做 bridge 动态分配。

## 7. 验收

1. 单测：`RuntimeEndpoints`、动态 `reserved()`、`DshAuthUrl`；`bash build.sh :app:testStandardDebugUnitTest`。
2. `tools/test-bridge-token-compat.mjs` 增加**非 3090** 端口的重写用例（含端口不匹配时仍拒绝）。
3. `python3 tools/prepare-runtime-descriptor.py --check` 在 `--write` 后通过；`runtimeId` 变化被明确记录。
4. 真机：把三个端口都改成非默认值启动一次，agent 侧 `/app/help`、`adb-shell`、LAN 访问与 `.bridge_status` 一致；同机双实例时第二个实例不会误用第一个的端点。
5. 不回归：`WebProcSel` 的进程判别、停止哨兵与出生身份核验全部照旧（本 ADR 不碰它们）。

## 8. 明确不做

- 不放宽 token 门控，不改 `.bridge_headers` 语义。
- 不用端口反查 PID（`WebProcSel` 的设计前提不变）。
- 不为了「看起来统一」把 `Constants.DEFAULT_WORKDIR` 之类的非端口常量一起搬家。
