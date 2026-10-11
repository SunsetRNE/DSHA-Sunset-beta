# ADR 0005：ADB 全局授权单一入口

状态：**基础模型、执行期与 UI 已实施；2026-10-11 审查修正文档，代码缺陷待修。** 历史工程包为 `install-175-adbglobal.apk`，实施记录与未完成项见 §9。授权撤销、状态一致性和后续验收统一见[修复交接计划](../audits/root-adb-bringup-20261009/remediation-plan-20261011.md)。
相关：[ADR 0004 统一 ADB 授权权威](0004-unified-adb-authority.md)、[安全模型](../security-model.md)、
[Root ADB 自举审计](../audits/root-adb-bringup-20261009/README.md)。

## 1. 问题

ADR 0004 阶段 0+1 把**状态**收敛成了一份快照，但**入口**没有收敛：设置页仍是「① App 自举 ②
系统调试 ③ Root ④ Shizuku」四节并列。用户直接点破：

> 无线调试和 USB 调试也是 ADB 授权，Shizuku 只是中间层作用都是 ADB 授权，却各自散落且在 UI 上是并列的。

四节并列带来的三个错误认知：

1. 页面没有分清“本应用的统一使用入口”与 USB/无线客户端认证、Shizuku 服务授权等不同来源边界；不能因统一 UI 就认为它们共享同一份系统授权；
2. 用户以为「开无线调试 = 授权了 ADB」，而 App 侧可能因为总开关关着根本不用它；
3. Root 与 ADB 混在一起，导致「关掉 ADB 会不会把 root 也关了」这种无法回答的问题。

## 2. 概念模型（2026-10-11 审查修订）

规范用语见[设备授权术语](../../GLOSSARY.md)。“全局”只指 DSHA 内部功能入口，不表示控制整台设备或其他 ADB 客户端。

| 概念 | 定义 | 边界 |
|---|---|---|
| **ADB 全局授权** | 本应用对 ADB 家族能力的统一使用许可与可用状态 | 不能凭本应用的开关或状态给外部客户端授权 |
| **ADB 授权来源** | 本应用获得设备命令能力的接入方式，分别核验其凭据与实际状态 | 检测到系统调试开关、存在某个监听，不等于已完成认证 |
| **ADB 授权总开关** | 开 = 允许本应用使用 ADB 家族通道；关 = 本应用不使用这些通道 | 复用 `adb_enabled`；不会自行删除系统公钥、关闭系统调试或撤销 Root |
| **Root 授权** | 用户允许本应用通过 Root 通道执行受控操作 | 与 ADB 总开关独立；可用性及实际 UID 仍须核验 |

### 2.1 四个来源

| 来源 | 取得方式 | 身份与证明范围 |
|---|---|---|
| **USB 调试授权** | 系统侧对某个客户端授权；App 当前只有调试设置的只读观测 | 系统调试开关不证明本应用持有 USB 会话；实际客户端需自己的认证证据 |
| **无线调试授权** | 客户端配对及实际连接核验，可按既有许可保活 | 信任特定客户端凭据；权限按真实会话判断 |
| **Shizuku 调试授权** | 服务已连接，且用户已向 DSHA 授权 | Binder 通道，有自己的授权和服务身份；不能把它的成功当作 ADB 握手证明 |
| **Root 自举 ADB 授权** | 经原生许可写入本应用公钥并建立受限监听 | 公钥、监听、双栈防护和实际客户端会话分别核验；写入由 Root 执行不证明后续所有会话同权 |

自举写入系统授权表只使持有对应私钥的客户端有机会通过 adbd 认证，**不会授权任意 ADB 客户端**。USB/无线配对也可建立特定客户端的系统信任；Shizuku 是独立的服务授权边界。总开关关闭后，系统里已有的公钥可能仍存在，删除本应用拥有的公钥需要显式清理动作。

### 2.2 判定与执行顺序

[当前执行计划](../../app/src/main/java/com/deepseekharness/app/util/DeviceAuthorization.java#L270)保持普通业务发送前的 Root → Shizuku → ADB 顺序，而不是按来源卡片的显示顺序选择：

```text
未强制 ADB，且 Root 开关开、su 存在          → 选择 Root，执行侧继续核验
否则 ADB 总开关关                           → 拒绝 ADB 家族通道
否则未强制 Root/ADB、非短信且 Shizuku 已授权 → 选择 Shizuku
否则                                       → 返回 ADB 计划，由客户端实际握手
```

强制 ADB 核验跳过 Root/Shizuku，仍尊重本应用许可；短信保持既有独立授权及不走 Shizuku 的约束。选择某条计划不代表它已经连接成功，命令发送后的未知结果也不能换通道重放。

**本应用可用状态的目标判据** = `总开关 && 至少一个来源有对应的有效证据`。当前模型仍有把调试开关或监听状态直接映射成 GRANTED 的展示性捷径，应按[交接计划的追加核对](../audits/root-adb-bringup-20261009/remediation-plan-20261011.md#5-文档纠正与后续观察)另行补证；此文档修订没有修改模型代码。

## 3. UI 结构

```
设备能力授权
├─ ADB 全局授权                         [总开关]      ← 入口行，点进 ADB 授权页
│     总结：已授权 · 来源=无线调试    /    未授权 · 无可用来源
│     └─ ADB 授权页 = 四个**入口行**（本页只给一行状态，点进去才是各自的页面）
│          ├─ ① USB 调试授权        → UsbAuthorizationActivity（只读检测 + 打开开发者选项）
│          ├─ ② 无线调试授权        → AdbPairActivity（沿用既有配对/验证页，不重画）
│          ├─ ③ Shizuku 调试授权    → ShizukuAuthorizationActivity（中间层 · 来源）
│          └─ ④ Root 自举 ADB 授权  → BootstrapAuthorizationActivity（原自举卡整体迁入）
└─ Root 授权（独立）                     [开关]        ← 入口行，点进 Root 授权页
      允许 Root Shell / 申请授权并验证 / su 状态
```

约束：

- 总开关只在**入口行**上；ADB 授权页与四个来源页里都不再出现第二个总开关（避免两处真相）。
- **四个来源各是一个入口**，`② 无线调试` 直接继承既有的配对/验证页（`AdbPairActivity` 的
  `CardPage` 绘制），不重画；其余三条各自成页。
- Root 授权页**没有** ADB 相关任何开关；ADB 授权页**没有** `allow_root_shell` 开关
  （自举需要 root 时提示"去 Root 授权页开启"，不在本页偷偷开）。
- 短信、位置、传感器、无障碍、系统权限仍留在原页，不进 ADB 授权页。

## 4. 安全不变式

1. **白名单独立于授权层**：`util/DeviceShellPolicy` 与 `DeviceShellExecutor` 的守卫不改语义。
   总开关只决定"有没有可用的 ADB 授权"，**不决定"哪条命令能执行"**。
2. **总开关关闭 = 本应用不使用 ADB 家族通道**：同时挡住 Shizuku 路径，但不影响独立 Root；不会自动撤销系统公钥或关闭其他客户端会话。
3. **Root 授权独立**：关 ADB 总开关不撤销 root；关 root 不撤销 ADB 总开关。
4. **短信仍不走 Shizuku**；`[POLICY_BLOCKED]` / `[EXECUTION_UNKNOWN]` 语义与退出码不变。
5. **自举的短期授权与互斥不变**：ticket / 120s / 进程存活 / APK 身份 / `WIRELESS_DEBUG_IN_USE`
   一个不少；总开关不得成为绕过它们的第二入口。
6. **不改历史 SharedPreferences 键名**：总开关复用 `adb_enabled`，表示本应用使用许可；新增意图代次或互斥记录仍是本机状态，不随用户归档恢复授权。
7. **按实际来源解释身份**：Shizuku 自己的服务授权不等于 adbd 对客户端公钥的信任；Root、Shizuku 和 ADB 会话分别验证。
8. **显示状态与用户意图区分**：设备可用状态按当前证据重算；为防止旧任务复活而保存的用户意图、代次和未决记录，不是永久有效的连接证明。
9. **确认后的撤销优先**：初始总开关关闭仍允许经确认自举；确认之后用户关闭或关闭再开启，不得由旧链重新启用。此目标及开关显示同步尚有缺口，见交接计划 F01/F04/F08。

## 5. 历史影响面与代价

下表记录本 ADR 最初实施时的影响面，不作为以后新增类或重构的豁免表。后续是否影响 `runtimeId`，以[当前受管输入清单](../../app/src/main/assets/managed-runtime-inputs.json)及其目录覆盖规则为准。

| 文件 | 改动 | 受管输入（改＝变 `runtimeId`） |
|---|---|---|
| `util/DeviceAuthorization.java` | 新增总开关维度与来源枚举、`plan()` 语义 | 否（新文件，不在清单里） |
| `HttpShellService.java` | `deviceExecute` 改问模型；总开关挡 Shizuku | **是** |
| `DeviceBridgeService.java` | 总开关语义注释与状态发布 | **是** |
| `core/DeviceAuthorizationReader.java` | 快照增加总开关与四来源 | 否（新文件） |
| `ui/DeviceGrantsFragment.java` | 入口行 + 总开关 + 跳转 | 否 |
| `ui/AdbAuthorizationActivity.java`、`ui/RootAuthorizationActivity.java`（新增） | 两页 | 否 |
| `res/layout/*.xml`、`res/values*`、`tools/i18n/messages.json` | 新布局与新文案 | 否 |
| `ShizukuShell.java`、`ui/AdbPairActivity.java`、`ui/RootBootstrapCard.java` | 接入/搬迁 | 否 |

改到受管输入就必须重跑 `python3 tools/prepare-runtime-descriptor.py --write` 并审阅 `runtimeId`
变化；按 `ManagedRuntimeTransaction` 走一次真实 `RuntimeTrial` 属**发布流程门禁**，工程验证包
只记录"已重算描述符、未走试用"。

## 6. 迁移顺序

1. **本文档**（模型与边界先落地）。
2. **模型**：`DeviceAuthorization` 增加 `globalEnabled` 与四来源；单测覆盖"任一来源激活"、
   "总开关关闭全部不激活"、"Root 不受影响"、"短信跳过 Shizuku"。
3. **执行期**：`deviceExecute` 改问模型；总开关挡住 Shizuku；重算描述符。
4. **UI**：入口行 + 总开关 + ADB 授权页（四节）+ Root 授权页。
5. **Root 自举收口**：模型稳定后，把"自举 = 推荐路径"做成一条可验证流程，
   在真机上验证「自举成功 → ADB 全局授权到手 → `id` 可用」。
6. **工程包 + 真机验收**：升版本号、出包、推送、安装、虚拟屏验收 + 真实设备命令验证。

## 7. 验收

1. 单测：`DeviceAuthorizationTest` 的等价性与新语义矩阵（总开关 × 四来源 × forceRoot/forceAdb × 短信）。
2. 执行期：总开关关时，即使 Shizuku 已授权也不得走 Shizuku；Root 授权开时 root shell 仍可用。
3. UI：入口行显示唯一结论；四节只解释原因；两个页面互不越权（ADB 页没有 root 开关，Root 页没有 ADB 开关）。
4. 真机：四种来源各自做到"有/没有"两态，入口行结论与实际执行通道一致。
5. 布局：`LayoutAuditInstrumentation` style 中英文验收（日夜、短屏、1.3 倍字体）。

## 8. 明确不做

- 不放宽任何白名单或短期授权；不为 root 通道另开一套策略。
- 不让总开关成为「绕过短信逐次确认」或「绕过危险命令确认」的开关。
- 不把 USB 调试做成写入口（今天 App 里没有 USB 调试代码路径，只做只读检测）。
- 不改 `[POLICY_BLOCKED]` / `[EXECUTION_UNKNOWN]` 的既有语义与退出码。

## 9. 实施记录（2026-10-11）

工程包 `install-175-adbglobal.apk`（`com.dsh.clienu.SunsetRNE`，versionCode 175，
sha256 `96653f77fd68c87070ed4a1dca0d60236e81984bdd4b16ec379fc8a41d47d17a`），
详见 [build175 发布说明](../releases/v0.2.23-build175-20261011.md)。

| 项 | 落点 |
|---|---|
| 模型 | `util/DeviceAuthorization`：`globalState/globalReason/grantedSource/adbSources`；`plan()` 总开关先于 Shizuku |
| 单测 | `DeviceAuthorizationTest`：128 组新语义矩阵、「总开关开=历史顺序」对照、「任一来源激活」四例 |
| 执行期 | `HttpShellService.deviceExecute` 改问模型；无通道时附决策理由（标记/退出码不变） |
| 快照 | `core/DeviceAuthorizationReader.adbEntrySummary/sourceLine` |
| UI | 首页两入口（ADB 全局授权带总开关 / Root 授权独立）；`ui/AdbAuthorizationActivity`（四节）、`ui/RootAuthorizationActivity`、`res/layout/card_root_bootstrap.xml` |

`runtimeId` 已变（`HttpShellService` 是受管输入）：
`273e3648…e687c` → `03ba9894dc975835b3fb539efe7c6ab13d6a515a90a3e4bd5a89478dc26f1653`；
描述符已重算，**未**走真实 `RuntimeTrial`（工程验证包，非发布交付）。

未完成：真机验收（安装后虚拟屏 + 四来源两态）、**Root 自举收口**（模型已收敛，下一步在设备上
验证「自举成功 → ADB 全局授权到手 → `id` 可用」）、`LayoutAuditInstrumentation` 布局验收。
