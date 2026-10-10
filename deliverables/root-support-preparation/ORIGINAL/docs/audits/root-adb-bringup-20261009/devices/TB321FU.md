# 机型能力档案：Lenovo TB321FU

实测档案，供跨机型比对与后续回归使用。所有条目都附判据命令；未实测的写「未知」。

## 1. 基本身份

| 项 | 值 | 判据 |
|---|---|---|
| 设备 | LENOVO TB321FU | 系统设置 |
| Android | 16 | `getprop ro.build.version.release` |
| 内核 | `6.1.124-android14-11-gd53339245194-ab13005711` | 系统设置 / `uname -r` |
| 构建类型 | `user`（`ro.debuggable=0`） | `getprop ro.build.type; getprop ro.debuggable` |
| Root 管理器 | ReSukiSU `v4.2.0-rc2 (35153/4)`，KernelSU 系 | 管理器界面 |
| su 域 | `u:r:ksu:s0` | `su -c 'cat /proc/self/attr/current'` |
| SELinux | Enforcing | `getenforce` |
| Seccomp | 过滤模式 | 管理器状态页 |
| 管理器 UID | 10281 | 管理器状态页 |

## 2. 内核能力（`zcat /proc/config.gz | grep -i <sym>`）

| 能力 | 值 | 影响 |
|---|---|---|
| `CONFIG_NAMESPACES` | `y` | 框架在位 |
| `CONFIG_NET_NS` | `y` | 可做网络隔离 |
| `CONFIG_UTS_NS` | `y` | 可改 hostname |
| `CONFIG_TMPFS` | `y` | tmpfs 可用 |
| `CONFIG_OVERLAY_FS` | `y` | 可叠层挂载 |
| `CONFIG_OVERLAY_FS_INDEX` | **未设置** | rename/硬链接场景有额外回退 |
| `CONFIG_OVERLAY_FS_METACOPY` | **未设置** | 同上 |
| `CONFIG_TMPFS_XATTR` | **未设置** | **overlay 的 upperdir 不能放 tmpfs**，必须落在 /data（ext4/f2fs） |
| `CONFIG_PID_NS` | **未设置** | 不能做 PID 隔离 |
| `CONFIG_USER_NS` | **未设置** | 不能做无特权/嵌套容器 |
| `CONFIG_IPC_NS` | 符号缺席，且 `/proc/self/ns/` 无 `ipc` | IPC namespace 不可用 |
| `CONFIG_SQUASHFS` | **未设置** | 不能挂 squashfs 单文件只读层 |

`/proc/self/ns/` 实际只有：`cgroup mnt net time time_for_children uts`。

## 3. 特权操作可用性（全部为 `u:r:ksu:s0` 域下实测）

| 操作 | 结果 | 判据 |
|---|---|---|
| `mount --bind` | ✅ | `mount --bind $T $T && umount $T` → `BIND_OK` |
| `mount -t proc`（在 mount ns 内） | ✅ | `unshare -m --fork sh -c 'mount -t proc proc $T/proc; …'` → `NSMOUNT_OK` |
| `unshare -m` / `unshare --net` | ✅ | 无输出即成功（无 EPERM） |
| `chroot(2)` | 权限通过（未最终确认 exec 链路） | 报错为 execve ENOENT 形态而非 EPERM/EACCES，详见 [chroot-l1.md](chroot-l1.md) |
| 写 `/data/misc/adb/adb_keys` | ✅ | `APPEND_OK`，label/属主/权限不变 |
| `setprop service.adb.tcp.port` | ✅ | `SETPROP_OK` |
| `setprop ctl.restart adbd` | ✅ | `CTL_OK`，adbd pid 变化 |
| `setprop service.adb.listen_address` | ❌ 不生效 | 属性回显 `127.0.0.1`，`ss` 仍为 `*:5555` |
| 写 iptables 规则 | ✅ | `-I INPUT 1 … -j DROP` 后 `-L` 显示在第 1 位（先于 `oem_in`） |
| iptables 规则持久性 | ❌ 不持久 | 重启后 `grep dpt:5555` 为空 |

## 4. SELinux 策略先例（`/data/adb/modules/*/sepolicy.rule`）

| 模块 | 关键规则 | 对本案的意义 |
|---|---|---|
| rezygisk | `allow zygote zygote capability sys_chroot`；注释 `# INFO: … KernelSU renamed su context to ksu` | `sys_chroot` 类 capability 有现成授权先例；坐实域名来源 |
| tricky_store | `allow ksu self:tcp_socket {…}` / `node_bind` / `name_connect` | `ksu` 域网络权限靠模块补，先例齐全 |
| trick_wheel | `allow * zygote unix_stream_socket { write read }` | — |
| zygisk_lsposed | `type/typeattribute lsposed_file …` | — |

本方案实测**未新增任何规则**（写 keys、setprop、ctl、iptables 全部一次通过）。

## 5. ADB 与暴露面

| 项 | 值 | 判据 |
|---|---|---|
| adbd 运行身份 | `uid=0`，`u:r:ksu:s0` | `su -c 'ps -A -o PID,UID,NAME | grep adbd'`、`ps -A -Z | grep adbd` |
| `service.adb.root` | 空（未设置） | `getprop service.adb.root` |
| `ro.adb.secure` | `1` | 无密钥客户端只能 `unauthorized` |
| `adb_keys` 初始状态 | 0 字节，`u:object_r:adb_keys_file:s0`，`1000:2000`，0640 | `ls -lnZ /data/misc/adb/` |
| `adb_temp_keys.xml` | 81 字节，条目数 0 | `grep -c "<key" …` |
| `service.adb.tcp.port` | 初始为空（不在 TCP 上监听） | `getprop` |
| 典型绑定 | `*:5555`（**全接口**） | `ss -ltnp | grep adbd` |
| DNS/netd 链 | INPUT 链第 2 位是厂商链 `oem_in` | `iptables -L INPUT -n` |

**结论条目**：本机 `adbd` 以 root 运行 ⇒ **ADB 通道等价于 root 通道**。这与仓库既有验收记录（`uid=2000(shell)`，Android 13 机型）不同，属于机型相关差异。

## 6. 容器侧（同一台设备内的 DSHA 运行时）

| 项 | 值 | 判据 |
|---|---|---|
| 容器用户 | `uid=0(root)`（容器内） | `id` |
| Python | 3.12.3 | `python3 -V` |
| 离线 wheel 包 | `adb-wheels.tar.gz`，sha256 `173000bd60f8d58f90f988320b9b675540806139da9eae64ea78a0e6ecf5d9d4` | 与 `/root/.dsh/adb-wheels.lock.json` 比对 |
| 密钥对位置 | `/root/.dsh/adbkeys/adbkey{,.pub}`（0600） | `ls -l` |
| 公钥形态 | `<base64> unknown@localhost`，**无 `ssh-rsa` 前缀** | `awk '{print NF, $2}'` |
| ADB 客户端 | `python3 /root/.dsh/adb-shell.py`（Python + `adb_shell_wifi`，非 adb 二进制） | 脚本本身 |
| 共享目录 | `/sdcard`、`/storage/emulated/0` 在容器内可见 | `ls -ld` |

## 7. 复现这套探测（照抄即可）

```bash
# 设备侧（root shell）
su -c 'id; cat /proc/self/attr/current; getenforce; getprop ro.build.version.release'
su -c 'zcat /proc/config.gz | grep -iE "config_(user_ns|pid_ns|ipc_ns|net_ns|uts_ns|tmpfs|overlay_fs|squashfs)" | sort'
su -c 'ls -lnZ /data/misc/adb/; getprop init.svc.adbd; getprop service.adb.tcp.port; getprop ro.adb.secure'
su -c 'ls -l /data/adb/modules/*/sepolicy.rule'
su -c 'ps -A -o PID,UID,NAME | grep adbd; ps -A -Z | grep -i adbd'
su -c 'iptables -L INPUT -n --line-numbers | head -4'
```

```bash
# 容器侧
python3 -V
sha256sum /root/Documents/deepseek-harness/default-workspace/DSHA/app/src/main/assets/adb-wheels.tar.gz
ls -l /root/.dsh/adbkeys/
python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port 5555 -- id
```

时间基准：档案建立于 2026-10-09（设备时区）。适用范围：仅该台设备；如需外推到其它机型，必须重跑第 7 节的命令。
