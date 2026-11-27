#!/system/bin/sh
VECTOR=/data/adb/modules/zygisk_vector/cli
PM=/system/bin/pm
APK_TMP_PARENT=/data/local/tmp
PACKAGE=local.cmcc.usage
SECURITY_CENTER=com.miui.securitycenter

[ "${BOOTMODE:-false}" = true ] || abort "请在已启动的手机上通过 KernelSU 或 Magisk 安装。"

install_usage_apk() (
  apk="$MODPATH/usage.apk"
  size=$(wc -c < "$apk" | tr -d '[:space:]')
  if result=$("$PM" install -r --user 0 -S "$size" < "$apk" 2>&1); then
    ui_print "$result"
    return 0
  fi
  ui_print "$result"
  case "$result" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      ui_print "! 已安装的配套应用签名不同，无法覆盖升级。"
      return 1
      ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*|*INSTALL_FAILED_OLDER_SDK*|*INSTALL_FAILED_USER_RESTRICTED*)
      return 1
      ;;
  esac

  # 部分安装环境无法用标准输入传递 APK，改用手机系统可读取的临时文件重试。
  ui_print "- 改用临时文件安装配套应用"
  stage=$(mktemp -d "$APK_TMP_PARENT/sim-usage.XXXXXX") || return 1
  trap 'rm -f "$stage/base.apk"; rmdir "$stage" 2>/dev/null || true' EXIT
  cp "$apk" "$stage/base.apk" && chmod 0755 "$stage" && chmod 0644 "$stage/base.apk" || return 1
  "$PM" install -r --user 0 "$stage/base.apk"
)

installed=false
[ -r "$MODPATH/usage.apk" ] && [ -s "$MODPATH/usage.apk" ] ||
  abort "模块 ZIP 缺少配套 APK，请重新下载模块附件。"
"$PM" path --user 0 "$PACKAGE" >/dev/null 2>&1 && installed=true
ui_print "- 安装澎湃 SIM 卡余量查询"
install_usage_apk || abort "配套应用安装失败，请保存以上安装日志。"
# 系统拒绝自动授权时保留已完成的安装，让用户在配套应用中处理权限。
granted=true
for permission in READ_PHONE_STATE READ_PHONE_NUMBERS; do
  "$PM" grant --user 0 "$PACKAGE" "android.permission.$permission" || granted=false
done
if [ "$granted" = false ]; then
  ui_print "- 请打开配套应用授予电话权限，并在应用内确认读取服务已开启。"
elif [ "$installed" = false ]; then
  # 首次安装沿用模块授权。覆盖升级保留用户当前设置的开关状态。
  . "$MODPATH/reader.sh"
  restore_usage_reader || ui_print "- 请在无障碍中手动开启「澎湃 SIM 卡余量查询」。"
fi

if ! "$PM" path --user 0 "$SECURITY_CENTER" >/dev/null 2>&1; then
  ui_print "- 未找到澎湃手机管家，可在配套应用内查看余量。"
elif [ -x "$VECTOR" ] && "$VECTOR" status >/dev/null 2>&1 &&
    "$VECTOR" modules enable "$PACKAGE" &&
    "$VECTOR" scope set "$PACKAGE" "$SECURITY_CENTER/0"; then
  touch "$MODPATH/.refresh-panel"
  ui_print "- 已在 Vector 启用「澎湃 SIM 卡余量查询」，作用域为「手机管家」。"
else
  ui_print "- 配套应用已安装，可在应用内查看余量。"
  if [ "${KSU:-false}" = true ]; then
    ui_print "- 在 Vector 或 LSPosed 管理器中启用「澎湃 SIM 卡余量查询」，勾选「手机管家」后重启。"
  else
    ui_print "- 控制中心显示需要 Magisk 启用 Zygisk，并在 LSPosed 启用「澎湃 SIM 卡余量查询」、勾选「手机管家」后重启。"
  fi
fi

if [ "${KSU:-false}" = true ] && [ "${KSU_HAS_HOT_INSTALL:-false}" = true ]; then
  export MODULE_HOT_INSTALL_REQUEST=true
  export MODULE_HOT_RUN_SCRIPT=hotinstall.sh
  if [ -e "$MODPATH/.refresh-panel" ]; then
    ui_print "- 若控制中心尚未显示读数，请重启后再打开移动数据面板。"
  fi
  ui_print "- 可在模块「操作」中打开配套应用。"
else
  ui_print "- 重启后可在模块「操作」中打开配套应用。"
fi
