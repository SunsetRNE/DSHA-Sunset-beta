# 真 chroot（L1）路线状态卡

面向 `docs/ROADMAP.md` 里那句「条件允许时上 chroot」。本卡只记录**判定与判据**，不含实现。

## 1. 三层定义

| 层 | 形态 | 依赖 | 现状 |
|---|---|---|---|
| L0 | proot / proroot（现有） | 无 root；app 私有目录 + `nativeLibraryDir` 执行 | 已交付（`ContainerRuntime` 抽象） |
| L1 | 真 `chroot` + `mount --bind`，由 root 侧宿主驱动 | root + mount 权限 | **权限面已实测通过，exec 链路待复验** |
| L2 | L1 + mount/net/uts namespace | root + 内核 namespace | 本机具备（缺 PID/USER/IPC ns） |

## 2. 已证与未证

| 项 | 状态 | 判据 |
|---|---|---|
| `mount --bind` 可用 | ✅ 已证 | `BIND_OK` |
| 在 mount namespace 内 `mount -t proc` 可用 | ✅ 已证 | `NSMOUNT_OK`，且 umount 后宿主无残留 |
| `unshare -m` / `unshare --net` 可用 | ✅ 已证 | 无输出（无 EPERM） |
| `sys_chroot` 类 capability 有策略先例 | ✅ 已证 | rezygisk 模块 `allow zygote zygote capability sys_chroot` |
| `chroot(2)` 本身是否被拒 | ✅ 已证未被拒 | 报错为 execve 的 ENOENT，而非 `Permission denied` / `Operation not permitted` |
| **被 chroot 后能否 exec 动态链接二进制** | ❌ **未证** | 上一条命令失败在缺 `/system/bin/linker64` |
| 静态二进制能否在 chroot 内执行 | ❌ 未证（`ksud` 通常静态，未测） | 见 §3 路线 B |

**上一次的判定错误已修正**：最初把 `chroot: exec /bin/toybox: No such file or directory` 记成「chroot 不可用」是**验证件选错**（用了动态链接的 toybox，`PT_INTERP` 指向 `/system/bin/linker64`，chroot 后该路径不存在）。

## 3. 修正后的验证（尚未执行）

```bash
T=/data/local/tmp/chroot-probe
su -c "rm -rf $T; mkdir -p $T/bin $T/proc $T/system/bin $T/system/lib64; cp /system/bin/toybox $T/bin/"

# 0) 先分清「文件本身能否 exec」（/data 的 W^X 因素）与被 chroot 后的差异
su -c "$T/bin/toybox echo HOST_EXEC_OK"          # 期望 HOST_EXEC_OK

# 1) 取真实解释器路径
su -c 'grep -ao "[^ ]*linker64" /system/bin/toybox | head -1'

# 2) 路线 A：把解释器与 libc 一起放进新根
su -c "cp /system/bin/linker64 $T/system/bin/; \
       cp /system/lib64/libc.so $T/system/lib64/ 2>/dev/null; \
       cp /system/lib64/libm.so  $T/system/lib64/ 2>/dev/null; \
       cp /system/lib64/libdl.so $T/system/lib64/ 2>/dev/null"
su -c "chroot $T /bin/toybox echo CHROOT_OK || echo CHROOT_DENIED"

# 3) 路线 B：静态二进制（ksud 通常为静态 Rust 二进制，未实测）
su -c "cp /data/adb/ksud $T/ksud; chroot $T /ksud -V && echo STATIC_CHROOT_OK || echo STATIC_CHROOT_DENIED"

su -c "rm -rf $T"
```

判定分支：

| 结果 | 结论 |
|---|---|
| `CHROOT_OK` 或 `STATIC_CHROOT_OK` | L1 成立，可进入"root 侧宿主 + bind + chroot"的设计 |
| 两条都 DENIED 且 `dmesg | grep avc` 有拒绝 | 需要补 `sepolicy.rule`（形状见 [design.md](design.md) §4），补完重测 |
| 两条都 DENIED 且无 avc | 回到 L0；不要在 L1 上继续投入 |

## 4. L2 的硬约束（本机实测）

- `CONFIG_PID_NS`、`CONFIG_USER_NS` **未设置**，`/proc/self/ns/` 无 `ipc` ⇒ 做不了 PID 隔离，也做不了无特权/嵌套容器。
- 可隔离的只有 **mount / net / uts**。
- 实际影响：容器内进程在宿主 PID 视图可见 ⇒ "杀干净"不能靠 PID ns 兜底，仍必须走既有的 pid 文件 + 出生身份核验（见 `AGENTS.md` 的停止 trap 段）。
- `CONFIG_TMPFS_XATTR` 未设置 ⇒ 若用 overlayfs 做"只读基础层 + 可写上层"，**upperdir 必须落在 `/data`（支持 xattr）**，不能放 tmpfs。
- `CONFIG_SQUASHFS` 未设置 ⇒ 不能像某些方案那样用单文件 squashfs 做只读层。

## 5. 与 L1 相关的收益/代价（与 L0 对照）

| 项 | L0（proroot 等） | L1（真 chroot） |
|---|---|---|
| 是否需要 root | 否 | **是** |
| 去掉 `--link2symlink` 模拟 | 需要（`ContainerRuntime` 注释：仅当文件系统不支持硬链接时才需要，而它会破坏 `dsh write` 工具） | **可以去掉**，硬链接语义原生 |
| syscall 开销 | proroot 已是零 ptrace | 更低（但相对 proroot 增量有限） |
| 生命周期 | app 进程内，现有核验机制可用 | 需要 root 侧宿主（模块 `service.sh` 或按需 `su`），需自建身份核验 |

## 6. 状态

| 项 | 值 |
|---|---|
| 当前层 | L0 为默认；L1 权限面已证，exec 链路未复验 |
| 阻塞点 | 无（只是验证未跑） |
| 前置条件 | 设备 root（已具备）；`/data` 可写（已具备） |
| 下一步 | 跑 §3；若通过则进入 design.md §4 的 root 侧宿主设计 |
