#!/system/bin/sh
# ============================================
#  熄屏切4G / 亮屏切5G  ——  核心守护 v1.1
#  日志输出到 /sdcard/Download/ScreenNetSwitch/
# ============================================

MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/screen_net_switch
. "$MODDIR/config.sh"

# ---------------- 日志 ----------------
# 注意：全程不用 local 关键字，避免 busybox ash / mksh 兼容问题
mkdir -p "$LOG_DIR" 2>/dev/null
chmod 0777 "$LOG_DIR" 2>/dev/null
LOG_FILE="$LOG_DIR/run.log"

rotate_log() {
  [ -f "$LOG_FILE" ] || return 0
  N=$(wc -l < "$LOG_FILE" 2>/dev/null)
  [ -z "$N" ] && return 0
  if [ "$N" -gt "$LOG_MAX_LINES" ]; then
    tail -n $((LOG_MAX_LINES / 2)) "$LOG_FILE" > "$LOG_FILE.tmp" 2>/dev/null
    mv -f "$LOG_FILE.tmp" "$LOG_FILE" 2>/dev/null
    chmod 0666 "$LOG_FILE" 2>/dev/null
  fi
}

log() {
  [ "$LOG_ENABLE" = "true" ] || return 0
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE" 2>/dev/null
}

logsep() {
  log "------------------------------------------"
}

# 记录一段命令输出到日志（带缩进）
logblock() {
  TITLE="$1"
  shift
  log "  $TITLE:"
  "$@" 2>&1 | head -n 30 | while read -r L; do
    log "    | $L"
  done
}

# ---------------- 提示 ----------------
# notification 方式：cmd notification post，稳定可用，不依赖 dex
notify() {
  [ "$TOAST_ENABLE" = "true" ] || return 0
  [ -z "$1" ] && return 0
  if command -v cmd >/dev/null 2>&1; then
    cmd notification post -S bigtext -t "网络切换" sns_tag "$1" >/dev/null 2>&1
    R=$?
    log "  notify(cmd) rc=$R"
    [ "$R" = "0" ] && return 0
  fi
  log "  notify 失败（cmd notification 不可用）"
  return 1
}

# toast 方式：需要 dex，v1.0 从未编译成功，这里保留但会记录失败原因
toast_ap() {
  [ -z "$1" ] && return 0
  DEX="$MODDIR/toast/out"
  if [ ! -f "$DEX/classes.dex" ]; then
    log "  toast 跳过：$DEX/classes.dex 不存在（安装时未编译成功）"
    return 1
  fi
  ( cd "$DEX" 2>/dev/null
    CLASSPATH="$DEX:/system/framework/framework.jar" app_process /system/bin \
      com.operit.Toast "$1" >/dev/null 2>&1
  )
  log "  toast app_process rc=$?"
}

show_msg() {
  [ -z "$1" ] && return 0
  case "$TOAST_MODE" in
    toast)  toast_ap "$1" || notify "$1" ;;
    *)      notify "$1" ;;
  esac
}

# ---------------- 屏幕状态检测 ----------------
# 输出检测来源到全局变量 SCREEN_SRC
# 返回: 0 = 熄屏, 1 = 亮屏  （与 shell 惯例一致，便于 if 直接判断）
screen_probe() {
  SCREEN_SRC="none"

  # 方法1: dumpsys power 的 mWakefulness（最权威）
  W=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')
  case "$W" in
    *mWakefulness=Awake*)    SCREEN_SRC="power:mWakefulness=Awake";    return 1 ;;
    *mWakefulness=Asleep*)   SCREEN_SRC="power:mWakefulness=Asleep";   return 0 ;;
    *mWakefulness=Dozing*)   SCREEN_SRC="power:mWakefulness=Dozing";   return 0 ;;
  esac

  # 方法2: dumpsys power 的 Display Power state
  P=$(dumpsys power 2>/dev/null | grep -m1 -E 'Display Power: state=')
  case "$P" in
    *state=ON*)  SCREEN_SRC="power:DisplayPower=ON";  return 1 ;;
    *state=OFF*) SCREEN_SRC="power:DisplayPower=OFF"; return 0 ;;
  esac

  # 方法3: 背光节点
  for F in /sys/class/backlight/*/actual_brightness \
           /sys/class/backlight/*/brightness \
           /sys/class/leds/lcd-backlight/brightness; do
    if [ -r "$F" ]; then
      B=$(cat "$F" 2>/dev/null)
      case "$B" in
        ''|*[!0-9]*) continue ;;
      esac
      if [ "$B" -eq 0 ]; then
        SCREEN_SRC="backlight:$F=0"; return 0
      else
        SCREEN_SRC="backlight:$F=$B"; return 1
      fi
    fi
  done

  # 方法4: dumpsys display
  D=$(dumpsys display 2>/dev/null | grep -m1 -E 'mScreenState=')
  case "$D" in
    *ON*)  SCREEN_SRC="display:mScreenState=ON";  return 1 ;;
    *OFF*) SCREEN_SRC="display:mScreenState=OFF"; return 0 ;;
  esac

  # 全部失败：当作亮屏，避免误切网
  SCREEN_SRC="fallback(unknown->assume ON)"
  return 1
}

# ---------------- 读取实际网络类型 ----------------
net_type() {
  T=$(getprop gsm.network.type 2>/dev/null)
  [ -z "$T" ] && T="unknown"
  echo "$T"
}

# 当前 PNM（数据库值）
pnm_now() {
  V=$(settings get global preferred_network_mode 2>/dev/null)
  [ -z "$V" ] && V="null"
  echo "$V"
}

# 判断当前是否已在使用 5G 侧接入
is_on_5g() {
  T=$(net_type)
  case "$T" in
    NR*|*NR*) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------- 应用网络模式（多通道） ----------------
# $1 = 目标 mode
apply_mode() {
  TARGET="$1"
  logsep
  log "== 开始切换 ==  目标 mode=$TARGET"

  OLD_PNM=$(pnm_now)
  OLD_NET=$(net_type)
  log "  切换前: PNM=$OLD_PNM  gsm.network.type=$OLD_NET"

  # --- 通道1: cmd phone（Android 12+ 多数可用）---
  if command -v cmd >/dev/null 2>&1; then
    log "  [通道1] cmd phone set-preferred-network-type $TARGET"
    R1=$(cmd phone set-preferred-network-type "$TARGET" 2>&1)
    RC1=$?
    log "    rc=$RC1 out=${R1:-<empty>}"
  else
    log "  [通道1] 跳过：系统没有 cmd 命令"
  fi

  # --- 通道2: service call phone（老通道，transaction 序号多版本尝试）---
  if command -v service >/dev/null 2>&1; then
    log "  [通道2] service call phone setPreferredNetworkType"
    # service call 的 transaction code 各版本不同，常见的几个都试一遍
    for CODE in 27 33 26 24 22; do
      S2=$(service call phone "$CODE" i32 "$TARGET" 2>&1 | head -n 2)
      log "    code=$CODE -> ${S2:-<empty>}"
      case "$S2" in
        *Exception*|*Error*|*error*|*"not found"*|*"unrecognized service"*) continue ;;
        *) break ;;
      esac
    done
  else
    log "  [通道2] 跳过：系统没有 service 命令"
  fi

  # --- 通道3: settings put（写数据库，配合广播触发监听方重读）---
  if command -v settings >/dev/null 2>&1; then
    log "  [通道3] settings put global preferred_network_mode $TARGET"
    settings put global preferred_network_mode "$TARGET" 2>&1 | head -n 2 | while read -r L; do log "    | $L"; done
    settings put global preferred_network_mode1 "$TARGET" 2>/dev/null
    settings put global preferred_network_mode2 "$TARGET" 2>/dev/null
    log "    写入后 PNM=$(pnm_now)"
  else
    log "  [通道3] 跳过：系统没有 settings 命令"
  fi

  # --- 通道4: 数据重连，强制基带重新注册 ---
  if [ "$RECONNECT_DATA" = "true" ]; then
    log "  [通道4] svc data disable/enable 重连"
    svc data disable >/dev/null 2>&1
    sleep 1
    svc data enable >/dev/null 2>&1
    log "    data 重连已执行"
  fi

  # --- 验证 ---
  log "  等待 ${VERIFY_DELAY}s 后验证实际网络..."
  sleep "$VERIFY_DELAY"
  NEW_PNM=$(pnm_now)
  NEW_NET=$(net_type)
  log "  验证结果: PNM=$NEW_PNM  gsm.network.type=$NEW_NET"

  OK=0
  case "$TARGET" in
    "$MODE_4G")
      case "$NEW_NET" in
        NR*|*NR*) log "  !! 仍在使用 NR，切 4G 未生效"; OK=0 ;;
        LTE*|*LTE*) log "  OK 已在 LTE"; OK=1 ;;
        *) log "  网络类型=$NEW_NET（非 NR 即视为可用）"; OK=1 ;;
      esac
      ;;
    *)
      case "$NEW_NET" in
        NR*) log "  OK 已在 NR（5G）"; OK=1 ;;
        *) log "  网络类型=$NEW_NET，暂未看到 NR"; OK=0 ;;
      esac
      ;;
  esac

  if [ "$OK" = "0" ] && [ "$RETRY_ON_FAIL" = "true" ]; then
    log "  首轮未达预期，重试一次（额外 data 重连）"
    cmd phone set-preferred-network-type "$TARGET" >/dev/null 2>&1
    settings put global preferred_network_mode "$TARGET" 2>/dev/null
    svc data disable >/dev/null 2>&1
    sleep 2
    svc data enable >/dev/null 2>&1
    sleep "$VERIFY_DELAY"
    NEW_NET=$(net_type)
    log "  重试后: gsm.network.type=$NEW_NET"
  fi

  log "== 切换结束 =="
  logsep
  rotate_log
}

# ---------------- 启动环境快照 ----------------
snapshot_env() {
  logsep
  log "============ 守护进程启动 v1.1 ============"
  log "  时间       : $(date)"
  log "  模块目录   : $MODDIR"
  log "  日志文件   : $LOG_FILE"
  log "  配置       : POLL=$POLL_INTERVAL ON_DELAY=$SCREEN_ON_DELAY 5G=$MODE_5G 4G=$MODE_4G"
  log "  提示方式   : $TOAST_MODE  重连数据=$RECONNECT_DATA  验证延迟=${VERIFY_DELAY}s"
  log "  机型       : $(getprop ro.product.model)"
  log "  品牌       : $(getprop ro.product.brand)"
  log "  Android    : $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
  log "  OriginOS   : $(getprop ro.vivo.os.version)"
  log "  当前 PNM   : $(pnm_now)"
  log "  当前网络   : $(net_type)"
  screen_probe
  if [ "$?" = "0" ]; then
    log "  初始屏幕   : OFF ($SCREEN_SRC)"
  else
    log "  初始屏幕   : ON ($SCREEN_SRC)"
  fi
  log "  cmd phone  : $(command -v cmd >/dev/null 2>&1 && echo 可用 || echo 缺失)"
  log "  service    : $(command -v service >/dev/null 2>&1 && echo 可用 || echo 缺失)"
  log "============ 进入主循环 ================="
  logsep
}

# ---------------- 主循环 ----------------
snapshot_env

LAST_STATE=""
LAST_SWITCH_TS=0
FIRST_RUN="1"

while true; do
  screen_probe
  if [ "$?" = "0" ]; then CUR="0"; else CUR="1"; fi

  # 首次只记录状态，不触发切换（避免开机就无谓地动一次网络）
  if [ "$FIRST_RUN" = "1" ]; then
    log "初始状态记录: $CUR  来源=$SCREEN_SRC （不触发切换）"
    LAST_STATE=$CUR
    FIRST_RUN="0"
    sleep "$POLL_INTERVAL"
    continue
  fi

  if [ "$CUR" != "$LAST_STATE" ]; then
    NOW=$(date +%s)
    GAP=$((NOW - LAST_SWITCH_TS))

    log "状态变化: ${LAST_STATE:-init} -> $CUR  来源=$SCREEN_SRC"

    if [ -n "$LAST_STATE" ] && [ "$GAP" -lt "$MIN_SWITCH_INTERVAL" ]; then
      log "  间隔仅 ${GAP}s < ${MIN_SWITCH_INTERVAL}s，防抖跳过（只更新状态）"
      LAST_STATE=$CUR
    elif [ "$CUR" = "0" ]; then
      log "  → 熄屏，切换到 4G (mode=$MODE_4G)"
      apply_mode "$MODE_4G"
      show_msg "$TOAST_TEXT_4G"
      LAST_SWITCH_TS=$(date +%s)
      LAST_STATE=$CUR
    else
      log "  → 亮屏，等待 ${SCREEN_ON_DELAY}s 后切 5G"
      sleep "$SCREEN_ON_DELAY"
      screen_probe
      if [ "$?" = "1" ]; then
        log "  亮屏确认（$SCREEN_SRC）→ 切换到 5G (mode=$MODE_5G)"
        apply_mode "$MODE_5G"
        show_msg "$TOAST_TEXT_5G"
        LAST_SWITCH_TS=$(date +%s)
        LAST_STATE=$CUR
      else
        log "  延迟期间又熄屏（$SCREEN_SRC），放弃本次 5G 切换"
        LAST_STATE="0"
      fi
    fi
  fi

  sleep "$POLL_INTERVAL"
done