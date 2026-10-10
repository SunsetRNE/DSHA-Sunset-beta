# 同步上游 build170 与无线调试冲突的 C 方案（2026-10-10）

本文记录两件事：① 把本地 Root 自举工作与上游 `DSH-APP/DSHA` 的 build170 变更合并、解冲突；
② 按用户决定「先 C 后 A」，先落无线调试冲突的 **C 方案**（说清楚 + 给直达入口 + 保活不再自动重开）。

## 1. 上游同步

`git fetch` 后，本地基线 `70e37a7` 落后上游 **7 个提交**（`origin/main = 18eaee9`）：

| 提交 | 内容 |
|---|---|
| `006254e` | **fix: 同步 build170 真机确认的 ADB 冷目录与离线准备修复** |
| `fd5b242` | fix: 修复问题报告中的 ADB 注入、附件入口与草稿残留锁 |
| `8362bba` | docs: 发布 build170 介绍并刷新星标曲线 |
| `bf60de7` `37966ce` `0f12b02` `18eaee9` | 星标曲线自动提交 |

### 1.1 上游已经覆盖了我们诊断出的根因

`006254e` 的 `util/AdbWheelPaths.java` 改动，正是本目录
[真机验证记录](device-vscreen-verification-20261010.md) 里 `ADB_ENVIRONMENT_NOT_READY`
的根因：`fs.child()` 要求**所有父目录在位**，而首次（冷）安装时
`root/…/dist-packages` 之类的多级目录还不存在，于是 wheel 补缺在发布前就抛错，
`ensureReady()` 永远到不了 `SETUP_DONE`。上游改成逐级 `stat`：已存在的前缀必须安全，
缺少的后缀交给原 NOFOLLOW 发布器逐级创建。

同一提交还给 `AdbPairActivity` 加了「**无线调试关闭时仍可先准备离线依赖**」
（`ADB_OFFLINE_READY`），与我们在真机上手动补 wheel 后链才前进的现象一致。
即：**#1 的因已经在上游修掉**，**#2 的「环境准备」部分上游已给出配对页的离线准备入口**。

### 1.2 合并与冲突处置

合并前先把本地 138 个文件的未提交工作做成快照提交（分支
`wip/root-adb-bootstrap-20261010`），再 `--ff-only` 快进 `main` 到上游，最后合回快照。
冲突只有 2 处，都是**生成文件**，都不手工挑边：

| 冲突文件 | 处置 |
|---|---|
| `gradle/verification-metadata.xml` | 单块冲突：HEAD 侧为空、快照侧新增 `org.json:20240303`（主机测试依赖）→ 取快照侧（并集） |
| `app/src/main/assets/runtime-descriptor.json` | 用 `python3 -B tools/prepare-runtime-descriptor.py --write` 从合并后的树**重算**，再 `tools/verify-runtime-descriptor-source.py`（113 资产 / 296 启动输入）与 `tools/test-runtime-descriptor-inputs.py` 复核 |

另外顺手把 3 份**历史 CRLF 文件**（`gradle/verification-metadata.xml`、
`tools/i18n/ui-source-baseline.json`、`tools/recovery-runtime/lock.json`）按
`.gitattributes` 的 `* text=auto eol=lf` 归一为 LF——否则它们会一直以「工作区已修改」
挡住任何合并。这是纯行尾变更，没有内容改动；如需回退，单独 revert 那个提交即可。

合并后主机回归：`test-root-support.py`（90 项）→ 91 项、
`test-root-bootstrap-endpoint.py` 15 项、`test-native-device-policy.py` 16 项、
`test-device-shell-policy.py` 27 项、`test-ui-i18n.py` 9 项、`check-ui-i18n.py`
新增缺陷 0，全部通过。

## 2. C 方案：无线调试冲突（已实现）

问题：自举**拒绝**接管系统的 ADB TLS 入口（无线调试），而 `DeviceBridgeService`
的保活**想让无线调试一直开着**。用户手动关掉后，保活可能又把它开回来 —— 真机上就是
这样卡住的。

### 2.1 拒绝码单独化

`RootBootstrapExecutor.verifyListenerScope()`：把「系统无线调试」从
`OTHER_ADB_PORT_IN_USE` 里拆出来，先查 `service.adb.tls.port` 与
`persist.adb.tls_server.enable`，命中即 `WIRELESS_DEBUG_IN_USE`。

```java
if (!disabled(command(false, "/system/bin/getprop", "service.adb.tls.port").trim())
    || command(false, "/system/bin/getprop", "persist.adb.tls_server.enable")
        .trim()
        .equals("1")) throw new AdbBootstrapEngine.Refused("WIRELESS_DEBUG_IN_USE");
```

行为不变（仍然拒绝接管、仍然零写入），变的只是**用户能不能看懂**：现在这句话指向
「无线调试」这个具体开关，而不是「另一个端口的 ADB 监听」。

### 2.2 卡片文案 + 直达入口

- `ui/RootBootstrapCard.detailLabel()` 新增 `WIRELESS_DEBUG_IN_USE` 分支：
  「系统「无线调试」正在运行（ADB TLS 入口），DSHA 不会接管它。请先关闭无线调试，
  再执行自举；在你处理期间 DSHA 不会自动重开它。」
- 布局 `fragment_device_grants.xml` 新增 `root_bootstrap_dev_options` 文字按钮，
  **只在最近一次结果正是这个码时可见**，点击经
  `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` 打开系统开发者选项；
  打不开时退回一句 toast，说明路径「设置 → 系统 → 开发者选项」。
  按钮只负责导航，**不会替用户改设置**。

### 2.3 保活不再自动重开

`core/AdbBootstrapController` 新增：

```java
public boolean blocksWirelessReopen() {
  if (running.get() || chainActive) return true;
  Snapshot latest = current;
  return latest != null && "WIRELESS_DEBUG_IN_USE".equals(latest.detail);
}
```

`DeviceBridgeService.tryReopenWirelessDebug()` 开头据此短路（跳过
`Settings.Global.putInt("adb_wifi_enabled", 1)`），并打一条 `DSHA-ADB` 日志。

拦停是**自解除**的：自举跑完、用户只读刷新、或出现别的结果，`detail` 都会换掉，
保活恢复正常。也就是说用户有充足时间去关开关，不会被应用自己反手打开。

### 2.4 测试与验证

新增 `RootBootstrapExecutorTest#systemWirelessDebuggingGetsItsOwnCodeAndNeverTakesOverForeignPorts`：

- `service.adb.tls.port` 非空 → `REFUSED` + `WIRELESS_DEBUG_IN_USE`，零写入、零替换；
- `persist.adb.tls_server.enable=1` → 同上（为此把测试桩里该属性从硬编码 `"0"`
  改成可配置字段）；
- 对照：外来 `service.adb.tcp.port=5556` 仍然只报 `OTHER_ADB_PORT_IN_USE`，
  不能被误标成无线调试。

主机：`tools/test-root-support.py` **OK (91 tests)**；描述符契约与 UI 国际化扫描随构建通过。

## 3. A 方案（未做，待决定）

由本应用**临时**关闭无线调试：复用 `WRITE_SECURE_SETTINGS`（`BootReceiver` 已在用，
但普通安装默认拿不到该权限，需要设备侧授权），逐次确认后写 `adb_wifi_enabled=0`，
记录「我们关的 + 原值」，自举成功/失败/清理后按记录恢复；无法归因到系统设置的 TLS
入口（如第三方 adbd）仍然拒绝。要担的责任是：改动系统调试状态，必须显著提示、恢复失败要留痕。

C 已经拿掉了「自己把开关开回来」这条自伤路径，A 只是把「让用户手动去关」变成一键完成，
因此可以先上 C，观察一段时间再决定是否需要 A。

## 4. C 的真机验证（虚拟屏，2026-10-11 00:0x）

被测包：`/sdcard/Download/install-170-c.apk`
（sha256 `4e72da0874902d41715a9df98fbe0b6ae4e7be949dec363f706dc118ea679628`，
291 102 004 字节，versionCode **170**，versionName 0.2.21-20261009.0750-dsh0.2.0-rc.2，
271 项资产，含 `runtime-descriptor.json` / `adb-wheels.bin` 与 `WIRELESS_DEBUG_IN_USE`、
`blocksWirelessReopen`、`root_bootstrap_dev_options` 三处新代码）。
覆盖安装到 `com.dsh.clienu.SunsetRNE` 后，设备复读 `base.apk` 的 sha256 与构建产物一致。

### 4.1 先复现「应用自己把无线调试开起来」

在设备授权页打开「启用 ADB 设备通道」（ADB 通道，非自举）后：

```
adb_wifi_enabled          0 → 1
persist.adb.tls_server.enable  0 → 1
service.adb.tls.port      '' → 40955
```

这台设备对 user 0 的 `WRITE_SECURE_SETTINGS` 是 `granted=true`，所以
`DeviceBridgeService.tryReopenWirelessDebug()` 真的能写 `adb_wifi_enabled=1`——
即用户手动关掉无线调试后，保活确实会把它开回来。这正是 C 要拆掉的环。

### 4.2 C1/C2：拒绝码与文案（已生效）

无线调试开启状态下「一键自举 ADB（免配对）」→「确认自举」，卡片原文：

```
请求：授权本应用公钥
本次请求被拒绝
上次观测：2026/10/10 23:59:16
公钥：未知   监听：未知   双栈防护：未知
系统「无线调试」正在运行（ADB TLS 入口），DSHA 不会接管它。请先关闭无线调试，再执行自举；在你处理期间 DSHA 不会自动重开它。
[按钮] 打开开发者选项关闭无线调试
```

即：仍是拒绝、仍然零写入（公钥/监听/双栈三项未知），但用户现在看得懂该去关哪个开关。

按钮实测可点：点击后系统「开发者选项」在虚拟屏内打开（虚拟屏 `package` 变为
`com.android.settings`）。注：应用跑在虚拟屏上时，它发出的设置 Intent 也开在虚拟屏
（Android 的 display 归属行为）；真机在主屏使用时会开在主屏。

### 4.3 C3：保活不再自动重开（拦停已实测）

logcat 抓到保活的自动重开路径被拦（`pid 7574` = 该包进程，线程为 ADB 保活线程）：

```
10-11 00:08:26.966  7574 16408 I DSHA-ADB: 保活：自举需要无线调试关闭，跳过自动重开
```

即 `blocksWirelessReopen()` 在真实 watchdog 周期里返回 true，跳过了后面唯一的写入点。

### 4.4 C3 观测窗口（4 分 12 秒）

把无线调试关到 `0/0`（用户手动关闭）后，重新打开该包的「启用 ADB 设备通道」，让保活
重新开始探活并失败——每个重试周期都会走到自动重开那一步。每 18 秒采样一次：

| 采样 | adb_wifi_enabled | persist.adb.tls_server.enable | service.adb.tls.port |
|---|---|---|---|
| T+18s … T+252s（14 次） | 0 | 0 | 空 |

整段窗口内无线调试**没有**被开回来。对照 §4.1：同一个开关在**没有 C** 时的行为是
`0→1` 且立刻出现 TLS 端口，所以这里的「保持 0」是 C 生效的结果，而不是保活没跑。

拦停是自解除的：卡片上点「只读刷新状态」或重跑自举都会换掉 `detail`，
保活随之恢复正常（那时若仍在自举场景，会再次被拦）。

### 4.5 本轮真机结论

- C1（独立拒绝码）：✅ 生效，`WIRELESS_DEBUG_IN_USE` 走到了卡片。
- C2（文案 + 入口）：✅ 文案正确，按钮可点且真的打开开发者选项。
- C3（保活不自动重开）：✅ 日志证明拦停执行；观测窗口证明结果保持关闭。
- **A 未做**：无线调试仍需用户手动关闭；本轮无人为绕过设备策略。
