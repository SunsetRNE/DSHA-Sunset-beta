#!/system/bin/sh
# 历史原型已停用：需原生命名入口、选择性撤销与双栈验证后重新实现。
printf "%s\n" "[POLICY_BLOCKED] 历史评估脚本不是部署入口，请参阅 ../design.md" >&2
exit 126

# authorize-adb.sh —— 在【设备 root shell】执行：把容器自己的公钥写进 adb_keys，并（可选）打开 TCP 端口。
#
# 用法：
#   su -c 'sh authorize-adb.sh --pub /sdcard/Download/adbkey.pub'
#   选项：
#     --pub PATH     公钥文件（默认 /sdcard/Download/adbkey.pub）
#     --port N       TCP 端口（默认 5555；传 0 表示不打开端口，只做授权）
#     --dry-run      只打印将要执行的动作
#
# 幂等：同一把公钥重复执行不会重复写入（按内容 grep 去重）。
# 回滚：脚本会在写入前备份 adb_keys，并用 revoke-adb.sh 恢复。

set -u

KEYS=/data/misc/adb/adb_keys
PUB=/sdcard/Download/adbkey.pub
PORT=5555
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --pub) PUB="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
done

[ "$(id -u)" = "0" ] || { echo "需要在 root shell 执行"; exit 2; }
[ -f "$PUB" ] || { echo "缺少公钥文件：$PUB（先在容器里 cp 到 /sdcard/Download/）"; exit 2; }
[ -f "$KEYS" ] || { echo "缺少 $KEYS"; exit 2; }

STAMP=$(date +%s)
BAK=/data/local/tmp/adb_keys.bak-$STAMP
KEY_LINE=$(tr -d '\r' < "$PUB" | sed -e 's/[[:space:]]*$//')

run() {
  if [ "$DRY" = "1" ]; then echo "[dry-run] $*"; else sh -c "$*"; fi
}

echo "== 1/4 备份"
run "cp -a $KEYS $BAK"
echo "   备份：$BAK（原 $(wc -c < "$KEYS" | tr -d ' ') 字节）"

echo "== 2/4 写入公钥（按内容去重）"
if grep -qF "$KEY_LINE" "$KEYS" 2>/dev/null; then
  echo "   已存在同一把公钥，跳过写入"
else
  # 确保追加前上一行以换行结束，避免两条公钥粘在一行
  if [ -s "$KEYS" ] && [ "$(tail -c 1 "$KEYS" | wc -l | tr -d ' ')" = "0" ]; then
    run "printf '\n' >> $KEYS"
  fi
  # 经临时文件交接，避免把 base64 内容再塞进 sh -c 的引号里
  TMP=/data/local/tmp/adb_key_line.$$
  printf '%s\n' "$KEY_LINE" > "$TMP"
  run "cat $TMP >> $KEYS"
  run "chown 1000:2000 $KEYS; chmod 0640 $KEYS"
  run "rm -f $TMP"
  echo "   写入后 $(wc -c < "$KEYS" | tr -d ' ') 字节"
fi

echo "== 3/4 端口"
if [ "$PORT" = "0" ]; then
  echo "   按参数要求不改端口"
else
  run "setprop service.adb.tcp.port $PORT"
  run "setprop ctl.restart adbd"
  echo "   已设 service.adb.tcp.port=$PORT 并重启 adbd"
fi

echo "== 4/4 当前状态"
echo "   adb_keys: $(ls -lnZ $KEYS)"
echo "   adbd:     $(getprop init.svc.adbd)"
echo "   监听:     $( (ss -ltnp 2>/dev/null || netstat -ltnp) | grep -i adbd || echo '（无 TCP 监听）')"
echo
echo "判据（在【容器】里执行）："
echo "  python3 /root/.dsh/adb-shell.py --host 127.0.0.1 --port ${PORT:-5555} -- id"
echo "  → uid=0(root) 表示本机 adbd 以 root 运行；uid=2000(shell) 表示普通调试身份"
echo "回滚：su -c 'sh revoke-adb.sh --backup $BAK'"
