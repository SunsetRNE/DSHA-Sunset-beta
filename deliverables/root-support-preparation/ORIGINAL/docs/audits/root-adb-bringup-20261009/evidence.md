# 原始证据归档（root → ADB 自举，2026-10-09）

按轮次记录**实机输出原文**与它证明/推翻了什么。凡属判断而非输出，标「判读」；凡被后续证据推翻的，保留原条目并标「已修正」。

执行位置标注：`容器` = DSHA 的 Ubuntu 工作区（本报告作者可执行）；`设备` = 用户的 Android root shell（`su -c`）；`App` = DSHA 界面操作。

---

## R1　能力探测（只读）

```
设备: id; cat /proc/self/attr/current; getenforce; getprop ro.build.version.release
  → uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
  → Enforcing
  → 16

设备: zcat /proc/config.gz | grep -E "CONFIG_(NAMESPACES|USER_NS|SECCOMP|OVERLAY_FS|SQUASHFS)="
  → CONFIG_NAMESPACES=y
  → CONFIG_SECCOMP=y
  → CONFIG_OVERLAY_FS=y

设备: ls -ld /data/adb/modules /data/adb/ksud; ls -l /data/adb/modules/*/sepolicy.rule
  → -rwxr-xr-x 1 root root 5666104 /data/adb/ksud
  → drwxr-xr-x 10 root root /data/adb/modules
  → rezygisk/sepolicy.rule 842B; treat_wheel/sepolicy.rule 49B;
    tricky_store/sepolicy.rule 690B; zygisk_lsposed/sepolicy.rule 281B

设备: ls -lnZ /data/misc/adb/; getprop init.svc.adbd; getprop service.adb.tcp.port
  → -rw-r----- 1 1000 2000 u:object_r:adb_keys_file:s0   0  adb_keys
  → -rw------- 1 1000 2000 u:object_r:adb_keys_file:s0  81  adb_temp_keys.xml
  → adbd: running
  → service.adb.tcp.port: （空）

设备: unshare --net --fork true; unshare -m --fork true
  → （无输出 = 成功）

设备: cat /proc/self/mountinfo | head -5; ls -l /proc/self/ns/
  → 14526 14525 254:14 / / ro,relatime master:1 - ext4 /dev/block/dm-14 ro,seclabel,discard
  → /proc/self/ns/ 只有 cgroup mnt net time time_for_children uts（无 pid / user / ipc）
```

判读：su 域为 `u:r:ksu:s0`（KernelSU 系）；namespace 与 mount 可用；`adb_keys` 已存在且 label/属主正确但为 0 字节；内核缺 PID/USER/IPC namespace。

## R2　内核配置补全 + sepolicy 先例

```
设备: zcat /proc/config.gz | grep -iE "config_(user_ns|pid_ns|ipc_ns|net_ns|uts_ns|tmpfs|overlay_fs|squashfs)" | sort
  → # CONFIG_USER_NS is not set
  → # CONFIG_PID_NS is not set
  → # CONFIG_SQUASHFS is not set
  → # CONFIG_TMPFS_XATTR is not set
  → # CONFIG_OVERLAY_FS_INDEX is not set
  → # CONFIG_OVERLAY_FS_METACOPY is not set
  → CONFIG_NET_NS=y ; CONFIG_UTS_NS=y ; CONFIG_TMPFS=y ; CONFIG_OVERLAY_FS=y
  → （grep 未命中 ipc_ns 任何行）

设备: cat /data/adb/modules/rezygisk/sepolicy.rule
  → allow zygote zygote capability sys_chroot
  → # INFO: Same as above. KernelSU renamed su context to ksu
设备: cat /data/adb/modules/tricky_store/sepolicy.rule
  → allow ksu self:tcp_socket { create connect read write getopt setopt }
  → allow ksu node:tcp_socket node_bind
  → allow ksu port:tcp_socket name_connect
```

判读：USER_NS/PID_NS/SQUASHFS 关闭；overlay 的 INDEX/METACOPY 关闭、tmpfs 无 xattr（upperdir 必须落在支持 xattr 的文件系统）；`capability sys_chroot` 与 `ksu` 网络权限在本机都有模块先例 —— 后续所有跨域操作因此无需新增规则（实测也确实没加）。

## R3　mount / chroot / namespace 最小验证

```
设备: mount --bind $T $T && umount $T
  → BIND_OK

设备: chroot $T /bin/toybox echo CHROOT_OK
  → chroot: exec /bin/toybox: No such file or directory
  → CHROOT_DENIED

设备: unshare -m --fork sh -c 'mount -t proc proc $T/proc; ls $T/proc/self/attr/current; umount $T/proc'
  → /data/local/tmp/chroot-probe/proc/self/attr/current
  → NSMOUNT_OK
```

判读（**修正过的一版判断**）：`chroot` 的报错是 `execve` 的 ENOENT 形态，不是 `chroot(2)` 的 `Permission denied`/`Operation not permitted`，所以**chroot 权限本身通过**，失败原因是新根里没有动态链接器 `/system/bin/linker64`。

已修正：本条验证最初被记为「CHROOT_DENIED＝chroot 不可用」，属**验证件选错**（用了动态链接的 toybox）。正确验证见 [chroot-l1.md](chroot-l1.md)。

## R4　`adb_keys` 写权限（可无损回滚）

```
设备: cp -a /data/misc/adb/adb_keys /data/local/tmp/adb_keys.bak
设备: printf "ssh-rsa AAAADSHAPROBE\n" >> /data/misc/adb/adb_keys; wc -c
  → 22 /data/misc/adb/adb_keys   → APPEND_OK
设备: truncate -s 0 /data/misc/adb/adb_keys; ls -lnZ
  → -rw-r----- 1 1000 2000 u:object_r:adb_keys_file:s0  0  …  adb_keys
```

判读：写入放行、label/属主/权限不变、可无损回滚 → **不需要 `chcon`/`restorecon`**（这两个命令本来被设备命令策略拦下）。

## R5　端口自举

```
设备: setprop service.adb.tcp.port 5555 → SETPROP_OK
设备: setprop ctl.restart adbd          → CTL_OK
设备: ss -ltnp | grep adbd
  → LISTEN 0 4 *:5555 *:* users:(("adbd",pid=25119,fd=8))
```

## R6　绑定收窄尝试（失败，已归因）

```
设备: setprop service.adb.tcp.port ""; setprop ctl.restart adbd        （回滚）
设备: setprop service.adb.listen_address 127.0.0.1; setprop ctl.restart adbd
     127.0.0.1:5555                       ← 该行来源命令不在粘贴内，当时标为「来源不明」
设备: ss -ltnp | grep adbd
  → LISTEN 0 4 *:5555 *:* users:(("adbd",pid=29870,fd=19))
```

R7 消歧后判读：`listen_address` 属性能写入，但 adbd 不采纳，仍绑 `*:5555`（pid 已变，证明重启生效）。

## R7　风险定级

```
设备: echo "listen_address=[$(getprop service.adb.listen_address)]"; echo "tcp_port=[$(getprop service.adb.tcp.port)]"; ss | grep adbd
  → listen_address=[127.0.0.1]
  → tcp_port=[5555]
  → LISTEN 0 4 *:5555 *:* users:(("adbd",pid=29870,fd=19))

设备: getprop ro.adb.secure; getprop ro.debuggable; getprop ro.build.type
  → 1 ; 0 ; user

设备: grep -c "<key" /data/misc/adb/adb_temp_keys.xml → 0
设备: command -v iptables → /system/bin/iptables
设备: iptables -L INPUT -n | head -3
  → Chain INPUT (policy ACCEPT)
  → target prot opt source destination
  → oem_in all -- 0.0.0.0/0 0.0.0.0/0
```

判读：`ro.adb.secure=1`（无密钥只能 `unauthorized`）、`ro.debuggable=0`、`build.type=user`；临时授权表 0 条；iptables 可用且 INPUT 链上有厂商链 `oem_in`（收窄规则要插在第 1 位）。

## R8　iptables 收窄（成功）

```
设备: iptables -I INPUT 1 -p tcp --dport 5555 ! -i lo -j DROP
设备: iptables -L INPUT -n --line-numbers | head -4
  → 1  DROP    tcp -- 0.0.0.0/0 0.0.0.0/0  tcp dpt:5555
  → 2  oem_in  all -- 0.0.0.0/0 0.0.0.0/0
```

判读：`u:r:ksu:s0` 域可写 iptables（此前只是推读权限，本条为写权限实证）。

## R9　容器侧密钥自举（本地实测）

```
容器: sha256sum app/src/main/assets/adb-wheels.tar.gz
  → 173000bd60f8d58f90f988320b9b675540806139da9eae64ea78a0e6ecf5d9d4
容器: adb-wheels.lock.json archiveSha256
  → 173000bd60f8d58f90f988320b9b675540806139da9eae64ea78a0e6ecf5d9d4   （一致）
容器: tar xzf … -C /root/.dsh/wheels  → 15 个文件；逐个 sha256 校验：通过 15，异常无
容器: bash /root/.dsh/adb-setup.sh
  → [1/4] Python 3.12.3
  → [2/4] pip 离线安装 adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf → 依赖安装成功
  → [3/4] KEY_GEN_OK
  → [4/4] SETUP_DONE      [exit=0]
容器: ls -l /root/.dsh/adbkeys/ → adbkey 1704B 0600 ; adbkey.pub 718B 0600
容器: awk '{print NF, $2}' adbkey.pub → 2 字段，注释 unknown@localhost（无 ssh-rsa 前缀）
```

判读：容器侧准备可离线完成；公钥形态为 `<base64> <comment>`。

## R10　设备侧授权 + 尾换行

```
设备: cat /sdcard/Download/adbkey.pub >> /data/misc/adb/adb_keys; wc -c
  → 718 /data/misc/adb/adb_keys          （无尾换行）
设备: printf "\n" >> /data/misc/adb/adb_keys; wc -c
  → 719 /data/misc/adb/adb_keys
设备: setprop ctl.restart adbd
设备: iptables -L INPUT -n | grep dpt:5555 → NO_RULE     （重启后规则丢失，F3）
设备: ss -ltnp | grep adbd → LISTEN *:5555 adbd pid=9883
```

判读：授权就位；iptables 规则不跨重启（pid 由 3942 变 9883 且规则消失，两条独立证据指向期间发生过重启）。

## R11　App 侧通道门控

```
容器: python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port 5555 -- id
  → [DEVICE_CHANNEL_UNAVAILABLE] 请在设置 → 设备能力授权中连接 root、Shizuku 或 ADB 通道
  → [EXIT=124]
```

判读：该消息由 App 的桥发出，来源是通道选择链的兜底分支（root 关 / Shizuku 无权限 / ADB 关）。App 开关打开后同一条命令即不再返回它。

## R12　端到端终判 + adbd 身份

```
容器: python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port 5555 -- id
  → uid=0(root) gid=0(root) groups=0(root),1004(input),…,3012(readtracefs) context=u:r:ksu:s0
  → [EXIT=0]

容器: 直接调 request_native_execution('id', False, True)
  → 返回类型 dict（ADB 计划，state=adb），不是 root 结果 → 证明本次确实走的是 ADB 计划
容器: curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:3090/device/execute?cmd=id&adb=1"
  → {"state":"adb","plan":{…  "argv":["id"] …}}

容器: python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port 5555 -- getprop service.adb.root → （空）
容器: python3 /root/.dsh/adb-shell.py -- getprop service.adb.root                            → （空）

容器: python3 /root/.dsh/adb-shell.py -- ps -A -o PID,UID,NAME | grep -E "adbd|PID"
  → 9883  0  adbd
容器: python3 /root/.dsh/adb-shell.py -- ps -A -Z | grep -i adbd
  → u:r:ksu:s0  root  9883  1  11020396  9016  do_epoll_wait  0  S  adbd
```

判读：ADB 计划确实被执行；`service.adb.root` 未设置；**adbd 自身 uid 0 + `u:r:ksu:s0`** —— 这就是 ADB shell 身份为 root 的根因。

---

## 证据有效性与已知缺口

| 条目 | 状态 |
|---|---|
| R1–R12 全部输出 | 实机粘贴原样保留（仅做了无关空行压缩） |
| R6 中 `127.0.0.1:5555` 单行的来源命令 | 未在粘贴中出现；R7 用同屏输出消歧，结论已定 |
| R3 的 chroot 判定 | 已修正（见 R3 判读），修正后的验证尚未执行 |
| 私钥内容 | 全程未读取、未输出、未离开容器；仅在容器内被 `adb-shell.py` 使用 |
| 公钥内容 | 以 sha256 前 8 位（`6319b1b1`）与字段结构记录，未贴全文 |
