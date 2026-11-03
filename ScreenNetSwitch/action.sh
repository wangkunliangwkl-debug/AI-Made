#!/system/bin/sh
# ============================================
#  action.sh —— 手动管理 & 诊断 v1.1
#  用法: sh action.sh {start|stop|restart|status|diag|log|test5g|test4g|clearlog}
# ============================================

MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch
. "$MODDIR/config.sh"

mkdir -p "$LOG_DIR" 2>/dev/null
chmod 0777 "$LOG_DIR" 2>/dev/null
LOG_FILE="$LOG_DIR/run.log"

say() { echo "$*"; }

# 屏幕上也能看到 + 同时写日志
saylog() {
  echo "$*"
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] [action] $*" >> "$LOG_FILE" 2>/dev/null
}

cur_screen() {
  W=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')
  case "$W" in
    *Awake*)  echo "ON (mWakefulness=Awake)" ;;
    *Asleep*) echo "OFF (mWakefulness=Asleep)" ;;
    *Dozing*) echo "OFF (mWakefulness=Dozing)" ;;
    *)        echo "未知 ($W)" ;;
  esac
}

case "$1" in
  start)
    for pid in $(pgrep -f "screen_watch.sh" 2>/dev/null); do kill "$pid" 2>/dev/null; done
    sleep 1
    nohup sh "$MODDIR/screen_watch.sh" > "$LOG_DIR/stderr.log" 2>&1 &
    P=$!
    echo "$P" > "$MODDIR/daemon.pid"
    sleep 2
    if kill -0 "$P" 2>/dev/null; then
      saylog "守护进程已启动 PID=$P"
    else
      saylog "!! 启动失败，请看 $LOG_DIR/stderr.log"
    fi
    ;;

  stop)
    for pid in $(pgrep -f "screen_watch.sh" 2>/dev/null); do kill "$pid" 2>/dev/null; done
    saylog "已停止守护进程"
    ;;

  restart)
    for pid in $(pgrep -f "screen_watch.sh" 2>/dev/null); do kill "$pid" 2>/dev/null; done
    sleep 1
    nohup sh "$MODDIR/screen_watch.sh" > "$LOG_DIR/stderr.log" 2>&1 &
    P=$!
    echo "$P" > "$MODDIR/daemon.pid"
    sleep 2
    saylog "已重启守护进程 PID=$P"
    ;;

  status)
    say "==== 运行状态 ===="
    PIDS=$(pgrep -f "screen_watch.sh" 2>/dev/null)
    if [ -n "$PIDS" ]; then say "守护进程: 运行中 (PID $PIDS)"; else say "守护进程: 未运行"; fi
    say "屏幕状态: $(cur_screen)"
    say "PNM 数据库值: $(settings get global preferred_network_mode 2>/dev/null)"
    say "实际网络类型: $(getprop gsm.network.type 2>/dev/null)"
    say "日志目录: $LOG_DIR"
    ;;

  diag)
    saylog "===== 开始完整诊断 ====="
    saylog "机型: $(getprop ro.product.model) / $(getprop ro.product.brand)"
    saylog "Android: $(getprop ro.build.version.release) SDK=$(getprop ro.build.version.sdk)"
    saylog "OriginOS: $(getprop ro.vivo.os.version)"
    saylog "ROM: $(getprop ro.build.display.id)"
    saylog "内核: $(uname -r)"

    saylog "--- 守护进程 ---"
    PIDS=$(pgrep -f "screen_watch.sh" 2>/dev/null)
    saylog "PID: ${PIDS:-未运行}"

    saylog "--- 屏幕检测 ---"
    saylog "mWakefulness: $(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')"
    saylog "DisplayPower: $(dumpsys power 2>/dev/null | grep -m1 'Display Power:')"
    saylog "backlight: $(cat /sys/class/backlight/*/actual_brightness 2>/dev/null | head -n 3 | tr '\n' ' ')"
    saylog "screenState: $(dumpsys display 2>/dev/null | grep -m1 'mScreenState=')"

    saylog "--- 网络模式 ---"
    saylog "preferred_network_mode : $(settings get global preferred_network_mode 2>/dev/null)"
    saylog "preferred_network_mode1: $(settings get global preferred_network_mode1 2>/dev/null)"
    saylog "gsm.network.type       : $(getprop gsm.network.type 2>/dev/null)"
    saylog "gsm.operator.alpha     : $(getprop gsm.operator.alpha 2>/dev/null)"
    saylog "gsm.sim.state          : $(getprop gsm.sim.state 2>/dev/null)"
    saylog "ro.telephony.default_network: $(getprop ro.telephony.default_network 2>/dev/null)"

    saylog "--- 命令可用性 ---"
    saylog "cmd      : $(command -v cmd 2>/dev/null || echo 缺失)"
    saylog "service  : $(command -v service 2>/dev/null || echo 缺失)"
    saylog "settings : $(command -v settings 2>/dev/null || echo 缺失)"
    saylog "svc      : $(command -v svc 2>/dev/null || echo 缺失)"

    saylog "--- cmd phone 支持情况 ---"
    CMDHELP=$(cmd phone 2>&1 | head -n 20)
    saylog "cmd phone 输出:"
    echo "$CMDHELP" | while read -r L; do saylog "  | $L"; done

    saylog "--- 网络相关 global 键 ---"
    settings list global 2>/dev/null | grep -iE 'network_mode|nr_|5g' | while read -r L; do
      saylog "  $L"
    done

    saylog "--- 当前蜂窝接口 ---"
    ip addr 2>/dev/null | grep -E 'rmnet|ccmni|pdp_' | head -n 10 | while read -r L; do
      saylog "  $L"
    done

    saylog "===== 诊断结束，文件在 $LOG_FILE ====="
    ;;

  log)
    if [ -f "$LOG_FILE" ]; then
      tail -n 60 "$LOG_FILE"
    else
      say "日志文件还不存在: $LOG_FILE"
    fi
    ;;

  clearlog)
    rm -f "$LOG_FILE" "$LOG_DIR/boot.log" "$LOG_DIR/stderr.log" 2>/dev/null
    saylog "日志已清空"
    ;;

  test5g)
    saylog "== 手动测试：切 5G (mode=$MODE_5G) =="
    saylog "切前: PNM=$(settings get global preferred_network_mode) net=$(getprop gsm.network.type)"
    cmd phone set-preferred-network-type "$MODE_5G" 2>&1 | while read -r L; do saylog "  cmd: $L"; done
    settings put global preferred_network_mode "$MODE_5G" 2>/dev/null
    settings put global preferred_network_mode1 "$MODE_5G" 2>/dev/null
    svc data disable >/dev/null 2>&1; sleep 2; svc data enable >/dev/null 2>&1
    sleep 8
    saylog "切后: PNM=$(settings get global preferred_network_mode) net=$(getprop gsm.network.type)"
    ;;

  test4g)
    saylog "== 手动测试：切 4G (mode=$MODE_4G) =="
    saylog "切前: PNM=$(settings get global preferred_network_mode) net=$(getprop gsm.network.type)"
    cmd phone set-preferred-network-type "$MODE_4G" 2>&1 | while read -r L; do saylog "  cmd: $L"; done
    settings put global preferred_network_mode "$MODE_4G" 2>/dev/null
    settings put global preferred_network_mode1 "$MODE_4G" 2>/dev/null
    svc data disable >/dev/null 2>&1; sleep 2; svc data enable >/dev/null 2>&1
    sleep 8
    saylog "切后: PNM=$(settings get global preferred_network_mode) net=$(getprop gsm.network.type)"
    ;;

  testnotify)
    cmd notification post -S bigtext -t "网络切换" sns_test "这是一条测试通知，能看到说明提示通道正常" 2>&1 | while read -r L; do saylog "  $L"; done
    saylog "已发送测试通知，拉下通知栏看看"
    ;;

  *)
    say "用法: sh action.sh {start|stop|restart|status|diag|log|clearlog|test5g|test4g|testnotify}"
    say ""
    say "  status     查看运行状态"
    say "  diag       完整环境诊断（最重要，出问题先跑这个）"
    say "  log        查看最近 60 行日志"
    say "  test5g     手动测试切 5G"
    say "  test4g     手动测试切 4G"
    say "  testnotify 测试通知通道"
    say "  clearlog   清空日志"
    ;;
esac