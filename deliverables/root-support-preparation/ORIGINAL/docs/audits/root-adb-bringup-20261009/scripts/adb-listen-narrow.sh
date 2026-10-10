#!/system/bin/sh
# adb-listen-narrow.sh —— 在【设备 root shell】执行：把 adbd 的 5555 收窄到只允许回环。
#
# 背景：本机 `service.adb.listen_address` 不生效（属性写入但 adbd 仍绑 *:5555，实测），
#       因此收窄只能靠 iptables；而 iptables 规则不跨重启，需要由 KernelSU 模块重复施加。
#
# 用法：
#   su -c 'sh adb-listen-narrow.sh'            # 幂等施加（默认动作）
#   su -c 'sh adb-listen-narrow.sh --status'   # 只看当前状态
#   su -c 'sh adb-listen-narrow.sh --restore'  # 移除规则
#   su -c 'sh adb-listen-narrow.sh --persist-hint'  # 打印写入模块的片段
#
# 判定：施加后从另一台机器 `nc <平板IP> 5555` 应超时；本机 127.0.0.1:5555 仍可连。

set -u

PORT=5555
RULE_ARGS="-p tcp --dport $PORT ! -i lo -j DROP"
ACTION=apply

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; RULE_ARGS="-p tcp --dport $PORT ! -i lo -j DROP"; shift 2 ;;
    --status) ACTION=status; shift ;;
    --restore) ACTION=restore; shift ;;
    --persist-hint) ACTION=hint; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
done

[ "$(id -u)" = "0" ] || { echo "需要在 root shell 执行"; exit 2; }

exists() { iptables -C INPUT $RULE_ARGS 2>/dev/null; }

show() {
  echo "   规则：$(iptables -L INPUT -n --line-numbers 2>/dev/null | grep -c "dpt:$PORT") 条命中"
  iptables -L INPUT -n --line-numbers 2>/dev/null | grep "dpt:$PORT" | sed 's/^/   /'
  echo "   监听：$( (ss -ltnp 2>/dev/null || netstat -ltnp) | grep -i adbd || echo '（无 TCP 监听）')"
}

case "$ACTION" in
  apply)
    if exists; then
      echo "== 规则已存在，不重复插入"
    else
      # 插到第 1 位：本机 INPUT 链第 2 位是厂商链 oem_in，规则必须先于它
      iptables -I INPUT 1 $RULE_ARGS && echo "== 已插入第 1 位"
    fi
    show
    ;;
  restore)
    if exists; then
      iptables -D INPUT $RULE_ARGS && echo "== 已移除规则"
    else
      echo "== 规则本就不存在"
    fi
    show
    ;;
  status)
    show
    ;;
  hint)
    cat <<'EOF'
# 写入 /data/adb/modules/<模块名>/service.sh（模块目录需自建 module.prop）：
iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || \
  iptables -I INPUT 1 -p tcp --dport 5555 ! -i lo -j DROP
# 若还需要端口常开，另在 post-fs-data.sh 里：
#   setprop service.adb.tcp.port 5555
EOF
    ;;
esac
