#!/system/bin/sh
# ============================================================
#  ScreenNetSwitch 手动测试脚本  v2.0
#  用途：在 MT 管理器里用 root 手动走一遍，看着结果一步步验证
#  不需要安装模块，单文件即可运行
#
#  用法（MT 管理器）：
#    长按本文件 → 打开方式 → 执行(sh) → 勾选 root
#
#  也可以终端里跑：
#    su -c 'sh /sdcard/Download/sns_test.sh'
# ============================================================

# ---------- 输出目录 ----------
OUT_DIR=/sdcard/Download/ScreenNetSwitch
mkdir -p "$OUT_DIR" 2>/dev/null
chmod 0777 "$OUT_DIR" 2>/dev/null
LOG="$OUT_DIR/manual_test.log"

# ---------- 颜色（部分终端支持，不支持也不影响）----------
C_R=""
C_G=""
C_Y=""
C_N=""

# ---------- 工具函数 ----------
say() {
  echo "$*"
  echo "$*" >> "$LOG" 2>/dev/null
}

line() {
  say "------------------------------------------------------------"
}

title() {
  line
  say "  $*"
  line
}

# 关键结果：同时写日志
ok()   { say "  [OK]   $*"; }
warn() { say "  [!!]   $*"; }
info() { say "         $*"; }

pause_short() {
  sleep 1
}

# ---------- 初始化日志 ----------
{
  echo ""
  echo "============================================================"
  echo " 手动测试开始  $(date '+%Y-%m-%d %H:%M:%S')"
  echo "============================================================"
} >> "$LOG" 2>/dev/null

# ---------- 说明 ----------
# 实测发现：set-allowed-network-types-for-users 改的是「允许哪些制式」
# 而 gsm.network.type 反映的是「当前实际在用哪个」。
# 切 4G 后如果信号好，可能仍显示 NR，这是正常的——
# 要看「允许列表」有没有去掉 NR 来判断命令是否生效。

# ---------- 0. root 检查 ----------
title "0. root 权限检查"

UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" != "0" ]; then
  warn "当前不是 root（uid=$UID_NOW）"
  say ""
  say "请在 MT 管理器里："
  say "  长按脚本 → 打开方式 → 执行(sh) → 右上角勾选「root」"
  say "或者在终端里：su -c 'sh 本脚本路径'"
  exit 1
fi
ok "root 权限正常"

# 检查关键命令是否存在，缺了直接提示（避免一堆 not found 刷屏）
MISSING=""
for c in getprop dumpsys cmd settings svc; do
  command -v "$c" >/dev/null 2>&1 || MISSING="$MISSING $c"
done
if [ -n "$MISSING" ]; then
  warn "缺少关键命令:$MISSING"
  say ""
  say "这说明脚本没有在 Android 系统 shell 里运行。"
  say "如果是在 Termux / Linux 容器里跑，请改用："
  say "  su -c 'sh /sdcard/Download/sns_test.sh'"
  exit 1
fi
ok "关键命令齐全"

# ---------- 1. 基本信息 ----------
title "1. 设备信息（记下来，出问题要报这个）"

MODEL=$(getprop ro.product.model)
BRAND=$(getprop ro.product.brand)
REL=$(getprop ro.build.version.release)
SDK=$(getprop ro.build.version.sdk)
ORIGIN=$(getprop ro.vivo.os.version)
ROM=$(getprop ro.build.display.id)

info "机型     : $MODEL"
info "品牌     : $BRAND"
info "Android  : $REL (SDK $SDK)"
info "OriginOS : ${ORIGIN:-未检测到}"
info "ROM      : $ROM"
info "内核     : $(uname -r 2>/dev/null)"
say ""

# ---------- 2. 命令可用性 ----------
title "2. 系统命令可用性"

HAS_CMD=0
HAS_SERVICE=0
HAS_SETTINGS=0
HAS_SVC=0

[ -x /system/bin/cmd ] && HAS_CMD=1
command -v service >/dev/null 2>&1 && HAS_SERVICE=1
command -v settings >/dev/null 2>&1 && HAS_SETTINGS=1
command -v svc >/dev/null 2>&1 && HAS_SVC=1

[ "$HAS_CMD" = "1" ]      && ok "cmd       可用"    || warn "cmd       缺失"
[ "$HAS_SERVICE" = "1" ]  && ok "service   可用"    || warn "service   缺失"
[ "$HAS_SETTINGS" = "1" ] && ok "settings  可用"    || warn "settings  缺失"
[ "$HAS_SVC" = "1" ]      && ok "svc       可用"    || warn "svc       缺失"
say ""

# ---------- 3. 关键：cmd phone 到底支持什么 ----------
title "3. 检查 cmd phone 支不支持「切换网络类型」"

# v1.1 用过的命令是错的，这里实际验证
if [ "$HAS_CMD" = "1" ]; then
  if cmd phone 2>/dev/null | grep -q "set-allowed-network-types-for-users"; then
    ok "支持 set-allowed-network-types-for-users（这是真正能用的命令）"
    PHONE_CMD_OK=1
  else
    warn "不支持 set-allowed-network-types-for-users"
    PHONE_CMD_OK=0
  fi

  # 顺便说明 v1.1 用的那个命令不存在
  if cmd phone 2>/dev/null | grep -q "set-preferred-network-type"; then
    info "也支持 set-preferred-network-type（少见）"
  else
    info "不支持 set-preferred-network-type（AOSP 本来就没有，v1.1 用错了）"
  fi
else
  PHONE_CMD_OK=0
fi
say ""

# ---------- 4. 当前网络状态 ----------
title "4. 当前网络状态（切换前的基线）"

SCREEN_W=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')
case "$SCREEN_W" in
  *Awake*)  SCREEN_ST="亮屏" ;;
  *Asleep*) SCREEN_ST="熄屏" ;;
  *Dozing*) SCREEN_ST="熄屏(Doze)" ;;
  *)        SCREEN_ST="未知" ;;
esac

NET_TYPE=$(getprop gsm.network.type)
PNM=$(settings get global preferred_network_mode 2>/dev/null)
OPERATOR=$(getprop gsm.operator.alpha)
SIM_STATE=$(getprop gsm.sim.state)

info "屏幕状态        : $SCREEN_ST ($SCREEN_W)"
info "gsm.network.type: ${NET_TYPE:-空}"
info "运营商          : ${OPERATOR:-空}"
info "SIM 状态        : ${SIM_STATE:-空}"
info "preferred_network_mode: ${PNM:-空}"
say ""
info "注意：双卡手机的 gsm.network.type 是「卡1,卡2」合在一起的（如 LTE,NR_SA）"
info "      下面测试只操作卡1（slot 0），所以卡2 的值不会变，属正常"
say ""

# 记录基线用于最后对比
BASE_NET="$NET_TYPE"

if [ "$HAS_CMD" = "1" ]; then
  BASE_ALLOWED=$(cmd phone get-allowed-network-types-for-users -s 0 2>/dev/null)
  info "slot0 允许的网络类型:"
  info "  ${BASE_ALLOWED:-读不到}"
  say ""
fi

# 判断当前是否 5G
case "$NET_TYPE" in
  *NR*) ok "当前是 5G（检测到 NR）" ;;
  *)    info "当前不是 5G（无 NR）" ;;
esac
say ""

# ---------- 5. 测试切 4G ----------
title "5. 测试①：切换到 4G（LTE Only）"

A_WORKS=0
TEST4G_RESULT=0

say "  说明：下面会依次尝试 3 种方式，看哪种能把网络切到 4G"
say "        每种方式后都会等几秒再读实际网络类型"
say ""

# --- 方式A: cmd phone set-allowed-network-types-for-users ---
if [ "$PHONE_CMD_OK" = "1" ]; then
  say "  [方式A] cmd phone set-allowed-network-types-for-users -s 0 01000001000000000000"
  say "          （LTE only 位掩码）"
  RESP=$(cmd phone set-allowed-network-types-for-users -s 0 01000001000000000000 2>&1)
  RC=$?
  info "返回码: $RC  输出: ${RESP:-<空>}"

  MASK_A=$(cmd phone get-allowed-network-types-for-users -s 0 2>/dev/null)
  info "命令后允许列表:"
  info "  $MASK_A"

  # 关键判断：允许列表里还有没有 NR（前后补竖线，精确匹配独立项）
  case "|$MASK_A|" in
    *"|NR|"*)
      A_WORKS=0
      if [ "$RC" = "0" ]; then
        warn "命令成功，但允许列表里仍然有 NR —— 系统没真正应用这个限制"
        info "  （部分 ROM 会忽略这个接口，需要走厂商私有通道）"
      else
        warn "命令失败，允许列表未变化"
      fi
      ;;
    *)
      A_WORKS=1
      ok "允许列表里已经不含 NR —— 方式A 生效！"
      ;;
  esac
  TEST4G_RESULT="$A_WORKS"

  sleep 8
  NOW_NET=$(getprop gsm.network.type)
  CARD1=$(echo "$NOW_NET" | cut -d',' -f1)
  info "等待 8s 后 gsm.network.type = ${NOW_NET:-空}（卡1=${CARD1}）"
  case "$CARD1" in
    *NR*) info "卡1 仍在 NR（信号好时会保持 5G 一段时间，正常）" ;;
    *LTE*) ok "卡1 已回落到 LTE！" ;;
    *)    info "卡1 当前: $CARD1" ;;
  esac
else
  warn "跳过方式A（cmd phone 不支持该命令）"
  A_WORKS=0
  TEST4G_RESULT=0
fi
say ""

# --- 方式B: settings put ---
if [ "$HAS_SETTINGS" = "1" ]; then
  say "  [方式B] settings put global preferred_network_mode 11"
  settings put global preferred_network_mode 11 2>&1
  settings put global preferred_network_mode1 11 2>/dev/null
  info "写入后 PNM = $(settings get global preferred_network_mode 2>/dev/null)"
  info "（这只是改数据库，历史经验表明大概率不会立刻影响基带）"
else
  warn "跳过方式B（没有 settings 命令）"
fi
say ""

# --- 方式C: 数据重连 ---
if [ "$HAS_SVC" = "1" ]; then
  say "  [方式C] svc data disable → enable 强制重连"
  svc data disable 2>&1
  info "已关闭数据，等待 2s"
  sleep 2
  svc data enable 2>&1
  info "已开启数据，等待 10s 让基带重注册"
  sleep 10
  NOW_NET=$(getprop gsm.network.type)
  CARD1=$(echo "$NOW_NET" | cut -d',' -f1)
  info "重连后 gsm.network.type = ${NOW_NET:-空}（卡1=${CARD1}）"
  case "$CARD1" in
    *NR*) info "卡1 仍是 NR（方式C 也没切下来）" ;;
    *LTE*) ok "卡1 已回落到 LTE！" ;;
    *)    info "卡1 当前: $CARD1" ;;
  esac
else
  warn "跳过方式C（没有 svc 命令）"
fi
say ""

say "  >>> 请现在看一眼状态栏，5G 图标是不是变成 4G 了？"
say "  >>> 如果变了，说明切 4G 这条路是通的"
say ""

# ---------- 6. 测试切 5G ----------
title "6. 测试②：切换回 5G"

RESTORE_MASK=""
if [ "$HAS_CMD" = "1" ]; then
  say "  恢复为「允许 NR」，位掩码：11001111101111111111"
  say "          (NR|LTE|CDMA|EVDO|GSM|WCDMA)"
  RESP=$(cmd phone set-allowed-network-types-for-users -s 0 11001111101111111111 2>&1)
  info "返回码: $?  输出: ${RESP:-<空>}"
  MASK_R=$(cmd phone get-allowed-network-types-for-users -s 0 2>/dev/null)
  info "恢复后允许列表:"
  info "  $MASK_R"
  case "|$MASK_R|" in
    *"|NR|"*) ok "NR 已恢复为允许" ;;
    *)        warn "NR 没恢复，建议手动到系统设置里开一下 5G" ;;
  esac
else
  warn "没有 cmd 命令，只能用 settings"
fi

if [ "$HAS_SETTINGS" = "1" ]; then
  say "  同时写回 PNM（原值 ${PNM:-26,26}）"
  settings put global preferred_network_mode "${PNM:-26}" 2>/dev/null
  settings put global preferred_network_mode1 "${PNM:-26}" 2>/dev/null
fi

if [ "$HAS_SVC" = "1" ]; then
  say "  数据重连触发重注册..."
  svc data disable 2>/dev/null
  sleep 2
  svc data enable 2>/dev/null
fi

info "等待 15s 让 5G 重注册（5G 重新挂上比切 4G 慢）"
sleep 15
NOW_NET=$(getprop gsm.network.type)
CARD1=$(echo "$NOW_NET" | cut -d',' -f1)
info "重连后 gsm.network.type = ${NOW_NET:-空}（卡1=${CARD1}）"
case "$CARD1" in
  *NR*) ok "卡1 已回到 NR（5G）！" ;;
  *LTE*) info "卡1 还是 LTE，5G 没回来（可能信号弱或需要更久，属正常）" ;;
  *)    info "卡1 当前: $CARD1" ;;
esac
say ""
say "  >>> 再看一眼状态栏，是不是变回 5G 了？"
say ""

# ---------- 7. 测试提示通道 ----------
title "7. 测试③：提示能不能弹出来"

if [ "$HAS_CMD" = "1" ]; then
  say "  发送一条测试通知..."
  RESP=$(cmd notification post -S bigtext -t "网络切换" sns_test "测试通知：如果你看到这条，说明提示通道正常" 2>&1)
  info "返回码: $?  输出: ${RESP:-<空>}"
  ok "已发送，请下拉通知栏看一下"
else
  warn "没有 cmd，无法测试通知"
fi
say ""

# ---------- 8. 屏幕检测 ----------
title "8. 屏幕状态检测（自动化功能依赖这个）"

W=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=')
info "mWakefulness : ${W:-读不到}"

P=$(dumpsys power 2>/dev/null | grep -m1 'Display Power:')
info "DisplayPower : ${P:-读不到}"

BL=""
for f in /sys/class/backlight/*/actual_brightness; do
  [ -r "$f" ] && BL="$BL $(cat "$f" 2>/dev/null)"
done
info "backlight    :${BL:- 读不到}"

D=$(dumpsys display 2>/dev/null | grep -m1 'mScreenState=')
info "screenState  : ${D:-读不到}"
say ""

if [ -n "$W" ]; then
  ok "屏幕检测可用（用的是 mWakefulness）"
else
  warn "mWakefulness 读不到，模块会自动降级到其他方式"
fi
say ""

# ---------- 9. 当前最终状态 ----------
title "9. 测试结束，最终状态"

FINAL_NET=$(getprop gsm.network.type)
FINAL_PNM=$(settings get global preferred_network_mode 2>/dev/null)
FINAL_ALLOWED=""
if [ "$HAS_CMD" = "1" ]; then
  FINAL_ALLOWED=$(cmd phone get-allowed-network-types-for-users -s 0 2>/dev/null)
fi

info "gsm.network.type        : ${FINAL_NET:-空}"
info "preferred_network_mode  : ${FINAL_PNM:-空}"
info "slot0 允许的网络类型    :"
info "  ${FINAL_ALLOWED:-读不到}"
say ""

# ---------- 10. 结论 ----------
title "10. 结论"

# 从最终允许列表里判断
NR_ALLOWED="否"
case "|$FINAL_ALLOWED|" in
  *"|NR|"*) NR_ALLOWED="是" ;;
esac

say ""
if [ "$TEST4G_RESULT" = "1" ]; then
  ok "切换通道有效 —— 允许列表能被成功改成 LTE only"
  say ""
  say "  说明这台机器的 cmd phone 接口是通的，模块可以工作。"
  say "  如果实际状态栏没变，可能是："
  say "  - 双卡：只操作了卡1，卡2 仍是 5G"
  say "  - 信号好时手机不愿从 5G 掉下来（正常行为）"
  say "  - 需要更长时间（有时 30s+）"
else
  warn "切换通道可能无效 —— 允许列表没能被改成 LTE only"
  say ""
  say "  这说明 cmd phone 接口被 ROM 拦截了。"
  say "  模块需要改用厂商私有通道（vivo/iQOO 需要额外适配）。"
  say "  请把这份日志发出来。"
fi

say ""
info "当前卡1 网络类型: $(echo "$FINAL_NET" | cut -d',' -f1)"
info "当前 NR 是否被允许: $NR_ALLOWED"
say ""

line
say "  测试完成，完整记录已保存到："
say "  $LOG"
say ""
say "  请把这个文件发出来，就能定位问题在哪一步。"
line

say ""
say "按回车退出..."
read -r _ 2>/dev/null
exit 0