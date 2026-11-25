#!/bin/sh
set -eu
. module/reader.sh

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM

settings() {
    case "$3:$5" in
        get:enabled_accessibility_services) cat "$scratch/services" ;;
        get:accessibility_enabled) cat "$scratch/master" ;;
        put:enabled_accessibility_services)
            printf '%s\n' "$6" > "$scratch/services"
            printf '%s\n' 'services' >> "$scratch/writes"
            ;;
        put:accessibility_enabled)
            printf '%s\n' "$6" > "$scratch/master"
            printf '%s\n' 'master' >> "$scratch/writes"
            ;;
        *) return 1 ;;
    esac
}

other='li.songe.gkd/.A11yService:example.reader/.Service'
printf '%s\n' "$other" > "$scratch/services"
printf '%s\n' '1' > "$scratch/master"
: > "$scratch/writes"
restore_usage_reader
test "$(cat "$scratch/services")" = "$other:local.cmcc.usage/.MobileReader"
test "$(cat "$scratch/writes")" = services
restore_usage_reader
test "$(cat "$scratch/writes")" = services

printf '%s\n' "local.cmcc.usage/local.cmcc.usage.MobileReader:$other" > "$scratch/services"
: > "$scratch/writes"
restore_usage_reader
test ! -s "$scratch/writes"

printf '%s\n' 'null' > "$scratch/services"
printf '%s\n' '0' > "$scratch/master"
restore_usage_reader
test "$(cat "$scratch/services")" = 'local.cmcc.usage/.MobileReader'
test "$(cat "$scratch/master")" = 1
printf '%s\n' '4 项无障碍恢复检查通过'
