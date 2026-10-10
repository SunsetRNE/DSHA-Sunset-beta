# 自举链真机打通到 OPEN：四个真因与修复（2026-10-11）

接 [设备验证](device-vscreen-verification-20261010.md) 与 [C 方案](fix-c-wireless-debug-20261010.md)。
本轮目标是 §4.5 的第 ③ 项：「AUTHORIZE 只报一个 `COMMAND_FAILED`，设备上无从排查」。
做法是**先让失败可见**，再顺着原文把链子一步步推到 OPEN。

**结论**：链子已在真机（联想 TB321FU / Android 16 / ReSukiSU root）上走到 **OPEN**：
`adb_keys` 增加本应用公钥、`service.adb.tcp.port=5555`、5555 正在监听、双栈规则位于首位。
最后一次「ADB 会话核验」在本验证环境里无法完成，原因与产品无关（见真因 4）。

---

## 1. 失败可见（本轮的基础设施）

| 层 | 改动 |
|---|---|
| `util/AdbBootstrapEngine` | `BootstrapFailure(code, evidence)`；`Refused` 继承它；`Outcome.evidence` |
| `RootBootstrapExecutor` | 任何非 0 退出的命令都带上「命令 + 退出码 + 输出首两行」 |
| `RootBootstrapNative` | 结果 JSON 增加 `evidence`（预检分支也要转义） |
| `core/AdbBootstrapController` | `Snapshot.evidence`；诊断页留档；非 JSON 回包也留原文；会话/环境失败同样带原文 |
| `ui/RootBootstrapCard` | 有证据时出现「查看失败原文」→ 可滚动弹窗（只读，不重放） |

不变式没动：错误码仍必须匹配 `[A-Z0-9_]{1,96}` 才能进事务日志；`evidence` 只用于显示与诊断，
不参与门禁、未知语义或重放判断。

**这一层立刻还抓到了它自己的一个 bug**（真因 3）。

## 2. 四个真因

### 真因 1：`ps` 的上下文列在 toybox 上叫 `LABEL`，不叫 `CONTEXT`

第一条「查看失败原文」直接点名：

```
{"status":"FAILED","code":"COMMAND_FAILED", ... ,
 "evidence":"/system/bin/ps -A -o PID,CONTEXT,NAME (exit=1) ps: bad -o 'PID,CONTEXT,N… "}
```

真机对三条命令的实测：

| 命令 | 结果 |
|---|---|
| `ps -A -o PID,UID,NAME` | ✅ |
| `ps -A -o PID,CONTEXT,NAME` | ❌ `ps: bad -o 'PID,CONTEXT,NAME'`（exit 1） |
| `ps -A -o PID,LABEL,NAME` | ✅ |

`daemonIdentity()` 硬编码 `CONTEXT`，因此从 `verifyListenerScope()` 一路失败，AUTHORIZE 在
任何写入之前就停住——这正是最初那个「只有一个 `COMMAND_FAILED`」的来源（当时被误判成 iptables）。

修法：首选 `LABEL`，失败再回退 `CONTEXT`，同一事务内复用解析结果；两种表头形状相同
（PID / 上下文 / NAME），`AdbdIdentity` 都接受。两种列名都被拒时报
`ADBD_SNAPSHOT_UNSUPPORTED`（写前拒绝），与「`ps` 本身跑不起来」区分开。

### 真因 2：`ps` 的 NAME 列可以含空格（内核线程）

换 `LABEL` 后错误码变成 `ADBD_IDENTITY_UNVERIFIED`。取真机原文发现 988 行里有 5 行是名字带空格的
内核线程：

```
  692 u:r:kernel:s0   [irq/207-8804000.sdhci cd]
  830 u:r:kernel:s0   [irq/261-q6v5 wdog]
```

按空白切满成 4 列 → 三列固定表的整体校验失败 → 整张表判为不可信 →
`ADBD_IDENTITY_UNVERIFIED`。**这是仓库里一个老 bug，历史假设备只产三列干净名字，所以单测从未发现。**

修法：三列表格只固定前两列，其余全部算 NAME（`AdbdIdentity.row3`）；十列 `ps -Z` 形状保持原样。

### 真因 3：证据里的 `[EXIT=…]` 会把结果整包变成「未知」（我自己引入的）

`RootSupportProbe.parse` 会把含 `[EXIT=`、`[OUTPUT_TRUNCATED]`、`[EXECUTION_UNKNOWN]`、
`[POLICY_BLOCKED]` 的输出判为不可信并整包丢弃。第一版证据文本里写了 `[EXIT=1]`，
于是**本该显示 `COMMAND_FAILED` 的结果变成了 `REQUEST_OUTCOME_UNKNOWN`**（构建 170-guard/visible
两轮都因此没有码）。修法：证据在生成（`AdbFirewallTool.describe` → `(exit=1)`）与展示
（`AdbFirewallTool.head` → `neutralize`）两处都中性化，并补了回归测试（证据进 JSON 后仍可解析）。

### 真因 4：两个「顺序/实例」问题

- **AUTHORIZE 不幂等**：`authorize()` 在「本应用已拥有且在位的公钥」分支里调用
  `AdbAuthorizedKeys.revoke(...)` —— 重跑一次授权就把刚写入的公钥删掉，第二次链子的会话核验
  必然失败。改为幂等返回。
- **OPEN 的会话核验存在死锁**：核验要走容器里的 `adb-shell.py --bootstrap-verify`，它会用
  `/device/execute?adb=1` 向桥申请 ADB 计划；而 `HttpShellService.deviceExecute` 在
  `DeviceBridgeService.isAdbEnabled(ctx)` 为假时返回 `[DEVICE_CHANNEL_UNAVAILABLE]`，
  通道开关又只在三步全成功后打开 → 永远拿不到计划。改为**监听确认打开后先开通道，再做会话核验**。
- **第二个实例无法用桥**（本次验证环境的限制，不是产品 bug）：`HttpShellService.PORT = 3090`
  是固定常量，`adb-shell.py` 里桥地址也是硬编码 3090。SunsetRNE 容器拿自己的 token 去问
  com.dsh.client 的桥，得到 `{"result":"[UNAUTHORIZED]"}`，脚本随后 `json.loads("[UNAUTHORIZED]")`
  抛 `JSONDecodeError`，对外只剩「设备桥响应不完整，命令可能已执行，不会切换通道重试」。
  本轮把这个说不清的报错改成明说原因（脚本版本整组升到 21），但**第二个实例的 ADB 会话核验
  在本机仍然不可能成功**——需要单实例环境（即正式包自己）才能端到端验证。

## 3. 顺带保留的一项兼容层

`util/AdbFirewallTool`（`-w 2` → `-w` → 无 `-w` 探测、全事务复用同一形式、未知结果不换形式重放、
全部不可用则 `GUARD_TOOL_UNAVAILABLE` 写前拒绝）。**本机并不需要它**——真机接受 `-w 2`，
STATUS 的双栈读取一直正常。它作为不同 ROM 的兼容层保留，并让失败命令可见。

## 4. 真机回执（2026-10-11 01:0x–01:2x，SunsetRNE `com.dsh.clienu.SunsetRNE`）

**只读刷新（STATUS）**：`本次操作已完成`；公钥「本应用新增的公钥」、监听「5555 端口正在监听」、
双栈「上次核验双栈规则位于首位」。

**一键自举（AUTHORIZE → NARROW → OPEN）**设备侧效果：

| 观测 | 之前 | 之后 |
|---|---|---|
| `/data/misc/adb/adb_keys` | 1 行 | **2 行**（新增本应用公钥） |
| `service.adb.tcp.port` | 空 | **5555** |
| 5555 监听 | 无 | **正在监听**（`/proc/net/tcp`/`tcp6` 的 `:15B3`） |
| 双栈防护 | 无 | **本应用规则位于 INPUT 首位** |
| `adb_wifi_enabled` | 0 | 0（无线调试保持关闭，符合 C 方案互斥要求） |

会话核验（链路最后一步）在本环境停在 `ADB_SESSION_UNVERIFIED`，原文即真因 4 的
`JSONDecodeError` / `[UNAUTHORIZED]` 分支。**OPEN 之前每一步都已按设计完成。**

## 5. 测试与产物

| 检查 | 结果 |
|---|---|
| `tools/test-root-support.py` | **OK (99 tests)** |
| `test-root-bootstrap-endpoint.py` / `test-native-device-policy.py` / `test-device-shell-policy.py` | OK / 16 dry plans / OK(27) |
| `test-adb-flow.py` / `test-adb-vscreen-bridge.py` / `test-ui-i18n.py` / `check-ui-i18n.py` | OK / OK / OK / 新增缺陷 0 |
| `prepare-runtime-descriptor.py` + `verify-runtime-descriptor-source.py` | 113 资产 / 296 启动输入，重算一致 |

验证包：`install-173-verify.apk`（`com.dsh.clienu.SunsetRNE`，versionCode 173 /
versionName 0.2.21-verify.173，sha256 `46478041be6f84d658bb77d2c9468893f25e527d1aa700d747fc9079f14526fa`），
已装入设备（01:50:30）。验证包的版本号在本地 init 脚本里上调，仓库版本号未动。

## 6. 未验证项 / 遗留

- 会话核验（`id` over 5555）与 OPEN 的「先开通道」修复需要单实例环境才能端到端验证。
- `CLOSE` / `REVOKE` 本轮未在设备上执行：设备目前**仍保留**本轮自举留下的公钥、5555 监听与
  双栈规则，需在卡片里按需「关闭 ADB 监听」「撤销本应用公钥」，或再跑一次只读刷新核对。
- `AdbFirewallTool` 的回退路径在本机不适用。
- 本地提交 11 个领先 `origin/main`（`18eaee9`），**未推送**。
