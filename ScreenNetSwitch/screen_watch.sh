#!/system/bin/sh
# ============================================
#  熄屏切4G / 亮屏切5G  ——  核心逻辑
# ============================================

MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch
. "$MODDIR/config.sh"

LOG="$MODDIR/run.log"
KEEP=200

log() {
  [ "$LOG_ENABLE" = "true" ] || return 0
  echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"
  # 简单轮转，防止日志无限增长
  if [ -f "$LOG" ]; then
    LINES=$(wc -l < "$LOG" 2>/dev/null)
    if [ -n "$LINES" ] && [ "$LINES" -gt "$KEEP" ]; then
      tail -n 100 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
  fi
}

# ---------- Toast（不依赖任何 App，用 app_process 起 Java） ----------
toast() {
  [ "$TOAST_ENABLE" = "true" ] || return 0
  [ -z "$1" ] && return 0
  ( cd /data/local/tmp 2>/dev/null
    CLASSPATH=/system/framework/framework.jar app_process /system/bin \
      com.operit.Toast "$1" >/dev/null 2>&1
  ) &
}

# ---------- 屏幕状态检测 ----------
# 返回值: 1 = 亮屏, 0 = 熄屏
screen_is_on() {
  # 方法1: dumpsys power（最通用）
  local st
  st=$(dumpsys power 2>/dev/null | grep -E 'mWakefulness=|Display Power:' | head -n 2)
  case "$st" in
    *mWakefulness=Awake*) return 1 ;;
    *mWakefulness=Asleep*|*mWakefulness=Dozing*) return 0 ;;
    *"Display Power: state=ON"*) return 1 ;;
    *"Display Power: state=OFF"*) return 0 ;;
  esac

  # 方法2: /sys/class/backlight 或 lcd 状态
  for f in /sys/class/backlight/*/actual_brightness \
           /sys/class/leds/lcd-backlight/brightness; do
    if [ -r "$f" ]; then
      local b
      b=$(cat "$f" 2>/dev/null)
      [ -n "$b" ] || continue
      if [ "$b" = "0" ]; then return 0; else return 1; fi
    fi
  done

  # 方法3: dumpsys display（兜底）
  st=$(dumpsys display 2>/dev/null | grep -m1 'mScreenState=')
  case "$st" in
    *ON*) return 1 ;;
    *OFF*) return 0 ;;
  esac

  # 全失败：当作亮屏，避免误切网
  return 1
}

# ---------- 应用网络模式 ----------
# $1 = mode 值
apply_mode() {
  local mode="$1"
  log "apply_mode -> $mode"

  # vivo/iQOO: nr_sa_mode 会崩，绝对不碰。只写 PNM。
  settings put global preferred_network_mode "$mode" 2>/dev/null
  settings put global preferred_network_mode1 "$mode" 2>/dev/null

  # 触发 RIL 重载（部分机型需要，否则设置不生效）
  #   phone 服务里 setPreferredNetworkType 是 transaction 序号会变，
  #   这里用更稳的 airplane/数据重连兜底方式。
  if [ "$RECONNECT_DATA" = "true" ]; then
    svc data disable 2>/dev/null
    sleep 0.5
    svc data enable 2>/dev/null
  fi

  # vivo 私有：尝试走 cmd（若支持）
  # cmd phone 在部分 ROM 上可用，失败也无妨
  cmd phone set-preferred-network-type "$mode" >/dev/null 2>&1 || true
}

# ---------- Toast 用的 Java 源码（编译成 dex 由 install 阶段处理）----------
# 见 toast/Toast.java，安装时编译。

# ---------- 主循环 ----------
LAST_STATE=-1        # -1 未知, 0 熄, 1 亮
LAST_SWITCH_TS=0

log "=========== daemon 启动 ==========="
log "POLL=$POLL_INTERVAL ON_DELAY=$SCREEN_ON_DELAY 5G=$MODE_5G 4G=$MODE_4G"

while true; do
  screen_is_on
  CUR=$?

  if [ "$CUR" != "$LAST_STATE" ]; then
    NOW=$(date +%s)
    GAP=$((NOW - LAST_SWITCH_TS))

    if [ "$LAST_STATE" != "-1" ] && [ "$GAP" -lt "$MIN_SWITCH_INTERVAL" ]; then
      log "变化 $LAST_STATE->$CUR 但间隔仅 ${GAP}s，跳过（防抖）"
      LAST_STATE=$CUR
    else
      if [ "$CUR" = "0" ]; then
        # ---- 熄屏：切 4G ----
        log "检测到熄屏 -> 切换 LTE Only"
        apply_mode "$MODE_4G"
        toast "$TOAST_TEXT_4G"
        LAST_SWITCH_TS=$(date +%s)
      else
        # ---- 亮屏：延迟后切 5G ----
        log "检测到亮屏，等待 ${SCREEN_ON_DELAY}s"
        sleep "$SCREEN_ON_DELAY"
        # 再确认一次确实还亮着（防止 1 秒内又灭了）
        if screen_is_on; then
          log "亮屏确认 -> 恢复 5G"
          apply_mode "$MODE_5G"
          toast "$TOAST_TEXT_5G"
          LAST_SWITCH_TS=$(date +%s)
        else
          log "延迟期间又熄屏，放弃本次切换"
        fi
      fi
      LAST_STATE=$CUR
    fi
  fi

  sleep "$POLL_INTERVAL"
done