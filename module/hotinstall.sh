#!/system/bin/sh
MODDIR=${0%/*}
BUSYBOX=/data/adb/ksu/bin/busybox

# 热更新会保留旧进程。停掉本模块此前通过 flock 启动的独立会话，以免旧脚本重新打开读取服务。
pids=$(/system/bin/ps -A -o PID,ARGS | awk -v dir="$MODDIR" '
  $3 == "flock" && $4 == "-n" && $6 == "/system/bin/sh" && $8 == "--locked" &&
  ($7 == dir "/service.sh" || $7 == dir "/usage-monitor.sh") {print $1}')
for pid in $pids; do
  group=$(sed 's/^[0-9]* (.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $3, $4}')
  if [ "$group" = "$pid $pid" ]; then
    kill -TERM -- "-$pid" 2>/dev/null || true
  fi
done
if [ -n "$pids" ]; then
    for attempt in 1 2 3; do
        "$BUSYBOX" flock -n /data/adb/china_mobile_usage_panel.usage.lock true && break
        sleep 0.2
    done
fi

/system/bin/sh "$MODDIR/boot-completed.sh"
[ -f "$MODDIR/.refresh-panel" ] || exit 0

# 只刷新已确认独立运行的余量 Provider。新进程会载入当前的 Vector 作用域。
remote=com.miui.securitycenter.remote
for provider_pid in $(pidof "$remote" || true); do
    actual=$(tr '\000' '\n' < "/proc/$provider_pid/cmdline" 2>/dev/null | head -n 1)
    if [ "$actual" = "$remote" ]; then
        kill -TERM "$provider_pid" || exit 1
    fi
done

# 手机管家在新的进程中异步绑定流量服务，安装完成前预热原生查询。
uri=content://com.miui.networkassistant.provider/bill_traffic_inquire
for attempt in 1 2 3; do
    result=$(/system/bin/content query --user 0 --uri "$uri" 2>/dev/null)
    if printf '%s\n' "$result" | grep -q 'sim_slot='; then
        exit 0
    fi
    [ "$attempt" -eq 3 ] || sleep 1
done
printf '%s\n' "- 手机管家正在启动，稍后打开移动数据面板"
