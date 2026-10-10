# 落地方案：root 自举 ADB 与暴露面控制

本文件是设计说明，**不含已应用的改动**。所有"代码"片段是骨架/示意，用于指导实现，不是可以直接合并的补丁。

---

## 1. 目标与不变量

**目标**：在设备已 root 的前提下，让 DSHA 不经系统配对弹窗、不依赖 Shuzuku 即可获得 ADB 通道；同时把这条通道的**暴露面**与**权限等级**都做成显式、可撤销、可判定的状态。

**不变量（不得因为本方案改动）**：

1. `util/DeviceShellPolicy` 的白名单**不放宽**：`setprop` / `settings put` / `pm grant` / `chcon` / `restorecon` / `mount` 仍然在 `BLOCK` 里。本方案走**命名特权操作**，不是给通用执行通道开后门。
2. App 的「设备能力授权」开关仍是显式同意点：root 自举完成后，通道仍受 `DeviceBridgeService.isAdbEnabled` 门控（`[DEVICE_CHANNEL_UNAVAILABLE]` 的分支保留）。
3. 失败一律不换通道重放：沿用 `[EXECUTION_UNKNOWN]` 语义。
4. 无 root 环境的功能不退化：proot/proroot 仍是默认运行时，ADB 通道在没有 root 时仍按原路径工作。

## 2. 通道模型

| 维度 | 现状 | 目标 |
|---|---|---|
| 授权来源 | 用户在系统里开无线调试 + 配对 | root 写 `adb_keys`（一次性），或保留原配对路径 |
| 身份等级 | 假设 `uid=2000(shell)` | **实测判定**：先查 adbd 自身 uid/域，再声明等级（本机为 root） |
| 绑定地址 | 无法收窄（属性无效） | iptables 只放行 lo，模块每次补；或按需开 |
| 端口发现 | 端口历史 + 5555 兜底（已有） | 不变 |
| 通道优先级 | root → Shizuku → ADB（已有） | 不变；`--host/--port` 强制 ADB（已有） |

## 3. 命名特权操作：`adb-bootstrap`

**落点**：`RootShellMain` 已有的 `operation` 分支（现成先例 `virtual-screen-start`，见 `RootShellMain` 与 `RootShell.execVirtualScreen`）。新增第二个 operation，**不走**通用 `DeviceShellPolicy.inspect`：

```text
# 现有分支（照抄其形状，不修改 BLOCK 面）
operation == "virtual-screen-start"  → DeviceShellPolicy.inspectVirtualScreenLaunch(...)

# 新增
operation == "adb-bootstrap"         → AdbBootstrapPolicy.inspect(request)

# 新增核验函数的要求（与 inspectVirtualScreenLaunch 同规格）
1. 只接受固定字段：{operation, action ∈ {authorize, revoke, narrow, open, close}}
2. 校验 selfPackage == 本包名、selfUid == 本应用 uid
3. 不接受任意命令行：所有目标路径与属性名是常量（/data/misc/adb/adb_keys、service.adb.tcp.port、iptables 规则串）
4. 幂等：authorize 前先 grep 去重；narrow 前先 -C；open/close 前先读当前属性
5. 结果写入原生启动记录（沿用现有脱敏通道），失败返回 [EXECUTION_UNKNOWN] 且不重放
```

**为什么不是放宽策略**：`adb_keys` 的写入、属性设置、iptables 规则在语义上是"设备状态自举"，不是"执行用户给的一条命令"。前者可以把参数表固定成常量并逐条核验，后者只能靠白名单。把两者混在一个通道里，就等于把 `BLOCK` 面交出去。

**授权语义**（与 `DeviceGrants` 的能力授权模型同构）：逐项开关、一次授权、可撤销、有机上记录。默认状态应为**关闭**。

## 4. 模块骨架（持久化：端口与防火墙）

`iptables` 规则不跨重启（实测），`service.adb.tcp.port` 也不是持久属性，所以"常开 + 收窄"这一档必须由 KernelSU 模块在启动阶段重复施加：

```text
/data/adb/modules/dsha-root-host/
├── module.prop
├── post-fs-data.sh        # 早于 adbd 启动：设端口
├── service.sh             # 晚启动：补防火墙规则
└── sepolicy.rule          # 仅在被拒时添加；本机实测无需新增
```

```sh
# post-fs-data.sh
#!/system/bin/sh
setprop service.adb.tcp.port 5555
```

```sh
# service.sh
#!/system/bin/sh
# 幂等：已存在就不重复插；规则在第 1 位，先于厂商链 oem_in
iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || \
  iptables -I INPUT 1 -p tcp --dport 5555 ! -i lo -j DROP
```

```text
# sepolicy.rule（本机已有同类先例，实测本方案未需要新增）
# 若某机型被拒，按下面两条的形状补，不要放宽整个策略面：
allow ksu self:capability sys_chroot
allow ksu self:tcp_socket { create connect read write getopt setopt }
allow ksu node:tcp_socket node_bind
allow ksu port:tcp_socket name_connect
```

**生命周期契约**（避免造出"第二个按端口猜 PID"的坑）：模块只负责启动期的一次性状态设置；容器宿主进程的存活仍由 App 前台服务与既有 `WebProcessManager` 的身份核验负责，不得用端口反查或名字匹配来终止进程。

## 5. 暴露面控制三档

| 档 | 命令 | 适用 | 失效方式 |
|---|---|---|---|
| 甲 常开 + 收窄 | `iptables -I INPUT 1 -p tcp --dport 5555 ! -i lo -j DROP`（模块重复施加） | 只从本机/容器连 | 重启后需模块补；模块缺失则回落到全接口 |
| 乙 按需开 | `setprop service.adb.tcp.port 5555` + `ctl.restart adbd`，用完 `setprop ""` + `ctl.restart adbd` | 需要 PC 侧工具链 | 忘了关就一直开着 |
| 丙 撤销 | `revoke-adb.sh`：清空 `adb_keys` + 关端口 + 删规则（可选） | 不再需要，或私钥可能外泄 | — |

**默认档建议：甲**。理由是实测结论 F1（ADB 通道 = root 通道）—— 在 adbd 以 root 运行的机型上，`*:5555` 是"持有密钥者即可获得 root shell"，与 DSHA 自己的 LAN 代理（`LanAuth` token fail-closed）姿态不一致。

## 6. 验收清单（逐条可勾，判据都是命令）

| # | 验收点 | 判据 |
|---|---|---|
| 1 | 免配对授权成立 | `verify-adb-identity.sh` 返回 `[EXIT=0]` |
| 2 | 身份等级已判定并记录 | 输出中含 `uid=0(root)`（或 `uid=2000(shell)`）；写入机型档案 |
| 3 | 幂等：重复执行不产生新 diff | `authorize-adb.sh` 跑两次，`adb_keys` 字节数不变 |
| 4 | 收窄生效 | 另一台机器 `nc <平板IP> 5555` 超时；本机连得上 |
| 5 | 收窄可回滚 | `adb-listen-narrow.sh --restore` 后 `grep dpt:5555` 为空 |
| 6 | 撤销彻底 | `revoke-adb.sh` 后 `adb_keys` 为 0 字节、5555 不再 LISTEN |
| 7 | 无 root 退化路径未被破坏 | 关掉 root 开关后，ADB 通道仍按原配对路径工作 |
| 8 | 失败不重放 | 人为断开端口后执行，返回 `CONNECT_FAIL`（`[EXIT=124]`），不得自动改用 root 通道 |

## 7. 与安全模型文档的接口

开工时同步改动 `docs/security-model.md` 的一行即可，不要另起"例外清单"：**"特权自举是受控能力（固定参数 + 命名核验 + 显式开关 + 机内记录），不是策略豁免；ADB 通道的权限等级随 ROM 而变，须先判定后声明。"**
