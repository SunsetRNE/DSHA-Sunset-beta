# 双栈守卫：失败可见 + 调用形式适配（2026-10-11）

解决 [真机验证](device-vscreen-verification-20261010.md) §4.5 的第 ③ 项：`AUTHORIZE` 的第一步
`verifyGuard()` 依赖 `/system/bin/iptables -w 2 -S INPUT`（`ip6tables` 同理），本机该命令返回
非 0，事务只报一个 `COMMAND_FAILED` —— 既不知道哪条命令、什么退出码，也没有 stderr，
在设备上完全无法排查。

本文件记录两部分改动：**先让失败可见**，**再让守卫适配设备**。

## 1. 失败可见（证据通路）

| 层 | 改动 |
|---|---|
| `util/AdbBootstrapEngine` | 新增 `BootstrapFailure(code, evidence)`；`Refused` 继承它；`Outcome` 增加 `evidence` |
| `RootBootstrapExecutor` | `command(...)` 在任何非 0 退出时抛 `BootstrapFailure`，证据 = `tool -w 2 -S INPUT [EXIT=2] <输出首两行>` |
| `RootBootstrapNative` | 结果 JSON 增加 `evidence`；**预检分支的 JSON 也要转义**（`JSONObject.quote`） |
| `core/AdbBootstrapController` | `Snapshot` 增加 `evidence`；同时 `RuntimeHostPorts.record("BOOTSTRAP_<code>", head)`，诊断页留一份 |
| `ui/RootBootstrapCard` | 有证据时出现「查看失败原文」→ 可滚动弹窗，只读展示 |

不变式没有动：错误码仍必须匹配 `[A-Z0-9_]{1,96}` 才能进事务日志；`evidence` 只用于显示与诊断，
**不参与任何门禁判断、未知语义或重放判断**。证据文本经 `SensitiveData.redact`、去控制字符、
限行限长（每行 200 字符、最多 2 行、总长 420）。

## 2. 调用形式适配（`util/AdbFirewallTool`）

不同 ROM 的 iptables 对等待参数支持不同（`-w 2` / 仅 `-w` / 完全不带）。新工具按候选顺序
探测**只读**的 `-S INPUT`：

```
iptables -w 2 -S INPUT   →   iptables -w -S INPUT   →   iptables -S INPUT
```

- 选定形式后**整个事务复用它**：`-S` 查询、`-C` 存在性检查、`-I` 插入、`-D` 删除、以及
  `observe()` 的防护状态读取，全部走同一个 `Form`。
  （实现时第一次单测就抓到：`observe()` 还留着硬编码的 `-w 2`，回退后会把结果判成失败 → `UNKNOWN`。
  这正是必须统一到一个 Form 的原因。）
- 三种形式都不可用 → `Refused("GUARD_TOOL_UNAVAILABLE", 证据)`，**fail-closed**：一条规则都不写。
- 结果**未知**（超时、输出无法解析）→ 立即停止并保留证据，**不换形式重放**。

`AdbFirewallTool` 是纯逻辑（`util/`，零 Android import），只通过 `Runner` 接口取命令结果，
因此可以用假设备完整单测。

## 3. 测试

| 检查 | 结果 |
|---|---|
| `tools/test-root-support.py` | **OK (94 tests)**（新增 `AdbFirewallToolTest` 7 项 + 执行器 3 项） |
| `test-root-bootstrap-endpoint.py` / `test-native-device-policy.py` / `test-device-shell-policy.py` | 15 / 16 / 27 项通过 |
| `check-ui-i18n.py` / `test-ui-i18n.py` | 新增缺陷 0；9 项通过 |
| `test-runtime-descriptor-inputs.py` + `verify-runtime-descriptor-source.py` | 65 安装项 / 113 资产 / 296 启动输入，重算后一致 |

执行器新增用例：

- `iptablesWithoutSecondsWaitStillAuthorizes`：模拟 `-w 2` 被拒（exit 2）→ 退到 `-w` 后
  `AUTHORIZE` 仍为 `OK`，两族规则都插入；
- `unusableFirewallRefusesWithCommandEvidence`：工具完全不可用 → `REFUSED` +
  `GUARD_TOOL_UNAVAILABLE`，证据含命令、`[EXIT=1]` 与 `Permission denied`，零写入；
- `failingMutationCarriesArgvExitAndOutput`：插入规则失败 → 状态 `UNKNOWN`（已派发写入）、
  码 `COMMAND_FAILED`，证据含 `-I`、退出码与 stderr。

## 4. 真机结果

见 §5（本轮验证回执）。
