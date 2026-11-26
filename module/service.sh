#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/root-tools.sh"
BUSYBOX=$(usage_busybox) || exit 1

"$BUSYBOX" setsid /system/bin/sh "$MODDIR/usage-monitor.sh" </dev/null >/dev/null 2>&1 &
