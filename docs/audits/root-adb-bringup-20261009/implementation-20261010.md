# Root 自举 ADB：实现与主机交付记录（2026-10-10）

本轮衔接既有 root 支持评估和工作树，实现、复核并修正五个固定自举动作、独立原生授权、密钥所有权事务、双栈防护及设备授权页接线。全过程未调用 Android 设备接口、未运行设备命令、未安装或生成 APK，也未创建持久 root 模块。历史 TB321FU 证据不计入本轮验收。

## 入口与授权

[RootShellMain](../../../app/src/main/java/com/deepseekharness/app/RootShellMain.java) 只接受 `adb-bootstrap` 固定字段与 `authorize/revoke/narrow/open/close/status`。由当前安装 APK、真实 UID 0、包名及 UID 核验进入 [RootBootstrapNative](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapNative.java)，再驱动 [生产事务执行器](../../../app/src/main/java/com/deepseekharness/app/RootBootstrapExecutor.java)。请求不携带任意命令、设备路径、属性名或公钥正文；Java/Python 通用命令白名单及普通通道选择策略未放宽。

[独立授权](../../../app/src/main/java/com/deepseekharness/app/core/DeviceGrants.java) 默认关闭，不随用户归档或便携设置恢复。原生 UI 先确认授权，再确认每次动作。授权代理从任务提交时创建，覆盖可能较慢的客户端准备过程；绑定一次动作、随机票据、原生主进程、对端 UID、网络 namespace 与授权代次，最长 120 秒。关闭再开启许可不能让旧请求复活；应用退出、过期或 IPC 失败停止后续写入。

| 动作 | 必需原生许可 | 行为与边界 |
|---|---|---|
| AUTHORIZE | Root、自举、ADB | 验证本机客户端公钥；排除其它 adbd TCP/TLS 入口；先完成双栈保护，再提交共享公钥表。已有同钥不认领。 |
| NARROW | Root、自举 | 两族都可查询，规则必须在 INPUT 首位；拒绝接管外部监听。 |
| OPEN | Root、自举、ADB | 先验证公钥、监听范围和双栈保护；只用固定 5555；开启后复核并进行严格回环会话验证。 |
| CLOSE | Root | 只关闭同一开机代次、本应用拥有的监听；属性提交与重启发送分别记账，未知重启不重发；确认无监听/存活连接后才移除有所有权记录的规则。 |
| REVOKE | Root | 只删除唯一、精确匹配的所有权行；完整证据证明 adbd 已停止时仍可撤销，不要求重新开启 ADB；运行中则核验开机代次、PID 和出生身份。 |
| STATUS | Root | 只读，不建立授权、不补规则、不续做中断事务。 |

关闭自举或 ADB 开关仍可执行 CLOSE/REVOKE/STATUS；Root 许可也关闭后不会暗中提权清理。关闭开关不代表设备公钥、监听或规则已清理。

## 本轮修正的边界

1. **未知结果不重放。** 关闭新增 `CLOSE_RESTART_SENT` 持久阶段；撤销在 `REVOKE_RESTART_SENT` 后发现公钥重新出现时保留原阶段并拒绝再次删除/重启。观察到旧进程或监听已退出后才提交清理；不能把“发送前写下意图但尚未收到回执”当成未执行。
2. **待核验动作与最后请求分开。** 控制器保存独立 unresolved 状态；失败或不完整的 STATUS、预检拒绝、结果落盘失败均不能清掉之前的未知动作。新增写动作同时受控制器和特权事务日志门禁约束。未决事务可检查或显式清理，不自动恢复整张旧授权表。
3. **共享公钥覆盖所有 adbd 入口。** 不再只看 5555：检查 service/persist/TLS 配置，并用出生身份绑定的 daemon socket inode 与 IPv4/IPv6 proc 表交叉核对。其它 TCP 监听或无线 TLS 入口会拒绝授权/开启。属性不是实际监听证明；不宣称这些观测构成对外部 root 写者的原子锁。
4. **固定端点真实固定。** [ADB 客户端](../../../app/src/main/assets/adb-shell.py) 新增仅原生调用的 `--bootstrap-verify` 模式：仅此一个参数，固定 `127.0.0.1:5555`、无 su 的只读 `id` 和 8 秒预算，仍需原生 ADB 策略计划。只连接一次，不扩展地址、不走 mDNS 或历史端口，连接失败/执行未知直接返回。普通命令的七元组参数契约与连接行为保持。
5. **公钥事实不能由日志单独宣称。** [公钥逻辑](../../../app/src/main/java/com/deepseekharness/app/util/AdbAuthorizedKeys.java) 校验 Android RSA 结构、规范 Base64 和 Montgomery 字段，并识别行首空白的已有同钥。重复同钥或所有权注释变化不会报告 OWNED。公钥、监听、防护独立取证，某一项失败不抹去其它观测。
6. **文件提交边界。** 固定路径、进程间锁、无软链接/硬链接、owner/mode/label 检查、备份与摘要、临时文件 fsync、原子替换和目录 fsync 保留。文件以非阻塞标志打开后核对类型；最终授权/网络复核放在原件字节与 inode 元数据再次比对之前，避免慢 IPC 扩大提交窗口。外部系统配对服务不一定遵守本应用锁，因此仍不具备全系统 CAS；失败保留证据而不覆盖其它人的新公钥。

## 原生 UI 与 Standard/Low

[应用级控制器](../../../app/src/main/java/com/deepseekharness/app/core/AdbBootstrapController.java) 只保存 Application 和结构化快照；单任务、不排队旧请求。请求记录、工作线程调度和授权代理创建失败时释放占用并关闭代理。

[原生卡片](../../../app/src/main/java/com/deepseekharness/app/ui/RootBootstrapCard.java) 分开显示授权、最后请求、公钥、监听、防护及观察时间。页面恢复从控制器和当前授权重绘；旋转、语言切换不发送设备动作。暂停或销毁页面时关闭旧确认；切换 Root/ADB 授权使旧确认失效。自举开关关闭 View 状态自动保存，避免旧勾选值在重建时触发授权监听；已有通道回调继续核对 view 和请求代次。中文/英文状态在显示时生成，共用现有卡片/按钮主题和可滚动对话框。

两版使用同一份 Java 17 功能代码，Standard minSdk 30、Low minSdk 23。修复 `OsConstants.O_CLOEXEC` 公开字段的 API 27 限制，改用项目既有的 Linux/Bionic 固定标志，保留 close-on-exec 行为。进程等待继续使用有界收集器；API 23–25 文件标签走系统 SELinux 接口反射，API 26+ 走 xattr，失败即停止，不修改 SELinux 策略。Low 静态兼容验证与真实 Android 6/7 的 ROM 行为是不同范围。

## 可重复主机验证

[主机入口](../../../tools/test-root-support.py) 现在运行真实生产事务代码，替换的仅是命令、文件存储、授权和时钟 I/O；不会启动 Android、shell、网络或真实设备文件操作。使用真实 JUnit 4.13.2、Hamcrest 1.3、JSON 20240303；缓存内容按摘要核验，不伪造测试框架。JSON 依赖来自 [Maven Central](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar.sha1)，仅加入 testImplementation。

| 检查 | 本轮结果 |
|---|---|
| 定向 JUnit | 89 项通过，包含 9 项生产执行器测试、4 项授权生命周期测试，以及策略、身份、公钥、日志和既有白名单测试 |
| Standard / Low Gradle 定向单测 | 各 89 项通过，失败/错误/跳过均为 0；两版运行同一组 11 个测试类 |
| Standard / Low Java 编译、Release Lint | 两版 Debug/Release Java 编译通过；Release Lint 均 0 错误，警告分别 235 / 160 项，包含低 API 标签反射提示 |
| 两版全量 JVM 单测 | 均在既有 ConfigurationSnapshotsTest.configurationOperationsPast65And129RemainAvailable 运行期间达到每任务 240 秒上限；全量未完成，不能记作通过 |
| 固定端点主机测试 | 15 项通过，执行当前脚本函数及原生桥的实际命令，假连接器覆盖失败与未知不回退 |
| 原生设备策略 dry plan | 16 项通过，通用守卫保持拒绝面 |
| Python 设备策略 | 27 项通过 |
| 既有隔离 ADB 流程 | 29 项通过 |
| UI 国际化测试与扫描 | 9 项通过；新增缺陷 0、过期豁免 0；既有一项翻译拼接债务保留 |
| 主机 manifest | 127 项登记核验通过 |
| 受管来源证明 | 108 份可用资产、291 份 launcher 输入核验；三份缺失离线归档列为 deferred |

生产执行器回归覆盖：五动作真实顺序、只读 STATUS 零写入、另一端口/仅 IPv6 暴露、第二族失败、关闭/撤销跨实例中断、不重发已未知重启、公钥重现、daemon 已停止、ps 失败、授权在提交前撤销、所有权冲突和网络清理不抹掉公钥 pending。它不执行 Android `Os.rename/fsync/xattr`、Binder、LocalSocket 凭据或真实 netfilter；这些仍是设备验收项目。

复跑命令：

```bash
python3 -B tools/test-root-support.py
python3 -B tools/test-root-bootstrap-endpoint.py
python3 -B tools/test-native-device-policy.py
python3 -B tools/test-device-shell-policy.py
python3 -B tools/test-adb-flow.py
python3 -B tools/test-ui-i18n.py
python3 -B tools/check-ui-i18n.py
python3 -B tools/run-host-tests.py --check-manifest
python3 -B tools/verify-runtime-descriptor-source.py
```

Android 编译、两版 Gradle 单测、Release Lint 的最终结果与完整命令见[本轮验证回执](validation-20261010.json)。完整无排除命令实际停止在缺失 `dsh-runtime.bin` 的资产门禁。代码验证明确排除以下四项，不能算完整 APK 构建通过：

```bash
bash build.sh :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease \
  -x :app:prepareStandardAssets -x :app:prepareBackupAssets \
  -x :app:prepareRuntimeDescriptor -x :app:prepareRecoveryAssets
```

本机为 arm64 Linux 容器，使用已有 JDK 17/API 37.0 SDK、arm64 aapt2 和仓库固定 AIDL 等价产物 shim；Gradle 9.3.1 及隔离缓存路径、离线模式和测试预算在回执中展开。普通 Gradle 缓存缺 Low 依赖、隔离 wrapper 下载超时和首次测试夹具 checked-exception 编译错误均留在构建日志，不混作通过结果。

## 交付与未验证范围

[来源描述符](../../../app/src/main/assets/runtime-descriptor.json) 已更新可用源码/资产摘要及 runtimeId；`dsh-runtime.bin`、`offline-rootfs.bin`、`ubuntu-tools.bin` 缺失，保留接手时自洽描述符中的历史归档摘要，未声称重新核验它们。完整构建需要准备真实归档并重新生成证明；不能用占位归档跳过门禁。

全仓源码卫生检查还报出接手前的四份 CRLF 文件和一个未钉住的历史工作流，列在回执中；本轮未为通过无关检查而更改这些字节。原有工作区的 CI、恢复锁、国际化基线及历史证据保留；历史 M1 准备包已明确标记为历史入口，ORIGINAL 的 15 份原件另行校验。

未完成设备/发布验收：实际 Root 管理器、Android 6/7、API 23–25 反射标签、真实 IPv4/IPv6 外网连通/拒绝、厂商 netfilter 重建、并发系统配对、掉电恢复，以及中英文/日夜/短屏/1.3 倍字体和旋转截图样式矩阵。没有发布 APK、签名核验或覆盖安装；没有更改当前设备的 Root、ADB、公钥、端口或防火墙。

后续清理只能依据所有权和实际状态关闭本应用监听、选择性撤销本应用公钥并清理本应用规则；不能还原整张共享表或删除未知记录。源码回退不等于设备状态已回退。
