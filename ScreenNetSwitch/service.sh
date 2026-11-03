#!/system/bin/sh
# ============================================
#  service.sh —— 开机启动守护 v1.1
#  额外职责：把启动过程也写进下载目录日志
# ============================================

MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch
. "$MODDIR/config.sh"

mkdir -p "$LOG_DIR" 2>/dev/null
chmod 0777 "$LOG_DIR" 2>/dev/null
BOOT_LOG="$LOG_DIR/boot.log"

blog() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$BOOT_LOG" 2>/dev/null
}

blogsep() {
  blog "=========================================="
}

blogsep
blog "service.sh 开始执行 (开机启动)"
blog "模块目录: $MODDIR"

# 等系统完全起来
WAITED=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
  WAITED=$((WAITED + 2))
  [ "$WAITED" -gt 300 ] && break
done
blog "sys.boot_completed=1 (等待 ${WAITED}s)"

# 再等一会，让 telephony / phone 服务就绪，否则 cmd phone 会失败
sleep 15
blog "额外等待 15s 结束，检查电话服务..."

# 等 RIL 就绪（有信号或至少有网络类型）
for i in 1 2 3 4 5 6 7 8 9 10; do
  T=$(getprop gsm.network.type 2>/dev/null)
  if [ -n "$T" ] && [ "$T" != "unknown" ]; then
    blog "RIL 就绪: gsm.network.type=$T (第 ${i} 次检查)"
    break
  fi
  sleep 3
done

# 清掉旧进程
for pid in $(pgrep -f "screen_watch.sh" 2>/dev/null); do
  blog "杀掉旧进程 PID=$pid"
  kill "$pid" 2>/dev/null
done
sleep 1

# 启动守护
nohup sh "$MODDIR/screen_watch.sh" > "$LOG_DIR/stderr.log" 2>&1 &
NEWPID=$!
echo "$NEWPID" > "$MODDIR/daemon.pid"
blog "已启动守护进程 PID=$NEWPID"

sleep 3
if kill -0 "$NEWPID" 2>/dev/null; then
  blog "守护进程存活确认 OK"
else
  blog "!! 守护进程启动后立即退出，请查看 $LOG_DIR/stderr.log"
fi

blogsep
