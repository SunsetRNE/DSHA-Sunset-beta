# 原始证据归档（root → ADB 自举，2026-10-09）

按轮次保存历史捕获原文，修正判读。本轮没有运行这些设备命令；代码围栏只作为历史证据。原文件和摘要在交付包 ORIGINAL 中保留。原捕获窗口使用设备日期 2026-10-09，完整 UTC 偏移未知，不承诺持续有效期。

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

判读（修正）：记录了该次 su 的 UID/域、配置与文件元数据。namespace 静默命令没有完整退出码，不能仅凭无输出断言成功；内核能力结合 R2 与 namespace 列表判断。不能从空密钥表证明没有配对历史。

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

判读（修正）：历史配置记录 USER_NS/PID_NS/SQUASHFS 未设置，INDEX/METACOPY 与 TMPFS_XATTR 未设置。overlay upperdir 仍需验证实际文件系统的 xattr、挂载及权限。模块文本是策略先例，不证明当前有效策略或未来所有跨域操作被允许。原记录称这些已做动作未新增规则；后续动作须按域、对象、capability 与实际拒绝日志独立验证。

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

判读（再次修正）：错误文本与 exec ENOENT 相容，不能直接认定 chroot 权限通过。缺少解释器是推测；还需实际 ELF 的 PT_INTERP/依赖、syscall 返回或成功 exec 证据。L1 仍未验证。

已修正：既不能把 CHROOT_DENIED 标记等同权限拒绝，也不能把 ENOENT 改写成权限已证；后续验证契约见 [chroot-l1.md](chroot-l1.md)。

## R4　`adb_keys` 写权限（可无损回滚）

```
设备: cp -a /data/misc/adb/adb_keys /data/local/tmp/adb_keys.bak
设备: printf "ssh-rsa AAAADSHAPROBE\n" >> /data/misc/adb/adb_keys; wc -c
  → 22 /data/misc/adb/adb_keys   → APPEND_OK
设备: truncate -s 0 /data/misc/adb/adb_keys; ls -lnZ
  → -rw-r----- 1 1000 2000 u:object_r:adb_keys_file:s0  0  …  adb_keys
```

判读（修正）：该次初始空表的写入后元数据记录未变；未给出完整恢复摘要。它不证明跨设备无损回滚，也不为未来动作授予权限。生产撤销只移除有本应用所有权证据的条目，通用策略继续拦截相关设备修改。

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

判读（修正）：属性值与临时条目数已记录；没有未授权客户端认证测试。IPv4 INPUT 厂商链存在，不证明 IPv6 状态、防护顺序或持续收窄。

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

判读（修正）：公钥授权与规则随后消失有记录；adbd PID 变化不能独自证明整机重启，也不能确定规则由 netd 清除。规则失效原因未知；需 boot_id/uptime、服务与双栈规则观测。

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

判读（修正）：明确 ADB 计划、该次会话 id 与 daemon UID/域均有历史记录。两者在该次捕获中都为 root；属性为空不确定 root 来源，daemon 身份也不证明所有会话与另一个 root 通道完整同权。

---

## 证据有效性与已知缺口

| 条目 | 状态 |
|---|---|
| R1–R12 全部输出 | 实机粘贴原样保留（仅做了无关空行压缩） |
| R6 中 `127.0.0.1:5555` 单行的来源命令 | 未在粘贴中出现；R7 用同屏输出消歧，结论已定 |
| R3 的 chroot 判定 | 已修正（见 R3 判读），修正后的验证尚未执行 |
| 私钥内容 | 全程未读取、未输出、未离开容器；仅在容器内被 `adb-shell.py` 使用 |
| 公钥内容 | 以 sha256 前 8 位（`6319b1b1`）与字段结构记录，未贴全文 |


## 本轮证据校准

已知：R1–R12 代码围栏原文保留；本轮只读历史文件、修订判读。
推测：R3 ENOENT 可能来自缺 ELF 解释器或依赖。
未知：当前设备授权、双栈、规则消失原因与 L1 exec。
过期：原记录中的持续现状断言（有效期止于原捕获窗口，精确截止未记录，依据 R1–R12）；替代为分项目标复验。

| 断言 | 态 | 有效期到 | 依据 |
|---|---|---|---|
| R12 记录了该次 root 会话和 daemon 身份 | 已知 | 仅历史窗口 | 保留的捕获文本 |
| 规则丢失由整机重启/netd 清除导致 | 未知 | 无 | 缺 boot_id/uptime 与链重建记录 |
| chroot ENOENT 由缺解释器引起 | 推测 | 无承诺 | 缺 ELF/syscall 样本 |
| 历史设备状态持续有效 | 过期 | 原捕获窗口；精确截止未知 | 没有连续观测 |

截至 2026 年 10 月 9 日（UTC）：仅修订捕获判读。
适用范围：TB321FU 原捕获窗口，非当前设备能力。
已知推测未知：原始文本与判读分离，缺失原始退出码不补造。
依赖与边界：需要实际目标环境复验；历史命令不作执行入口。
