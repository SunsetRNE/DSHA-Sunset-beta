# Root 支持落地准备包（历史 M1 快照）

> 2026-10-10 衔接说明：此目录保留接手时的 M1 材料，以下旧状态、目录规划和命令不代表当前工作树或完整交付。当前五个原生动作、独立授权、事务、防护、UI 与实际主机验证，统一以[实现与验证说明](../../docs/audits/root-adb-bringup-20261009/implementation-20261010.md)为准。`ORIGINAL/` 原字节不改写；本轮没有部署设备动作，也未执行本页历史构建/回滚命令。

生成时间：2026-10-09 UTC。此包是 DSHA root/ADB 支持的主机侧评估、M1 纯逻辑实现和落地前验收材料；它不是 APK、KernelSU 模块或设备配置包。没有 Android 设备命令、ADB bootstrap、端口监听、密钥写入或持久 root 配置在本轮执行。

## 目录

| 目录或文件 | 内容 |
|---|---|
| `ORIGINAL/` | 15 份评估前文档与历史脚本的逐字节副本 |
| `original-manifest.json` | ORIGINAL 的路径、SHA-256 和模式 |
| `MODIFIED/` | 修订文档候选、M1 新增源码与受控停用的历史脚本副本 |
| `REFERENCE/` | 未改写但为 M1 编译/入口审阅所需的源码副本 |
| `DIFF/` | 可审阅补丁、验证记录、保留性检查与交付摘要 |
| `SHA256SUMS` | 包内交付文件摘要；生成后用于完整性检查 |
| `ROLLBACK.sh` | 只对摘要匹配的临时应用副本执行恢复/删除的脚本 |
| `rollback-manifest.json` | 回滚允许处理的固定路径和期望摘要 |

## 当前实现状态

- 新增纯 Java `AdbBootstrapPolicy`：固定五个动作、固定端口、严格字段类型、可信安装包/UID 比对与每动作执行条件。
- 新增纯 Java `AdbdIdentity`：只解析固定列进程证据，要求两份捕获为同一唯一 PID，失败/歧义保持 `UNKNOWN`。
- `SelfPackageIdentity` 是既有依赖，本包只作参考副本；新代码没有修改它。
- `RootShellMain` 仍没有 `adb-bootstrap` 分支。策略允许不表示原生授权、密钥事务、防火墙、监听或 UI 已实现。
- 历史 scripts 的 `MODIFIED` 副本在首行后立即退出 126，防止评审者误把不完整的 IPv4-only/整表撤销原型当成部署件；原始可执行字节保留在 `ORIGINAL`。

## 审阅与复验

在仓库根运行：

```bash
python3 -B tools/test-root-support.py
python3 -B tools/test-native-device-policy.py
python3 -B tools/verify-runtime-descriptor-source.py
```

完整 Android 验收在安装 API 37 SDK 后执行：

```bash
bash build.sh :app:testStandardDebugUnitTest --tests '*AdbBootstrapPolicyTest' --tests '*AdbdIdentityTest'
bash build.sh :app:assembleStandardDebug :app:assembleLowDebug
```

主机通过只证明纯 Java 编译/测试和既有主机回归。设备验收仍须分别覆盖安装包身份、root 原生许可、密钥所有权和并发、IPv4/IPv6 先防护后监听、撤销、未知结果、rooted/non-rooted 通道与 L1 exec/回收。

## 评审和回滚模型

先校验 ORIGINAL 与仓库中预先存在文件的摘要，再比较 `ORIGINAL/` 与 `MODIFIED/`。所有回滚都限制在 `rollback-manifest.json` 中列出的路径：若目标内容不等于交付的预期摘要，脚本停止，不覆盖后续编辑。脚本只用于从本包 MODIFIED 副本构建的临时应用目录；未在主工作树执行。

已知：包内 ORIGINAL 及既有工作树保留摘要将被验证；M1 测试由本轮运行留证。
推测：状态拆分能减少后续原生/UI 接入返工；没有设备工时实测。
未知：完整 SDK 构建、设备状态、双栈防护、密钥事务、持久模块和 chroot exec。
过期：历史报告把捕获窗口的状态视为持续现状（有效期止于原捕获窗口，精确截止未知）；替代为 MODIFIED 中的分项验收。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| M1 纯 Java 源码和测试副本可审阅 | 已知 | 本次工作树快照 | MODIFIED/ 与源文件清单 |
| 完整 Android 构建已经通过 | 未知 | 无 | 缺 API 37 平台包 |
| 历史设备状态持续有效 | 过期 | 原捕获窗口；精确截止未知 | ORIGINAL/evidence.md |
| 拆分状态降低后续返工 | 推测 | 无承诺 | 设计依赖分析 |

截至 2026 年 10 月 9 日（UTC）：完成主机侧准备，未部署设备动作。
适用范围：DSHA root 支持落地前评审与可重复主机验证。
已知推测未知：源码和历史证据已分层；设备状态与生产接入待验收。
依赖与边界：完整 Gradle 验证需要 Android SDK API 37；设备验收需要实际目标环境。
