#!/system/bin/sh
# ============================================
#  customize.sh —— 安装时执行：编译 Toast 的 dex
# ============================================
SKIPUNZIP=0

ui_print "- 熄屏切4G / 亮屏切5G"
ui_print "- 正在编译 Toast 组件..."

# 给脚本执行权限
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh"   0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh"    0 0 0755
set_perm "$MODPATH/screen_watch.sh" 0 0 0755

# 编译 Toast.java -> classes.dex
# 用 dalvikvm 自带的 d8 或 dx。部分 ROM 上没有 d8，用 app_process 直接跑 java 源码不现实，
# 所以这里用「运行时编译」：把源码留在模块里，第一次需要时由 action.sh 编译。
if [ -f "$MODPATH/toast/Toast.java" ]; then
  D8=$(find /system/bin /system/framework -name "d8" -o -name "dx" 2>/dev/null | head -n 1)
  if [ -n "$D8" ]; then
    ui_print "- 找到 $D8，尝试编译 dex"
    mkdir -p "$MODPATH/toast/out"
    cd "$MODPATH/toast" && "$D8" --output "$MODPATH/toast/out" Toast.java >/dev/null 2>&1
    if [ -f "$MODPATH/toast/out/classes.dex" ]; then
      ui_print "- dex 编译成功"
    else
      ui_print "- dex 编译失败，Toast 将使用备用方案"
    fi
  else
    ui_print "- 系统无 d8/dx，Toast 将使用备用方案（见 README）"
  fi
fi

ui_print "- 安装完成，重启生效"
ui_print "- 重启后可用: su -c 'sh $MODPATH/action.sh probe' 探测环境"