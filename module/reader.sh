#!/system/bin/sh

restore_usage_reader() (
  reader=local.cmcc.usage/.MobileReader
  long_reader=local.cmcc.usage/local.cmcc.usage.MobileReader
  restored=false
  for attempt in 1 2 3; do
    services=$(settings --user 0 get secure enabled_accessibility_services) || exit 1
    case ":$services:" in
      *":$reader:"*|*":$long_reader:"*)
        restored=true
        break
        ;;
    esac
    case "$services" in ""|null) updated="$reader" ;; *) updated="$services:$reader" ;; esac
    # 有其他服务同时写入时重新读取，确保补回自己的条目时使用最新列表。
    latest=$(settings --user 0 get secure enabled_accessibility_services) || exit 1
    [ "$latest" = "$services" ] || continue
    settings --user 0 put secure enabled_accessibility_services "$updated" || exit 1
    restored=true
    break
  done
  [ "$restored" = true ] || exit 1
  master=$(settings --user 0 get secure accessibility_enabled) || exit 1
  [ "$master" = 1 ] || settings --user 0 put secure accessibility_enabled 1
)
