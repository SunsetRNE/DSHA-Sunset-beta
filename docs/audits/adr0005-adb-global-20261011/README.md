# ADR 0005 真机进展（2026-10-11）

## 1. build175 安装后立即发现的问题

用户点「ADB 全局授权」入口 → **白屏**。定位为代码缺陷（不是设计）：

- `ui/AdbAuthorizationActivity`、`ui/RootAuthorizationActivity` 只搭了 `CardPage` 的视图树，
  **没有调用 `setContentView(page.root)`**；`CardPage` 只组装 `root/content/footer`，不自动挂载。
- 修复见 build176；这是**回归项**，安装后必须复验。

## 2. build175 触发了受管运行时更新（预期内，但值得记录）

build175 改了 `HttpShellService`（受管输入），`runtimeId` 由 `273e3648…` → `03ba9894…`。
安装 175 后 SunsetRNE 进入「运行环境维护 → 新版运行时准备完成，正在保留并切换受管文件…」，
随后回到主界面（`运行环境 READY`），**未丢用户目录**。

这正是 ADR 0004 阶段 2 要求「改 `runtimeId` 必须走一次真实 `RuntimeTrial`」的原因：
本轮**没走**试用就把运行时更新推到了设备上。build176 与 175 的 `runtimeId` 相同，不会二次触发。

## 3. ADB 全局授权：设备侧已实际生效（关键证据）

见 [device-state.txt](device-state.txt)：

| 观测 | 值 |
|---|---|
| `/data/misc/adb/adb_keys` | 2 行，含 `dsha:com.dsh.clienu.SunsetRNE`（上一轮自举写入） |
| `service.adb.tcp.port` | `5555` |
| `[::]:5555` | LISTEN（uid 0） |
| `adb-shell.py --host 127.0.0.1 --port 5555 id` | **`uid=0(root) … context=u:r:ksu:s0`，`[EXIT=0]`** |

即：走 5555 的 ADB 客户端**确实拿到了 shell**，这正是 ADR 0005 说的「ADB 全局授权」本体。
该设备 adbd 以 uid 0 运行，所以返回 root；**daemon UID 0 ≠ 所有会话同权**（沿用既有审计限定）。

## 4. Root 自举仍被互斥挡住（需要用户动作）

`adb_wifi_enabled=1`（系统无线调试在跑）→ 自举按设计拒绝接管（`WIRELESS_DEBUG_IN_USE`）。
卡片会给「打开开发者选项关闭无线调试」的入口，但**改设置仍由用户自己确认**（AGENTS 既有规矩）。

另外核对了上一轮 `ADB_SESSION_UNVERIFIED` 的一个可疑方向并**排除**：
`adb_shell_wifi.adb_device.AdbDeviceTls` 的文档明确「transport stays unencrypted until the device
greets the connection with `A_STLS`」——它本身就能连普通 5555，所以**不是 TLS/明文协议不匹配**。
剩下的方向是：写入 `adb_keys` 后 adbd/系统侧密钥表未即时生效，而自举核验按设计**只试一次、不重放**，
于是首次核验失败即定格。

## 5. 下一步

1. 用户安装 build176 → 复验 ADB 授权页/Root 授权页渲染（本轮回归项）。
2. 用户在开发者选项**关闭无线调试**后，在 ADB 授权页跑一次自举，观察
   「授权公钥 → 收窄 → 开启监听 → 会话核验」并记录 `id` 结果；若核验仍停在
   `ADB_SESSION_UNVERIFIED`，按「密钥表未即时生效 + 一次性核验」的方向处理（例如在核验前
   触发 adbd 重新读取密钥表，或允许一次受控重试——需要单独评审，不能顺手放宽）。
