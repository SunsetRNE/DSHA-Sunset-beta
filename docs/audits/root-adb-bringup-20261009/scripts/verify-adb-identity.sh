#!/bin/bash
# 历史原型已停用：需原生命名入口、选择性撤销与双栈验证后重新实现。
printf "%s\n" "[POLICY_BLOCKED] 历史评估脚本不是部署入口，请参阅 ../design.md" >&2
exit 126

# verify-adb-identity.sh —— 在【容器内】（DSHA 的 Ubuntu 工作区）执行：
# 经 DSHA 的 ADB 通道执行 `id`，并把「身份等级」判定出来。
#
# 用法：
#   bash verify-adb-identity.sh                 # 默认 127.0.0.1:5555
#   bash verify-adb-identity.sh --port 5556
#   bash verify-adb-identity.sh --cmd 'getprop ro.serialno'
#
# 退出码：0 = 通道可用（无论身份是 root 还是 shell）；124 = 连不上；125 = 结果未知；126 = 策略/开关拦截。

set -u

HOST=127.0.0.1
PORT=5555
CMD=id
SHELL=/root/.dsh/adb-shell.py

while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --cmd)  CMD="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
done

[ -f "$SHELL" ] || { echo "缺少 $SHELL（容器内 ADB 客户端）"; exit 2; }

OUT=$(timeout 150 python3 "$SHELL" --host "$HOST" --port "$PORT" -- "$CMD" 2>&1)
RC=$?

echo "$OUT"
echo "--- 判定"
echo "命令：$CMD  @ $HOST:$PORT   退出码：$RC"

case "$RC" in
  0)
    case "$OUT" in
      *"uid=0(root)"*)
        echo "身份：ROOT —— 本机 adbd 以 root 运行，ADB 通道与 root 通道同权"
        echo "处置：确认 5555 已收窄到回环（adb-listen-narrow.sh），或改为用完即关"
        ;;
      *"uid=2000(shell)"*)
        echo "身份：shell(uid=2000) —— 常规调试身份"
        ;;
      *)
        echo "身份：未识别（命令输出不含 uid= 判据）"
        ;;
    esac
    ;;
  124) echo "判定：连不上 → 端口未开或 adbd 未重启（检查 service.adb.tcp.port 与 ss）" ;;
  125) echo "判定：结果未知 → 不要重放；先核对设备侧状态" ;;
  126) echo "判定：策略/开关拦截 → DEVICE_CHANNEL_UNAVAILABLE 时在 App 打开 ADB 开关" ;;
  *)   echo "判定：未知退出码，按输出原文处理" ;;
esac

exit $RC
