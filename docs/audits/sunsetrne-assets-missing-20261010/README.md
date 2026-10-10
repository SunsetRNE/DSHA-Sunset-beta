# SunsetRNE APK 解压环境失败 —— 诊断记录（2026-10-10）

## 结论（一句话）

`dist/SunsetRNE-70e37a7dbcae.apk` 里**没有任何应用资产**：CI 打包时用 `-x` 排除了
`prepareStandardAssets` / `prepareRecoveryAssets`，而这两个任务的产物目录正是该模块
asset 源目录的**唯一**输入（`main.assets.setSrcDirs(...)`），于是 APK 的 `assets/`
只剩两个 debug fixture。安装后应用在「检查安装条件」阶段读不到
`runtime-descriptor.json`，拿到空串去解析 JSON，抛
`java.io.EOFException: End of input at line 1 column 1 path $`，环境解压中止。

这不是"离线大包缺失"的降级表现，而是**整包资产为空**。

## 现场证据

### 1. 构建产物自述

`dist/SunsetRNE-70e37a7dbcae.build.json`：

- `offlineBundlesIncluded: false`
- `excludedTasks: [":app:prepareStandardAssets", ":app:prepareRecoveryAssets"]`
- `applicationId: com.dsh.clienu.SunsetRNE`、`versionCode: 162`

### 2. APK 内 assets 实际内容

```
$ unzip -l dist/SunsetRNE-70e37a7dbcae.apk | grep -c " assets/"
2
  assets/java/WebBrowserFixtureProbe.java
  assets/web-state-fixture.html
```

两个文件都来自 `debug.assets.srcDir(tools/fixtures/web-state)` 与 debug 源集。
应在包内、但全部为 `0` 的条目：

| 资产 | APK 内 | 运行期用途 |
|---|---|---|
| `runtime-descriptor.json` | 缺失 | 受管运行时契约 / `runtimeId` |
| `offline-rootfs.version` | 缺失 | 环境身份 `environmentIdentity()` |
| `offline-rootfs.bytes` | 缺失 | 解压所需空间估算 |
| `offline-rootfs.bin` | 缺失 | Ubuntu rootfs 本体 |
| `offline-rootfs.layout` | 缺失 | `split-runtime-v1` 分包标记 |
| `dsh-runtime.bin` | 缺失 | dsh 运行时叠加层 |
| `adb-shell.py`、`glibc-python.tar.gz`、`pnpm-runtime.bin` 等 | 缺失 | 设备 shell / Python / pnpm |

### 3. 设备实测（虚拟屏，LENOVO TB321FU / Android 16）

设备上已安装该包（`firstInstallTime=lastUpdateTime=2026-10-10 21:28:08`，
`versionCode=162`，`DEBUGGABLE`），且设备上 `base.apk` 为 **25 770 570 字节**，
与 `dist/SunsetRNE-70e37a7dbcae.apk` 逐字节同尺寸。

主界面进入「受限模式：环境需要恢复 · 点击处理」，「▶ 启动 DSHA」与「↻ 重启」禁用
（见 `01-limited-mode.png`）。

冷安装诊断记录（见 `02-cold-install-diagnostics.png`，时间戳为 2026-10-10 21:29 +08:00）：

```
[1791638940818] RUNTIME_ASSET_TEXT_UNAVAILABLE runtime-descriptor.json:FileNotFoundException
[1791638946057] RUNTIME_ASSET_TEXT_UNAVAILABLE offline-rootfs.version:FileNotFoundException
[1791638951346] STAGE 检查安装条件
[1791638951359] RUNTIME_ASSET_TEXT_UNAVAILABLE offline-rootfs.bytes:FileNotFoundException
[1791638951367] RUNTIME_ASSET_TEXT_UNAVAILABLE runtime-descriptor.json:FileNotFoundException
[1791638951375] FAILED 检查安装条件 java.io.EOFException: End of input at line 1 column 1 path $
（随后两次重试同样停在同一点）
[1791639247803] RUNTIME_ASSET_TEXT_UNAVAILABLE offline-rootfs.version:FileNotFoundException
```

维护页据此显示「未完成的操作：更新运行环境 / 最后记录阶段：正在停止 Web，等待运行队列完成…」。

## 根因链

1. `app/build.gradle:120`

   ```groovy
   main.assets.setSrcDirs([layout.buildDirectory.dir("generated/standardAssets").get().asFile,
           layout.buildDirectory.dir("generated/recoveryAssets").get().asFile])
   ```

   `setSrcDirs` 是**替换**语义，默认的 `app/src/main/assets/` 被移出 asset 源集。
   仓库里 `app/src/main/assets/` 有 79 个条目（含 `runtime-descriptor.json`），
   但它们只有先被 `tools/prepare-standard-assets.py` 复制到
   `app/build/generated/standardAssets/` 之后才会进 APK。

2. `.github/workflows/ci-apk-sunsetrne.yml` 的构建步骤：

   ```bash
   ./gradlew :app:assembleStandardDebug --no-daemon --max-workers=2 \
     -x :app:prepareStandardAssets -x :app:prepareRecoveryAssets
   ```

   两个生成任务被排除（`merge*Assets` 原本 `dependsOn(prepareRecoveryAssets)`，
   也被一并跳过），生成目录不存在，asset 源集为空。

3. 运行期 `RuntimeAssetText.read()` 打不开资产时**记录后返回空串**（不抛出）：

   ```java
   catch (IOException error) {
     RuntimeHostPorts...record("RUNTIME_ASSET_TEXT_UNAVAILABLE", name + ":" + error.getClass().getSimpleName());
     return "";
   }
   ```

   空串随后被当作 JSON 交给 Jackson 解析 → `EOFException: End of input at line 1 column 1 path $`
   → `ColdBundleTransaction` 的「检查安装条件」阶段 `FAILED`。

## 为什么构建、CI 都没拦住

- Gradle 对**不存在**的 asset srcDir 静默跳过，`assembleStandardDebug` 照常 `BUILD SUCCESSFUL`。
- workflow 只做了三件校验：包名 `aapt2 dump badging`、debug 证书、上传产物；
  没有任何一步检查 APK 内资产是否完整。
- 构建元数据把 `offlineBundlesIncluded: false` 记为预期（注释写的是
  "offline bundles excluded"），掩盖了真实后果：排除的不只是离线大包，
  而是**全部** application assets。

## 修复方向

1. **可运行包**：不要在 assemble 上 `-x` 资产任务。CI 上按 `ci-package.yml` 的既有路径准备离线输入
   （`tools/ci-assets.py` 还原输入 → `tools/prepare-test-runtime.py` → `tools/verify-stability.py`），
   然后正常打包。
2. **即使没有 rootfs 输入**：`tools/prepare-standard-assets.py` 本身支持精简分支
   （`if not rootfs.is_file(): print("未提供离线 rootfs，生成精简资产"); return`），
   会照常复制 `selected_assets` 并生成 `glibc-python.bin` 等。跑它即可避免"整包资产为空"；
   此时包内仍没有 `offline-rootfs.bin`，**依旧无法解压环境**，但不会再崩在「检查安装条件」。
3. **构建期护栏（建议必做）**：在 `merge*Assets` 之后增加断言，校验合并后的资产至少包含
   `runtime-descriptor.json` / `offline-rootfs.version`，否则 `fail`，防止再产出这类"能装不能跑"的包。
4. 若确实需要"不含环境的测试包"，应在产物名与说明里显式标注不可安装运行，
   而不是沿用可交付 APK 的命名与流程。

## 取证范围说明

- 本记录只覆盖 Standard 变体、该次 `assembleStandardDebug` 产物与该台设备（Android 16 / arm64-v8a）。
- 未验证 Low 变体；未对正式签名发布包做同类断言检查。

---

## 附：完整包的恢复与验证（同日晚）

### 离线输入从哪来

官方 asset bundle（`tools/ci-assets.py --url` 的外部输入包）不在仓库内、git 历史里从未提交过实际地址、
远端 `ci-package.yml` 运行次数为 0 —— 按设计它就是由发布执行者临时提供的，**不该在仓库里找**。

实际采用的替代来源：设备上已安装的**完整正式包** `com.dsh.client`
（`0.2.16-20261007.2100-dsh0.2.0-rc.2`，build 165，`base.apk` 286 351 672 字节）——
它的 `assets/` 里就带着全套离线输入。从包中提取并逐字节核对：

| 资产 | 提取摘要 | 与当前源码期望 |
|---|---|---|
| `dsh-runtime.bin` | `3ddafc8f…` | ✅ 一致 |
| `ubuntu-tools.bin` | `44eeebf8…` | ✅ 一致 |
| `pnpm-runtime.bin` | `08873508…` | ✅ 一致（仓库内已有同字节） |
| `glibc-python.bin` → `.tar.gz` | `d3f47284…` | ✅ 一致 |
| `python-support.bin` | `9f8299ea…` | ✅ 一致 |
| `adb-wheels.bin` → `.tar.gz` | `173000bd…` | ✅ 一致 |
| `offline-rootfs.bin` | `b9fe8a04…` | ⚠️ 已是「优化后」形态；未优化输入版已不可得 |

`offline-rootfs.bin` 的 `var/lib/dpkg/status` 摘要为 `922b9fd7…`，与
`tools/ubuntu-tools/packages.lock.json` 的 `baseStatusSha256` 一致 —— 说明该 rootfs 与
离线工具锁配套，可作为输入使用。

### 重建了哪些文件

- 新增（均被 `.gitignore` 覆盖，不入库）：`offline-rootfs.bin`、`dsh-runtime.bin`、
  `ubuntu-tools.bin`、`dsh-runtime.inputs.json`、`ubuntu-tools.inputs.json`
- 重写（已提交文件）：`app/src/main/assets/runtime-descriptor.json`，
  `runtimeId` 由 `bc136bb9…` 变为 `e6422369…`（因为输入摘要集合变了）

### 构建与验证结果

```bash
export ANDROID_SDK_ROOT=/root/Android ANDROID_HOME=/root/Android
bash build.sh -Pandroid.aapt2FromMavenOverride=/opt/aapt2-arm64/aapt2 \
  --project-cache-dir app/build/sunsetrne-local-20261010/project-cache \
  --init-script app/build/sunsetrne-local-20261010/local.init.gradle \
  :app:assembleStandardDebug --no-daemon --max-workers=2 --console=plain
```

**注意：不再带 `-x :app:prepareStandardAssets -x :app:prepareRecoveryAssets`。**

前置预检（全部 PASS）：`test-runtime-descriptor-inputs`、`test-runtime-descriptor-source`、
`verify-runtime-descriptor-source`、`test-runtime-input-contract`。

产物 `app-standard-debug.apk`：

- 290 932 304 字节（对比故障包 25 770 570），`assets/` 条目 **236** 个（对比 2 个）
- 包内 `offline-rootfs.bin` / `dsh-runtime.bin` / `ubuntu-tools.bin` 摘要与官方锁逐字节一致
- `package: com.dsh.clienu.SunsetRNE`，versionCode 162，debug 签名证书
  `db1fb87a…`（与设备上已安装包同签名，可覆盖安装）
- `tools/audit-standard-apk.py`：`unaligned_16k: []`、`other_architectures: []`、
  `relro_outside_load: []`；`recovery_archive_sources` 显示应急资产按摘要共用了正式归档

交付位置：`/sdcard/Download/dsha-fixed-full.apk`（+ `.sha256`，
`e510c86d46e07dfae03f659f0a1497c280805ab7e57aae01b68ac5d5876958f1`）。

### 尚未完成的验证

`pm install` 被设备策略拦截（「pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令」），
因此**安装与首次解压的真机验证尚未执行** —— 需要由用户在设备上点装后确认。
本记录不含「已真机验证可解压」的结论。
