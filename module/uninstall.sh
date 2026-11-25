#!/system/bin/sh
VECTOR=/data/adb/modules/zygisk_vector/cli
if [ -x "$VECTOR" ]; then
  # 框架离线时也要继续卸载配套应用。
  "$VECTOR" modules disable local.cmcc.usage || true
fi
pm uninstall --user 0 local.cmcc.usage
