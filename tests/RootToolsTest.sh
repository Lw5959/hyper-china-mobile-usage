#!/bin/sh
set -eu

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
mkdir "$scratch/module" "$scratch/ksu" "$scratch/magisk"
cp module/service.sh module/usage-monitor.sh "$scratch/module/"
# 替换设备路径，验证两个管理器各自的 BusyBox 都能把启动脚本接到监听进程。
sed 's|^  ksu=.*|  ksu="$scratch/ksu/busybox"|; s|^  magisk=.*|  magisk="$scratch/magisk/busybox"|' \
    module/root-tools.sh > "$scratch/module/root-tools.sh"
export scratch

for manager in ksu magisk; do
    cat > "$scratch/$manager/busybox" <<'EOF'
#!/bin/sh
printf '%s %s\n' "$0" "$*" >> "$scratch/commands"
case "$1" in
    setsid)
        shift 2
        exec sh "$@"
        ;;
    flock) exit 0 ;;
    *) exit 1 ;;
esac
EOF
    chmod +x "$scratch/$manager/busybox"
done

check_startup() (
    manager=$1
    if [ "$manager" = ksu ]; then
        export KSU=true
    else
        unset KSU
    fi
    : > "$scratch/commands"
    sh "$scratch/module/service.sh"
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if [ "$(wc -l < "$scratch/commands")" -ge 2 ]; then break; fi
        sleep 0.05
    done
    grep -qxF "$scratch/$manager/busybox setsid /system/bin/sh $scratch/module/usage-monitor.sh" "$scratch/commands"
    grep -qxF "$scratch/$manager/busybox flock -n /data/adb/china_mobile_usage_panel.usage.lock /system/bin/sh $scratch/module/usage-monitor.sh --locked" "$scratch/commands"
)

check_startup magisk
check_startup ksu

. "$scratch/module/root-tools.sh"
unset KSU
chmod -x "$scratch/magisk/busybox"
test "$(usage_busybox)" = "$scratch/ksu/busybox"
chmod -x "$scratch/ksu/busybox"
! usage_busybox
printf '%s\n' '4 项 Root 工具选择和开机启动检查通过'
