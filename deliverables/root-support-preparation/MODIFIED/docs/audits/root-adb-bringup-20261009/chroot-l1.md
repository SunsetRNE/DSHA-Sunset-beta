# 修正 chroot 路线状态与验收条件

自有 DSHA 的主机侧评估。本轮不执行设备 mount、chroot、su 或模块配置；历史命令原字节保存在 ORIGINAL，本文件供后续实现评审。

## 三层状态

| 层 | 目标 | 本轮结论 |
|---|---|---|
| L0 | 现有 proot / proroot | 保持默认，不依赖 root |
| L1 | root 宿主 + bind + chroot + exec | 尚未完成可执行验证 |
| L2 | L1 加 mount/net/uts namespace | 有历史配置与探测线索，未完成隔离/生命周期验收 |

历史 R3 含 `BIND_OK`、`NSMOUNT_OK`，还含 `chroot: exec /bin/toybox: No such file or directory`。ENOENT 与缺少 ELF 解释器相容，但没有 syscall 返回、完整 ELF 依赖或成功的新根 exec 证据。因此既不能凭 `CHROOT_DENIED` 认定权限必然被拒，也不能改写成“chroot 权限已通过”。

## 证据缺口

| 项 | 已读证据 | 后续判据 |
|---|---|---|
| root 权限 | 历史 su 的 UID/SELinux 捕获 | 当前原生许可、真实 UID、capability 与域 |
| bind/proc mount | 历史成功标记 | 原始退出码、mountinfo 前后差异、清理后无残留 |
| namespace | 配置和 `/proc/self/ns` 列表 | 父子 namespace inode 对比、隔离行为、退出与回收 |
| chroot syscall | 错误文本 | 可用时 syscall 返回/errno，或成功 exec 与新根身份共同证明 |
| 动态 exec | ENOENT | 同一二进制的 PT_INTERP、DT_NEEDED、解释器/库与 linker namespace |
| 静态 exec | 没有实测 | ELF 确认无解释器与动态依赖；不能按 `ksud` 名字假定静态 |
| 停止/回滚 | 没有专项捕获 | PID 出生身份、所有权挂载表与清理结果 |

只读分析已导出的二进制样本（宿主命令，不执行样本）：

```bash
readelf -lW /path/to/authorized-sample.elf
readelf -dW /path/to/authorized-sample.elf
```

主机侧已交付策略验证：

```bash
python3 -B tools/test-root-support.py
```

判据：ELF 样本路径需替换为实际已导出文件；第一块尚未执行、不作设备成功证明。第二块已跑通 23 项纯 Java 测试，与 chroot 权限无关。

## 后续设备验证契约

1. 通过原生允许的专用验证入口记录当前安装包、root 授权和执行身份；策略拒绝时停止该动作，不改用其他 shell 绕行。
2. 创建并记录独占的临时工作目录、规范化路径、内容摘要及 owner。清理前核对路径、挂载和进程所有权，不能以固定路径直接递归删除。
3. 先确认同一测试二进制在宿主路径可执行，再按实际 ELF 依赖构造新根；不吞掉库复制错误，不把字符串搜索当 PT_INTERP 解析。
4. 分别记录 chroot 与 exec 阶段、退出码、errno 和拒绝日志。看到 AVC 时定位具体操作、域与对象；没有 AVC 也不能证明权限通过或路线不可行。
5. L1 成功至少要求新根内执行成功、可确认的根目录身份与依赖一致；仍需生命周期、挂载回滚、真实宿主停止与 App 重启验收。
6. 命令结果未知先只读观察，禁止换通道或重放。清理只处理已确认属于本次验证的资源。

## namespace 与性能结论

历史配置显示 PID_NS/USER_NS 未设置，IPC_NS 的配置和 namespace 列表都缺项；它们限制该历史内核的相应隔离能力。不能进一步推导所有嵌套运行方式不可用，也不能把现有 mnt/net/uts 配置等同完整内核沙箱。

overlay upperdir 需要实际文件系统的 xattr、权限与挂载支持；不能仅按 `/data` 路径承诺可用。是否能取消硬链接模拟、是否有性能收益，也需要实际 link/exec 以及同工作负载基准；本轮没有这些实测。

已知：R3 捕获文本存在；没有成功 chroot exec 记录。
推测：ENOENT 可能来自缺少 ELF 解释器或依赖，仍需样本与 syscall 验证。
未知：当前设备的 chroot、namespace 隔离、硬链接、性能和回滚能力。
过期：历史“L1 权限面已证”的现状声明（有效期仅原捕获窗口且其推论不充分，依据 R3）；替代为 L1 未验证。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| 历史 R3 含 ENOENT 和 mount 成功标记 | 已知 | 仅原捕获窗口 | evidence.md R3 |
| ENOENT 由缺解释器引起 | 推测 | 无承诺 | 缺 ELF 与 syscall 捕获 |
| L1 当前可以执行并可靠回收 | 未知 | 无 | 尚未运行目标验收 |
| 原“L1 权限面已证”可作为开工前提 | 过期 | 原报告窗口；精确截止未知 | 历史判读已修正 |

截至 2026 年 10 月 9 日（UTC）：本轮修正文档，未进行设备探测。
适用范围：DSHA L1/L2 路线设计，不改变 L0 默认运行时。
已知推测未知：原输出保留，解释器归因是推测，执行支持未知。
依赖与边界：需要实际目标环境复验；不提供已部署或性能收益保证。
