# 设备 shell 指令放宽评估（2026-10-11）

起因：本轮做设备授权统一改造，需要把 291 MB 工程包推到公共区并安装、调试，过程中撞到
若干 shell 限制。本文只做**评估**：哪些放宽真的能改善调试与安装，哪些必须继续拒绝，以及
改动代价。所有结论都基于本机实测，不是推演。

## 1. 结论先说

1. **安装/推送的摩擦主要不在命令白名单**，而在两处具体上限：`/app/export` 的 64 MB 文件上限
   （291 MB 被 `TOO_LARGE` 拒），以及**没有任何受控安装入口**。这两处不碰白名单文件即可改。
2. 只读调试命令只缺**极少数几个**（实测只发现 `top`；`dumpsys package/activity/meminfo/window`、
   `pm list/dump/path`、`logcat -d`、`find/cp/sha256sum` 都已可用）。
3. `pm install` 若要开放，**不应作为通用 shell 命令**，而应做成受控入口（路径限定 + 用户确认 +
   不开放 `-d`/`--user`/卸载/清数据）。管道、变量、嵌套、`su -c`、`settings put`、`setprop`、
   分区/SELinux/挂载、卸载/清数据这些**不得放宽**：它们与硬约束直接冲突，放宽即等于白名单失效。

## 2. 现状（判定在哪一层）

| 层 | 位置 | 作用 |
|---|---|---|
| 命令白名单 | `util/DeviceShellPolicy`（544 行，受管输入） | 唯一权威：单条 argv、默认拒绝、无 meta 字符 |
| guest 侧目录规则 | `app/src/main/assets/device-shell-policy.py`（受管资产） | ADB 客户端共用同一份目录/路径规则 |
| 通道 | RootShell / ShizukuShell / AdbBridge | 三条通道**同一套**策略，root 不放宽 |

判定要点（`DeviceShellPolicy.split`）：任何 `; & \| > < $ \` ( ) { } \ #` 直接拒绝，控制字符与
多行拒绝；命令必须是系统目录里的已识别名字；`toybox <子命令>` 只是去壳，不接受嵌套执行器。

## 3. 实测清单（本机逐条跑过）

| 命令 | 结果 |
|---|---|
| `cp <应用私有目录>/app-standard-debug.apk /sdcard/Download/DSHA/…` | ✅ 可用（291 MB 一次成功） |
| `sha256sum /sdcard/Download/DSHA/install-174-adbauth.apk` | ✅ 可用，与本地一致 |
| `find /sdcard/Download/DSHA -maxdepth 1 -name X -print` | ✅ 可用 |
| `ls -la` / `stat` / `df` / `du` / `wc` / `grep` / `readlink` / `getprop` | ✅ 可用 |
| `pm list packages -3 -U`、`pm dump <pkg>`、`pm path <pkg>` | ✅ 可用 |
| `dumpsys package <pkg>`、`dumpsys activity top`、`dumpsys meminfo <pkg>`、`dumpsys window displays` | ✅ 可用 |
| `logcat -d -t 5` / `logcat -d -s TAG:V` | ✅ 可用（必须显式 `-d` 或 `-t`） |
| `pm install -r /sdcard/…apk` | ⛔ `pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令` |
| `pm install-existing <pkg>`、`install -r …` | ⛔ 同上 / `不认识的命令不执行：install` |
| `cmd package list packages` | ⛔ `不认识的命令不执行：cmd` |
| `am start -n …` | ⛔ `不认识的命令不执行：am`（只有 `am get-current-user`、`am force-stop <pkg>` 放行） |
| `top -n 1 -b` | ⛔ `不认识的命令不执行：top`（READ 集合里有 `ps`/`free`/`uptime`，没有 `top`） |
| `monkey`、`screencap`、`input` | ⛔ 不识别；对应能力都有受控端点（`/app/launch`、`/app/ui/screenshot`、`/app/ui/tap`） |
| `logcat -c` | ⛔ `logcat 只允许读取，禁止清日志…` |
| `settings put global …` / `setprop` | ⛔ 明确拒绝 |
| `su -c id` | ⛔ `不认识的命令不执行：su`（`--su` 是通道，不是命令） |
| `am instrument -w com.dsh.clienu.SunsetRNE/com.deepseekharness.app.ui.LayoutAuditInstrumentation` | ⛔ `am` 只放行 `get-current-user` 与 `force-stop`；仓库自带的设备审计（`AdbFlowAudit`、`LayoutAuditInstrumentation` 等）默认是**从宿主 PC 的 adb** 调起的，容器内今天跑不了 |
| `ls … \| head`、`find … 2>/dev/null \| head` | ⛔ `禁止脚本、管道、重定向、变量或嵌套命令；请分条执行` |

## 4. 建议（按"值不值"排序）

### P0 — 不动白名单，收益最大

1. **`/app/export` 的 64 MB 上限**（`HttpShellService` 里 `f.length() > 64L * 1024 * 1024` →
   `TOO_LARGE`）。工程包 291 MB 因此推不出去，只能走设备 shell `cp`。方案二选一：
   (a) 提高上限到一个可配置值（例如 512 MB），(b) 保持上限但补一条**分块导出**路径。
   影响面：只动宿主端点，不改任何命令策略。
2. **受控安装入口**（真正解决"安装"）：新增 `Kind`/端点，而不是放宽 `pm`：
   - 只接受绝对路径，且必须位于 `Download/DSHA/` 或应用自己的导出目录，禁止 `/data/local/tmp` 之外；
   - 不接受 `-d`（降级）、`--user`、`-t` 以外的任何 flag；
   - 安装前先 `PackageManager.getPackageArchiveInfo` 核对包名、versionCode、签名，并把结果回执；
   - **每次安装仍走用户确认**（与短信、危险命令同一套确认渠道），撤销后立即失效；
   - 永不提供 `uninstall` / `clear` / `grant` / `revoke`。
3. **把"大 APK 走设备 shell `cp`"写进流程文档**：本次已验证可行（root 通道读应用私有目录 →
   写 `Download`），无需任何策略变更。

### P1 — 只读白名单的小幅增补（低风险）

| 命令 | 理由 | 约束建议 |
|---|---|---|
| `top` | 看 CPU/内存热点，调试卡顿最常用 | 只允许 `-n <1..5>`、`-b`、`-o <字段>`、`-m <1..50>` |
| `dumpsys` 增补 `activity services/providers/broadcasts <pkg>`、`package <pkg> <字段>` | 应用状态取证 | 仍只读，`<pkg>` 必须完整包名 |
| `cmd package` 的只读子域（`list`/`path`/`dump`/`resolve-activity`） | 与 `pm` 等价信息 | 白名单子命令，禁止 `install`/`uninstall`/`clear` |

这些都不改变"只读"性质，风险与现有 READ 命令同级。

### P2 — 需要单独拍板的

- **容器内驱动仪器化审计**（本轮真实缺口）：`LayoutAuditInstrumentation`、`AdbFlowAudit` 等
  仓库自有审计在文档里都是从宿主 PC 的 `adb shell am instrument …` 调起的；容器内 `am` 只放行
  `get-current-user`/`force-stop`，因此**本轮布局验收跑不了**。建议不是放宽 `am`，而是加一个
  受控入口（只接受仓库内已注册 instrumentation 的**完整组件名**、限定参数键与超时，并复用
  `app/src/debug` 的注册表），这样容器内也能跑验收，且不引入"任意 am 动作"。
- `pm install` 作为**通用 shell 命令**：不建议。理由：白名单一旦接受安装，就等于让任何
  agent 生成的内容（含插件、网页提示）获得"写代码到设备"的能力，且历史规矩是
  "通用命令白名单不放宽"。
- `am start`：不建议。`/app/launch` 与虚拟屏 `launch` 已覆盖，且后者带 frameSeq 与授权核验。

## 5. 明确不得放宽（与硬约束冲突）

- 管道 / 重定向 / 变量 / 命令替换 / 嵌套 shell（`; & \| > < $ \` …`）：放宽即等于执行任意程序。
- `settings put`、`setprop`、`mount`/`umount`、`setenforce`/`chcon`/`restorecon`、块设备与分区、
  `fastboot`/`flash`：系统明确禁止。
- `pm uninstall`、`pm clear`、卸载/清应用数据：系统明确禁止。
- `pm grant`/`revoke`：权限提升；无线调试那条 `WRITE_SECURE_SETTINGS` 是**产品内建的一次性派生凭据**，
  不能扩成通用能力。
- `screencap`/`input`/`monkey`/`app_process`：都有受控入口，shell 版本会绕过授权与帧号核验。
- `logcat -c` 与 `logcat -f`：破坏证据链。

## 6. 改动代价（必须先知道）

- `util/DeviceShellPolicy.java` 与 `assets/device-shell-policy.py` **都在 `managed-runtime-inputs.json`
  的受管输入里**：任何改动都会改 `runtimeId`，必须 `prepare-runtime-descriptor.py --write`
  并走一次真实 `RuntimeTrial` 才能提交。也就是说 P1/P2 必须与阶段 2 一起打包，
  不能当作顺手改。
- P0 的两项（导出上限、受控安装入口）同样会改宿主源码；导出上限在 `HttpShellService` 里，
  它也在受管输入里 → 一样改 `runtimeId`。真正"零代价"的只有文档化 + 走设备 shell `cp`。
- 三条通道共用同一份策略，**不要**为 root 通道另开一套。

## 7. 建议的最小落地顺序

1. 现在：文档化大文件走设备 shell `cp`（零改动），本轮已按此完成推送。
2. 阶段 2 一起打包：`top` + `dumpsys/cmd` 只读增补（P1）。
3. 单独评估后再做：受控安装入口（P0-2，含用户确认与签名核对）。
