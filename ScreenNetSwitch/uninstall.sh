#!/system/bin/sh
# ============================================
#  uninstall.sh —— 卸载还原 v1.1
#  避免卸载后留下「锁在 LTE Only」导致上不了 5G
# ============================================
MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch

. "$MODDIR/config.sh" 2>/dev/null

for pid in $(pgrep -f "screen_watch.sh" 2>/dev/null); do
  kill "$pid" 2>/dev/null
done

# 恢复网络模式为 5G（用配置值兜底 9）
RESTORE="${MODE_5G:-9}"
cmd phone set-preferred-network-type "$RESTORE" >/dev/null 2>&1
settings put global preferred_network_mode "$RESTORE" 2>/dev/null
settings put global preferred_network_mode1 "$RESTORE" 2>/dev/null

# 写一条卸载记录，日志目录保留（方便事后查看历史）
{
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] 模块已卸载，网络模式恢复为 $RESTORE"
} >> "${LOG_DIR:-/sdcard/Download/ScreenNetSwitch}/run.log" 2>/dev/null

rm -f "$MODDIR/daemon.pid" 2>/dev/null