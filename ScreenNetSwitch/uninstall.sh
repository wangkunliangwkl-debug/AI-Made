#!/system/bin/sh
# ============================================
#  uninstall.sh —— 卸载时还原，避免留下「锁 LTE」的坑
# ============================================
MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch

. "$MODDIR/config.sh" 2>/dev/null

for pid in $(pgrep -f "screen_watch.sh"); do
  kill "$pid" 2>/dev/null
done

# 恢复成运营商默认（用配置里的 5G 值，避免卸载后手机没网）
settings put global preferred_network_mode "${MODE_5G:-9}" 2>/dev/null
settings put global preferred_network_mode1 "${MODE_5G:-9}" 2>/dev/null

rm -f "$MODDIR/daemon.pid" "$MODDIR/run.log" "$MODDIR/daemon.log" 2>/dev/null