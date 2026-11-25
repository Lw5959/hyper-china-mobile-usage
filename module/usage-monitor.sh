#!/system/bin/sh
BUSYBOX=/data/adb/ksu/bin/busybox

if [ "${1:-}" != --locked ]; then
  umask 077
  exec "$BUSYBOX" flock -n /data/adb/china_mobile_usage_panel.usage.lock /system/bin/sh "$0" --locked
fi

MODDIR=${0%/*}
active() {
  [ -f "$MODDIR/module.prop" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]
}

# 开机早期 PackageManager 还不可用。等 Android 就绪再按读取应用的 UID 监听。
attempt=0
until [ "$(/system/bin/getprop sys.boot_completed)" = 1 ]; do
  active && [ "$attempt" -lt 60 ] || exit 0
  attempt=$((attempt + 1))
  sleep 2
done
active || exit 0
uid=$(/system/bin/pm list packages -U --user 0 local.cmcc.usage | sed -n 's/^package:local\.cmcc\.usage uid:\([0-9][0-9]*\)$/\1/p')
[ -n "$uid" ] || exit 0
uri=content://com.miui.networkassistant.provider/bill_traffic_inquire

# 运营商读数落盘后即交给原生流量管理，即使此时控制中心尚未打开。
while active; do
  /system/bin/logcat -b main -T 1 -v brief "--uid=$uid" -s 'CmccUsagePanel:I' '*:S' |
    while IFS= read -r event && active; do
      case "$event" in
        *": 中国移动流量基准已更新")
          "$BUSYBOX" timeout 8 /system/bin/content query --user 0 --uri "$uri" >/dev/null 2>&1
          ;;
      esac
    done
  sleep 3
done
