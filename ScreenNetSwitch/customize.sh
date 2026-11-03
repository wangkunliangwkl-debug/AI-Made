#!/system/bin/sh
# ============================================
#  customize.sh —— 安装脚本 v1.1
#  v1.0 的 d8 编译 Toast 方案已废弃（系统里没有 d8/dx）
#  改为安装时建好日志目录，提示走系统通知
# ============================================
SKIPUNZIP=0

ui_print ""
ui_print "***********************************"
ui_print "  熄屏切4G / 亮屏切5G  v1.1"
ui_print "***********************************"
ui_print ""

ui_print "- 设置文件权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
for f in service.sh screen_watch.sh action.sh uninstall.sh customize.sh; do
  [ -f "$MODPATH/$f" ] && set_perm "$MODPATH/$f" 0 0 0755
done

# 建日志目录（下载目录），并确保所有 App 可读写
ui_print "- 创建日志目录 /sdcard/Download/ScreenNetSwitch ..."
mkdir -p /sdcard/Download/ScreenNetSwitch 2>/dev/null
chmod 0777 /sdcard/Download/ScreenNetSwitch 2>/dev/null
if [ -d /sdcard/Download/ScreenNetSwitch ]; then
  ui_print "  成功，日志将写入这里"
else
  ui_print "  失败，将退回到模块目录 /data/adb/modules/screen_net_switch"
fi

# 写一条安装记录，方便确认模块装好了
{
  echo "=== 安装记录 $(date '+%Y-%m-%d %H:%M:%S') ==="
  echo "模块版本: v1.1"
  echo "安装时间: $(date)"
  echo "机型: $(getprop ro.product.model)"
  echo "品牌: $(getprop ro.product.brand)"
  echo "Android: $(getprop ro.build.version.release) SDK=$(getprop ro.build.version.sdk)"
  echo "OriginOS: $(getprop ro.vivo.os.version)"
  echo "------------------------------------------"
} >> /sdcard/Download/ScreenNetSwitch/install.log 2>/dev/null

# 关闭 Toast dex 编译尝试，改为提示
ui_print "- 提示方式: 系统通知（不需要编译，比 v1.0 的 toast 可靠）"

ui_print ""
ui_print "- 安装完成，重启手机生效"
ui_print ""
ui_print "重启后如何确认工作正常："
ui_print "  1. 打开文件管理器 → Download/ScreenNetSwitch/"
ui_print "  2. 看 run.log 有没有「守护进程启动」"
ui_print "  3. 灭屏再亮屏，看有没有新记录"
ui_print ""
ui_print "出问题请执行（终端或 Magisk 模块页的『执行』）："
ui_print "  sh /data/adb/modules/screen_net_switch/action.sh diag"
ui_print "  然后把 Download/ScreenNetSwitch/run.log 发出来"
ui_print ""