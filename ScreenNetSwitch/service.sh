#!/system/bin/sh
# ============================================
#  service.sh —— 开机 late_start 阶段启动守护
# ============================================
MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch

# 等系统完全起来（电源服务可用）
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
sleep 8

# 杀掉旧进程
for pid in $(pgrep -f "screen_watch.sh"); do
  kill "$pid" 2>/dev/null
done

# 启动守护（nohup 保证不被父进程退出带走）
nohup sh "$MODDIR/screen_watch.sh" >> "$MODDIR/daemon.log" 2>&1 &
echo $! > "$MODDIR/daemon.pid"