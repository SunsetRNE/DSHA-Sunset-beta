# ADB 自举审查与修复交接计划（2026-10-11）

状态：**2026-10-11 第二轮已实施 F03/F04/F05/F07/F08 与 F01/F02 的生产路径；F06 仅完成 D1（前置拒绝），D2（首次建表）未实施。** 每项的修改位置、能击中旧错误的测试、实际结果和剩余范围见 §7。本文仍是实施入口：先读 §7 的剩余范围，再按阶段补未完成的验证。

## 1. 接手范围与阅读顺序

用户要求本轮只修改文档、规划修复，代码交由其他 AI 实施。本轮未修改 Java、Python、资源、构建配置或运行时描述符，未操作手机、安装 APK 或发布。下一位实施者可以按后续用户授权开展代码工作；本文不自动授权设备写入、覆盖安装、发布或执行故障注入。

> **时间线：** 上面这段描述的只是 2026-10-11 的**文档轮**。同一天晚些时候的**第二轮**已经实施代码（见 §7），修改了 Java 源码、测试、`managed-runtime-inputs.json` 与 `runtime-descriptor.json`；它同样没有操作手机、没有安装 APK、没有发布，也没有开展设备验收。两轮都以「软件通过 ≠ 设备验收」为前提。

审查对象为 HEAD `8a4e09ba8190ae6a0d1898d8afda489357bdad67` **加 2026-10-11 当时的未提交工作树**。控制器、授权界面和模型有未提交/未跟踪文件；仅检出该提交不能复原本次审查现场。接手先检查工作树，保留用户已有改动，并按下面的方法名重新定位；链接行号只是本次快照定位。

按需阅读：

1. [AGENTS.md](../../../AGENTS.md)、[授权术语](../../../GLOSSARY.md)及本文：确认工作边界和问题编号。
2. 改原生协议/事务时读[自举设计](design.md)与[安全模型](../../security-model.md)。
3. 改授权编排时读[ADR 0005](../../adr/0005-adb-global-authorization.md)和[ADR 0006](../../adr/0006-root-bootstrap-one-button.md)。
4. 处理会话验证时读[修订后的 ADR 0007](../../adr/0007-bootstrap-session-verification.md)。保留当前单端点、单次核验；额外重试和额外重启不在本计划的默认实现范围。

## 2. 证据基线

| 编号 | 优先级 | 问题 | 本次证据 | 状态 |
|---|---|---|---|---|
| F01 | P1 | 在途自举覆盖用户后续关闭的 ADB 总开关 | 静态可达调用链 | 已实施（控制器级并发用例仍缺，见 §7） |
| F02 | P1 | 开机/覆盖升级绕过自举与无线调试的互斥 | 静态可达调用链 | 已实施（接收器写入计数用例仍缺） |
| F03 | P2 | 无操作 REVOKE 清除另一未完成事务的本地标志 | **真实执行器 + 假设备隔离复现** | 已实施（含生产响应→控制器归约组合回归） |
| F04 | P2 | 最后一个 OPEN 提交失败仍触发启用通道 | 静态可达调用链 | 已实施 |
| F05 | P2 | 保活用 Root/Shizuku 的成功结果冒充 ADB 已连接 | 静态调用链；既有路由测试印证普通入口会提前返回 | 已实施（真机探活未验收） |
| F06 | P2 | 系统授权表不存在时没有首次建表路径 | 缺文件条件下源码确定；设备发生范围未验收 | **部分完成（仅 D1 前置拒绝；D2 未实施）** |
| F07 | P2 | 会话核验回调向已销毁 Activity 弹窗 | 静态生命周期路径 | 已实施（无仪器测试，见 §7） |
| F08 | P2 | 自举修改授权后首页总开关未同步 | 静态界面刷新路径 | 已实施（无仪器测试，见 §7） |

P1 表示应优先修复，P2 表示明确的功能/可靠性问题；不等于已证明外部可利用漏洞。除 F03 外，本次没有为这些场景做动态复现，更没有真机复现。

本次已执行且通过的既有测试：

```bash
python3 -B tools/test-root-support.py                         # 99 项
python3 -B tools/test-root-bootstrap-endpoint.py             # 15 项
python3 -B tools/test-adb-flow.py NativeRouteTest             # 3 项
```

共 117 项。数量只对应本次工作树；不代表完整 Gradle、双 flavor、APK 或设备矩阵通过。F03 的附加复现在临时目录编译生产代码和仓库现有假设备，未增加仓库测试文件；实施者须把它落成持久回归。

## 3. 修复时保持的边界

- 固定原生命名动作、真实 UID/APK/进程身份、短期票据和每次写入前回查继续生效；不扩大通用设备命令白名单。
- 双栈防护在公钥写入/监听开启之前验证。只清理本应用证明拥有的规则和公钥，不整表回滚、不清空其他客户端授权。
- 请求结果、持久未决事务、用户许可和实际会话状态分别表达。一次 `id` 成功、一个 `OPEN` 观测或当前开关为开，都不能替代其他维度的证据。
- 用户确认之后的关闭、关闭再开启均使旧操作意图失效。已有设备变更仍保留并如实报告，不借取消自动重放、自动撤钥或重启 adbd。
- 初始 ADB 总开关关闭仍可经原生确认自举；Root 是独立授权。修复不能重新引入“先有 ADB 才能自举 ADB”的循环。
- 自举验证保持 `127.0.0.1:5555`、无 `su`、单次只读 `id`；普通业务继续 Root → Shizuku → ADB 的发送前选择。专用探活与普通业务使用不同的通道意图。
- 后续新增的版本/意图/互斥记录属于本机状态，不能通过用户归档或便携设置恢复成授权。保留历史偏好键名。

## 4. 分阶段实施

推荐顺序：**A（F03）→ B（F01/F04）→ C（F02/F05）→ D（F06）→ E（F07/F08）→ F（整体验收）**。每阶段先取得可使旧逻辑失败的行为测试，再修生产路径。原生协议、控制器和保活有交叉依赖，按阶段交接；不要让多个修改者同时改同一控制器。

### A. F03：让持久未决事务穿透所有动作响应

**落点：**[RootBootstrapNative.execute](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapNative.java#L23)、[RootBootstrapExecutor.revoke](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapExecutor.java#L340)、[AdbBootstrapJournal.unresolved](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapJournal.java#L98)、[控制器结果保存](../../../app/src/main/java/com/deepseekharness/app/core/AdbBootstrapController.java#L281)。

**当前触发：**首次 AUTHORIZE 在写 IPv6 规则时失败，留下网络 `pending=NARROW`，尚无 `keyOwned`；显式 REVOKE 无事可做，观察得到完整的 `NOT_REGISTERED/CLOSED/UNPROTECTED`。原生包装只对 STATUS 外传 `PENDING_ACTION`，REVOKE 被报为 COMPLETE，本地 `unresolved` 被清除。

**修改方向：**

1. 响应独立包含“未决状态是否已核实”以及网络/公钥两个未决阶段。所有动作都返回它；原生异常、缺字段或无法加载日志时保持未知。字段名可按现有风格选定，编码、解码和兼容规则必须在同一阶段交付。
2. 当前动作可无操作成功，但不得据此证明其他事务已结束。控制器只在可信响应确认两个阶段均结束、观察完整且本地结果保存成功后解除屏障。
3. 旧格式、未知协议版本、原生日志异常、本地持久化失败都不能把已有 `unresolved=true` 清成 false。已有原生日志仍可读；若改变日志格式，补兼容和损坏记录测试。
4. 将生产响应编码/解码和完成判据放到可在主机测试的接缝，Android 适配器实际调用该接缝；不要复制判断逻辑另写一套只给测试用的实现。

**复现入口：**在[现有执行器测试](../../../app/src/test/java/com/deepseekharness/app/RootBootstrapExecutorTest.java)的 `Device` 夹具里设置 `failV6=true`，依次执行 AUTHORIZE、REVOKE，检查原生日志、动作响应和控制器归约结果。旧逻辑输出为：

```text
AUTHORIZE=UNKNOWN/COMMAND_RESULT_UNKNOWN
REVOKE=OK/COMPLETE
observed=NOT_REGISTERED,CLOSED,UNPROTECTED
durable pending=NARROW; controller unresolved=false
```

**完成条件：**上述序列保持未决提示、持久屏障和禁止自动重开无线调试；覆盖无操作 REVOKE、仅网络完成的 CLOSE、仅公钥完成的清理、失败 STATUS、缺字段响应及重建控制器。仅在两类遗留均确认清理后解除。测试须覆盖生产响应到控制器消费的组合，不能只测 `Host.pending()`。

### B. F01/F04：绑定整链授权意图，修正完成判定

**落点：**[submitBootstrapChain / perform / advanceChain / enableAdbChannel / execute](../../../app/src/main/java/com/deepseekharness/app/core/AdbBootstrapController.java#L184)、[RootBootstrapAuthority](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapAuthority.java)、[DeviceGrants](../../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java)、[首页开关写入](../../../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L109)、[配对页关闭入口](../../../app/src/main/java/com/deepseekharness/app/ui/AdbPairActivity.java)。

**当前触发：**F01 是旧链在用户关闭后再次写 `adb_enabled=true`。F04 是 `submit(chain.poll())` 先取掉 OPEN，提交失败后用前一步 OK 与空队列判断成功。此外，`perform` 当前在结果保存失败后仍把原 `result` 传给续链，也必须纳入相同完成条件。

**修改方向：**

1. 为整次确认捕获操作 ID、Root/自举授权代次，以及 ADB 总开关的用户意图代次。逐步新建票据仍需服从最初确认，不能在中间关闭再开启后借新票据复活旧链；整链意图校验须接入每次原生写入前的票据回查。总开关的初始值仍不是自举门禁，检查的是确认后的用户意图是否改变。单独 OPEN 同样受自动启用意图约束。
2. 收敛总开关受管写入口；核对设置页、配对页、控制器及偏好清理/恢复入口。用户关闭或明确重新选择应改变意图代次；后台自动启用只能在同一协调锁下按预期代次提交，并返回明确结果。自动启用自己的写入不能被误认成用户的新撤销。
3. 原子覆盖“核对意图 → 持久化开关 → 决定启动服务”的并发边界；服务启动/继续执行再读真实开关。关闭发生在写前、写后、服务启动前都不能被旧完成回调反转。
4. 保存当前步骤及其派发/完成状态。下一步提交失败就是终止，不能由队列为空推导成功；只有对应操作 ID 的最后一步真正完成、结果持久化成功且无未决事务时才报告整链成功。整链单飞覆盖步骤之间，外部 STATUS/另一条链不能趁 `running=false` 插入并改变归属。
5. 控制器应消费已持久化的有效结果；日志提交失败保持 UNKNOWN 并停止续链。自动启用提交失败也要可见，不能忽略 `commit()` 返回值。

**核验前自动开启的处理：**当前 OPEN 后先开启本应用通道，是为了让受管客户端取得强制 ADB 计划。推荐本轮保留这一先后顺序，但使开启动作受上述整链意图校验，并明确显示“监听已开、会话待核验”；核验失败保留真实设备/开关状态，用户后续关闭优先。删除这一步、改成核验后才开会重新造成循环。若未来改为核验专用短期许可，应另设计固定只读入口，不能绕过总开关给任意客户端开放计划。

**完成条件：**至少覆盖：初始总开关关的正常自举；每个步骤边界关闭总开关；Root/自举/总开关的关闭再开启；OPEN 提交拒绝；请求与结果保存失败；并发 STATUS；旧步骤迟到；自动启用提交失败；等待 2.5 秒期间撤销。断言没有后续派发、没有覆盖用户关闭、没有虚假整链成功，且对可能已生效的设备状态仍保存未知证据。用可控执行器和时钟，不依赖真实 sleep 重现竞态。

### C. F02/F05：统一无线恢复门禁，探活强制实际 ADB

**落点：**[BootReceiver.onReceive](../../../app/src/main/java/com/deepseekharness/app/BootReceiver.java#L21)、[DeviceBridgeService.tryReopenWirelessDebug](../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java#L564)、[blocksWirelessReopen](../../../app/src/main/java/com/deepseekharness/app/core/AdbBootstrapController.java#L104)、[AdbBridge.probe](../../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java#L537)、[客户端路由](../../../app/src/main/assets/adb-shell.py#L442)。

**当前触发：**F02 的接收器只看总开关和系统写设置权限，没有消费自举未决状态；F05 的默认 probe 不带端点，普通路由在 Root/Shizuku 成功后就返回，保活误报 connected。

**修改方向：**

1. 所有自动开启无线调试的入口共用同一无副作用判据。至少覆盖运行中、未决事务、无线占用拒绝、有效的自举使用意图及已知自举监听占用。仅给接收器补一次内存 getter 不够：进程死亡/升级后必须恢复保守的本机互斥意图。
2. 自举确认时先持久化“本轮选择自举/禁止自动重开无线入口”的意图；屏障清除须有可信清理结果及明确的使用模式切换。成功 STATUS 或无操作清理本身不能表示用户选择了无线调试。互斥记录是保守意图，不是永久有效的设备健康证明。
3. 迁移保留已有普通无线配对用户的使用路径；存在自举记录但无法确定是否清理的旧数据，保持禁止自动重开并提示显式观察。开机接收器只读取本机证据，不自动申请 Root、执行自举或在广播中补做设备写动作。
4. 保活探测要显式请求 ADB 计划。自举模式固定回环 5555；普通无线模式使用已确认/发现的本机端点。若需要强制 ADB 且允许普通无线发现的新参数，限定其语义，保持普通业务默认顺序不变；已经发送命令后的未知结果仍不重放。
5. `connected` 必须来自真实 ADB 握手和该会话命令结果。Root/Shizuku 输出及仅 TCP 可达都不是此次认证证明；检查现有快速探活分支是否只是在保留旧状态，避免给它新增认证成功含义。

**完成条件：**开机和覆盖升级在持久 pending、成功自举、清理未知、无线冲突时均不写开启无线调试；清理完且用户明确选择普通无线后，原恢复路径仍可用。Root/Shizuku 可用而 ADB 失败时不得发布 ADB connected。实际端点失败无发送后回退；端点测试继续证明自举只做一次。用模拟设置写入计数验证接收器，不在真机反复开关系统调试。

### D. F06：把首次建表拆成安全前置检查和完整事务

**落点：**[RootBootstrapFiles.keys / replaceKeys](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapFiles.java#L98)、[AUTHORIZE 次序](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapEngine.java#L100)、[原生授权实现](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapExecutor.java#L207)、[文件标签适配](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapFileLabels.java)。

**当前触发：**可信系统目录存在但授权表文件不存在。读取和替换都要求既有 inode/label；执行到这里之前已设置防火墙。此项不能靠把所有读取异常转为空字节修复。

**D1：先做无副作用预检。**在修改规则前区分可信缺失、已有空表、权限拒绝、目录/链接/元数据异常。平台还不能安全初始化时，返回明确的未初始化/不支持原因，防火墙、公钥、监听写入均为零。D1 仅止损，**不能据此关闭 F06 或宣称首次自举已支持**。

**D2：再完成首次建表。**取得可核验的 owner/group/mode/SELinux 标签来源；确认缺失只代表原件不存在，不代表可覆盖别人随后创建的文件。设计无覆盖发布与文件/目录持久化步骤，记录“原先不存在”和本应用创建的确切对象；保留授权回查、路径/链接核验和读回验证。若 Android 23–25 上缺少可靠原语或标签证据，就报告该兼容分支尚未支持，不猜标签、不关闭保护。普通 `rename` 会替换并发出现的目标，不能直接当作不存在目标的安全提交方式。

**完成条件：**覆盖缺失、已有空表/非空表、EACCES、软链接/硬链接、父目录替换、提交前外部建表、元数据来源失败、提交中断/结果未知及撤销重建。并发出现的外部授权始终保留；自身未完成文件只按可证明归属处理。夹具通过后，需独立授权的设备样本验证可支持的平台；不为制造缺文件场景删除用户真实系统授权表。

### E. F07/F08：界面回调和开关值服从最新状态

**落点：**[BootstrapAuthorizationActivity.verifySession](../../../app/src/main/java/com/deepseekharness/app/ui/BootstrapAuthorizationActivity.java#L172)、[RootAuthorizationActivity](../../../app/src/main/java/com/deepseekharness/app/ui/RootAuthorizationActivity.java)、[DeviceGrantsFragment](../../../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java#L109)、[公共弹窗容器](../../../app/src/main/java/com/deepseekharness/app/ui/DshaDialogBuilder.java)。

**修改方向：**

1. 每次只读核验带请求代次，页面暂停/销毁使其 UI 交付失效；线程完成后先核对请求及 Activity 状态，再渲染或显示对话框。清理已发布回调还不够，后台线程可能在清理之后继续 `post`。结果可存应用级状态，页面销毁不触发设备命令重放。
2. 处理会话核验及 Root 授权页同类回调；记录并关闭归属当前页面的对话框。公共样式容器不能替代业务请求的代次控制。
3. 每次首页恢复/刷新从真实授权存储同步总开关，用现有 `updating` 或等价保护抑制监听器。状态渲染只能读，不应因 `setChecked` 引发再次授权、停止服务或确认弹窗。

**完成条件：**核验途中返回、旋转、切换语言、再次发起核验后旧结果到达，均无旧页弹窗/崩溃/重发。自举自动启用后返回驻留首页，及配对页关闭后返回，开关与真实值一致；确认渲染写回次数为零。覆盖中英文和短屏布局，沿用仓库样式验收。

### F. 组合验证与交付

阶段 A–E 完成后，按实际修改范围运行定向测试，再执行两版单测及所需编译/Lint。不要把源文本包含检查当作跨层行为测试。新增主机测试入口需登记[主机测试清单](../../../tools/host-tests.manifest.json)。

```bash
python3 -B tools/test-root-support.py
python3 -B tools/test-root-bootstrap-endpoint.py
python3 -B tools/test-adb-flow.py
python3 -B tools/test-native-device-policy.py
python3 -B tools/test-device-shell-policy.py
python3 -B tools/run-host-tests.py --check-manifest
bash build.sh :app:testStandardDebugUnitTest :app:testLowDebugUnitTest
```

这些是**后续修复的验证命令**，不表示本次文档阶段已全部运行。原有无关失败须记录基线、原因与本轮差异，不删除或改弱断言掩盖。

受管输入以[当前清单](../../../app/src/main/assets/managed-runtime-inputs.json)及[清单解释器](../../../tools/runtime_input_contract.py)为准，不沿用旧 ADR 的“某类永远不影响 runtimeId”静态表。若新增纯逻辑协作者进入受管执行链，检查它是否被目录规则覆盖，必要时纳入清单；不能靠移出清单避开维护。代码批次收敛后再执行：

```bash
python3 -B tools/prepare-runtime-descriptor.py --write
python3 -B tools/prepare-runtime-descriptor.py --check
python3 -B tools/verify-runtime-descriptor-source.py
```

描述符重算不能代替真实 RuntimeTrial。软件验证、运行时试用、APK 签名/身份、覆盖安装和真机验证分别记录；后几项在后续用户授权和环境条件具备时开展，未完成就明确写未验证。

**组合验收至少覆盖：**

| 场景 | 必须成立的结果 |
|---|---|
| 总开关初始关 → 一键自举 | 可进入固定自举；真实会话验证后才称整链完成 |
| 每步撤销或关闭再开启 → 旧结果到达 | 旧意图失效，不继续派发、不反向开启总开关 |
| IPv6 写失败 → 无操作 REVOKE → 进程重建/升级广播 | 原生 pending、本地未决提示与禁止重开保持一致 |
| OPEN 提交或持久化失败 | 不以空队列报告成功，不执行成功后的副作用 |
| Root 正常而真实 ADB 握手失败 | Root 可按自己的授权继续用，ADB 状态不变成 connected |
| 系统授权表缺失且有并发写者 | 安全初始化或零设备写入的明确拒绝，外部条目不丢失 |
| 页面重建/返回，首页状态再显示 | 无旧回调弹窗，显示与最新授权一致 |
| 结果已发送但未知 | 保留证据、只读观察；不换通道、不重放写动作 |

**交付完成条件：**8 个 F 编号逐项附修改位置、能够击中旧错误的测试、实际结果和剩余设备范围；D1-only 标为 F06 部分完成。保持既有未提交改动，审阅最终差异，更新本文状态和对应 ADR，软件通过与设备通过分别列出。未经本轮实际验证，不把历史 Android/Root 方案样本扩写为当前全部兼容。

## 5. 文档纠正与后续观察

本次已纠正 ADR 0005/0007，并给 ADR 0006、自举设计、安全模型和入口增加本计划指针：

- “全局授权”只指本应用入口；系统信任仍按客户端公钥成立。USB/无线配对同样会建立特定客户端的系统信任，Shizuku 是另一个授权和传输边界。
- `portBoot` 是整机开机 UUID；它不因单独重启 adbd 而改变。当前固定动作已有重启能力，通用 shell 的拒绝规则不能用来证明固定入口不存在。
- 历史一次成功会话不能排除另一次失败的桥凭据、路由、依赖、授权或传输问题。先修本计划中的确定缺陷，再根据完整失败证据决定是否调整等待/重试。

以下仅作为状态展示的追加核对，**不计入上述 F01–F08 的修复验收**：[AdbKeyTable](../../../app/src/main/java/com/deepseekharness/app/util/AdbKeyTable.java) 将任意 `dsha:` 注释判为本应用；授权表 UI 没有充分区分读取失败与空表；[DeviceAuthorizationReader](../../../app/src/main/java/com/deepseekharness/app/core/DeviceAuthorizationReader.java) 仅以监听 OPEN 推导自举 active；[DeviceAuthorization](../../../app/src/main/java/com/deepseekharness/app/util/DeviceAuthorization.java) 把系统调试开关当作 USB 来源 GRANTED。若实施中要解决这些问题，应补独立证据/测试并明确新增范围，避免继续以展示性状态代替实际授权证明。

## 6. 可直接交给实施 AI 的任务

> 阅读本文件、[AGENTS.md](../../../AGENTS.md) 和被当前阶段引用的设计文档，以当前工作树为准实施 F01–F08。保留已有未提交改动，按 A→F 的依赖顺序推进；先补能击中旧错误的行为测试，再修改生产路径。当前单端点、单次自举核验和原生授权边界保持不变。逐项报告修复、测试及剩余限制，F06 仅前置拒绝不能标为功能修复完成。设备操作、安装和发布根据后续用户授权决定；不要把本文中的待办写成已经通过。
>
> **2026-10-11 第二轮已执行本节的大部分生产路径；本轮的实际结果与剩余范围见 §7。** 再次接手时先看 §7.2 的未完成项，不要重复已经完成的实现。

## 7. 实施记录（2026-10-11 第二轮）

实施范围：F01–F05、F07、F08 的生产路径；F06 仅 D1。新增的判定全部放在 `util/` 的纯逻辑里并由主机测试击中；Android 适配器（原生入口、控制器、广播、界面）只调用这些接缝。Android 类没有 Robolectric 可用，因此界面 / 广播的**行为**仍需仪器或真机验收；本轮对它们只做到 Gradle 编译与单测任务通过。

| 编号 | 修改位置 | 能击中旧错误的回归 | 实际结果 |
|---|---|---|---|
| F03 | 新增 [AdbBootstrapReply](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapReply.java)（编码 / 解码 / 完成判据接缝）、[AdbBootstrapJournal](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapJournal.java)（网络与公钥两阶段独立判据）、[RootBootstrapExecutor.durableRecord](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapExecutor.java)、[RootBootstrapNative](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapNative.java)（所有动作返回 `pendingVerified` / `networkPending` / `keyPending`）、[AdbBootstrapController](../../../app/src/main/java/com/deepseekharness/app/core/AdbBootstrapController.java) 消费可信响应 | `RootBootstrapExecutorTest.noOpRevokeCannotClearTheDurableNetworkPending`（`failV6` → AUTHORIZE=UNKNOWN/NARROW → 无操作 REVOKE=OK 仍保持未决 → CLOSE 清理后才解除）、`AdbBootstrapJournalTest.onlyATrustedClearedObservationReleasesThePendingBarrier`、`AdbBootstrapReplyTest`（旧格式 / 缺字段 / 退出码不符一律不可信） | `test-root-support.py` 123 项通过；F03 相关 12 项为本轮新增 |
| F01 / F04 | 新增 [AdbSwitch](../../../app/src/main/java/com/deepseekharness/app/core/AdbSwitch.java)（总开关唯一受管写入口 + 用户意图代次）、[AdbBootstrapChainPlan](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapChainPlan.java)（续链与意图判定，含**步骤之间**的总开关意图回查）、[AdbBootstrapChainSession](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapChainSession.java)（链的状态机：步骤归属、队列、只捕获一次意图）、控制器（改为委托状态机；结果持久化失败即终止；`ADB_SWITCH_INTENT_CHANGED` 明确停在中间状态并立起未决屏障）、[DeviceGrantsFragment](../../../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java)、[AdbPairActivity](../../../app/src/main/java/com/deepseekharness/app/ui/AdbPairActivity.java)、[DeviceBridgeService.isAdbEnabled](../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java) | `AdbBootstrapChainPlanTest.submittedNextStepContinues` / `rejectedNextStepAbortsInsteadOfReportingSuccess`（旧实现此处报整链成功）/ `intentChangeBetweenStepsStopsAllFurtherWrites`（旧实现不看意图就继续派发）/ `autoEnableOnlySurvivesAnUnchangedUserIntent`；`AdbBootstrapChainSessionTest`（链内只接受当前步骤、外部动作插不进、队列取空即结束、意图只捕获一次） | `test-root-support.py` 137 项通过 |
| F02 / F05 | 新增 [WirelessReopenPolicy](../../../app/src/main/java/com/deepseekharness/app/util/WirelessReopenPolicy.java)、[WirelessBootAction](../../../app/src/main/java/com/deepseekharness/app/util/WirelessBootAction.java)（开机 / 覆盖升级的唯一判据：只有 ENABLE 分支写设置）、控制器（持久互斥意图 + [旧数据迁移判据](../../../app/src/main/java/com/deepseekharness/app/util/WirelessReopenPolicy.java) + 显式模式切换解除）、[BootReceiver](../../../app/src/main/java/com/deepseekharness/app/BootReceiver.java)、[DeviceBridgeService](../../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java)（共用判据；TCP 快路径只保留既有状态，探活显式请求 ADB 计划）、[AdbBridge.probe](../../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java)（必须带端点，新增 `endpoint` 工厂） | `WirelessReopenPolicyTest`（含 `persistedBlock` 迁移矩阵：显式记录优先；旧数据里 AUTHORIZE/NARROW/OPEN 保守拦截，STATUS/CLOSE/REVOKE 或无记录不影响普通无线用户）；`WirelessBootActionTest`（无权限 / 自举互斥 / 已开启三种情况都不得写设置）；`test-root-bootstrap-endpoint.py` 证自举端点只连回环 5555、只跑一次 `id` | `test-root-support.py` 137 项通过；`test-root-bootstrap-endpoint.py` 15 项、`test-adb-flow.py` 29 项通过 |
| F06 | 新增 [AdbKeyTablePreflight](../../../app/src/main/java/com/deepseekharness/app/util/AdbKeyTablePreflight.java)（D1 判定表）、[RootBootstrapFiles.preflightKeyTable / keys](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapFiles.java)、[RootBootstrapExecutor.preflight](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapExecutor.java)、[AdbBootstrapEngine](../../../app/src/main/java/com/deepseekharness/app/util/AdbBootstrapEngine.java) 写入前预检钩子 | `AdbKeyTablePreflightTest`（缺失 / 链接 / 元数据 / 标签）、`RootBootstrapExecutorTest.unsupportedFirstWriteRefusesBeforeAnyDeviceWrite`（零防火墙 / 公钥 / 监听写入） | 通过；**仅 D1：首次建表（D2）未实施** |
| F07 | [BootstrapAuthorizationActivity](../../../app/src/main/java/com/deepseekharness/app/ui/BootstrapAuthorizationActivity.java)（核验与授权表请求代次、生命周期判定、对话框归属与销毁）、[RootAuthorizationActivity](../../../app/src/main/java/com/deepseekharness/app/ui/RootAuthorizationActivity.java) | 无自动化回归，需仪器；逻辑是请求代次比较与 `isFinishing/isDestroyed` | 编译通过，待设备验收 |
| F08 | [DeviceGrantsFragment.syncAdbSwitch](../../../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java)（每次 `onResume` 从真实存储同步总开关，`updating` 抑制监听器，渲染不写回） | 无自动化回归，需仪器 | 编译通过，待设备验收 |

顺带修好的既有红灯：[AdbFirewallTool.neutralize](../../../app/src/main/java/com/deepseekharness/app/util/AdbFirewallTool.java) 过去只替换 `[EXIT=` 左半段，留下 `(exit=125]` 这种半截文本；现按完整标记连方括号一起中性化，`AdbFirewallToolTest.evidenceNeverCarriesTheReservedProtocolTokens` 从失败转为通过。该失败在本轮开始前就存在（生产文件与 HEAD 相同）。

### 7.1 本轮执行的验证命令与结果

```bash
python3 -B tools/test-root-support.py                    # OK（第二轮 123 → 增量 124 → 链状态机 130 → 开机判据 134 → 迁移判据 137 tests）
python3 -B tools/test-root-bootstrap-endpoint.py          # OK (15)
python3 -B tools/test-adb-flow.py NativeRouteTest         # OK (3)
python3 -B tools/test-native-device-policy.py             # PASS（16 项 dry plan）
python3 -B tools/test-device-shell-policy.py              # OK
python3 -B tools/prepare-runtime-descriptor.py --write    # 重算（launcher 输入已纳入 5 个新协作者）
python3 -B tools/prepare-runtime-descriptor.py --check    # exit 0；runtimeId 随每轮 launcher 输入变化
#   第二轮 7d6c660c → 91d7f91a → 40a470c4 → e4013087
python3 -B tools/verify-runtime-descriptor-source.py      # 113 资产 / 301 启动输入
```

Android 侧的命令与结果（`ANDROID_SDK_ROOT=/root/Android bash build.sh …`）：

```bash
# 全量：本轮尝试未完成，原因见下（既有测试卡住，与 F01–F08 无关）
:app:testStandardDebugUnitTest

# 替代：定向过滤（主源码与全部单测源码仍然整包编译）
:app:testStandardDebugUnitTest --tests 'com.deepseekharness.app.RootBootstrapExecutorTest' \
  --tests 'com.deepseekharness.app.util.AdbBootstrap*' \
  --tests 'com.deepseekharness.app.util.AdbKeyTablePreflightTest' \
  --tests 'com.deepseekharness.app.util.WirelessReopenPolicyTest' \
  --tests 'com.deepseekharness.app.util.AdbFirewallToolTest' \
  --tests 'com.deepseekharness.app.util.AdbAuthorizedKeysTest' \
  --tests 'com.deepseekharness.app.util.AdbdIdentityTest' \
  --tests 'com.deepseekharness.app.util.RootSupportProbeTest'
# → BUILD SUCCESSFUL in 57s；13 个类 / 110 项，0 失败
:app:testLowDebugUnitTest --tests '…同一组过滤…'
# → BUILD SUCCESSFUL in 2m31s；13 个类 / 110 项，0 失败
```

**全量任务为什么没跑完：** `ManagedRuntimeTransactionTest`（`backup/`，本轮未改）在 `JvmBackupFileSystem.read` → `Files.newInputStream` → `LinuxFileStore.findMountEntry` → `getmntent0` 上持续占用 CPU 且 10 分钟没有任何新结果写入（本机是 391 条挂载项的 Android 容器）。本轮开始前仓库里那份 `app/build/test-results`（901 项、2 失败、1 跳过）也停在同一位置，说明这是既有环境问题，不是本轮改动引入。因此本轮以「整包编译通过 + 定向单测通过 + 主机测试通过」作为软件通过标准，并在交付里明确写出全量任务未完成。

同一份既有报告里的两项失败：`AdbFirewallToolTest.evidenceNeverCarriesTheReservedProtocolTokens`（本轮已修，见上表下方说明）与 `AdbWheelPathsTest.externalBindingsForeignHostsAndLinkCyclesNeverCreateCache`（环境相关，未动）。

### 7.2 剩余范围（不得据此宣称完成）

- **F06 只完成 D1**：系统授权表缺失时在任何设备写入前明确拒绝（`ADB_KEY_TABLE_MISSING_UNSUPPORTED` 等），首次建表（无覆盖发布、owner/group/mode/SELinux 标签来源、提交前外部建表、提交中断处理）未实施。D1 不是功能修复完成。
- **F01 / F04 仍有控制器级用例未交付**：链的**状态机**（步骤归属、队列语义、只捕获一次意图、步骤之间改总开关不再派发）已经抽到 `util/AdbBootstrapChainSession` 并有主机测试；仍未覆盖的是控制器 I/O 粘合层的组合场景 ——「请求与结果保存失败」「OPEN 提交在状态机推进后被拒」「等待 2.5 秒期间撤销」「并发 STATUS / 旧步骤迟到」需要可控执行器与时钟的注入，控制器目前不是主机可测的。计划 §B 的完成条件只能算部分满足。
- **F02 的接收器只差 Android 层的写入计数用例**：`WirelessReopenPolicy` 与 `WirelessBootAction` 都有主机测试，接收器现在也只按 `WirelessBootAction.writesSettings(decision)` 决定是否写设置；但「被拦住时 `Settings.Global.putInt` 一次都没被调用」本身需要模拟 Android 设置的测试，仓库当前没有 Robolectric，未做。
- **F05 的真机探活未验收**：`probe` 已强制 ADB 计划、TCP 快路径不再发布新认证；但 Root 可用而 ADB 失败的设备样本、实际端点失败后的行为只在源码与主机路由测试层面确认。
- **F07 / F08 的仪器验收未做**：返回 / 旋转 / 切换语言 / 再次核验的界面行为、首页开关与真实值一致、渲染写回次数为零，需按仓库既有 style 验收方式在设备上核对。
- **描述符与设备验收分开记录**：本轮重算了 `runtime-descriptor.json`。注意 `AdbBootstrapController` / `RootBootstrapExecutor` / `AdbBridge` / `DeviceBridgeService` / `DshaApp` 都在 `launcherSources` 里，**每轮改它们都会改变 `runtimeId`，覆盖安装后会再触发一次环境维护** —— 这是受管契约的代价，不能靠把文件移出清单来回避（计划 §155）；打包时应尽量攒批。描述符重算不能代替 `RuntimeTrial`、APK 签名 / 身份、覆盖安装与真机验证；这些未开展，按未验证处理。
- **既有无关失败（本轮未修，不掩盖）**：
  - `AdbWheelPathsTest.externalBindingsForeignHostsAndLinkCyclesNeverCreateCache`：`expected java.io.IOException to be thrown, but nothing was thrown`，与本轮改动无关（环境相关，容器内以 root 运行）。
  - `python3 -B tools/run-host-tests.py --check-manifest`：**本轮已修**。HEAD 上失败是因为 `tools/test-mobile-reasoning-defaults.mjs` / `test-mobile-refresh.mjs` / `test-web-file-actions.mjs` 三个测试文件没有登记。现在：`test-mobile-reasoning-defaults.mjs` 登记为 `fast`（只需 node24，已通过执行），另两个登记为 `support`（需要构建产出的 `app/build/test-runtimes/current.json`，不纳入默认主机测试运行）。`--check-manifest` 现在返回 `PASS_MANIFEST`（130 项）。
- **旧接口未变**：本轮没有改变原生日志格式（未新增字段），因此不需要日志兼容 / 损坏记录的新测试；响应新增字段属于 JSON 协议层，旧格式一律解码为「不可信」，方向是保持未决而不是解除。

### 7.4 设备实测发现（2026-10-12，不属于 F01–F08）

覆盖安装后用文件管理器「打开」不触发环境维护，退出重开才触发。根因是**门禁位置**，不是环境状态：
维护门禁只写在 `MainActivity.onCreate` 里，任何不新建 MainActivity 的入口（任务被恢复到别的
Activity、已有任务回前台、克隆 / 快捷方式打开）整段跳过；build187 补的 `onResume` 只渲染横幅。
实机 `dumpsys activity activities` 显示任务栈顶是自举授权页，证实这次进入没有经过门禁。

修复：新增 `ui/EnvironmentGate`（判据单一来源）+ `DshaApp` 注册应用级 `ActivityLifecycleCallbacks`，
任意非豁免页面回到前台先过门禁；`MainActivity` 改用同一判据。安全属性不变 —— 即使漏过 UI 重定向，
Web 启动仍由 `WebLifecycleController` 的同一运行时门禁挡住。包与验证见
[build189](../releases/v0.2.37-build189-20261012.md)。
