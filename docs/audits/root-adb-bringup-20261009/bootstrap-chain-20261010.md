# 一键自举 ADB：门禁修正与链式实现（2026-10-10）

## 背景：门禁语义被倒置

接手时的实现要求 **AUTHORIZE / OPEN 之前「ADB 设备通道」必须已开启**
（`AdbBootstrapGrant.needsAdb()` → `RootBootstrapAuthority.allowed()` →
`DeviceBridgeService.isAdbEnabled`）。

而本目录 `design.md` 开头的目标是「让 DSHA **不经系统配对弹窗**即可获得 ADB 通道」，
手段是「root 写 `adb_keys`（一次性）」。两者相抵：

- 自举的用途是在**尚无 ADB 授权**时把它建立起来；
- 门禁却要求先有 ADB 通道，而该通道的 UI 说明正是「在同一 Wi-Fi 下配对无线调试」。

结果形成「要先有 ADB 才能自举 ADB」的循环。设备上的表现是两个关键按钮
「授权本应用公钥」「开启 ADB 监听」长期置灰，看起来像功能缺失 —— 实际能力是完整的
（`RootBootstrapExecutor` 真实写 `/data/misc/adb/adb_keys`、`setprop service.adb.tcp.port 5555`、
双栈防护与密钥事务）。实测勾选「启用 ADB 设备通道」（纯偏好开关，不需要配对码）后两钮即刻解锁。

## 本轮改动

| 文件 | 改动 |
|---|---|
| `util/AdbBootstrapGrant.java` | 删除 `needsAdb` 门禁项，`valid()` 去掉 `adb` 参数；新增 `needsClientKey`（仅表达执行路径需要客户端公钥） |
| `RootBootstrapAuthority.java` | `allowed()` / `valid()` 去掉 ADB 通道条件；`needsAdb()` 改为 `needsClientKey()`；偏好监听不再因 `adb_enabled` 撤销票据 |
| `core/AdbBootstrapController.java` | 新增 `submitBootstrapChain()` / `chaining()` / `advanceChain()` / `enableAdbChannel()` |
| `ui/RootBootstrapCard.java` | 新增一键自举按钮与确认流程（`reviewChain` / `readyChain`）；按钮矩阵与提示去掉 ADB 前置 |
| `res/layout/fragment_device_grants.xml` | 新增 `root_bootstrap_all` 按钮 |
| `app/src/test/.../AdbBootstrapGrantTest.java` | 按新语义更新；新增断言锁住「ADB 通道状态不参与自举门禁」 |
| `app/src/main/assets/runtime-descriptor.json` | 因 launcher 源码变更重新生成（`runtimeId` → `1ad10145…`） |
| `docs/audits/root-adb-bringup-20261009/design.md` | 动作前提表去掉 AUTHORIZE/OPEN 的「ADB 许可」并说明原因 |

## 一键自举的语义

一次确认后按**固定顺序**串行执行 `AUTHORIZE → NARROW → OPEN`：

- 每一步复用与单动作**完全相同**的门禁、短期票据（`RootBootstrapAuthority`）与结果记录路径；
- 任一步不成功即**终止整条链**并保留该步现场，不重试、不换通道、不自动重放；
- 三步全部成功后才把 `adb_enabled` 置为开启并 `DeviceBridgeService.apply`，
  使自举结果真正可用（`enableAdbChannel`）；
- 门禁仍是两项硬条件：**Root 许可 + 独立自举授权**。

## 验证范围

已通过：

- `:app:compileStandardDebugJavaWithJavac`（Android SDK 37 / JDK 17 / arm64）
- `python3 -B tools/test-root-support.py` —— **OK (90 tests)**，含 `AdbBootstrapGrantTest`、
  `AdbBootstrapPolicyTest`、`AdbBootstrapEngineTest`、`RootBootstrapExecutorTest` 等
- `tools/test-root-bootstrap-endpoint.py`（15 tests OK）
- `tools/test-native-device-policy.py`（PASS，16 dry plans）
- `tools/test-device-shell-policy.py`（27 tests OK）
- 完整构建 `:app:assembleStandardDebug` 成功；`assets/` 236 条，
  三份大资产摘要与官方锁一致，`root_bootstrap_all` 已进包

**未完成**：

- **全量单测**（`:app:testStandardDebugUnitTest`）在本容器内阻塞超过 19 分钟且进程 0% CPU，
  已终止。改动的 API 经 grep 确认只被 `AdbBootstrapGrantTest` 引用，而该测试类已在上面的
  定向运行中通过；其余 171 个测试类不引用这些 API。
- **真机验收**：安装与一键自举的实际执行由用户在设备上确认，本轮记录不含真机结论。

交付包：`/sdcard/Download/dsha-bootstrap-chain.apk`
（`f31d2bc4c945721d5a29f1b7455a119fbe59fba0accdedd843074b1794311778`，292 661 080 字节，
同包名 `com.dsh.clienu.SunsetRNE`、同 debug 签名，可直接覆盖安装）。
