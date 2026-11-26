#!/system/bin/sh

usage_busybox() (
  ksu=/data/adb/ksu/bin/busybox
  magisk=/data/adb/magisk/busybox
  # KernelSU 会在脚本环境中设置 KSU，优先使用当前管理器提供的工具。
  if [ "${KSU:-false}" = true ]; then
    set -- "$ksu"
  else
    set -- "$magisk" "$ksu"
  fi
  for candidate do
    [ -x "$candidate" ] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
)
