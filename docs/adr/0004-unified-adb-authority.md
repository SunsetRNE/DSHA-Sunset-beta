# ADR 0004：统一 ADB 授权权威

状态：**阶段 0+1 已实施**（2026-10-11，工程包 `install-174-adbauth.apk`）；阶段 2–3 未实施，见 §10 实施记录。方案与影响面的依据是 2026-10-11 对当前 `main` 的源码核验。

相关：[ADR 0003 运行时端点单一来源](0003-runtime-endpoint-single-source.md)、[安全模型](../security-model.md)、[ADB 流程审计](../adb-flow-audit.md)、[Root ADB 自举审计](../audits/root-adb-bringup-20261009/README.md)。

## 1. 问题

设备命令的**授权**被实现成了四套并列的开关，而它们其实是同一个东西的不同取得路径：

`app/src/main/res/layout/fragment_device_grants.xml:7-51` 在「设备通道」标题下并列四张卡：

1. `config_root_shell` + `device_root_status` + `device_root_verify` —— Root Shell
2. `workspace_shizuku_status` + `workspace_shizuku_auth` + `device_shizuku_open` —— Shizuku
3. `config_adb_enable` + `config_adb_status` + `config_adb_pair` —— ADB（无线配对）
4. `root_bootstrap_card`（21 个控件）—— Root 自举 ADB

其中 **Shizuku 被摆成 ADB 的同级**，而它自身必须由 ADB 或 root 启动服务才能工作 —— 它是中间层，不是权威。用户的原话点破了这一点：*「无线调试和 USB 调试也是 ADB 授权，Shizuku 只是中间层作用都是 ADB 授权，却各自散落且在 UI 上是并列的」*。

### 1.1 状态散落在至少 8 处（另有 2 份各自拼装的报告）

| 状态 | 位置 |
|---|---|
| `allow_root_shell`、`adb_enabled` | `Constants.PREFS`（`util/Constants.java:64` 与 `DeviceBridgeService.java:78`） |
| `sms_read`、`adb_bootstrap`、`adb_bootstrap_revision` | `dsha_device_grants`（`core/DeviceGrants.java:13-40`） |
| Root 运行期最后结果 | `RootShell.lastResult`（`RootShell.java:62`） |
| Root 授权代次 | `RootShell.authorizationRevision()` / `authorizationChanged()`（`RootShell.java:53-57`） |
| Shizuku 失败原因 | `ShizukuShell.failure`（`ShizukuShell.java:75-100`） |
| ADB 通道状态 | `DeviceBridgeService.adbState` / `adbDetail` 两个 `volatile static`（`DeviceBridgeService.java:73-74`） |
| 桥就绪 | `HttpShellService.isReady()` / `isStarting()` / `bindError()` |
| 自举短期授权 | `RootBootstrapAuthority` 的 ticket / 120s / LocalServerSocket / grant revision |
| ADB 的第二份报告 | `ui/DeviceGrantsFragment.java:546-577` `computeAdbStatus()` 把「观测状态 + `AdbBridge.status()` 的 key/deps/port」再拼一段文本 |
| 诊断页的第三份报告 | `core/DiagnosticRepository.java:485-497` 把 `RootShell.status` + `ShizukuShell.userStatus` + `adbState` + 无障碍状态**按固定顺序拼成一段**，不做一致性对齐 |
| 屏幕授权 | `HttpShellService.hasScreenGrant`（`HttpShellService.java:1019-1024`），绑定 DSH `webGeneration` |

**已经出现过自相矛盾**：`HttpShellService.java:321-326` 的注释记录了一次真机事故 —— App 自检报「已重新写入 token」，容器侧 selftest 同时报「缺 `.bridge_token`」。这是「多个组件各自维护同一事实」的必然结果，授权状态今天有同样的风险。同类注释还有 `RootBootstrapAuthority.java:77-86`（「不再要求 ADB 通道先行开启，否则与该用途自相矛盾」）与 `AdbBootstrapController.java:331-335`（实机缺陷：顺序反了导致会话核验永远拿不到计划）。

### 1.1.1 两套事实：意图 vs 观测（结构性矛盾，不是 bug）

- `adb_enabled` 是**用户意图**且持久化；`DeviceBridgeService.adbState` 是**观测**且不持久化（进程死即回 `unknown`）。
- `deviceExecute`（`HttpShellService.java:798`）只读**意图**，UI 读**观测**。于是「通道已启用」与「还没配对」可以同时为真；进程重启后「设置页说开着、状态行说未知」。
- `ui/AdbPairActivity.java:400-401,445-446,497-498` 会**直接写** `DeviceBridgeService.adbState`，绕过 `DeviceBridgeService.java:593-597` 的实例防重入校验 → 服务未运行/已停止时，UI 仍显示配对页写入的状态。
- `ShizukuShell.userStatus()` 会说「已授权 · 设备 Shell 已连接，可直接使用，无需 ADB 配对」，但 `deviceExecute` 可能因 Root 可用而根本不用 Shizuku，短信场景更是**明确跳过 Shizuku**（`HttpShellService.java:795`）→ 用户以为在用 Shizuku，实际走 root。

### 1.1.2 六条来源的真实状态（穷尽盘点结论）

| 来源 | 是否有代码路径 | 执行身份 | 权威性质 |
|---|---|---|---|
| **App 自举** | 有，且最完整（`core/AdbBootstrapController`、`RootBootstrap*`、`util/AdbBootstrap*`） | `uid=0` → 写 `/data/misc/adb/adb_keys` | **唯一拿到系统级 ADB 权威本体**（全局 ADB 授权） |
| **无线调试** | 有（`ui/AdbPairActivity`、`bridge/AdbBridge`、`DeviceBridgeService`、`BootReceiver:34-68`） | `uid=2000 (shell)` | 取得 ADB shell 授权的一种；**唯一附带系统权限提权链**：配对后用 `uid=2000` 反向 `pm grant WRITE_SECURE_SETTINGS`（`AdbBridge.grantSecureSettings:494-514`），此后 `tryReopenWirelessDebug:564-591` 才能自动把无线调试开回来 |
| **USB 调试** | **完全没有**。全仓无 `tcpip`/`usb` 分支；唯一出现是 `ui/DeviceGrantsFragment.java:222-223` 的一句文案「或由电脑开启 ADB TCP 通道」 | 不适用 | 无检测、无状态、无引导 |
| **Shizuku** | 有（`ShizukuShell`、`ShellService`、`ShizukuManagerCompat`） | `uid=2000`，Binder user-service | **中间层**，自身仍需 ADB 或 root 启动 |
| **Root / su** | 有（`RootShell`、`RootShellMain`、`util/RootShellCandidates`） | `uid=0`（`RootShellMain.java:16` 自检） | **独立权威**，同时是取得 ADB 权威的手段 |
| **无障碍** | 有（`DshaAccessibilityService`） | **App 自身 UID** | 完全正交，不需要任何设备 shell |

### 1.2 执行期选择写死在一处 if 链

`HttpShellService.deviceExecute()`（`HttpShellService.java:783-810`）：

```java
boolean sms = "sms.read".equals(plan.optString("capability"));
if (!forceAdb && RootShell.enabled(ctx) && RootShell.present())        → root
if (!forceAdb && !forceRoot && !sms && ShizukuShell.hasPermission())   → shizuku
if (!DeviceBridgeService.isAdbEnabled(ctx))                            → [DEVICE_CHANNEL_UNAVAILABLE]
                                                                        → adb plan
```

顺序 Root → Shizuku → ADB 是写死的；`forceRoot` / `forceAdb` / 短信敏感读各自旁路。这本身是合理的（AGENTS.md 也把「发送前选择」列为经验），问题是**它无法表达「为什么选它」和「另一条通道为什么不行」**，UI 只能各自猜。

### 1.3 已经做对的部分：App 自举拿到了真正的「全局 ADB 授权」

`RootBootstrap*` 这条链（`RootBootstrapAuthority/Executor/Native/Files/FileLabels`、`core/AdbBootstrapController`、`util/AdbBootstrap*`）做的事是：借助 root 拉起/接入 adbd，并把 App 自己生成的公钥写进 `/data/misc/adb/adb_keys`。**这一步之后，本机所有 ADB 客户端（包括容器里 `adb-shell.py` 的 ADB 传输）都被系统 adbd 接受** —— 这就是「通过 App 授权即可获得全局 ADB 授权」。

它今天已经存在、已经有短期授权（ticket + 120s + 进程存活核验 + APK 身份）、已经处理了「无线调试与自举互斥」，但：

- 它的开关 `adb_bootstrap` 与 Root Shell 的 `allow_root_shell` 是**两把互不知情的锁**；
- 它的 UI 是四张卡里的**最下面一张**，而它其实是**推荐路径**；
- 「自举成功 = 全局 ADB 授权到手」这件事没有任何地方被表达成单一状态。

## 2. 概念模型：把混在一起的三件事拆开

| 概念 | 含义 | 今天的实现 |
|---|---|---|
| **权威 Authority** | 谁批准本应用在设备上执行特权操作 | 没有独立对象，散在 4 个开关里 |
| **凭据 Grant** | 一条可撤销的许可：来源、证据、代次 | `DeviceGrants` 只存了 2 个布尔 + 1 个 revision |
| **管道 Transport** | 真正把 argv 送进设备 shell 的通道 | RootShell / ShizukuShell / AdbBridge 三条实现 |

关键结论：

1. **Shizuku 不是权威，是管道**，且它自身有来源（由 ADB 启动 / 由 root 启动）。UI 必须显示这个来源，否则用户会以为「装了 Shizuku 就等于授权了 ADB」。
2. **USB 调试、无线调试、App 自举是同一个权威（系统 adbd 的 shell 授权）的三条取得路径**，因此它们应当在 UI 上同属一节，而不是三个并列开关。
3. **Root（uid 0）是独立的权威**，同时又是取得 ADB 权威的手段之一。两种身份都要能表达。
4. **全局 ADB 授权** = 「本应用持有对系统 adbd 的有效 shell 授权」。它一旦成立，对 App 自身传输与外部 ADB 客户端同时生效。

## 3. 决策

### 3.1 新增唯一权威对象 `util/DeviceAuthorization`

纯逻辑（零 Android import，必须配单测），把上面三件事变成类型：

```
enum Authority { NONE, ROOT_UID0, ADB_SHELL, SHIZUKU_ADB, SHIZUKU_ROOT }
enum Pipe      { ROOT_SU, SHIZUKU_BINDER, ADB_USB, ADB_TCP, ADB_BOOTSTRAP, VS_LAUNCH }
enum State     { UNKNOWN, ABSENT, GRANTABLE, PENDING, GRANTED, STALE, REVOKED, BLOCKED }
```

对外只暴露：

- `authority()` —— 当前生效的权威及来源
- `pipes()` —— 每条管道的可用性与**阻塞原因**（不是布尔）
- `plan(forceRoot, forceAdb, sensitiveRead)` —— 执行前选一次通道，**输出决策 + 理由**
- `evidence()` —— 取证：uid、`SelPackageIdentity`、公钥指纹（`util/AdbAuthorizedKeys`）、监听状态（`util/AdbdIdentity`）、时间、代次
- `revision()` —— 统一代次；任何授权开关变化即递增，用于取消在途授权

### 3.2 执行期只问这一个对象

`HttpShellService.deviceExecute()` 的 if 链替换为 `DeviceAuthorization.plan(...)`，**语义完全等价**（顺序、旁路、短信限制都不变），但决策与理由可被 UI 与 `/app/device` 读到。`DeviceShellExecutor` 的守卫**位置与强度都不动** —— 它在选通道**之前**执行，与授权层正交。

### 3.3 UI 从「四张并列卡」改成「一张授权卡 + 来源分节」

```
设备命令授权
├─ 总结状态：已授权 · 来源=App 自举(ADB) · 可用于：设备 shell / 虚拟屏 / 短信(需单独授权)
├─ ① App 自举（推荐，免配对）        ← 现在的 root_bootstrap_card，升到首位
├─ ② 系统调试授权
│     ├─ USB 调试授权               ← 今天没有独立入口，靠系统授权
│     └─ 无线调试配对               ← 现在的 config_adb_pair
├─ ③ Root（独立权威，也可作为自举手段）
└─ ④ Shizuku（中间层：来源=ADB / root，不是权威）
```

约束：**只改表述与状态来源，不改任何权限判定**（见 §4）。布局修订要跑 `LayoutAuditInstrumentation` 的 style 中英文验收（日夜、短屏、1.3 倍字体），文案进 `tools/i18n/messages.json`。

### 3.4 代次与撤销统一

沿用仓库已有的正确模式（`RootBootstrapAuthority` 监听 prefs 变化即 `grant.revoke()`、`RootShell.authorizationRevision()`）：任何授权开关变化 → `revision++` → 取消在途授权与短期 ticket。撤销立即生效，未授权不得自动重连。

### 3.5 状态只从证据重算，不缓存

UI 不再各自维护 `adbStatusRequest` / `rootStatusRequest` / `shizukuStatusRequest` 三个计数器（`ui/DeviceGrantsFragment.java:35-37`）分别刷新，而是从 `DeviceAuthorization.snapshot()` 渲染；`UiStateText` 仍负责按显示边界渲染。

### 3.6 必须显式建模「无线调试附带的系统权限」

盘点发现一条今天被藏在实现里的链：**无线调试配对成功后，App 用 `uid=2000` 的 ADB shell 反向给自己 `pm grant android.permission.WRITE_SECURE_SETTINGS`**（`bridge/AdbBridge.java:494-514` → `adb-pair.py --grant-keepalive`）。拿到之后：

- `BootReceiver.java:39-60` 可开/升级时自动 `Settings.Global.putInt("adb_wifi_enabled", 1)` 把无线调试开回来；
- `DeviceBridgeService.tryReopenWirelessDebug:564-591` 断线后自动重开。

也就是说，**无线调试不只是「一条管道」，它还派生出一个系统权限，而这个权限又用来反过来维持它自己**。这与「自举和无线调试互斥」（`AdbBootstrapController.blocksWirelessReopen:109-113`）直接纠缠：一旦自举接管 adbd，这条自动重开必须被禁止，否则会把自举的监听踢掉。

统一模型必须把它表达成 `DeviceAuthorization` 的一条**派生凭据**（`WRITE_SECURE_SETTINGS` 持有与否、它是由哪次配对派生的、当前是否被互斥规则禁止使用），而不是散落在 `BootReceiver` 与 `DeviceBridgeService` 的两个 `if` 里。这条派生凭据的撤销也必须跟着配对授权一起走。

## 4. 安全不变式（统一不得放松）

1. **白名单独立于授权层**：`util/DeviceShellPolicy`（544 行、默认拒绝、逐命令参数文法）与 `DeviceShellExecutor` 的守卫不改语义；授权只决定「有没有通道能执行」，**不决定「哪条命令能执行」**。
2. **自举的短期授权保留**：`RootBootstrapPolicy` 的 ticket / 120s / 进程存活 / APK 身份 / `Requirement` 集合一个不少；统一层**不得成为绕过它的第二入口**。
3. **互斥关系显式化**：无线调试与自举互斥（`WIRELESS_DEBUG_IN_USE` → `AdbBootstrapController.blocksWirelessReopen`）必须在模型里表达为管道之间的互斥，而不是散落的 if。
4. **不美化 Shizuku**：文案必须如实说它是中间层。不得让用户产生「装了 Shizuku = 授权了 ADB」的误解。
5. **不动 `--su` 语义**：root 仍需在设备能力授权页开启且经 root 管理器原生授权。
6. **[POLICY_BLOCKED] / [EXECUTION_UNKNOWN] 语义不变**：`[EXECUTION_UNKNOWN]` 不得切换通道或自动重放。
7. **派生系统权限不得越权**：`WRITE_SECURE_SETTINGS`（§3.6）只能用于维持无线调试自身，不得被统一层扩展成通用 `settings put` 能力；`DeviceShellPolicy` 对 `settings put`/`setprop` 的禁止与它无关、继续生效。
8. **互斥优先于便利**：自举生效期间，任何「自动重开无线调试」的路径都必须被禁止（今天靠 `blocksWirelessReopen`），统一层不得为了「更顺滑」而放宽。

## 5. 影响面清单

### 5.1 按「是否改变受管运行时身份」分组

判据同 [ADR 0003 §4.1](0003-runtime-endpoint-single-source.md)：改到 `managed-runtime-inputs.json` 里的 141 个 Java 源 / 113 个资产就会改 `runtimeId`，必须重跑 `tools/prepare-runtime-descriptor.py --write` 并走一次真实 `RuntimeTrial`。

| 分组 | 文件 |
|---|---|
| **改 = 变 runtimeId** | `HttpShellService.java`（`deviceExecute:783-810`）、`DeviceShellExecutor.java`、`ShellService.java`、`RootShell.java`、`RootShellMain.java`、`RootBootstrapAuthority/Executor/Native/Files/FileLabels.java`、`DeviceBridgeService.java`、`DeviceFileCore.java`、`bridge/AdbBridge.java`、`bridge/AppDeviceActions.java`、`core/DeviceGrants.java`、`core/AdbBootstrapController.java`、`util/AdbBootstrap*.java`、`util/DeviceShellPolicy.java`、`util/AdbdIdentity.java`、`util/RootSupportProbe.java`、`util/RootShellCandidates.java`、`util/DeviceAppPolicy.java`、`vscreen/*`、`runtime/*`（整目录） |
| **改 ≠ 变 runtimeId（可先做）** | `ShizukuShell.java`、`ShizukuManagerCompat.java`、`vscreen` 之外的 `ui/*`（`DeviceGrantsFragment.java`、`RootBootstrapCard.java`、`AdbPairActivity.java`、`ConfigFragment.java`）、`res/layout/fragment_device_grants.xml`、`res/values*`、`tools/i18n/messages.json`、`app/src/debug/*` |

> 注意：`ShizukuShell.java` 不在 launcherSources 里，所以它的改动本身不改 `runtimeId`；但一旦它被 §3.1 的对象取代，调用方（`HttpShellService`）的改动必然改 `runtimeId`。

### 5.2 测试（穷尽清单）

**单测**（`app/src/test/java/com/deepseekharness/app/`）：

| 来源 | 单测文件 |
|---|---|
| Root / su | `RootBootstrapExecutorTest.java`（根包）、`util/RootShellCandidatesTest.java`、`util/RootSupportProbeTest.java` |
| ADB 自举 | `util/AdbBootstrapPolicyTest.java`、`AdbBootstrapGrantTest.java`、`AdbBootstrapJournalTest.java`、`AdbBootstrapEngineTest.java`、`AdbAuthorizedKeysTest.java`、`AdbFirewallToolTest.java`、`AdbdIdentityTest.java` |
| 无线调试 / 配对 | `util/AdbResultTest.java`、`AdbPairingInfoTest.java`、`AdbEnvironmentTaskTest.java`、`AdbDependencyPreparationTest.java`、`AdbWheelCacheTest.java`、`AdbWheelPathsTest.java`、`runtime/AdbWheelBundleTest.java` |
| 执行策略（与授权正交，**不得因本 ADR 改动**） | `util/DeviceShellPolicyTest.java`、`DeviceAppPolicyTest.java`、`DeviceFileOperationsTest.java`、`DeviceCommandResultTest.java`、`DeviceScriptProofTest.java`、`SmsQueryTest.java` |
| 无障碍 / 屏幕 | `util/ScreenSessionGrantTest.java`、`AccessibilityDumpBoundsTest.java`、`vscreen/VirtualScreenManagerOwnershipTest.java`、`VirtualScreenManagerRevocationTest.java` |
| **Shizuku** | **无专属单测** —— 这是统一模型必须补的第一块（见 §5.4 缺口 7） |

**主机侧 Python 回归**（`tools/`）：`test-root-support.py`、`test-root-bootstrap-endpoint.py`、`test-native-device-policy.py`、`test-device-shell-policy.py`、`test-adb-flow.py`、`test-adb-vscreen-bridge.py`、`test-bridge-files.py`、`test-device-script-selection.py`、`device_script_privacy.py`、`run-host-tests.py --check-manifest`、`test-architecture-boundaries.py`。

**真机审计插桩**（`app/src/debug/`，已在 `app/src/debug/AndroidManifest.xml` 注册）：`DeviceCapabilityAudit.java`、`DevicePolicyAudit.java`、`RootSupportAudit.java`、`SmsGrantAudit.java`、`ui/AdbFlowAudit.java`、`BridgeAuditInstrumentation.java`、`ui/LayoutAuditInstrumentation.java`（改 UI 必跑）。驱动脚本：`tools/control-adb-flow-audit.py`、`device-policy-device-audit.py`、`measure-stability-device.py`、`device-startup-diagnostics.py`。

### 5.3 文档（穷尽清单）

- 统一 / 总览：`docs/security-model.md`（+ `.en.md`）、`docs/adr/0002-current-threat-model.md`、`AGENTS.md`「安全模型 / ADB 通道经验 / 设备验收经验」、`docs/module-map.md`
- 无线调试：`docs/adb-flow-audit.md`（含 `uid=2000` 的**范围限定**：只对应当台 Android 13 与原捕获窗口，daemon UID 0 ≠ 所有会话同权）、`docs/device-permissions-2026-09-09.md`、`docs/device-acceptance-2026-09-05.md`、`docs/device-acceptance-alpha15-2026-09-09.md`
- ADB 自举：`docs/audits/root-adb-bringup-20261009/` 整个目录（`README.md`、`design.md`、`implementation-20261010.md`、`validation-20261010.json`、`bootstrap-chain-20261010.md`、`guard-visibility-20261011.md`、`fix-c-wireless-debug-20261010.md`、`devices/TB321FU.md`、`scripts/{authorize,revoke}-adb.sh`、`scripts/verify-adb-identity.sh`、`scripts/adb-listen-narrow.sh`）
- 虚拟屏 / 无障碍：`docs/virtual-screen-guide.md`、`docs/audits/root-adb-bringup-20261009/device-vscreen-verification-20261010.md`
- 架构审计：`docs/audits/2026-09-27-rc2-full-architecture/`、`docs/audits/2026-09-27-round2-architecture/r2-b07-adb-sensitive-dispatch.md`、`docs/audits/build15{4,5,6}/`
- Shizuku：**无独立文档**，仅散见于上述审计与 `AGENTS.md`（版本与 `ShizukuProvider` 约束）
- `README.md` / `README.en.md` / `CHANGELOG.md` 的对应段落。**历史发布说明不改。**

### 5.4 本轮穷尽盘点补出的缺口清单

1. **USB 调试无代码路径，也无「已检测到 USB 调试」的探测** —— §1.1.2 已确认。它今天既不是通道也不是来源。
2. **执行期选择不返回理由**：`deviceExecute` 返回 `transport` 字符串，但不返回「另一条通道为什么不行」，UI 只能各自猜。
3. **`adb_enabled` 一词三义**：保活开关 / 通道授权 / `deviceExecute` 的可用性判据（`DeviceBridgeService.java:76-79`、`HttpShellService.java:798`）。这是统一模型必须先定义清楚的语义。
4. **自举卡 40+ 条文案全部内联在 `RootBootstrapCard.java:89-179,425-572`，未进 `tools/i18n/messages.json`**，与仓库 i18n 约定不一致。
5. **`tools/i18n/messages.json` 有死 key**：`m0284`/`m0285`/`m0286` 三条无障碍文案的中文原文在 `DeviceGrantsFragment.java` 里已不存在（现行实现是 `refreshA11yStatus:649-675` 的三态），仍留在双语真源与生成的 `UiMessages.EN` 里。
6. **`AdbPairActivity` 直接写 `DeviceBridgeService.adbState`**，绕过实例防重入校验（§1.1.1）。
7. **Shizuku 无专属单测**：`ShizukuShell` / `ShellService` / `ShizukuManagerCompat` 在 `app/src/test` 无覆盖。
8. **Shizuku 的授权不在 App 手里**：`DeviceGrants` 里根本没有 Shizuku 字段（只有 `sms_read`/`adb_bootstrap`/`adb_bootstrap_revision`）。统一对象要么新增 Shizuku 维度，要么按 §2 把它明确降级为管道。
9. **诊断页与 UI 页是两份独立表述**：`DiagnosticRepository.java:485-497` 的字符串拼接与 `DeviceGrantsFragment.computeAdbStatus()` 各写一遍，是 §三.3 类矛盾的温床；统一后两者都必须从同一快照渲染。

## 6. 开放问题（需要你拍板）

1. **Root 的地位**：Root 是「独立权威」还是「取 ADB 权威的手段」？我倾向两者都表达，但 UI 上只给一个入口（放在自举节里），避免又变成并列。
2. **USB 调试要不要做**：盘点确认今天 App 里**完全没有 USB 调试的代码路径**，连「是否已授权 USB 调试」都不探测（§1.1.2）。手机场景通常没有数据线，但 USB 调试是用户已经在系统里授权过的现成权威。选项：(a) 完全不碰，继续只在文案里提「由电脑开启 ADB TCP 通道」；(b) 只做**只读探测**并在总结状态里显示「已检测到 USB 调试授权」；(c) 做完整入口。我倾向 (b)，因为它零成本地扩大了「全局 ADB 授权」的可见性。
3. **`adb_enabled` 这个开关的含义**：它现在是「保持 ADB 连接/保活」的总开关（`DeviceBridgeService.java:78`），既不是授权也不是通道。统一后它应该是「允许 App 维持 ADB 会话」的策略开关，放在哪一节？
4. **短信的位置**：短信是独立敏感能力（`sms_read` + 逐次确认），它**不属于** ADB 授权。统一卡里要明确把它排除在「设备命令授权」之外，还是继续留在「敏感能力」节？
5. **是否引入「授权快照」文件**：像 ADR 0003 的端点契约那样把授权状态落盘给 guest 读（供 `adb-shell.py` 判断哪条管道可用），还是继续只走环境变量/命令行？我倾向**不落盘**：授权状态是安全边界，落盘会多一个可被篡改的输入面。

## 7. 建议的迁移顺序

- **阶段 0（不碰 runtimeId）**：新增 `util/DeviceAuthorization` 纯逻辑 + 单测，把散落的取值收敛成 `snapshot()`；顺带补上盘点发现的**零成本缺口**：Shizuku 专属单测（§5.4-7）、清理 `messages.json` 死 key `m0284/m0285/m0286`（§5.4-5）。UI 先不动（或只加一行总结状态）。可独立合并。
- **阶段 1（不碰 runtimeId）**：UI 改版 —— 一张卡 + 来源分节；Shizuku 明确标注为「中间层 · 来源=ADB/root」；修 `AdbPairActivity` 直写 `adbState` 绕过校验的问题（§5.4-6）；自举卡 40+ 条内联文案迁进 `tools/i18n/messages.json`（§5.4-4）；诊断页与 UI 页改为从同一快照渲染（§5.4-9）。跑 `LayoutAuditInstrumentation` 布局验收。
- **阶段 2（碰 runtimeId，一次性打包）**：`HttpShellService.deviceExecute` 改为问 `DeviceAuthorization.plan(...)` 并**返回理由**（§5.4-2）；统一 revision 与撤销；`/app/device` 暴露决策理由；定清 `adb_enabled` 的一义（§5.4-3）。重跑 `prepare-runtime-descriptor.py --write` + 受管试用。
- **阶段 3**：把「App 自举 = 推荐路径」做成一键流程（今天 `root_bootstrap_all` 已接近）；按 §6-2 的决定处理 USB 调试。

**建议先做阶段 0+1**：它们不触碰受管运行时身份，能立刻解决用户抱怨的「并列」问题，且与 ADR 0003 的阶段 0 可以并行。

## 8. 验收

1. 单测：`DeviceAuthorization` 的状态机与 `plan()` 决策矩阵（含 `forceRoot` / `forceAdb` / 短信 / 全部不可用）。
2. **等价性证明**：迁移前后 `deviceExecute` 在同样的输入组合下选择同一条管道（写成表驱动测试）。
3. `DeviceShellPolicy` 全部现有单测不变绿；`DeviceShellExecutor` 的守卫顺序不变。
4. 真机：分别用 自举 / 无线配对 / Shizuku / root 四种状态，UI 总结状态与实际可用管道一致；撤销后立即失效且不自动重连。
5. 布局：`LayoutAuditInstrumentation` style 中英文验收通过（日夜、短屏、1.3 倍字体）。

## 9. 明确不做

- 不放松任何白名单或短期授权。
- 不让 Shizuku 成为「不装 Shizuku 也能……之外」的必需品。
- 不把授权状态写进可被 guest 篡改的文件。
- 不改 `[POLICY_BLOCKED]` / `[EXECUTION_UNKNOWN]` 的既有语义与退出码。

## 10. 实施记录（2026-10-11）

工程包：`/sdcard/Download/DSHA/install-174-adbauth.apk`（`com.dsh.clienu.SunsetRNE`，
versionCode 174 / versionName `0.2.22-20261011.1552-dsh0.2.0-rc.2`，测试签名，
sha256 `0fb91220b2d2b657dcbc4abb457ee1551e4a967101c90b03c0aa2f1e61227651`，291,138,716 字节）。

### 已实施

| 项 | 落点 |
|---|---|
| 唯一授权对象（纯逻辑 + 单测） | `util/DeviceAuthorization`：Authority / Pipe / State / Reason、`plan()`（128 组表驱动等价性）、`pipes()`、`authority()`、`keepalive()`、`fingerprint()`；`DeviceAuthorizationTest` 8 项 |
| Shizuku 专属单测 | `util/ShizukuStatus`（level / source / 措辞）+ `ShizukuStatusTest` 5 项；`ShizukuShell.userStatus` 改为委托，新增 `failureReason()` |
| 统一观测入口 | `core/DeviceAuthorizationReader.snapshot/summary/pipes/usbLabel`；设置页与诊断页都从这里取 |
| UI：一张授权卡 + 来源分节 | `fragment_device_grants.xml` 改为一卡四节（① App 自举 ② 系统调试 ③ Root ④ Shizuku），新增总结状态与管道原因行；文案进 `res/values*` |
| Shizuku 如实标注 | 界面追加「中间层 · 由 Root/ADB 启动，不是授权本身」 |
| USB 只读探测（§6-2 选 (b)） | 读 `Settings.Global.ADB_ENABLED`，措辞明确「无法区分 USB 与无线」 |
| 去掉绕过防重入的写 | `AdbPairActivity` 三处直写 `DeviceBridgeService.adbState/adbDetail` 全部移除，只显示本地结果 |
| 诊断页同一快照 | `DiagnosticRepository` 先渲染 `summary()+pipes()`，再附原始证据行 |
| 清理死 key | `messages.json` 删除 `m0284/m0285/m0286` |

`runtimeId` 未变（`273e364865a18e49ae6fc4bd16b2e62f0bf16bb1badca26cb0740935823e687c`），符合阶段 0+1
「不碰受管运行时身份」的约束。

真机验收（虚拟屏 displayId 6，安装后即测）与截图证据见
[阶段 0+1 真机验收](../audits/adr0004-unified-auth-20261011/README.md)：一张卡 + 四节来源成立，
七条管道各带原因，关掉「允许 Root Shell」后 1 秒内总结变「未授权 · 没有可用的设备通道」、
ADB 自举原因随之变为「自举需要 Root」，重新开启即恢复 —— 状态从证据重算，不缓存结论。

### 未实施 / 已知偏差

- **阶段 2**（`deviceExecute` 改问 `plan()`、统一 revision、`/app/device` 暴露理由）与**阶段 3**（一键自举收口）未做，都需要改受管文件并重跑 `RuntimeTrial`。
- §5.4-4 自举卡内联文案**未迁入 `messages.json`**：`tools/check-ui-i18n.py` 明确把
  `setText(UiText.choose("中文","English"))` 视为非缺陷（0 新发现），迁移是风格统一而非用户可见缺陷，
  为控制本轮改动面而推迟。
- 两处**先于本轮存在**的单测失败（main `8a4e09b` 上即红，均不在本轮改动文件内）：
  `AdbFirewallToolTest.evidenceNeverCarriesTheReservedProtocolTokens`（`neutralize()` 把
  `[EXIT=125]` 变成 `(exit=125]`，测试要求 `(exit=125)`）；`AdbWheelPathsTest` 的软链环用例（本机
  `/tmp` 语义差异）。前者要改受管文件，未在本轮处理。
- 另修了一处**先于本轮存在**的测试编译错误：`AdbFirewallToolTest` 六个方法缺 `throws Exception`，
  不修则整个 `testStandardDebugUnitTest` 无法编译。
