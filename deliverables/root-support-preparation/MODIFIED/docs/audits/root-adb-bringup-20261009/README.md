# 修正 root → ADB 历史评估与落地判据

本报告基于自有 TB321FU 的历史捕获与本轮仓库检查。原记录使用设备日期 2026-10-09，整理日期为 2026-10-08（UTC）；原捕获没有完整时区偏移，不能据此建立持续有效期。本轮只修改主机源码、测试和文档副本，没有操作设备。原始文档、脚本与字节摘要保留在交付包的 ORIGINAL 中。

| 配套材料 | 用途 |
|---|---|
| [evidence.md](evidence.md) | 保留 R1–R12 捕获文本，纠正从输出到结论的推导 |
| [design.md](design.md) | 固定请求、可信安装包身份、密钥事务、双栈与原生门控 |
| [workload-assessment.md](workload-assessment.md) | 当前 M1 代码状态、剩余依赖与历史估算适用范围 |
| [devices/TB321FU.md](devices/TB321FU.md) | 历史设备样本；不能代表现在的设备能力 |
| [chroot-l1.md](chroot-l1.md) | L0 保持默认，L1 exec 与 syscall 证据仍待取得 |

## 历史捕获证明了什么

- R12 记录了明确 ADB 计划、该次客户端 `id` 的 UID 0 与 `u:r:ksu:s0`，以及 adbd 进程的同样 UID/域。它证明那次会话身份为 root；不能只从 daemon 身份推导所有会话权限，也不能宣称与另一 root 通道完整同权。
- R6/R7 中 `listen_address` 回显和 `ss` 绑定不一致，说明该历史环境不能单靠属性证明只监听 loopback。地址族、远端可达性和已建立连接仍需各自证据。
- R8 记录了 IPv4 INPUT 规则写入；R10 记录了规则消失。不能由这些输出断言已完成 IPv6 防护，也不能仅由 PID 改变证明整机重启或由规则消失确定 netd 是原因。
- R4/R10 记录了该窗口密钥表可写与一个公钥被接受。空表及临时条目数不能证明设备从未配对；整表清空也不能用于通用撤销。
- R9 记录了当时 wheel 归档与锁值匹配、离线准备成功。它不代表本轮安装或资产运行验证。

## 权限与暴露面独立判断

| 问题 | 必需证据 | 本轮状态 |
|---|---|---|
| 是否找到且获准使用 su | 原生许可、管理器结果、真实身份 | 未复验 |
| adbd 当前身份 | 固定列 UID/SELinux 捕获、真实退出码、同一 PID/出生身份 | 解析代码已写；设备未复验 |
| 强制 ADB 会话身份 | ADB 计划与会话 `id`、域、退出码 | 历史 R12；当前未知 |
| IPv4/IPv6 暴露面 | 两族监听、规则顺序/所有权、连接与外部探测 | 历史仅部分 IPv4；当前未知 |
| chroot 是否可执行 | 宿主 exec、ELF 依赖、syscall 或新根 exec 判据 | 未证明 |

`ro.adb.secure=1` 的属性值不是独立的认证测试；`service.adb.root` 为空也不能单独解释 adbd 身份来源。SELinux 域、capability、实际 syscall 和文件权限均须独立检查。

## 默认档与事务顺序

默认关闭，按需开启。产品实现须先取得 APK/UID 事实与原生授权，再建立本应用公钥所有权与快照，验证双栈防护先于开启监听。关闭后复读两族监听和已有会话状态；撤销只删除本应用记录拥有的条目，保留其他授权。

持久 KernelSU 模块尚未创建或安装。历史“先 post-fs-data 开端口、后 service.sh 补 IPv4”骨架不能作为验收通过的实现。规则失效、启动时序、netd 重建、撤销及卸载需单独设计并实测。外部 `nc` 超时不能代替本地规则与监听证据。

原 scripts 目录的四个文件作为历史原型保留：存在任意端口、整表撤销、IPv4-only、`sh -c` 参数构造、dry-run 副作用和过宽身份推断等缺口。本交付没有调用它们，也不把其语法正确视为设备策略许可。生产执行只能通过后续受控命名操作；不能通过裸 su、替代 shell 或桥外路径绕过策略。

## 当前可运行件

本轮新增 `AdbBootstrapPolicy`、`AdbdIdentity` 和两类边界测试；`SelfPackageIdentity` 是仓库既有可信身份依赖。请求只允许五个动作和固定端口，实际执行条件由 requirements 声明。`RootShellMain` 未接通 `adb-bootstrap`，所以 M1 通过不表示自举动作已可调用。

主机纯 Java 验证：

```bash
python3 -B tools/test-root-support.py
```

原生策略与资产来源回归：

```bash
python3 -B tools/test-native-device-policy.py
python3 -B tools/verify-runtime-descriptor-source.py
```

判据：定向 JUnit 已输出 `OK (23 tests)`（新增 20 项与既有身份 3 项）；native dry plan 为 16；descriptor 检查为 108 个资产/277 个启动器。完整 Gradle 单测与 APK 构建仍受 Android SDK platform 37 缺失门禁。

## 下一阶段验收

1. 接通原生命名入口、默认关闭的自举授权与可信 APK 身份，维持通用 BLOCK 面。
2. 实现密钥所有权、快照、并发与选择性撤销，验证其他客户端条目原字节保留。
3. 实现并验证双栈先防护后暴露、监听关闭、规则所有权和失败清理。
4. UI 分别显示授权、daemon、会话、暴露面和执行未知；不由一条 `id` 合并成完整 root 支持。
5. SDK 就绪后跑双 flavor 构建；再在获准的目标环境验收 root/non-root、撤销、异常和生命周期。
6. chroot 另取可执行证据，L0 保持默认；持久档独立验收。

已知：本轮读取了历史捕获、源码与构建声明，主机定向测试和 dry plan 通过。
推测：历史 chroot ENOENT 与缺少 ELF 解释器相容；syscall 返回及实际依赖仍需确认。
未知：当前 su/daemon/会话、双栈防护、密钥状态、L1 exec 与完整 APK 构建。
过期：原报告的“当前监听/规则/会话”持续有效声明（有效期止于原捕获窗口，精确截止无记录，依据 R1–R12）；替代为本报告状态分层与复验清单。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| R12 文件记录过一次 root ADB 会话 | 已知 | 仅历史捕获窗口 | evidence.md R12；本轮读文件 |
| 历史设备状态持续代表当前环境 | 过期 | 原捕获窗口；精确截止未知 | 没有连续观测与本轮设备复验 |
| daemon UID 0 可独自证明所有会话同权 | 未知 | 无 | 缺会话、域与 capability 验证 |
| 缺 ELF 解释器解释 chroot ENOENT | 推测 | 无承诺 | R3 错误形态；待 ELF/syscall 证据 |
| 纯 Java 策略与解析器通过定向 JUnit | 已知 | 所测源码快照 | `OK (23 tests)`；20 新增+3 既有 |

截至 2026 年 10 月 9 日（UTC）：本轮只完成主机源码准备与历史评估修订。
适用范围：DSHA 工作树及 TB321FU 的历史样本，不外推当前设备与其他 ROM。
已知推测未知：文件与主机验证有证据；持续设备能力与部署未验证。
依赖与边界：需要实际目标环境复验；Android 构建依赖 API 37 SDK。
