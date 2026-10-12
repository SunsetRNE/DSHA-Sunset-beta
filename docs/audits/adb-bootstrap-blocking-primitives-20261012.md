# 自举链无界阻塞原语的平台实证（Android 16 / SDK 36 · KernelSU）

**结论先行**：自举链的两个阻塞原语里，**客户端 `connect()` 根本不是问题源**，服务端 `accept()` 的无界阻塞**可以被有界化，而且不需要隐藏 API**。`close()` 唤醒不了 `accept()` 这一条在本容器（Linux AF_UNIX）上**已由 syscall 级实验复现并确认**，并与 AOSP 源码路径一致。

## 0. 环境与证据边界

| 项 | 值 |
|---|---|
| 设备 | Lenovo TB321FU，Android 16 / `ro.build.version.sdk=36`，kernel `6.1.124-android14-11` |
| Root | KernelSU **Next 系**（`/data/adb/ksu`、`/data/adb/ksud`、`supervisor`、`zygiskd64`）；`id` → `uid=0 context=u:r:ksu:s0` |
| SELinux | `Enforcing`（`getenforce`） |
| 受审应用 | `com.dsh.clienu.SunsetRNE`，uid **10307**，pid 27679，`u:r:untrusted_app:s0:c51,c257,c512,c768` |

**验证分层（全文严格区分）**

- **VERIFIED(本机/设备)**：本次在设备上以只读命令实测，或在容器内以 syscall 级实验实测。
- **VERIFIED(AOSP 源码)**：取自 AOSP main 分支源码原文。
- **INFERRED**：源码 + Linux 语义推导，未在该 Android 设备上跑过插桩测试。

**容器不是 Android**：宿主 shell 无 `getenforce`/`toybox`、`/proc/1/ns/net` 不存在 —— 容器内测得的 AF_UNIX 结论一律标 INFERRED(Android)，设备侧一律走受保护的设备 shell。本次**全程只读**：未改设备、未杀进程、未装任何东西、未跑 Gradle。

**链路**（读代码确认，非推测）：`AdbBootstrapController.java:243` 建 `RootBootstrapAuthority` → 其 `serve()` 线程在 `:106` 阻塞于 `server.accept()`（许可窗口 120 s）→ 同时 `RootShell.java:179-184` 用 `ProcessBuilder(su, "-c", "CLASSPATH=… /system/bin/app_process /system/bin …RootShellMain")` 拉起 root 子进程 → 子进程回调 `RootBootstrapAuthority.check()`（`:135` connect）取许可。**所以「accept 卡住」直接卡住整条自举链**。

---

## 1. `LocalServerSocket.accept()`：超时、以及 `close()` 能否唤醒

### 1.1 accept 没有超时参数 —— VERIFIED(AOSP 源码)
[`LocalSocketImpl.accept()`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocketImpl.java) 直接调 `Os.accept(fd, null)`，即裸 `accept(2)`，**没有任何超时入参**；[`LocalServerSocket`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalServerSocket.java) 整个类只有 `accept/close/getFileDescriptor/getLocalSocketAddress`，**没有 `setSoTimeout`**。官方文档对该方法的描述就是 "Blocks until a new connection arrives"（[LocalServerSocket](https://developer.android.com/reference/android/net/LocalServerSocket)）。→ `server.accept()` 在 API 层**不可能**被设置超时。`closed`/`expires` 只在两次 accept 之间被检查，正是当前写法失效的原因。

### 1.2 `close()` 唤不醒已阻塞的 `accept()` —— VERIFIED(设备级 syscall 实验，容器)
[`LocalSocketImpl.close()`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocketImpl.java) 只做 `Os.close(fd)`：**close(2) 一个 fd，不动任何线程状态**。容器内 AF_UNIX abstract 名称实测（`gcc` 编译的 C 探针，与 `Os.accept`/`Os.close` 同形）：

```
C1 select(300ms) on listening fd -> 0 after 300ms (0=timeout, no busy spin)
C2 close(fd) returned immediately
C2 RESULT: after close()+2000ms the accept thread is STILL BLOCKED (timedjoin rc=110)
C3 re-bind same abstract name after close -> rc=-1 errno=98 (Address already in use)
```

**三条可操作推论**：① 阻塞线程永不返回（`accept4` 在可中断睡眠里，close 不产生 EINTR/EBADF）；② 该 abstract 名字仍被内核 listen 表占着，**不能立即重绑同名**；③ 每次自举失败都永久泄漏一个 daemon 线程 + 一个 fd。这与工单记录的「1.5 s 后线程仍存活」一致，也是当前 `:192` `server.close()` 的真实语义。

### 1.3 三种**可用**的有界化方案
| 方案 | 机制 | 证据 |
|---|---|---|
| **A. 给监听 fd 设 `SO_RCVTIMEO`** | `accept(2)` 在监听 socket 上遵守 `SO_RCVTIMEO`，超时返回 `EAGAIN` | **VERIFIED**：`T2 SO_RCVTIMEO: accept TIMED OUT after 513ms (rc=-1 errno=11 EAGAIN)` |
| **B. 自连接 poke** | close 时由本进程 connect 自己的 abstract 名，让 accept 立即返回；对端 uid==本应用，被 `:108` 的 `getUid()!=0` 天然拒绝 | **VERIFIED**：`T1 connect rc=0; accept RETURNED after 0ms` |
| **C. 就绪等待 + 非阻塞 accept** | 用 `Os.poll()` / `Selector` 等在监听 fd 上并带超时，就绪后再 accept | **VERIFIED**：`C1 select(300ms) -> 0`，无忙转 |

方案 A 在 API 层的落地要点：`LocalServerSocket.getFileDescriptor()` 是 **public**（API 36 `javap` 实测），而 `Os.setsockoptTimeval/Os.poll/Os.fcntlInt` 均为 `android.system.Os` 公开方法，[`Os.accept`](https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/android/system/Os.java) 文档写明语义等同 `accept(2)`（非阻塞下即 `EAGAIN`→`ErrnoException`）。**注意**：`LocalSocketImpl.setOption(SO_TIMEOUT)` 只设 `SO_RCVTIMEO`+`SO_SNDTIMEO`（**不**设 `SO_RCVLOWAT`），而 `LocalServerSocket` 无法调它 —— 需要自行对监听 fd 调 `setsockoptTimeval`，这依赖把 `LocalServerSocket.impl.fd` 取出（非公开）。若不想碰非公开结构，**方案 B 是零反射、任何 API 级别都成立的首选**。

### 1.4 一个必须澄清的死路（避免返工）—— VERIFIED(API 36 jar)
`ServerSocketChannel.open(StandardProtocolFamily.UNIX)` **在 Android 16 上不可用**：`java.net.UnixDomainSocketAddress` 不在 `platforms/android-36/android.jar` 里，官方参考页 404，且 `ServerSocketChannel` 只有无参 `open()`、`bind` 只收 `SocketAddress`。`StandardProtocolFamily.UNIX` 虽然存在于 android.jar，但没有可构造的 AF_UNIX `SocketAddress`，**因此这条路在 SDK 36 上无法成句**。

### 1.5 附带发现：`readUTF()` 的「半有界」
`:107` 的 `socket.setSoTimeout(2000)` 确实让 `:110` 的 `readUTF()` 有界（`SO_TIMEOUT`→`SO_RCVTIMEO` 作用于读，VERIFIED(源码)）。但 `close()` **不会**打断已在 `read()` 的线程，所以对端卡住时该 root 线程仍会**滞留到 2 s 超时**。这是延迟问题、不是存活性问题，修复 accept 后仍需保留这条超时。

**能不能有界化：能。** 推荐：**B（自连接 poke）为主 + A 的 `SO_RCVTIMEO` 兜底**；两者叠加后 `close()` 语义变为「立即返回且 accept 循环在下一轮看到 `closed` 退出」，不再泄漏线程/fd。

---

## 2. `LocalSocket.connect()`：有没有 connect 超时

### 2.1 公开的 timeout 参数被明确忽略 —— VERIFIED(AOSP 源码)
[`LocalSocket.connect(LocalSocketAddress)`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocket.java) 写死调 `impl.connect(endpoint, 0)`；而 `LocalSocketImpl.connect(LocalSocketAddress, int timeout)` 上方源码注释就是 **`/** note timeout presently ignored */`**，函数体只调 `connectLocal(fd, name, ns)`，**timeout 从不参与**。`Os.connect`/`Os.bind` 对 AF_UNIX 也无公开的 abstract 名字入口。官方 [LocalSocket](https://developer.android.com/reference/android/net/LocalSocket) 文档对重载参数同样没有超时语义说明 —— 只能靠源码定案。

### 2.2 实际阻塞风险：低，但不是零 —— INFERRED(源码 + Linux 语义)
abstract 名字无监听者时 `connect` 立即 `ENOENT`；名字在但不再 listen 时立即 `ECONNREFUSED`。真正的无界场景是**服务端 backlog 塞满**：当前 `serve()` 是单线程且卡在 accept，队列不被消费，恰好构成这个场景。所以「把 `setSoTimeout` 挪到 `connect` 之前」**基本无收益**（`SO_SNDTIMEO` 约束的是 write，不是 connect）。

**能不能有界化：connect 本身基本无界化必要（无界概率低且超时参数无效）。** 推荐：**不要为此改客户端顺序**；真正的修复是让 §1 的 `accept()` 有界，使 backlog 始终被消费。若要防御性处置，用已连接的写侧 `SO_TIMEOUT` 或直接在客户端加短重试预算，而不是指望 `connect` 超时。

---

## 3. Android 16 进程回收：`destroy()` / `destroyForcibly()` 何时收不掉

只读实测（未杀任何进程）：设备上存在一个 **已经 `u:r:ksu:s0` 的 `app_process`**（pid 28587），以及 **15+ 个 `u:r:ksu:s0` 的 `sh`**（多次残留，含 `[sh]` 僵尸态条目）—— 说明「`su -c` 派生的特权子进程会脱离 Java 侧句柄并被遗留」在真机上**确实在发生**。

`destroy()=SIGTERM`、`destroyForcibly()=SIGKILL`（[Process](https://developer.android.com/reference/java/lang/Process)）。**收不掉的情形**：

1. **不可中断睡眠（D 态）**：SIGKILL 对 D 态无效，只能等内核 I/O 完成 → 必须用**有界轮询 + `waitFor(timeout)`**，不能假设 KILL 必定生效。**INFERRED(内核语义，本次未在设备复现)**
2. **SELinux 域隔离**：应用侧句柄指向的只是 `su` 包装进程；`app_process` 已在 `u:r:ksu:s0`，**`untrusted_app` 域能否对 `ksu` 域发信号取决于 KernelSU 自带策略**（KernelSU 通常显式允许 app→ksu），本次**未能**在不改设备的前提下判定 → **需要上手验证**。
3. **进程句柄不含孙进程**：`su`/`ksud` 是否 `exec` 覆盖自身、是否 fork 后再 exec，决定 `destroy` 是否真能命中 `app_process`。KernelSU 的授权对话框形态还不透明 —— 若提示由 **root 管理器的独立进程**持有，则**杀掉子进程不会关闭提示，杀掉提示也不会让子进程返回**，两个方向都不成立。
4. **孤儿化**：应用进程死亡后 `app_process` 被 reparent 到 init，Java 侧句柄不再代表它；下轮启动只能靠 `ps` 侧证据发现残留（正是 pid 28587 / `[sh]` 的形态）。

**能不能有界化：部分能，需要设备验证。** 推荐：`destroyForcibly()` + `waitFor(短超时)` + 复核 `ps` 出生身份；**永不**在不核对出生身份时按 pid 补刀；把「提示仍在 / 子进程仍在」两种残留都记入状态而不是继续等。

---

## 4. `waitFor` / `exitValue` 在子进程被 root 提示阻塞时的行为

- `waitFor()` **可被 `Thread.interrupt()` 打断**（`InterruptedException`）—— 这是 JLS/Javadoc 层面的契约。**VERIFIED(文档语义)**
- `waitFor()` 无参版本**没有截止时间**：子进程活着（等提示就是活着）就一直阻塞；`waitFor(timeout,unit)` 返回 `false`，**不改变子进程状态**。**INFERRED(标准 Java;Android 同契约)**
- `exitValue()` 在进程未退出时抛 **`IllegalThreadStateException`**。源码里正是这样用的：`BoundedProcessRunner.exitCode()` 捕获该异常返回 `null`，作为「尚未退出」的判据；`RootShell.java:188-189` 用 `BoundedProcessRunner.collect(process, 65_000, 400_000, Compat::destroy)` 兜住，`:121` 在 `exitCode(process)==null` 时才 `terminate`。**VERIFIED(本仓库源码)**
- 一旦进程真正退出，退出码在后续调用中保持一致。**INFERRED**

**所以「一直 Busy」的机制是**：子进程被 root 提示挡住 → 它不是死进程，`waitFor` 只是被阻塞 → 必须靠**墙钟截止**（已有 65 s）与外部信号才能结束等待；而 65 s 之后 `Compat::destroy` 能不能真的收掉它，回到 §3 的未决问题。

**能不能有界化：能（等待侧已经是）。** 推荐：统一用「截止时间 + `waitFor(timeout)` 返回值判定」的三态（完成/超时/状态未知），**不**把「已 KILL」当作「已退出」；Android 16 上可评估 `Process.onExit()`/`toHandle()` 等公开 API 以获得更稳的终止与观测语义（**需要设备验证**，本次未测）。

---

## 5. 在平台上取 Java 线程栈（交接取证工单）

| 手段 | 实测结果 | 可用性 |
|---|---|---|
| `/proc/<pid>/task/<tid>/stack` | **VERIFIED(设备)**：`-r-------- root root`，root 可读。对 pid 27679 读出完整内核栈，例如 `unix_stream_read_generic → unix_stream_recvmsg → ____sys_recvmsg → __arm64_sys_recvmsg` | ✅ **首选**。但**只有内核栈，没有 Java 帧**；运行中的线程会显示 `[<0>]` 占位 |
| `/proc/<pid>/task/<tid>/wchan` | **VERIFIED(设备)**：38 个线程全部可读（`do_epoll_wait`、`futex_wait_queue`、`binder_thread_read`…） | ✅ 廉价的首轮「谁卡在哪」筛查 |
| `/proc/<pid>/task/<tid>/syscall` | **VERIFIED(设备)**：读出 syscall 号与参数（主线程 `22` = `epoll_pwait`） | ✅ 与 wchan 互补 |
| `/proc/<pid>/status` | **VERIFIED(设备)**：`Threads: 38`、`SigBlk: 0x80001204`、`Seccomp: 2`、`CapEff: 0`、`TracerPid: 0` | ✅ 线程数与信号掩码取证 |
| `debuggerd -b <pid>` | 二进制在 `/system/bin/debuggerd`（**无** `debuggerd64`/`crash_dump*`），但**设备 shell 策略拒绝**：`[POLICY_BLOCKED] 不认识的命令不执行：debuggerd` | ❌ **本通道不可用**。要走它只能改生产白名单（本工单不允许）或不经策略包装器 —— 二者都需单独立项 |
| `kill -3`（SIGQUIT） | **未执行**（会向他人进程发信号，工单禁止）。已在 `/proc/27679/status` 实测到该应用 **`SigBlk` 已屏蔽 SIGQUIT**，ART 惯例由 `Signal Catcher` 线程 `sigwait` 消费（该线程实测存在，pid 27698，wchan `do_sigtimedwait`） | ⚠️ 机制上可行，输出到 logcat 与 `/data/anr`（`/data/anr` 实测存在且**无新文件写入**也是可核验的）。**需在取证工单中显式批准**后使用 |
| `Thread.getAllStackTraces()` | 公开 API、应用内自取、不受 SELinux 限制；但要经桥暴露就需要**改生产代码**（本次禁止），且**无法在不改设备的前提下实测** | ⚠️ Java 帧的唯一完整来源；**需要设备验证**（或作为取证工单的插桩项） |

**一个直接可复用的既有成功样本**：应用内 `TcmReceiver`（tid 27816）实测停在 `unix_stream_read_generic`，其同名线程正走 AF_UNIX 读路径 —— 证明这套 `/proc` 取证在不触发 root 提示的情况下**当下就能跑通**。

**能不能有界化：能（内核侧），Java 帧需要一次插桩决策。** 推荐：取证工单先用 `wchan`+`syscall`+`task/*/stack` 三件套做零侵入取证（本次已验证可用），再单独决定是否引入 `kill -3` 或应用内 `Thread.getAllStackTraces()` 端点；`debuggerd` 路线先搁置。

---

## 6. 结论汇总

| # | 问题 | 判决 | 推荐技术 |
|---|---|---|---|
| 1 | `accept()` 有界化 / `close()` 唤醒 | **能** | 自连接 poke（零反射）+ 监听 fd `SO_RCVTIMEO` 兜底；**不要**指望 `close()` |
| 2 | `connect()` 超时 | **基本无必要** | 超时参数被 AOSP 明确忽略；改 accept 有界即可消除 backlog 风险 |
| 3 | `su -c app_process` 回收 | **部分能 / 需要设备验证** | `destroyForcibly` + 有界 `waitFor` + `ps` 出生身份复核；SELinux 与提示进程归属未决 |
| 4 | `waitFor` / `exitValue` | **能** | 截止时间 + `waitFor(timeout)` 三态判定；`exitValue` 的 `IllegalThreadStateException` 即「未退出」判据 |
| 5 | Java 线程栈获取 | **能（内核侧）/ Java 帧需决策** | `wchan`+`syscall`+`task/*/stack` 已实测可用；`debuggerd` 被策略挡、`kill -3` 与应用内 API 待批准 |

**没能定下来的事（不臆断，交给取证/设备工单）**

1. Android 上「`SO_RCVTIMEO` 作用于监听 fd 的 `accept`」与「`accept4` 不产生 EINTR」只在**容器 Linux** 上 syscall 级验证；Android 侧属内核通用行为，标 INFERRED，**未在设备上插桩复现**。
2. `untrusted_app` 域能否对 `u:r:ksu:s0` 子进程发信号（KernelSU Next 自带策略的具体规则）—— **未判定**。
3. KernelSU Next 授权提示由哪个进程持有、以及它是否阻塞子进程的 `exec` —— **未判定**；这决定 §3 的回收策略是否能成立。
4. `kill -3` 的实际输出位置与完整性、`Thread.getAllStackTraces()` 在本设备可运行性 —— **未执行**（均需改状态或改代码）。
5. `debuggerd -b` 的真实可用性 —— **未执行**（被设备策略拒绝，未绕过）。

**取证卫生**：`/data/adb/dsha-adb-bootstrap/state.json` 含密钥类字段（`clientKey`/`ownedLine`/`keyBackup`）。本次只读确认其存在与字段名，**未**把任何凭据值写入本文件；后续取证同样不得复制这些值。
