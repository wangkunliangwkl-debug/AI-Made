#!/system/bin/sh
# ============================================
#  action.sh —— 手动管理脚本（可在 Magisk 模块页面点「执行」）
# ============================================
MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch
. "$MODDIR/config.sh"

log() { echo "$*"; }

case "$1" in
  status)
    log "==== 模块状态 ===="
    PGID=$(pgrep -f "screen_watch.sh")
    if [ -n "$PGID" ]; then log "守护进程: 运行中 (PID $PGID)"; else log "守护进程: 未运行"; fi
    log ""
    log "当前 PNM: $(settings get global preferred_network_mode)"
    log ""
    log "==== 屏幕检测 ===="
    dumpsys power | grep -E 'mWakefulness=|Display Power:' | head -n 2
    log ""
    log "==== 最近日志 ===="
    tail -n 20 "$MODDIR/run.log" 2>/dev/null
    ;;
  reload)
    for pid in $(pgrep -f "screen_watch.sh"); do kill "$pid" 2>/dev/null; done
    sleep 1
    nohup sh "$MODDIR/screen_watch.sh" >> "$MODDIR/daemon.log" 2>&1 &
    echo "已重启守护进程"
    ;;
  stop)
    for pid in $(pgrep -f "screen_watch.sh"); do kill "$pid" 2>/dev/null; done
    echo "已停止守护进程（重启手机或 reload 恢复）"
    ;;
  test-toast)
    CLASSPATH=/system/framework/framework.jar app_process /system/bin com.operit.Toast "已切换到5G网络" &
    echo "已尝试弹 Toast，看看屏幕"
    ;;
  force-5g)
    settings put global preferred_network_mode "$MODE_5G"
    settings put global preferred_network_mode1 "$MODE_5G"
    echo "已强制 5G (mode=$MODE_5G)"
    ;;
  force-4g)
    settings put global preferred_network_mode "$MODE_4G"
    settings put global preferred_network_mode1 "$MODE_4G"
    echo "已强制 4G (mode=$MODE_4G)"
    ;;
  probe)
    log "===== iQOO/vivo 环境探测 ====="
    log "型号      : $(getprop ro.product.model)"
    log "品牌      : $(getprop ro.product.brand)"
    log "Android   : $(getprop ro.build.version.release)"
    log "SDK       : $(getprop ro.build.version.sdk)"
    log "OriginOS  : $(getprop ro.vivo.os.version)"
    log ""
    log "--- 当前网络相关 global 键 ---"
    settings list global 2>/dev/null | grep -iE "network_mode|nr_|5g" 
    log ""
    log "--- 当前 system 键 ---"
    settings list system 2>/dev/null | grep -iE "network_mode|nr_|5g"
    log ""
    log "--- 屏幕字段 ---"
    dumpsys power 2>/dev/null | grep -E 'mWakefulness=|Display Power:' | head -n 3
    ;;
  *)
    echo "用法: sh action.sh {status|reload|stop|test-toast|force-5g|force-4g|probe}"
    ;;
esac