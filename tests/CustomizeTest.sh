#!/bin/sh
set -eu

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
# 保持安装逻辑一致，测试只替换设备上的命令和临时目录路径。
sed -e 's|^VECTOR=.*|VECTOR="$MODPATH/vector-cli"|' -e 's|^PM=.*|PM=pm|' \
    -e 's|^APK_TMP_PARENT=.*|APK_TMP_PARENT="$MODPATH/tmp"|' \
    module/customize.sh > "$scratch/customize.sh"

verify_install() (
    framework=$1
    existing=${3:-false}
    manager=${4:-kernelsu}
    behavior=${5:-normal}
    case "$framework" in no_security_center) native_available=false ;; *) native_available=true ;; esac
    BOOTMODE=true
    KSU_HAS_HOT_INSTALL=$2
    expected_hot=false
    if [ "$manager" = magisk ]; then
        unset KSU
        MAGISK_VER_CODE=31100
    else
        KSU=true
        expected_hot=$KSU_HAS_HOT_INSTALL
    fi
    MODPATH="$scratch/$framework-$manager-$KSU_HAS_HOT_INSTALL-$existing-$behavior"
    unset MODULE_HOT_INSTALL_REQUEST MODULE_HOT_RUN_SCRIPT
    mkdir "$MODPATH"
    mkdir "$MODPATH/tmp"
    printf '%s\n' 'test apk' > "$MODPATH/usage.apk"
    cp module/reader.sh "$MODPATH/reader.sh"
    test_log="$MODPATH/commands"
    export framework test_log

    if [ "$framework" != missing ]; then
        cat > "$MODPATH/vector-cli" <<'EOF'
#!/bin/sh
printf 'vector %s\n' "$*" >> "$test_log"
case "$framework:$1" in
    stopped:status|rejected:scope) exit 1 ;;
esac
EOF
        chmod +x "$MODPATH/vector-cli"
    fi

    pm() {
        printf 'pm %s\n' "$*" >> "$test_log"
        case "$1" in
            path)
                if [ "$4" = local.cmcc.usage ]; then
                    [ "$existing" = true ]
                else
                    [ "$native_available" = true ]
                fi
                ;;
            install)
                if [ "$5" = -S ]; then
                    [ "$6" = 9 ] && [ "$#" = 6 ] || return 1
                    case "$behavior" in
                        different_signature)
                            printf '%s\n' 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' >&2
                            return 1
                            ;;
                        file_retry|file_retry_fails)
                            printf '%s\n' 'Failure [Unable to stream APK]' >&2
                            return 1
                            ;;
                    esac
                    cat > "$MODPATH/installed.apk"
                else
                    case "$5" in "$MODPATH"/tmp/sim-usage.*/base.apk) ;; *) return 1 ;; esac
                    if [ "$behavior" = file_retry_fails ]; then
                        printf '%s\n' 'Failure [INSTALL_FAILED_INVALID_APK]' >&2
                        return 1
                    fi
                    cp "$5" "$MODPATH/installed.apk"
                fi
                ;;
            grant)
                if [ "$behavior" = grant_denied ] && [ "$5" = android.permission.READ_PHONE_NUMBERS ]; then
                    printf '%s\n' 'SecurityException: grant denied' >&2
                    return 1
                fi
                return 0
                ;;
            *) return 1 ;;
        esac
    }
    settings() {
        printf 'settings %s\n' "$*" >> "$test_log"
        case "$3:$5" in
            get:enabled_accessibility_services) printf '%s\n' 'li.songe.gkd/.A11yService' ;;
            get:accessibility_enabled) printf '%s\n' '1' ;;
            put:enabled_accessibility_services) return 0 ;;
            *) return 1 ;;
        esac
    }
    ui_print() { printf '%s\n' "$1"; }
    abort() { printf '%s\n' "$1" >&2; exit 1; }

    if [ "$behavior" = different_signature ] || [ "$behavior" = file_retry_fails ]; then
        if (. "$scratch/customize.sh") > "$MODPATH/output" 2>&1; then return 1; fi
        ! grep -q '^pm grant ' "$test_log"
        [ -z "$(ls -A "$MODPATH/tmp")" ]
        if [ "$behavior" = different_signature ]; then
            grep -qF 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$MODPATH/output"
            grep -qF '签名不同' "$MODPATH/output"
            ! grep -qF '改用临时文件' "$MODPATH/output"
        else
            grep -qF 'INSTALL_FAILED_INVALID_APK' "$MODPATH/output"
            grep -qF '请保存以上安装日志' "$MODPATH/output"
        fi
        return 0
    fi

    . "$scratch/customize.sh" > "$MODPATH/output" 2>&1
    cmp "$MODPATH/usage.apk" "$MODPATH/installed.apk"
    [ -z "$(ls -A "$MODPATH/tmp")" ]
    if [ "$behavior" = file_retry ]; then
        grep -qF 'Unable to stream APK' "$MODPATH/output"
        grep -qF '改用临时文件' "$MODPATH/output"
    fi
    grep -qxF 'pm grant --user 0 local.cmcc.usage android.permission.READ_PHONE_STATE' "$test_log"
    grep -qxF 'pm grant --user 0 local.cmcc.usage android.permission.READ_PHONE_NUMBERS' "$test_log"
    if [ "$behavior" = grant_denied ]; then
        grep -qF '请打开配套应用授予电话权限' "$MODPATH/output"
        ! grep -q '^settings ' "$test_log"
    elif [ "$existing" = true ]; then
        ! grep -q '^settings ' "$test_log"
    else
        grep -qxF 'settings --user 0 put secure enabled_accessibility_services li.songe.gkd/.A11yService:local.cmcc.usage/.MobileReader' "$test_log"
    fi
    [ "${MODULE_HOT_INSTALL_REQUEST:-false}" = "$expected_hot" ]
    if [ "$expected_hot" = true ]; then
        [ "${MODULE_HOT_RUN_SCRIPT:-}" = hotinstall.sh ]
    else
        [ -z "${MODULE_HOT_RUN_SCRIPT:-}" ]
    fi
    case "$framework" in
        running)
            grep -qxF 'vector scope set local.cmcc.usage com.miui.securitycenter/0' "$test_log"
            grep -qF '已在 Vector 启用' "$MODPATH/output"
            [ -f "$MODPATH/.refresh-panel" ]
            ;;
        no_security_center)
            [ ! -f "$MODPATH/.refresh-panel" ]
            grep -qF '未找到澎湃手机管家' "$MODPATH/output"
            ! grep -q '^vector ' "$test_log"
            ;;
        missing|stopped|rejected)
            [ ! -f "$MODPATH/.refresh-panel" ]
            if [ "$manager" = magisk ]; then
                grep -qF 'Magisk 启用 Zygisk' "$MODPATH/output"
            else
                grep -qF 'Vector 或 LSPosed 管理器中启用' "$MODPATH/output"
            fi
            ;;
    esac
)

verify_install missing true
verify_install stopped true
verify_install rejected true
verify_install running true
verify_install running false
verify_install no_security_center true
verify_install running true true
verify_install missing false false magisk
verify_install missing true true magisk
verify_install missing false false magisk file_retry
verify_install missing false true magisk different_signature
verify_install missing false true magisk file_retry_fails
verify_install missing false false magisk grant_denied
printf '%s\n' '13 项安装兼容性检查通过，覆盖升级保留读取服务开关'
