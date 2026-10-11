# 环境维护（受管运行时更新）——机制、状态与处置

面向"为什么又进维护页了 / 为什么我没看到维护页 / 现在该点什么"。2026-10-11 在真机上把
四个阶段与两种入口都实测过一遍后整理；代码依据都标了位置。

## 1. 一句话

「环境维护」= **受管运行时（Ubuntu 基础环境 + 内置 dsh）的更新事务**。它不是安装 APK 的必然结果，
而是「设备上已安装的运行身份 ≠ 当前 APK 里受管描述符的身份」时，App 必须做的一次切换。

## 2. 什么时候会触发

`MainActivity.onCreate` 的门禁（无论冷热启动都读这些条件，但**只有冷启动会跑这段**，见 §5）：

```java
if (!limitedAllowed
    && (MaintenanceCoordinator.pending(controller)                 // ① 有未完成的事务
        || (!skipExtract && (!controller.isEnvironmentReady()      // ② 环境未就绪
            || EnvironmentAccess.requiresMaintenanceBeforeStart(controller)))))  // ③ 运行身份不是最新
  → startActivity(ExtractActivity)
```

③ 的判据是 `RuntimeDescriptor.latest(expected)`：

```java
return id().equals(expected.id())            // runtimeId 必须一致
    && Objects.equals(inputs, expected.inputs)   // 受管输入摘要一致
    && compatible(expected);                     // baseVersion / launcherContract / bridgeProtocol / dataRead
```

所以**改到 `app/src/main/assets/managed-runtime-inputs.json` 清单里的任何文件，都会改 `runtimeId`，
从而在设备上留下一次"待更新"**。清单包含：141 个 Java 源（`HttpShellService`、`DeviceBridgeService`、
`core/AdbBootstrapController` 等）、113 个资产（`adb-shell.py`、`device-shell-policy.py`、内置插件…）、
launcher 树与 generator 源。

只改 `ui/`、`res/`、`tools/i18n/`、`app/src/debug/`、**新增**文件（不在清单里）**不会**改 `runtimeId`，
也就不会触发维护。

## 3. 四个阶段（2026-10-11 真机实测）

| 阶段 | 页面文案 | 说明 |
|---|---|---|
| 1 | 正在解压新版 dsh（保留现有 Ubuntu 与个人目录）… | 解包新运行时载荷 |
| 2 | 新版运行时准备完成，正在保留并切换受管文件… | `ManagedRuntimeTransaction` 切换 |
| 3 | 正在启动 DSH 网页… | 起 proot + node 做真实启动 |
| 4 | 正在确认网页与数据读写… | 鉴权 / 端口 / 存储读写核验（最慢的一步） |

跑完回到主界面（`DSH 未运行 / 运行环境 READY`）。整轮数分钟；**中途被杀 = 可恢复**，不是报废。

## 4. 事务是可中断、可恢复的

页面上的按钮就是状态机手势：

| 按钮 | 含义 |
|---|---|
| 更新运行环境 | 事务未开始：开始跑 |
| **恢复中断维护** | 事务被打断（典型：覆盖安装杀进程），点它接着跑 |
| 进入受限主界面 · 查看日志与配置 | 受限入口（不改状态） |
| 启动应急 DSH | `recovery/` 的独立环境 |
| 查看本次维护记录 | 上次事务的结论 |
| 格式化并全新开始 | 核选项 |

**覆盖安装会杀 App 进程**：若正好杀在阶段 2，就停在"可恢复"；下一次冷启动接着来 ——
这就是"感觉每次安装都在维护"的真实原因：**不是每次重新开始，而是上一次没跑完**。

## 5. 两种入口（本轮踩坑与修复）

| 入口 | 行为 |
|---|---|
| **冷启动**（进程不在、从最近任务划掉后打开、系统全新拉起） | 走 `onCreate` 门禁 → 条件成立时**直接进维护页** |
| **热启动**（任务已在后台，点图标把它拉到前台） | **不重跑 `onCreate`** → 旧版本什么都不显示（既没页面也没提示） |

修复历史：

- **build183**：主界面横幅加入"有未完成事务"这一档 → 热启动也能看到「环境维护待处理 · 点击恢复」。
- **build187**：横幅再补一档 `requiresMaintenanceBeforeStart()`（§2 ③：运行身份不是最新）
  → **"需要更新运行时"同样在热启动可见**，文案统一为「环境维护待处理 · 点击处理」。
  横幅点击进入维护页（`review_only`，只展示与手动推进，不自动开跑）。

> 排障提示：如果"我这边打开没反应、别人拉起来却在维护"，先看是不是**热启动**。
> 强制冷启动的方法：最近任务里划掉该应用，再打开。

## 6. 现在处于什么状态：三句话判断

1. 主界面顶部横幅：**进行中**（正在跑）/ **待处理**（需要你点）/ 无横幅（一切正常）；
2. 点进维护页看按钮：有「恢复中断维护」= 被打断过；有「更新运行环境」= 尚未开始；
3. 维护期间**不要**覆盖安装、不要强杀 App —— 那只会把它变成"下一次接着跑"。

## 7. 开发与交付纪律（血泪）

1. **改受管输入 = 改 `runtimeId`**：必须
   `python3 tools/prepare-runtime-descriptor.py --write`，并审阅 `runtimeId` 变化；
   `--check` 必须在提交前通过（构建会跑它）。
2. **改 `runtimeId` 必须配一次真实 `RuntimeTrial`**（ADR 0004/0005 的要求）。本轮工程验证包
   **只重算了描述符、没跑试用**，代价是"每装一次就在设备上现走一遍维护"，这也是必须补的账。
3. **攒批**：能不改受管文件就不要改（优先 `ui/` / `res/` / 新文件）；必须改时**一次改完再出包**，
   别让用户每个版本都吃一次维护。
4. **不要在"同机装两个同源 DSHA 包"的环境里做端到端验收**：`HttpShellService.PORT=3090` 是固定
   常量，桥只属于**启动它的那个实例**，第二个包的容器脚本拿自己的 token 去问必然被拒
   （表现为 `(policy blocked) 3090 桥拒绝了这个 token … / (exit=126)`，见
   [guard-visibility §真因 4](audits/root-adb-bringup-20261009/guard-visibility-20261011.md)）。
   单实例（正式包自己）才能把 ADB 会话核验跑绿。
5. 受管运行时**身份独立于 DSH 与 UI 版本**：UI-only 的包不必替换环境；普通 dsh 更新也不递增
   Ubuntu 基础环境版本。

## 8. 常见现象与处置

| 现象 | 根因 | 处置 |
|---|---|---|
| 打开没进维护页，但别人拉起来有 | 热启动不跑 `onCreate` 门禁（build187 起横幅可见） | 划掉最近任务再打开；或点横幅 |
| 每次覆盖安装都进维护 | 上一次事务被杀在阶段 2，停在"可恢复" | 让它跑完；开发侧**攒批**出包 |
| 主界面"运行环境 READY"却仍要求维护 | `runtimeId` 不一致（READY 只代表能跑，不代表是最新身份） | 正常，按提示更新 |
| 自举报 `REQUEST_OUTCOME_UNKNOWN` | 失败没有可识别错误码（build182 起会保留原文） | 看「查看失败原文」；先在维护结束后再操作 |
| 自举报 `WIRELESS_DEBUG_IN_USE` | 系统无线调试在跑，自举拒绝接管 | 开发者选项关闭无线调试；注意 ADB 保活可能把它开回来（build186 起"结果未知"窗口也拦停保活） |
| 自举最后一步 `3090 桥拒绝了这个 token` | 双实例环境，桥属于另一个包 | 单实例验证；build185 起失败原文会附解释 |
| 无线调试"自己又开了" | `DeviceBridgeService.tryReopenWirelessDebug()`：有 `WRITE_SECURE_SETTINGS` + 探活失败 | 先关那个包的「ADB 全局授权」总开关，再关无线调试 |

## 9. 未验证与已知偏差（如实记录）

- **未走真实 `RuntimeTrial`**：本轮的 `runtimeId` 变化只在设备上现走了受管切换，没有走
  ADR 要求的试用门禁。正式发布前必须补。
- 维护四个阶段的**耗时**没有做统计口径（只有"数分钟"的观察），也没有掉电/中断后的穷尽恢复矩阵。
- 双实例环境的 ADB 会话核验限制未在产品层解决（跨包协调需要单独决策）。
