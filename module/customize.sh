#!/system/bin/sh
VECTOR=/data/adb/modules/zygisk_vector/cli
PACKAGE=local.cmcc.usage
SECURITY_CENTER=com.miui.securitycenter

[ "$BOOTMODE" = true ] || abort "请在已启动的手机上通过 KernelSU 安装。"

installed=false
pm path --user 0 "$PACKAGE" >/dev/null 2>&1 && installed=true
ui_print "- 安装移动卡余量读取应用"
size=$(wc -c < "$MODPATH/usage.apk")
if ! pm install -r --user 0 -S "$size" < "$MODPATH/usage.apk"; then
  abort "移动卡余量应用安装失败。"
fi
# 由 Root 配置本模块的读取权限。
pm grant --user 0 "$PACKAGE" android.permission.READ_PHONE_STATE ||
  abort "无法授权识别中国移动卡。"
pm grant --user 0 "$PACKAGE" android.permission.READ_PHONE_NUMBERS ||
  abort "无法授权读取中国移动卡的号码。"
if [ "$installed" = false ]; then
  # 首次安装沿用模块授权。覆盖升级保留用户当前设置的开关状态。
  . "$MODPATH/reader.sh"
  restore_usage_reader || ui_print "- 请在移动卡余量中手动开启读取服务。"
fi

if ! pm path --user 0 "$SECURITY_CENTER" >/dev/null 2>&1; then
  ui_print "- 未找到澎湃手机管家，可在配套应用内查看余量。"
elif [ -x "$VECTOR" ] && "$VECTOR" status >/dev/null 2>&1 &&
    "$VECTOR" modules enable "$PACKAGE" &&
    "$VECTOR" scope set "$PACKAGE" "$SECURITY_CENTER/0"; then
  touch "$MODPATH/.refresh-panel"
  ui_print "- 已在 Vector 启用移动卡余量，作用域为「手机管家」。"
else
  ui_print "- 配套应用已安装，可在应用内查看余量。"
  ui_print "- 若需显示在控制中心，请在可用的 Vector 或 LSPosed 管理器中启用「移动卡余量」，勾选「手机管家」后重启。"
fi

if [ "${KSU_HAS_HOT_INSTALL:-false}" = true ]; then
  export MODULE_HOT_INSTALL_REQUEST=true
  export MODULE_HOT_RUN_SCRIPT=hotinstall.sh
  if [ -e "$MODPATH/.refresh-panel" ]; then
    ui_print "- 若控制中心尚未显示读数，请重启后再打开移动数据面板。"
  fi
  ui_print "- 读取权限已配置，可在模块「操作」中打开设置。"
else
  ui_print "- 读取权限已配置，重启后可使用模块「操作」。"
fi
