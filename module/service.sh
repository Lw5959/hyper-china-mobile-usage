#!/system/bin/sh
BUSYBOX=/data/adb/ksu/bin/busybox
MODDIR=${0%/*}

"$BUSYBOX" setsid /system/bin/sh "$MODDIR/usage-monitor.sh" </dev/null >/dev/null 2>&1 &
