# 端口契约改造的权威影响面清单（消费者 / runtimeId 成员 / 受管试用步骤）

范围：`docs/adr/0003-runtime-endpoint-single-source.md`（状态仍为「待确认，尚未实施」）落地为
`util/RuntimeEndpoints` + 端点契约文件所需的**全部替换点**。本文件只登记事实，不重开已决事项
（Web 允许落在 3090–3109 并做启动仲裁；LAN 3081 也补回退与契约；`3080` 进契约；桥不做 `0` 动态分配；
guest 只在**真正缺目录项**时回退 3090）。

基线：`git` HEAD `2d336cb`（build190 发布说明补记）。所有行号是本次核验时的实际行号，不是 ADR 里的旧行号。
标记：**VERIFIED** = 本次实际读到该行；**INFERRED** = 由已读代码推导，未逐字核验。

## 0. 对 ADR 0003 的行号/计数纠正（先看这个）

ADR 正文与本次核验的差异，改造时以本表为准：

| ADR 的说法 | 实际 | 证据 |
|---|---|---|
| `/app/help` 正文里 3090 出现 **12 次** | **4 次**（同一个字符串字面量内） | `HttpShellService.java:1466` 实测，见 §1.6 |
| guest 客户端 `bridge-token-compat.cjs:8` 是 `url.port!=='3090'` | 现为 **3090–3109 范围判据 + 桥路由判据**，且 fail-closed | `bridge-token-compat.cjs:23-27,138-144` VERIFIED |
| `adb-shell.py:188,306,333,413` 四处写死 URL | 只有 **1 个字面量**（默认值 `:37`）；URL 由函数拼装 `:287` | §2.1 VERIFIED |
| `adb-setup.sh:110-115` 生成的包装**不含端口** | 正确（ADR 已自纠）；`dsha-device-shell.sh` 同样不含 | §2.2 VERIFIED |
| `dsh-device-shell-guide/lib/index.js:10,16` 两处 3090 | 提示词 2 处，但**不在同两行**；另有 `:12` 默认值 | §2.5 VERIFIED |
| `tools/device-policy-device-audit.py:95` 断言 `BRIDGE_PROTOCOL=2` | 未核验（超出本票范围） | 未核验 |

## 1. host 侧：端口事实与替换点

### 1.1 默认值常量

- `app/src/main/java/com/deepseekharness/app/util/Constants.java:22` — `DSH_WEB_PORT = 3080` — VERIFIED
- `Constants.java:25` — `SHELL_BRIDGE_PORT = 3090` — VERIFIED
- `Constants.java:28` — `LAN_BRIDGE_PORT = 3081` — VERIFIED
- `Constants.java:31` — `ADB_DEFAULT_CONNECT_PORT = 5555`（非本契约范围，列出以免误改）— VERIFIED
- `app/src/main/java/com/deepseekharness/app/LanProxyService.java:39` — `LAN_PORT = 3081`（**独立字面量，不复用 `Constants`**）— VERIFIED
- `LanProxyService.java:40` — `DEFAULT_BACKEND_PORT = 3080`（同上，独立字面量）— VERIFIED
- `LanProxyService.java:126` — `backendPort = DEFAULT_BACKEND_PORT`（可变后端端口的初值）— VERIFIED
- `LanProxyService.java:292` — `start(..., DEFAULT_BACKEND_PORT, ...)` — VERIFIED
- `LanProxyService.java:312` — 后端端口合法性判据 `backend != LAN_PORT ? backend : DEFAULT_BACKEND_PORT` — VERIFIED
- `app/src/main/java/com/deepseekharness/app/util/DshAuthUrl.java:15` — `LOOPBACK_BASE_URL = "http://127.0.0.1:3080/"` — VERIFIED
- `DshAuthUrl.java:18` — `AUTH_URL_PREFIX = LOOPBACK_BASE_URL + "?token="`（解析器已支持任意端口，只有默认值写死）— VERIFIED
- `app/src/main/java/com/deepseekharness/app/HttpShellService.java:51` — `PORT = BridgePortPolicy.PREFERRED`（**已参数化，不是字面量**）— VERIFIED

### 1.2 派生常量与选择点（桥）

- `app/src/main/java/com/deepseekharness/app/util/BridgePortPolicy.java:23` — `PREFERRED = Constants.SHELL_BRIDGE_PORT` — VERIFIED
- `BridgePortPolicy.java:26` — `LAST = 3109`（回退上界，含）— VERIFIED
- `BridgePortPolicy.java:50-52` — `bindLoopbackFirstAvailable()` = `bindFirstAvailable(LOOPBACK, PREFERRED, LAST)` — VERIFIED
- `BridgePortPolicy.java:64-71` — 逐端口真实 bind；只吞 `BindException` 继续，其它失败立即上抛；耗尽抛 `BRIDGE_PORT_EXHAUSTED` — VERIFIED
- `BridgePortPolicy.java:75-79` — `loopbackOnly()` 回环校验 — VERIFIED
- `HttpShellService.java:560` — `run.server = BridgePortPolicy.bindLoopbackFirstAvailable()` — VERIFIED
- `HttpShellService.java:561` — `loopbackOnly` 断言 — VERIFIED
- `HttpShellService.java:569` — 就绪门槛 `publishReadyEndpointFor(run, run.port, token)` — VERIFIED

### 1.3 保留（reservation）判据 —— 目前有**两份实现**

- `ConfigInput.java:24` — `reservedPort(p) = (p == Constants.LAN_BRIDGE_PORT) || (p == Constants.SHELL_BRIDGE_PORT)` — VERIFIED
- `ConfigInput.java:29` — `reservedPortMessage()` 返回字面量文案 `"3081 和 3090 已用于 App 桥接，请换一个端口"`；`:14` — 范围文案 `"端口请输入 1—65535 的整数"` — VERIFIED
- `util/WebPortPolicy.java:41` — `reserved(port) = (port == 3081 \|\| port == 3090)`（**字面量，未复用 `ConfigInput`**）— VERIFIED
- 消费 `ConfigInput.reservedPort` 的宿主路径：`core/ConfigStore.java:171`（读取兜底）、`:182-184`（写入拒绝）、`:196-197`（`portForWrite` 拒绝）— VERIFIED
- **INFERRED**：两份 `reserved` 是同一事实的两份抄写，改造后必须只留一份；`WebPortPolicyTest` 的 5 个用例目前不覆盖 `reserved()` 边界（见 §5）。

### 1.4 Web 端口仲裁与动态分配

- `WebPortPolicy.java:29-38` — `choose(preferred, previous, probe)`：首选可用即用；否则复用上次 `previous`；再否则返回 `listen = 0`（交给 dsh/内核分配）— VERIFIED
- `WebPortPolicy.java:31` — `!reserved(preferred) && probe.available(preferred)` — VERIFIED
- `WebPortPolicy.java:32-36` — 上次 fallback 端口的复用条件 — VERIFIED
- `WebPortPolicy.java:37` — `new Choice(preferred, 0)` 动态分配出口 — VERIFIED
- `WebPortPolicy.java:57-64` — `authentication(output, listen)`：`listen != 0` 用 `DshAuthUrl.findAny`；`listen == 0` **只接受本轮官方启动行**且端口不得是保留端口 — VERIFIED
- `WebPortPolicy.java:66-75` — `conflictPort(detail)` 从 `EADDRINUSE` 详情里提取 `127.0.0.1:<port>` — VERIFIED
- `app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java:44` — `--no-open --host 127.0.0.1 --port ` + listenPort（Web 端口已参数化进 guest）— VERIFIED
- `app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java:196` — 试用启动用 `--port 0` — VERIFIED
- `app/src/main/java/com/deepseekharness/app/runtime/ProfileSettingsTrial.java:182` — 同上 `--port 0` — VERIFIED
- `app/src/main/java/com/deepseekharness/app/runtime/TrialImportStop.java:153` — `--port` 参数传递 — VERIFIED
- **INFERRED**：LAN 3081 目前**没有**任何回退/仲裁实现（`LanProxyService.LAN_PORT` 是 `static final`）；ADR 已决「LAN 也补回退 + 契约」，这是**新增**能力而非改造既有逻辑。

### 1.5 桥端点文件与就绪门槛

- `app/src/main/java/com/deepseekharness/app/util/BridgeEndpointFiles.java:32` — `FILE_NAME = CredentialPaths.BRIDGE_PORT`（即 `.bridge_port`）— VERIFIED
- `BridgeEndpointFiles.java:35` `UNAVAILABLE = 0`（明确不可用）；`:38` `MISSING = -1`；`:41` `LEGACY_DEFAULT = Constants.SHELL_BRIDGE_PORT`（**只给旧消费者**）；`:43` `READ_LIMIT = 16` — VERIFIED
- `BridgeEndpointFiles.java:54-60` — `parse()` 严格 `[0-9]{1,5}\n?`，越界抛 `BRIDGE_PORT_RANGE` — VERIFIED
- `BridgeEndpointFiles.java:66-77` — `read()`：缺失返回 `MISSING`；非普通文件/坏内容抛错；**任何情况下不返回 `LEGACY_DEFAULT`** — VERIFIED
- `BridgeEndpointFiles.java:80-92` `publish()` 原子写 + `0600` + 读回核验；`:101-106` `publishIfCurrent()` 代次门禁；`:118-146` `publishReady()` 要求同根 `.bridge_token` / `.bridge_headers` 在位，否则 `BRIDGE_TOKEN_UNAVAILABLE` / `BRIDGE_HEADERS_UNAVAILABLE` — VERIFIED
- `HttpShellService.java:490-494` `publishReadyEndpointFor` 委托 `BridgeEndpointFiles.publishReady`；`:688` 停止/失败发布 `0`，注释明说 consumer 读到 0 **不得**回退 3090；`:57` `activePort()` 供 `/app/version`、`/app/help`、页面注入使用 — VERIFIED

### 1.6 `/app/help` 正文里的 3090（**实测 4 次，不是 12 次**）

- `HttpShellService.java:1462-1470` — `appHelp()`：模板经 `UiText.format` 后，`int port = activePort()`，`port > 0` 时整串 `body.replace("3090", Integer.toString(port))` — VERIFIED
- `HttpShellService.java:1466` — 模板字面量，其中 3090 出现 **4 次**，逐处为：
  1. `"DSHA 3090 桥端点清单（BRIDGE_PROTOCOL=%s）…"` — 标题 — VERIFIED
  2. `"读屏  curl -s -H @/root/.dsh/.bridge_headers \"127.0.0.1:3090/app/ui/dump\""` — VERIFIED
  3. `"点按  curl -s -G 127.0.0.1:3090/app/ui/tap …"` — VERIFIED
  4. `"输入  curl -s -G 127.0.0.1:3090/app/ui/input …"` — VERIFIED
- 同串内 `127.0.0.1:3090` 出现 3 次、`127.0.0.1` 出现 3 次（即第 2–4 处）；`/root/.dsh/.bridge_headers` 出现 5 次 — VERIFIED
- **INFERRED（改造风险）**：`replace("3090", …)` 是无差别子串替换。把模板改成 `%s`/`%d` 占位符时，必须同时改 `tools/i18n/messages.json:25839`（zh）与 `:25840`（en）——该模板是**既有翻译键**，注释 `:1463` 明说「模板保持字节不变」。漏改会让中文帮助落到旧端口。
- `/app/version` 另有一处端口事实但不含 3090 字面量：`HttpShellService.java:1452-1458` 输出 `BRIDGE_PORT=<activePort>` 与 `BRIDGE_PORT_RANGE=<PREFERRED>-<LAST>` — VERIFIED

### 1.7 其它 host 侧 3090 出现处（i18n / 注释 / 文案）

`app/src/main/java/` 全树 3090 共 17 次：`HttpShellService.java` 14 次（`:38,:40,:49,:434,:559,:592,:593,:617,:688,:720,:742,:941` 各 1 次＝注释或 i18n 文案，`:1466` 4 次，`:1469` 1 次）+ `WebPortPolicy.java:41` 1 次 + `BridgeEndpointFiles.java:17,21,41` 与 `BridgePortPolicy` 注释 — VERIFIED（逐行计数）

`tools/i18n/messages.json`（**非** `launcherSources` 成员，见 §3）里 3090 共 **42 次**（zh/en 成对），逐行：`:276-277`（「3080（1—65535，避开 3081 / 3090）」）、`:802-803`（LAN 说明里的 3081）、`:6881-6882`（`ConfigInput.reservedPortMessage()`）、`:6889-6890`/`:26793-26794`/`:27478-27479`（「3090 桥启动失败」三组近似重复键）、`:6898-6899`/`:25740-25741`（绑定失败）、`:6906-6907`/`:25758-25759`（IPv6 附加监听）、`:7290-7291`/`:25839-25840`（`/app/help` 模板，`:25839` 含 4 次）、`:9052-9053`（token 文件写入失败）、`:11571-11572`（恢复后重置 token）、`:17547-17548`/`:25749-25750`（端口被占用）、`:14124-14125`/`:15478-15479`/`:15526-15527`（含 `127.0.0.1:3090` 且用 `token=$T` 的旧帮助片段）— 全部 VERIFIED

- **INFERRED**：`:14124/:15478/:15526` 三组在 `app/src/main/java` 与 `app/src/main/assets` 全树**找不到生产引用**（现行模板已改用 `-H @/root/.dsh/.bridge_headers`），是孤儿翻译键。改契约时不必改，但也不应据此认为「帮助里有 12 处在用 3090」。

## 2. guest 侧：消费者（穷尽）

契约常量 `3090` 在 guest 侧共出现 **5 份实现**（1 个 Python + 4 个 Node），加上 1 份浏览器侧说明与若干纯文案。
**5 份都实现同一条语义**：≤16 字节普通文件、ASCII 十进制 + 可选换行、**只有 `lstat` 真 ENOENT 才回退 3090**、
`0`/坏格式/越界/非 ASCII/带尾巴/目录/符号链接（含悬空）一律 fail-closed。

### 2.1 `app/src/main/assets/adb-shell.py`（Python，设备 shell 客户端）

- `:37` — `BRIDGE_PORT_DEFAULT = 3090`（唯一致的**默认值**字面量）— VERIFIED
- `:38` — `BRIDGE_PORT_NAME = '.bridge_port'` — VERIFIED
- `:203-264` — `read_bridge_port()`：完整契约实现，`lstat` ENOENT → `:219` 返回默认；否则非普通文件 `:222-223` 抛 `BridgeUnavailable`；`O_NOFOLLOW` 打开 `:225-226`；`stat`/大小 `1..BRIDGE_PORT_LIMIT` `:234-235`；ASCII `:252-254`；`re.fullmatch(r'[0-9]{1,5}\n?')` `:256-257`；`0` 拒绝 `:259-261`；范围 `:262-263` — VERIFIED
- `:227-229` — 悬空链接的 ENOENT **不**回退（注释明写）— VERIFIED
- `:267-279` — `bridge_credentials()` 返回 `(X-Token, port)`；端口失败包成 `policy.Blocked`，不得改试别的端口 — VERIFIED
- `:287` — `bridge_url()` = `'http://' + BRIDGE_HOST + ':' + str(port) + path`（**URL 由函数拼装，无写死端口**）— VERIFIED
- `:517-521` — 被桥拒绝时的诊断文案提到 `.bridge_port` 指向本实例 — VERIFIED

### 2.2 包装脚本：**不含端口**（ADR 已自纠，此处留证）

- `app/src/main/assets/adb-setup.sh:109-115` — 生成 `/root/dsh-bin/adb-shell`，正文只有 `exec python3 /root/.dsh/adb-shell.py "$@"`，`DSHA_ADB_SCRIPT_VERSION=21` — VERIFIED
- `app/src/main/assets/dsha-device-shell.sh:1-4` — 同样只有 `exec python3 /root/.dsh/adb-shell.py "$@"` — VERIFIED
- 结论：**两个包装脚本都不是替换点。** 端口只在它们 exec 的 `adb-shell.py:37`。

### 2.3 `app/src/main/assets/bridge-token-compat.cjs` —— **端口闸是安全边界**

- `:19` — `LEGACY_PORT=3090` — VERIFIED
- `:20` — `PORT_FILE='.bridge_port'`；`:21` — `PORT_FILE_LIMIT=16` — VERIFIED
- `:23-24` — `BRIDGE_PORT_MIN=3090`、`BRIDGE_PORT_MAX=3109` — VERIFIED
- `:27` — `BRIDGE_ROUTE=/^\/(?:app\/|device\/|exec(?:$|\/))/` — VERIFIED
- `:39-42` — `contractPort()`：`/^[0-9]{1,5}\n?$/`，不做 Unicode trim — VERIFIED
- `:44-67` — `filePort()`：`lstat` 的 ENOENT → `LEGACY_PORT`（`:53`）；`O_NOFOLLOW` + `fstat` + `size 1..16`（`:57-60`）；任何异常 → `null`（fail-closed）— VERIFIED
- `:70-75` — `browserPort()`：`__DSHA_BRIDGE_PORT__` 未定义才回退 3090；注入过但非法 → `null` — VERIFIED
- **安全边界本体** `:133-144`（`rewriteWithPort`）：
  - `:137` `addressedToUs = (typeof port === 'number' && numeric === port)`；`:138` `inRange = numeric >= 3090 && numeric <= 3109`
  - `:141` `if (!addressedToUs && !(inRange && request.bridgeRoute)) return null;` → 范围外的回环请求（`3080` 的 dsh Web 鉴权、`3081` 的 LAN 鉴权）**原样放行，一字节不碰**
  - `:144` `if (!addressedToUs) throw unavailable();` → 已知桥请求但端口不是本实例 → **拒绝，绝不换端口重放**
  - `:146` token 必须匹配 `/^[A-Za-z0-9_-]{1,128}$/`，否则 `BRIDGE_CREDENTIAL_INVALID`；`:148` 已有 `X-Token` 且不一致 → `BRIDGE_CREDENTIAL_CONFLICT`；`:149` 把 token 从 URL 查询串**删除**并改写成 `X-Token` 头
- `:89-91` `providerRequired()` 读 `__DSHA_BRIDGE_PORT_REQUIRED__`（getter 抛错也返回 `true`，fail-closed）；`:94-114` `providerPort()` 每次现取，`PROVIDER_WAIT_MS=2000` 兜底，`1..65535` 之外一律 reject — VERIFIED
- `:159-183` `fetch` 覆写入口（`:182` 幂等标记 `__dshaBridgeTokenCompatV1`）；`:184` 导出 `{rewrite, allowedPort}` 供测试 — VERIFIED
- **改造要求**：`BRIDGE_PORT_MIN/MAX` 是**范围**契约，不是单值；若桥改到范围外端口，这个闸会**合法地拒发** token。任何「让桥用范围外端口」的方案都会静默打断 guest 鉴权。
- 浏览器侧配套（同属 `assetTrees/web-integration`）：`app/src/main/assets/web-integration/startup.js:6` — 注释声明 provider 拿不到就 reject，「绝不回退 3090」— VERIFIED

### 2.4 内置插件（4 个，端口来源全部同一模式）

| 插件 | 默认值 | 读文件 | URL 拼装 |
|---|---|---|---|
| `dsh-tool-vscreen/lib/server.cjs` | `:8` `LEGACY_BRIDGE_PORT=3090` | `:33` `.bridge_port` | `:96` `'http://127.0.0.1:'+(port===undefined?bridgePort():port)+'/app/vscreen/'+route` |
| `dsh-computer-use-android/lib/server.cjs` | `:12` `LEGACY_BRIDGE_PORT=3090` | `:37` | `:70` `new URL('http://127.0.0.1:'+port+def[2])` |
| `dsh-task-notifier/lib/index.js` | `:26` `LEGACY_BRIDGE_PORT = 3090` | `:65` | `:91` `'http://127.0.0.1:' + port + '/app/notify'` |
| `dsh-status-overlay/lib/index.js` | `:39` `LEGACY_BRIDGE_PORT = 3090` | `:78` | `:85` `port ? 'http://127.0.0.1:' + port : ''` |

全部 VERIFIED。共同锚点：
- `bridgeRoot()` 取 `process.env.DSH_HOME`，空/`'/'` 时回落 `/root/.dsh`：vscreen `:11`、computer-use `:15`、notifier `:28`、overlay `:41` — VERIFIED
- 缺目录项即回退默认：vscreen `:34`、computer-use `:38`、notifier `:66`、overlay `:79`（均为 `if (value === 'missing') return LEGACY_BRIDGE_PORT`）— VERIFIED
- 每份注释都写明 3090 只是**旧默认**、`0`/坏值一律 fail-closed — VERIFIED
- `dsh-status-overlay/lib/index.js:7` — 头注释说明端口来自与 `.bridge_token` 同数据根的 `.bridge_port` — VERIFIED
- `dsh-task-notifier/package.json:3` — 描述串里写「(via 3090 bridge)」（**纯文案，无功能影响**）— VERIFIED

### 2.5 `dsh-device-shell-guide`（提示词型插件）

- `lib/index.js:12` `const LEGACY_BRIDGE_PORT = 3090;`；`:13` `BRIDGE_PORT_LIMIT = 16`；`:30` 大小/类型判据；`:51-52` `readBridgePortFile(...)` 的 `'missing'` → 返回默认 — VERIFIED
- `:60` 提示词分支「本机桥端口未知：`.bridge_port` 无效…不要猜端口」；`:63`「不要猜端口或改试别的桥」— VERIFIED
- **INFERRED**：真实端口从不进入提示词文本（只有「未知」/「正常」两条分支 + `3090` 兜底值）。`tools/test-bridge-port-discovery.mjs:34-40` 用**源码切片 + `new Function`** 取出这段构造函数，所以改动这段文本要同步改测试锚点字符串。

### 2.6 其它 guest 侧出现（非消费者）

- `app/src/main/assets/credential-paths.json:7` — `"bridgePort": ".bridge_port"`；`:11-12` 把它列进精确/前缀凭据名（护栏依据）— VERIFIED
- `app/src/main/assets/managed-package-proofs.json:343`、`runtime-descriptor.json:325` — 十六进制摘要里**偶然**含 `3081` 子串，非端口事实 — VERIFIED
- **INFERRED**：`app/src/main/assets/` 下除上述之外没有读到其它 3090/3081/3080 消费者（已按 `*.py,*.cjs,*.js,*.mjs,*.sh,*.json,*.html` 扫描 `app-integration/`、`web-integration/`、`recovery-web-integration/`、`runtime-fs/`、`session-compat/`、`client-combo-cache/`、`runtime-python/`）。

**实测旁证（本容器）**：宿主 `/app/version` 返回 `APP_CODE=165`，且 `/root/.dsh/.bridge_port` **不存在** —— 这正是「旧版本不写契约文件 → guest 回退 3090」的现实样本；本容器里的桥跑在 3090 上。VERIFIED

## 3. runtimeId 成员分类

契约文件：`app/src/main/assets/managed-runtime-inputs.json`（549 行；`assetFiles` 34 / `assetTrees` 3 / `launcherTrees` 4 / `launcherSources` 150 / `generatorSources` 17 / `installs` 65）。VERIFIED

| 文件 | 归属 | 结论 |
|---|---|---|
| `assets/adb-shell.py` | `installs`（`:…` 65 项之一） | **IN** |
| `assets/bridge-token-compat.cjs` | `installs` | **IN** |
| `assets/adb-setup.sh` | `installs` | **IN** |
| `assets/dsha-device-shell.sh` | `installs` | **IN** |
| `assets/credential-paths.json` | `installs` | **IN** |
| `assets/web-integration/startup.js` | `assetTrees`（`web-integration`） | **IN** |
| `builtin-plugins/dsh-tool-vscreen/lib/{index.js,server.cjs}` | `installs` | **IN** |
| `builtin-plugins/dsh-computer-use-android/lib/{index.js,server.cjs}` | `installs` | **IN** |
| `builtin-plugins/dsh-task-notifier/{package.json,lib/index.js}` | `installs` | **IN** |
| `builtin-plugins/dsh-status-overlay/{package.json,lib/index.js}` | `installs` | **IN** |
| `builtin-plugins/dsh-device-shell-guide/{package.json,cordis.patch.yml,lib/index.js}` | `installs` | **IN** |
| `util/BridgePortPolicy.java` | `launcherSources` | **IN** |
| `util/BridgeEndpointFiles.java` | `launcherSources` | **IN** |
| `util/DshAuthUrl.java` | `launcherSources` | **IN** |
| `util/CredentialPaths.java` | `launcherSources` | **IN** |
| `HttpShellService.java` | `launcherSources` | **IN** |
| `util/WebLaunchCommand.java` | `launcherSources` | **IN** |
| `util/WebPortPolicy.java` | —— | **OUT** |
| `util/ConfigInput.java` | —— | **OUT** |
| `util/Constants.java` | —— | **OUT** |
| `LanProxyService.java` | —— | **OUT** |
| `util/LanListenerStatus.java` | —— | **OUT** |
| `util/WebPageScripts.java` | —— | **OUT** |
| `tools/i18n/messages.json` | —— | **OUT** |

（全部为本次程序化比对结果，VERIFIED。）

**成员语义与必做动作**（VERIFIED）：
- 身份算法：`tools/prepare-runtime-descriptor.py:33-43` —— `inputs` = 每个 asset 路径的 SHA-256；`launchers` = 每个 launcher 路径的 SHA-256；`contract = {baseVersion, dshVersion, launcherContract, bridgeProtocol, launcherInputs, inputs, dataRead, dataWrite}`；`runtimeId = sha256(canonical_json(contract))` — VERIFIED
- `runtimeId` 写入 `app/src/main/assets/runtime-descriptor.json`（`prepare-runtime-descriptor.py:47-48`）— VERIFIED
- **新增 `util/RuntimeEndpoints.java` 必须加入 `launcherSources`**（`tools/runtime_input_contract.py:70-71` 按该列表逐个 digest；`launcher_paths` 顺带把 `generatorSources` 以 `generator:<path>` 前缀并入，`:80-81`）— VERIFIED
- **INFERRED 风险（高）**：`Constants.java` 与 `WebPortPolicy.java` 都**不在** `launcherSources`，但 `BridgePortPolicy.java:23` / `BridgeEndpointFiles.java:41` 引用 `Constants.SHELL_BRIDGE_PORT`，`HttpShellService.java:51` 引用 `BridgePortPolicy.PREFERRED`。Java 把 `static final int` **编译期内联**，所以「改 `Constants.java` 里的端口数值」**不会改变 `runtimeId`**，却会改变 `launcherSources` 里那些类的**编译行为**。改造若把默认值搬进 `Constants.java`（或让 `RuntimeEndpoints` 从它取值），必须同时把该文件纳入契约，否则 `runtimeId` 不再代表真实启动行为。
- 成员集合是**显式列表**，没有任何「目录内文件自动入册」的全量校验（`verify-runtime-descriptor-source.py:53` 的 `MEMBER_SET` 是「描述符 == 契约算出的集合」，不是「契约覆盖某个目录」）— VERIFIED

## 4. 重算 runtimeId 与跑受管 RuntimeTrial 的可执行步骤

### 4.1 前置资产：本容器**齐备**（与 ADR 担心的相反）

| 必需输入 | 本容器状态 |
|---|---|
| `app/src/main/assets/offline-rootfs.bin` | **PRESENT** 85,783,057 B — VERIFIED |
| `app/src/main/assets/dsh-runtime.bin` | **PRESENT** 122,692,708 B — VERIFIED |
| `app/src/main/assets/ubuntu-tools.bin` | **PRESENT** 29,919,475 B — VERIFIED |
| `dsh-runtime.inputs.json` / `ubuntu-tools.inputs.json` / `ubuntu-tools.layout` / `ubuntu-tools.manifest.json` / `offline-rootfs.version` | **PRESENT** — VERIFIED |
| `dsh-runtime.bin` 归档是否「当前」 | **是**：`archive_sha256` == 实际 SHA-256 且 `inputs` == 当前 recipe — VERIFIED |
| 工具链 | Node `v24.19.0`、Python `3.12.3`、JDK `17.0.20.1`（`/usr/lib/jvm/java-17-openjdk-arm64`，**`JAVA_HOME` 未设置**，必须显式传 `--jdk`）— VERIFIED |
| npm registry 可达 | 是（`npm ping` → PONG）— VERIFIED |

→ **结论：ticket #10/#11 在本容器内是可推进的**（前提是 §4.3 的夹具先建好，且遵守「宿主构建/测试串行」的仓库纪律）。

### 4.2 重算并校验 runtimeId（只读，已在本容器实测通过）

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
python3 -B tools/verify-runtime-descriptor-source.py       # 期望 {"checkedAssets":115,"checkedLaunchers":307,"deferredArchives":[]}
python3 -B tools/prepare-runtime-descriptor.py --check      # 期望打印 runtime descriptor: <64位hex>，不相等则报错
```

- 本次实测输出：`{"checkedAssets": 115, "checkedLaunchers": 307, "deferredArchives": []}`，`runtimeId = 35112031026a2a4b2834102122e79236d7037649ac40bc71e6db45bafac90e0f` — VERIFIED
- 改了 `launcherSources` / 资产后，先 `python3 -B tools/prepare-runtime-descriptor.py --write` 再审阅 `runtime-descriptor.json` 的 diff；`--check` 只比对不写。两脚本都必须**在 `tools/` 的相对导入下运行**（`runtime_input_contract`、`source_text` 是同级模块；直接 `python3 tools/xxx.py` 可用，但从别处 `import` 会 `ModuleNotFoundError`）。— VERIFIED
- 隔离的契约回归：`python3 -B tools/test-runtime-input-contract.py`、`python3 -B tools/test-runtime-descriptor-inputs.py`、`python3 -B tools/test-runtime-descriptor-source.py` — VERIFIED（manifest stage=fast）

### 4.3 跑受管 RuntimeTrial（宿主机可跑的那一半）

**必须先建夹具**（`app/build/test-runtimes/` 目前**不存在**，`DSHA_TEST_RUNTIME` / `DSHA_TEST_MANAGED_RUNTIME` 均未设置）— VERIFIED：

```bash
cd /root/Documents/deepseek-harness/default-workspace/DSHA
python3 -B tools/prepare-test-runtime.py                 # 默认 --platform host；本机=aarch64/linux，等价
# 然后（任一）
DSHA_TEST_RUNTIME=<raw> DSHA_TEST_MANAGED_RUNTIME=<managed> node tools/test-runtime-trial.mjs <raw>/node_modules
# 或统一入口
python3 -B tools/run-host-tests.py --stage runtime --only tools/test-runtime-trial.mjs --jdk /usr/lib/jvm/java-17-openjdk-arm64
```

- `prepare-test-runtime.py:38-51` 用 `npm ci --ignore-scripts --no-audit --no-fund --os=<os> --cpu=<cpu>` 装全新 `raw`，再 `:62` 复制成 `managed` 并打补丁；`:52-57` 会校验锁文件/版本，并**默认拒绝**过期归档（`ARCHIVE_INPUTS_STALE`，本容器归档是当前的所以不需要 `--allow-stale-archive`）；`--offline` 仅在 npm 缓存齐备时可用 — VERIFIED
- 写指针 `app/build/test-runtimes/current.json`（`prepare-test-runtime.py:132-133`）— VERIFIED
- `tools/test-runtime-trial.mjs:13` 取 `testRuntime('raw')`，`:91` 取 `testRuntime('managed')`；`:180-190` 报告里 **`androidRendererVerified:false`** — VERIFIED
- **诚实边界**：`test-runtime-trial.mjs` 是**桌面协议回归**（真实 JSON 存储后端 + 合成浏览器事件），头注释明写「不是 Android / Gecko 健康证明」。真正的 `runtime/RuntimeTrial.java`（739 行）签名是 `verify(Context, ProotBootstrap, BackupControl, StaticCheck)`，需要 **Android `Context` + proot 容器 + 真实设备**，本容器**跑不了** — VERIFIED
- **必然副作用 / 编排约束**：`prepare-test-runtime.py` 会写 `app/build/`（并且 `npm ci` 走网络）。仓库纪律是宿主构建与测试**串行**，且父 agent 正在并发提交 —— 执行前应先确认没有并行的 Gradle/宿主测试。— INFERRED（纪律来自 `AGENTS.md`/`BUILD.md`）
- **不在本票范围但要知道**：`app/build/` 下已有多个 `sunsetrne-*` 构建树，说明本容器**做过程序集构建**；`tools/ci-assets.py:13` 的 `NAMES` 正是 9 个离线输入（含 `pnpm-runtime.bin`、`python-support.bin`、`glibc-python.tar.gz`、`adb-wheels.tar.gz`），本容器全部 PRESENT — VERIFIED

## 5. 既有测试已经锁住了什么

全部为本次**实际运行**结果（VERIFIED）。

**宿主脚本测试**

| 测试 | 本容器实测 | 锁住的不变式 |
|---|---|---|
| `tools/test-bridge-endpoint-native.py` | **OK (20 tests)** | 编译 `BridgePortPolicyTest`+`BridgeEndpointFilesTest`；额外用 JDK AST 证明 `HttpShellService.bindAndServe` 里 `publishReadyEndpointFor` + `LIFECYCLE.publish` + 就绪赋值**同处一个 `synchronized (LIFECYCLE)`** 临界区（正 fixture 通过、2 个负 fixture 被拒）；并对 `HttpShellService`/`RootBootstrapCard` 做 javac 类型检查 |
| `tools/test-bridge-port-discovery.mjs` | **PASS／234 断言** | 五个 guest 消费者（computer-use / vscreen / overlay / notifier / guide）的 `.bridge_port` 契约：≤16 字节、ASCII 完整内容、普通文件、Unicode 空白、填充+垃圾尾巴、悬空符号链接、0 值全部 fail-closed 且**零真实 fetch**；只有真 ENOENT 回退 |
| `tools/test-bridge-token-compat.mjs` | **PASS** | 仓库字节（非部署副本）：已知桥请求在端点不可用时 Promise reject 且底层 fetch 零调用；token 不进 URL/日志/异常；`3080` dsh Web 鉴权、`3081` LAN 鉴权、非回环、https、POST **原样放行**；`__DSHA_BRIDGE_PORT_REQUIRED__` + provider 每请求现取、并发隔离、`0`/reject/超时/缺失 fail-closed、不回落 3090；真 `startup.js` provider 与 Gecko 内容脚本往返 |
| `tools/test-adb-flow.py` | **OK (36 tests)** | `adb-shell.py` 的坏负载矩阵（`b'0'`、`b'65536'`、`b'70000'`、`b'abc'`、空、`b' 3090 3091 '`、`b'+3090'`、`b'3090\n3091\n'`、非 ASCII、40 位数字）全部拒绝；悬空符号链接不回退；**文件缺失才回退 3090**；`http://127.0.0.1:3090/device/execute?...` 只把 token 放头部；换端口后 URL 跟随；`bridge_url('/device/execute', 3090)` 直传 |
| `tools/test-web-bridge-port.py` | **PASS／32 checks** | 生产 `ui/WebPageScripts` 生成的桥端口接线：正式页在 shim 之前注入**实际**端口快照并声明 `REQUIRED`；`activePort()==0` 时按 0 显式注入、shim 见 0 拒绝；桥换端口后重新生成即反映新值；应急页不含任何桥字节；原生两条通道（`DshaBridgePort` / Gecko `bridge-port-request`）按源码契约核对只在同服务/主框架/当页代次门禁内回一个端口数字 |

**JVM 单测**

| 测试类 | 用例数 | 锁住的不变式 |
|---|---|---|
| `util/BridgePortPolicyTest` | 6 | 首选被占仍绑下一个空闲回环端口；**首个成功即停、绝不越过它探测**；范围耗尽报不可用而**不**绑别处；非「端口占用」的失败不被伪装成冲突；非法范围在任何 bind 之前被拒；**契约范围避开公众已知端口**（`contractRangeStaysOffPublicKnownPorts`） |
| `util/BridgeEndpointFilesTest` | 14 | `0`/实际端口以精确十进制字节发布；**缺失 ≠ 不可用**且 `LEGACY_DEFAULT` 不是新的回退结果；`0`/坏内容永不成为端口；端点路径是目录时拒绝且不覆写；数据根缺失什么都不发布；写 IO 失败与读回不一致都失败；**旧代次不能擦除新代次已发布的端点**；`publishIfCurrent` 在代次过期时一个字节都不写；**两个实例不共享端口契约也不共享 token**；就绪发布要求同根 token+headers；无根时报不可用且不写；写/读回失败 fail-closed；**数据根切换后把活动端口重发进新根** |
| `util/WebPortPolicyTest` | 5 | 保留首选端口；只复用可用的缓存端口；探测已占用监听器时不连接、不打扰它；探测失败不冒充端口冲突；**动态端口只来自无歧义的官方 loopback 启动行**；能识别 Web 绑定失败而不误判其它插件错误 |
| `util/ConfigInputTest` | 3 | 可用端口（含 `" 3080 "` 去空白）；`3081`/`3090`/`0`/`-1`/`65536`/`""`/`"x"`/超长/`"30.80"` 一律抛错**绝不静默回落**；**保留端口判据只有一份实现**——断言 `Constants.LAN_BRIDGE_PORT==3081`、`Constants.SHELL_BRIDGE_PORT==3090`、`ConfigInput.reservedPort` 对两者为真、对 `{1,1024,3080,3089,3091,65535}` 为假 |

（`BridgePortPolicyTest` + `BridgeEndpointFilesTest` 合计 20 个用例，即上表实测的 `OK (20 tests)`；`WebPortPolicyTest` + `ConfigInputTest` 合计 8 个用例，即另一次运行实测的 `OK (8 tests)`。）

**已知测试盲区（改造前必须补）** — INFERRED：
- 没有任何测试断言 `WebPortPolicy.reserved()` 与 `ConfigInput.reservedPort()` **一致**；两份实现目前可以各自漂移而全绿。
- `BridgePortPolicyTest.contractRangeStaysOffPublicKnownPorts` 锁的是「范围避开公众端口」，**不**锁 `PREFERRED==3090` / `LAST==3109` 的具体数值。
- `ConfigInputTest.reservedPortRuleHasOneImplementation` 反过来把 `Constants` 的两个数值**钉死**在 3081/3090：若把默认值搬去 `RuntimeEndpoints`，这个用例必须先改。
- `tools/host-tests.manifest.json` 里**没有**这 4 个 JVM 类的独立条目（它们经 `test-bridge-endpoint-native.py` 间接跑），改这些类时不能只依赖 `--only` 过滤。
- LAN 3081 **完全没有**回退/契约测试（`LanProxyService.LAN_PORT` 是 `static final`，且 `LanProxyService.java` 不在 `launcherSources`）。

## 6. 未能确定 / 未核验

- `tools/device-policy-device-audit.py:95` 是否仍断言 `BRIDGE_PROTOCOL=2`（ADR 的附带发现）—— 未读该行。
- `util/LanListenerStatus.java` 的 `reserved` 行为、LAN 引入回退会牵动哪些类 —— 只核到 `:11`/`:83` 注释提到 3081，未逐行读。
- `messages.json:14124/15478/15526` 三组孤儿帮助片段是否被 `tools/`、`docs/` 下任何生成器引用 —— 只搜了 `app/src/main`。
- `prepare-test-runtime.py` **实测未执行**（会写 `app/build/` 且走 npm 网络，父 agent 在并发提交）；§4.3 的夹具生成与 `test-runtime-trial.mjs` 通过**未经验证**，只核到入口与前置条件。
- `runtime/RuntimeTrial.java` 的失败码/回执字段全清单 —— 只读了类头与 `--port 0` 行，未通读 739 行。
- 手机端部署副本里的 3090 出现 —— 不在本容器可核范围。
