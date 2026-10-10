#!/system/bin/sh
# 此修订副本只保留历史原型供审阅；原始可执行字节在 ORIGINAL。
# 生产执行等待原生命名入口、密钥所有权、双栈与未知结果验收。
printf '%s\n' 'HISTORICAL_PROTOTYPE_ONLY: no device action; see corrected design.md'
exit 126

# revoke-adb.sh —— 在【设备 root shell】执行：撤销 root 自举的 ADB 授权与端口暴露。
#
# 用法：
#   su -c 'sh revoke-adb.sh'                        # 清空 adb_keys 并关闭 TCP 端口
#   su -c 'sh revoke-adb.sh --backup /data/local/tmp/adb_keys.bak-<ts>'   # 从备份恢复
#   su -c 'sh revoke-adb.sh --keep-keys'            # 只关端口，保留授权（下次免重授权）
#   su -c 'sh revoke-adb.sh --drop-rule'            # 同时移除 5555 的 lo-only 收窄规则
#
# 默认动作 = 清空 adb_keys + 清空 service.adb.tcp.port + 重启 adbd。

set -u

KEYS=/data/misc/adb/adb_keys
BAK=
KEEP=0
DROP_RULE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BAK="$2"; shift 2 ;;
    --keep-keys) KEEP=1; shift ;;
    --drop-rule) DROP_RULE=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
done

[ "$(id -u)" = "0" ] || { echo "需要在 root shell 执行"; exit 2; }

echo "== 1/4 adb_keys"
if [ "$KEEP" = "1" ]; then
  echo "   按参数保留（--keep-keys），$(wc -c < "$KEYS" 2>/dev/null | tr -d ' ') 字节不变"
elif [ -n "$BAK" ]; then
  [ -f "$BAK" ] || { echo "   备份不存在：$BAK"; exit 2; }
  cp -a "$BAK" "$KEYS"
  echo "   已从 $BAK 恢复为 $(wc -c < "$KEYS" | tr -d ' ') 字节"
else
  : > "$KEYS"
  chown 1000:2000 "$KEYS" 2>/dev/null
  chmod 0640 "$KEYS" 2>/dev/null
  echo "   已清空（0 字节）"
fi

echo "== 2/4 TCP 端口"
setprop service.adb.tcp.port ""
setprop service.adb.listen_address ""
setprop ctl.restart adbd
echo "   已清空 service.adb.tcp.port / listen_address 并重启 adbd"

echo "== 3/4 防火墙规则"
if [ "$DROP_RULE" = "1" ]; then
  iptables -D INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null
  echo "   已尝试删除 lo-only 规则"
else
  echo "   按参数保留（未传 --drop-rule）"
fi

echo "== 4/4 当前状态"
echo "   adb_keys: $(ls -lnZ $KEYS 2>/dev/null)"
echo "   监听:     $( (ss -ltnp 2>/dev/null || netstat -ltnp) | grep -i adbd || echo '（无 TCP 监听）')"
echo "   规则:     $(iptables -L INPUT -n 2>/dev/null | grep dpt:5555 || echo '（无 5555 规则）')"
