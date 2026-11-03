#!/system/bin/sh
# ============================================
#                用户配置中心  v1.1
#  改完重启手机生效，或执行 action.sh reload
# ============================================

# ---- 屏幕检测轮询间隔（秒）----
POLL_INTERVAL=2

# ---- 亮屏后延迟多久再开 5G（秒）----
SCREEN_ON_DELAY=1

# ---- 防抖动：两次切换之间最小间隔（秒）----
MIN_SWITCH_INTERVAL=20

# ---- 网络模式数值（AOSP RILConstants.java）----
# 9  = NR/LTE/GSM/WCDMA        -> 开 5G（联通/电信）
# 32 = NR/LTE/TDSCMA/GSM/WCDMA -> 开 5G（移动）
# 11 = LTE Only                -> 纯 4G，禁 5G
# 26 = NR/LTE/GSM/WCDMA        -> 开 5G（联通/电信备选）
MODE_5G=9
MODE_4G=11

# ---- 是否写日志 ----
LOG_ENABLE=true

# ---- 日志目录（下载目录，普通文件管理器可直接看）----
LOG_DIR=/sdcard/Download/ScreenNetSwitch

# ---- 单个日志文件上限（行数），超了自动截断 ----
LOG_MAX_LINES=4000

# ---- 是否弹提示 ----
TOAST_ENABLE=true

# ---- 提示方式 ----
#   notification = 系统通知栏（稳定，推荐）
#   toast        = 悬浮吐司（需 dex 编译，多数机型会失败）
TOAST_MODE=notification

# ---- 亮屏提示文案 ----
TOAST_TEXT_5G="已切换到5G网络"
# ---- 熄屏提示文案（屏幕黑了看不见，默认空 = 不弹）----
TOAST_TEXT_4G=""

# ---- 切换后是否重连数据（部分机型必须，否则不生效）----
RECONNECT_DATA=true

# ---- 切换后等待多少秒再读实际网络类型做验证 ----
VERIFY_DELAY=6

# ---- 是否强制校验：切完发现没生效就再重试一次 ----
RETRY_ON_FAIL=true