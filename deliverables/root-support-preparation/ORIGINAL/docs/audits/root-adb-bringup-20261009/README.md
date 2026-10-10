# root → ADB 自举实测报告（TB321FU / 2026-10-09）

本报告记录一次在**自有设备**上把「root 授权」与「ADB 通道」打通的全过程与实测结论。所有结论都挂到两条来源之一：本机实测输出，或仓库内可指认的代码行。不含推测性数字。

配套文件：

| 文件 | 内容 |
|---|---|
| [evidence.md](evidence.md) | 逐轮原始输出归档（含被推翻的判断与归因修正） |
| [design.md](design.md) | 落地方案：命名特权操作、模块骨架、暴露面控制、验收清单 |
| [devices/TB321FU.md](devices/TB321FU.md) | 机型能力档案（内核配置 / namespace / 域 / adbd 身份） |
| [chroot-l1.md](chroot-l1.md) | 真 chroot（L1）路线状态卡与未跑完的验证 |
| [scripts/](scripts/) | 四个可跑脚本：授权 / 撤销 / 收窄 / 身份判据 |

---

## 1. 结论摘要

1. **免配对的 ADB 通道在本机成立**。把容器自己的公钥写进 `/data/misc/adb/adb_keys` 后，容器内客户端直连 `127.0.0.1:5555` 执行 `id`，返回 `[EXIT=0]` —— 全程没有系统配对弹窗、没有配对码、没有 Shizuku。
2. **本机的「ADB 通道」等价于 root 通道**：adbd 自身以 `uid=0`、SELinux 域 `u:r:ksu:s0` 运行，所以经 ADB 拿到的 shell 是 `uid=0(root) … context=u:r:ksu:s0`，而不是仓库既有验收记录里的 `uid=2000(shell)`。这一条必须在安全模型文档里显式记录。
3. **暴露面必须单独控制**：`service.adb.listen_address=127.0.0.1` 在本机**不生效**（属性写入成功、adbd 仍绑 `*:5555`）；能收窄的只有 iptables，而 **iptables 规则不跨重启**，只能由 KernelSU 模块重复施加。
4. **root 自举 ADB 不需要新增任何 SELinux 规则**：写 `adb_keys`、`setprop service.adb.tcp.port`、`ctl.restart adbd`、iptables 写入，在 `u:r:ksu:s0` 域下**全部一次通过**（机上的模块 sepolicy 里本来就有同类先例）。
5. **容器侧可离线自举**：仓库自带 `app/src/main/assets/adb-wheels.tar.gz` 的 sha256 与运行中容器的 `adb-wheels.lock.json` 逐字节一致；注入后 `adb-setup.sh` 离线生成密钥对并写出 `SETUP_DONE`。

## 2. A 路线（root 直连 ADB）实测链路

| # | 步骤 | 执行位置 | 实测判据 | 结果 |
|---|---|---|---|---|
| 1 | 注入离线 wheel 包（哈希对齐） | 容器 | `sha256(adb-wheels.tar.gz) == lock.archiveSha256`，15 个 wheel 逐个校验 | ✅ 校验通过 15 个 |
| 2 | 生成密钥对 | 容器 | `bash /root/.dsh/adb-setup.sh` → `KEY_GEN_OK` / `SETUP_DONE` | ✅ exit 0 |
| 3 | 交接公钥 | 容器 → 设备 | `cp adbkey.pub /sdcard/Download/` | ✅ |
| 4 | 备份并追加公钥 | 设备 root shell | `wc -c /data/misc/adb/adb_keys` → 718（补尾换行后 719） | ✅ |
| 5 | 打开 TCP 监听 | 设备 root shell | `setprop service.adb.tcp.port 5555` + `ctl.restart adbd` → `ss` 见 LISTEN | ✅ |
| 6 | 打开 App 侧通道 | App | 设置 → 设备能力授权 → ADB（此前返回 `[DEVICE_CHANNEL_UNAVAILABLE]`） | ✅ |
| 7 | 端到端判据 | 容器 | `adb-shell.py --host 127.0.0.1 --port 5555 -- id` | ✅ `[EXIT=0]`，身份为 root |

判据命令（第 7 步，可反复执行）：

```bash
# 容器内（DSHA 终端 / Ubuntu 工作区）
python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port 5555 -- id
# 期望（本机特性）：uid=0(root) … context=u:r:ksu:s0
# 只是 uid=2000(shell) → adbd 已不再以 root 运行，暴露面随之下调
# CONNECT_FAIL / [EXIT=124] → 端口没开；unauthorized → 公钥被清或格式不被接受
```

## 3. 关键发现

### F1　adbd 以 root 运行，ADB 通道在本机等于 root 通道

```
ps -A -o PID,UID,NAME | grep adbd   → 9883  0  adbd
ps -A -Z | grep adbd                → u:r:ksu:s0  root  9883  1  …  adbd
getprop service.adb.root            → （空）
```

`service.adb.root` 为空，说明不是「adb root」模式，而是 **adbd 进程本身就在 root + ksu 域下运行**（ROM/内核补丁或 root 方案注入所致；具体来源未溯因，见 §6）。

**影响**：`ro.adb.secure=1` 只保证"没钥匙进不来"，不保证"有钥匙的人拿到的是普通 shell"。本机上持有容器私钥者，拿到的是**局域网可达的 root shell**。

### F2　绑定收窄不能靠属性

`service.adb.listen_address` 写入成功（`getprop` 回显 `127.0.0.1`），但 `ctl.restart adbd` 后仍然 `LISTEN *:5555`（新 pid 可证确实重启过）。收窄必须走 iptables。

### F3　iptables 规则不持久

规则确曾插入并显示在 `Chain INPUT` 第 1 位（先于厂商链 `oem_in`），但在设备重启后 `iptables -L INPUT | grep dpt:5555` 返回空 —— 规则被 netd 重建清掉。

### F4　通道优先级：root 先于 ADB

`HttpShellService.deviceExecute` 的选择链是 root → Shizuku → ADB，且只有 `adb=1` 才会跳过 root。容器脚本的 `--host/--port` 正是映射到 `adb=1`（`adb-shell.py` 传 `bool(port or host)`）。因此：

- 不带端点参数：命令走 **root 通道**（在 root 开关打开时总是如此）；
- 带 `--host/--port`：强制走 **ADB 计划**，客户端随后自行连 adbd。

### F5　免配对的本质是写 `adb_keys`

设备上 `adb_keys` 原本是 0 字节且 label 已正确（`u:object_r:adb_keys_file:s0`、`1000:2000`、0640），临时授权表 `adb_temp_keys.xml` 条目数为 0。也就是说：**本机从来没有配对过任何客户端**，而 root 直接写一把公钥就完成了"授权"，无需触碰 label（因此也不需要 `chcon`/`restorecon` 这两个被设备命令策略拦下的操作）。

## 4. 暴露面与处置

当前快照：`*:5555` 全接口监听、规则为空、`adb_keys` 里有一把有效公钥、adbd 为 root。

| 档 | 做法 | 适用 |
|---|---|---|
| 甲（默认建议） | iptables 只放行 lo：`-I INPUT 1 -p tcp --dport 5555 ! -i lo -j DROP`，并写进模块 `service.sh` 每次补 | 希望 ADB 常开但只从本机连 |
| 乙 | 用完即关：`setprop service.adb.tcp.port ""` + `ctl.restart adbd` | 需要从 PC 侧工具链接入时临时开 |
| 丙 | 完全撤销：清空 `adb_keys` + 关端口 + 删规则 | 不再需要该能力，或私钥可能已离开设备 |

脚本见 [scripts/](scripts/)：`adb-listen-narrow.sh`（甲）、`authorize-adb.sh` / `revoke-adb.sh`（开/撤）、`verify-adb-identity.sh`（身份判据）。

## 5. 需要回写到现有文档的地方

| 文档 | 现状 | 建议 |
|---|---|---|
| [docs/security-model.md](../../security-model.md) | 记录"Root、Shizuku、ADB 在发送前选择实际通道" | 增加一条：**ADB 通道在某些 ROM 上其 adbd 以 root 运行，此时 ADB 通道与 root 通道同权**；暴露面控制不能省 |
| [docs/adb-flow-audit.md](../../adb-flow-audit.md) | 记录真机验收 `uid=2000(shell)`（Android 13） | 标注该结论**不适用于** root-adbd 机型；补一条"先判 adbd 身份再声明 ADB 通道权限等级" |
| [AGENTS.md](../../../AGENTS.md) 验收经验段 | 有"截图/读屏不可写敏感值"等条目 | 增加：`service.adb.listen_address` 不可依赖；`--host/--port` 才强制 ADB 通道；iptables 规则需模块重复施加 |
| [docs/ROADMAP.md](../../ROADMAP.md) | 已写"条件允许时上 chroot" | 状态更新为"L1 权限面已验证（见 audits/root-adb-bringup-20261009/chroot-l1.md），exec 链路待复验" |

## 6. 未完成项

1. **L1 真 chroot 的 exec 链路未复验**：`chroot(2)` 未被拒绝（失败发生在 execve，缺 `/system/bin/linker64`），修正后的验证命令在 [chroot-l1.md](chroot-l1.md)，尚未跑。
2. **公钥格式二态**：本次写入的是无 `ssh-rsa` 前缀的 `<base64> <comment>` 形态并被接受；带前缀形态未测（不需要，但值得记一笔）。
3. **adbd 为何以 ksu 域 root 运行**：需要内核构建来源或 root 方案配置才能溯因，当前属未知。
4. **模块化落地未做**：持久施加端口与防火墙规则的模块尚未创建（骨架见 [design.md](design.md)）。

## 7. 四态

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| 免配对 ADB 端到端成立（`id` 经 127.0.0.1:5555 返回 EXIT=0） | 已知 | 直到公钥被清或 App 开关关闭 | 本机实测（容器内执行） |
| 本机 adbd 以 uid 0 + `u:r:ksu:s0` 运行，故 ADB 通道 = root 通道 | 已知 | 随 ROM / 内核 / root 方案变化 | `ps -A -o PID,UID,NAME`、`ps -A -Z` |
| `service.adb.listen_address` 在本机不生效 | 已知 | 直到更换 ROM/内核 | `getprop` 回显与 `ss` 绑定不一致 |
| iptables 规则不跨重启存活 | 已知 | — | 重启后 `grep dpt:5555` 为空 |
| DSHA 文档中的 `uid=2000(shell)` 适用于本机 | 过期：该断言出自 Android 13 那台设备的验收记录（docs/adb-flow-audit.md），本机替代判据是第 2 节第 7 步的 `id` | — | 本机实测与之相反 |
| 离线 wheel 包与运行中容器期望归档一致 | 已知 | 随 App 升级换包 | `sha256` 与 `adb-wheels.lock.json` 比对 |
| adbd 为何以 root 运行 | 未知 | — | 需内核构建来源 / root 方案配置 |

已知：第 2 节全部 7 步、F1–F5 的每一条判据均为本机实跑输出。
推测：无（本报告不含推测性结论）。
未知：adbd root 身份的来源；chroot exec 链路。
过期：`uid=2000(shell)`（见上表）。

---

时间基准：实测发生于 2026-10-09（设备时区），报告整理于 2026-10-08（UTC）。
适用范围：Lenovo TB321FU（Android 16 / 内核 6.1.124-android14-11 / ReSukiSU v4.2.0-rc2）上的 DSHA ADB 通道与容器。
依赖与边界：本报告只针对自有设备，未做跨机型外推；命令与判据需要实际目标环境复验。
