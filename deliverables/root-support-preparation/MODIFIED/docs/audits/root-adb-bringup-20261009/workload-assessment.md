# Root 支持落地准备：工作量与验收评估

评估对象：把 `adb-bootstrap` 命名特权操作、adbd 身份证据、授权模型、暴露面控制和 UI 状态完整落地。本文修订历史评估的现状判断；不把纯 Java 策略代码的通过扩大成设备 root 支持已部署。

## 当前结论

| 工作包 | 当前状态 | 后续范围 | 估算状态 |
|---|---|---|---|
| A1 策略与可信身份 | 已实现并有纯 Java 单测 | 入口接线前复核 PackageManager 事实与竞态 | 仅剩集成工时未测 |
| A5 adbd 身份解析 | 已实现并有纯 Java 单测 | 目标设备采集、会话 `id` 复验与快照时效控制 | 仅剩设备工时未测 |
| A2/A3 特权执行器与命名入口 | 未接通 | 五动作原生实现、未知结果、无重放 | 未估实测 |
| A4 授权存储与 UI | 未接通 | 默认关闭、可撤销、状态分层与文案 | 未估实测 |
| A7/A8 暴露面与持久模块 | 设计修正完成，未安装 | 双栈先防护后监听、选择性清理、启动期重建 | 未估实测 |
| D 构建与设备验收 | 主机部分通过；Android 构建受 SDK 门禁 | 两 flavor、签名、rooted/non-rooted 设备 | 未完成 |

M1 新增 `AdbBootstrapPolicy`、`AdbdIdentity`、两类边界测试与主机 runner；`SelfPackageIdentity` 和其测试是既有依赖，本轮未改写。`AdbBootstrapPolicy` 是元数据校验器，不执行 `setprop`、iptables、密钥写入或任何设备命令；`RootShellMain` 尚未接通 `adb-bootstrap`。

## 已修正的评估假设

1. adbd 的 UID/SELinux 进程证据不等于客户端会话权限；必须另取强制 ADB 路径的 `id`，并记录会话域与时间。
2. IPv4 规则不等于双栈收窄。IPv4 与 IPv6 监听、规则顺序和端到端探测都要有证据；无法核验的地址族禁止开启。
3. `adb_keys` 是共享授权表。撤销只能删除有明确所有权记录的本应用条目，不能清空整表。
4. 先开监听再补规则会产生暴露窗口；生产顺序必须先确认双栈防护，再开启并复读监听状态。
5. 任意端口、持久“常开+收窄”、超时即视为失败、历史 chroot `ENOENT` 即视为权限拒绝，均从当前评估结论中移除。

## 历史工时与重新估算

原评估列 A 34–40、B 15–18、C 16–24、D 9–12 人时，总计 74–94 人时；R3 路线为 96–124 人时。这些是原文件的人时模型，未由实施或设备耗时测量，本轮不将它们当剩余工期。原值仍在 ORIGINAL 的 workload-assessment.md 中可审阅。

现有 M1 完成减少了部分编码任务；密钥所有权/并发、双栈先防护、未知结果恢复、APK 竞态与设备矩阵增加了原估算未覆盖的验收。UI、持久模块和插件不再强绑同一批：先完成原生命名操作与状态分层，插件只在字节必须改变时联动描述符，持久档独立评审。全接口长期常开的历史最小路线不作为当前可交付档。剩余人时未知，先按 M2/M3 各动作的实际证据重估。

## 已完成代码与边界

### 策略层

`AdbBootstrapPolicy.inspect(...)` 只接受固定字段和小写动作，拒绝额外字段、类型强转、任意端口、错误包名/UID、系统与 isolated UID。已安装包身份由独立 `SelfPackageIdentity` 事实提供，不能由请求自述构造。动作要求集合区分授权、撤销、收窄、开启和关闭，并将 APK 身份、原生许可、公钥、密钥快照、双栈防护和监听观测分别列出。

### adbd 证据层

`AdbdIdentity` 只解析固定的 `ps -A -o PID,UID,NAME` 与 `ps -A -o PID,CONTEXT,NAME` 捕获件，并兼容已记录的十列 `ps -A -Z` 形状。两份捕获必须以同一 PID 指向唯一 `adbd`，退出码非零、控制字符、策略拒绝、未知结果和格式歧义均返回 `UNKNOWN`。它明确不把 daemon 身份当成所有客户端会话的授权证明。

### 未接通面

以下工作仍需 Android 原生实现和设备验证：

- `RootShellMain`/`RootShell` 的命名操作分支与原生授权门控；
- `adb_keys` 公钥格式、所有权记录、快照和原子更新；
- IPv4/IPv6 规则、监听生命周期、失败清理和 KernelSU 模块；
- `DeviceGrants`、桥路由、UI 状态与默认关闭持久化；
- rooted 与非 rooted 设备的通道选择、未知结果和回滚验收；
- 修正后的 chroot 静态/动态执行链路。

## 复验命令

主机回归（不操作 Android 设备）：

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
python3 -B tools/test-native-device-policy.py
python3 -B tools/verify-runtime-descriptor-source.py
```

纯 Java 定向测试（需要 JDK 17、锁定的真实 JUnit/Hamcrest 缓存；脚本生成 UI 文案源并建立独立临时编译目录）：

```bash
python3 -B tools/test-root-support.py
```

判据：格式化后的源码重新编译，JUnit 输出 `OK (23 tests)`；其中 root 新增测试 20 项、既有安装包身份回归 3 项。编译参数包含真实依赖 classpath；不使用 Android stub 或替代 JUnit。缓存根可用 `--gradle-user-home` 指定。

完整 Android 构建（当前环境需要先准备 API 37 `android.jar`）：

```bash
bash build.sh :app:testStandardDebugUnitTest --tests '*AdbBootstrapPolicyTest' --tests '*AdbdIdentityTest'
bash build.sh :app:assembleStandardDebug :app:assembleLowDebug
```

## 当前证据

- `tools/test-native-device-policy.py`：通过，16 个 native dry plan；脚本明确不执行 Android 或设备权限操作。
- `tools/verify-runtime-descriptor-source.py`：通过，`checkedAssets=108`、`checkedLaunchers=277`，三个延迟归档仍按构建约定跳过。
- 锁定 Java 格式化：4 个文件完成格式化，token 摘要保持一致。
- 定向 JUnit：23 项通过（新增 20 项、既有身份 3 项）；完整 Gradle 门禁尚未通过，环境缺 `android-37` 平台包。
- 设备侧 root、adbd、双栈、监听、密钥事务、KernelSU 和 chroot exec：本轮未操作、未复验。

## 阶段与回滚

| 阶段 | 可交付判据 | 回滚点 |
|---|---|---|
| M1 策略层 | 定向 JUnit、native dry plan、descriptor 校验通过 | 删除新增纯 Java 文件与测试 |
| M2 命名入口 | 未接通命令返回拒绝；接通后需 APK/UID 事实核验 | 删除 `adb-bootstrap` 分支，保留通用 BLOCK 面 |
| M3 原生事务 | 双栈顺序、选择性撤销、未知结果测试通过 | 恢复密钥快照/规则所有权记录 |
| M4 UI/授权 | 默认关闭、撤销后状态可见、无自动换通道 | 回退 UI 与授权键，不恢复设备状态 |
| M5 发布 | SDK 就绪后双 flavor 构建与设备验收留证 | 保留上一 APK，不覆盖未验收产物 |

已知：M1 源码、23 项定向 JUnit、16 个 native dry plan 和 descriptor 数字来自本会话实测；运行时入口仍未接通。
推测：把身份、密钥所有权和双栈防护拆成独立状态会降低后续返工；没有工时实测。
未知：目标环境的 root 授权、客户端会话身份、双栈规则、chroot exec、完整 Android 构建和发布验收。
过期：原评估中“全仓不存在 `adb-bootstrap`”的现状断言（有效期到本轮代码加入前，依据原始评估 §2），替代为本节的 M1/M2 状态表。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| M1 策略代码已在工作树中 | 已知 | 本次工作树快照 | Java 源文件、定向测试 |
| native dry plan 通过 16 项 | 已知 | 本次会话验证时点 | `tools/test-native-device-policy.py` 输出 |
| descriptor 检查为 108/277 | 已知 | 本次工作树资产快照 | `tools/verify-runtime-descriptor-source.py` 输出 |
| 完整 Android 构建通过 | 未知 | 无 | 当前缺 `android-37` 平台包 |
| 设备 root/ADB 支持已部署 | 未知 | 无 | 本轮没有设备操作 |

截至 2026 年 10 月 9 日（UTC）：本文依据本次源码与主机回归检查修订。
适用范围：本仓库工作树的 root 支持落地准备，涵盖 `standard`/`low` 共用纯逻辑代码。
已知推测未知：M1 已验证；集成、设备与发布项仍待目标环境。
依赖与边界：需要实际目标环境复验；完整 Gradle 验证依赖 Android SDK API 37。
