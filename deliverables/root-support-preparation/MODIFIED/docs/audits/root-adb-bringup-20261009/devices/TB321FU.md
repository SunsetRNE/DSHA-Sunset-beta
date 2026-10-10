# 校准 TB321FU 历史 root 能力档案

自有设备的历史样本整理，所有设备条目来自 [evidence.md](../evidence.md) R1–R12 或原档案，本轮没有设备操作。原档案日期为 2026-10-09（设备日期），完整时区偏移未知；本轮整理使用 UTC。机型名称与版本用于定位样本，不构成当前兼容性声明。

## 历史样本与证据强度

| 项 | 原档案记录 | 证据与限制 |
|---|---|---|
| 设备/Android | LENOVO TB321FU / Android 16 | 系统设置与 R1；本轮未复验 |
| 内核 | `6.1.124-android14-11-gd53339245194-ab13005711` | 原档案引用设置/`uname -r`，R1–R12 未含完整原始输出 |
| 管理器 | ReSukiSU `v4.2.0-rc2 (35153/4)`；UID 10281 | 原档案界面记录，不是本应用安装 UID 事实 |
| 构建/SELinux | `user`，`ro.debuggable=0`，Enforcing | R1/R7 |
| 历史 su 身份 | UID 0，`u:r:ksu:s0` | R1；不证明当前授权 |
| 历史 adbd 身份 | PID 9883，UID 0，`u:r:ksu:s0` | R12 两份进程捕获 |
| 历史 ADB 会话 | 明确 ADB 计划，`id` 为 UID 0 / `u:r:ksu:s0`，EXIT=0 | R12；独立于 daemon 证据 |
| 当前上述状态 | 未知 | 本轮不操作设备 |

su、daemon、客户端会话是不同证据。即使 UID/域一致，也不能据此保证 capability、权限集合或所有连接都与另一个 root 通道同权。容器内 `uid=0` 也不等于 Android 宿主 root。

## 内核与 chroot

R1/R2 的历史配置记录 NET_NS、UTS_NS、TMPFS、OVERLAY_FS 为 y；USER_NS、PID_NS、SQUASHFS、TMPFS_XATTR、OVERLAY_FS_INDEX/METACOPY 未设置。IPC_NS 未见配置行，namespace 列表未含 ipc，这支持该历史环境缺少对应能力的判断。配置存在不能代替实际隔离行为、回收和新内核复验。

R3 有 `BIND_OK`、`NSMOUNT_OK`；`unshare` 只有无输出的记录，没有完整退出码，不能单凭静默判断成功。`chroot ... toybox` 返回 ENOENT：缺 ELF 解释器是推测，L1 exec 未证明。详细判据见 [chroot-l1.md](../chroot-l1.md)。overlay upperdir 要验证实际文件系统的 xattr、权限及挂载；不能仅按 `/data` 路径保证可用。

## 密钥、监听与规则

| 项 | 历史输出 | 校正后的结论 |
|---|---|---|
| 密钥表初始状态 | 0 字节；label、owner、mode 有记录 | 不证明从未配对；不能默认整表撤销 |
| 公钥/离线准备 | 无 ssh-rsa 前缀的公钥被接受；R9 含归档摘要匹配与 SETUP_DONE | 只证明当时样本；不读取或打包私钥 |
| `ro.adb.secure` | 值为 1 | 不是未授权客户端认证实测 |
| `service.adb.root` | 空 | 不能单独确定 root 来源或 root 模式 |
| `listen_address` | 回显 loopback，但 ss 为 `*:5555` | 该历史环境不能依赖属性收窄；地址族待核验 |
| IPv4 规则 | R8 插入 INPUT 第一位 | 不等于 IPv6 已保护或未来规则持续有效 |
| 规则随后消失 | R10 为 NO_RULE | 消失原因未知；PID 变化不能独自证明整机重启/netd 重建 |
| 当前暴露面 | 未捕获 | 未知；默认关闭，双栈先防护后开启 |

模块文本仅为策略先例，不能由 zygote 的 `sys_chroot` allow 推导 ksu 或 DSHA 获得该能力。原档案说已做动作未新增规则；未来动作按实际目标策略与拒绝日志验证。

## 后续采集契约

生产采集使用固定 argv：`ps -A -o PID,UID,NAME` 与 `ps -A -o PID,CONTEXT,NAME`，保存两份真实退出码与 PID 出生身份；未知、拒绝、超时和格式歧义保持未知。当前纯解析器不执行这些命令。强制 ADB 会话另取计划与 `id`；暴露面另取 IPv4/IPv6 监听、规则顺序/所有权、已有连接及外部探测。

主机解析与策略验证：

```bash
python3 -B tools/test-root-support.py
```

主机原生策略回归：

```bash
python3 -B tools/test-native-device-policy.py
```

判据：前者已输出 `OK (23 tests)`；后者为 16 个 dry plan，不代表 Android 进程或设备权限实测。设备采集必须经原生授权和受保护通道，不使用历史脚本绕过策略。

已知：本轮读取了上述历史文件，原始输出和原字节另存；纯 Java 验证通过。
推测：R3 ENOENT 与缺解释器相容；规则消失原因未定。
未知：当前管理器授权、daemon/会话身份、两族暴露面、L1 exec。
过期：原档案的持续现状与“照抄即可”保证（有效期止于原捕获窗口，精确截止无记录，依据 R1–R12）；替代为分项采集与验收契约。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| 历史 R12 记过 daemon 与该次会话为 root | 已知 | 仅原捕获窗口 | evidence.md R12 |
| 样本能代表当前设备/其他 ROM | 过期 | 原窗口；精确截止未知 | 缺连续观测 |
| ENOENT 的解释器归因 | 推测 | 无承诺 | R3；缺 ELF 与 syscall 证据 |
| 当前双栈及 chroot 可用 | 未知 | 无 | 需实际目标验收 |

截至 2026 年 10 月 9 日（UTC）：本轮整理历史档案并跑主机测试。
适用范围：TB321FU 历史样本与 DSHA 评估，不作跨机型承诺。
已知推测未知：历史文件与主机验证有据；设备持续状态未知。
依赖与边界：需要实际目标环境复验；本轮无设备命令执行。
