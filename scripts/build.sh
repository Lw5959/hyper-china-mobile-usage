#!/bin/sh
set -eu

project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
test -n "$sdk" || { echo '请设置 ANDROID_SDK_ROOT' >&2; exit 1; }
tools="$sdk/build-tools/37.0.0"
android="$sdk/platforms/android-36/android.jar"
test -r "$android" && test -x "$tools/aapt2" || {
    echo '请安装 Android SDK Platform 36 和 Build Tools 37.0.0' >&2
    exit 1
}
mkdir -p build dist
stage=$(mktemp -d "$project/build/package.XXXXXX")
trap 'rm -rf "$stage"' EXIT HUP INT TERM

xposed=${XPOSED_API_JAR:-"$project/build/xposed-api-82.jar"}
if [ ! -f "$xposed" ]; then
    curl -fLSs --max-time 30 -o "$stage/xposed-api.jar" \
        https://maven.aliyun.com/repository/jcenter/de/robv/android/xposed/api/82/api-82.jar
    mv "$stage/xposed-api.jar" "$xposed"
fi
checksum=$(shasum -a 256 "$xposed" | awk '{print $1}')
test "$checksum" = f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25 ||
    { echo 'Xposed API 82 SHA-256 校验失败' >&2; exit 1; }

mkdir -p "$stage/classes" "$stage/generated" "$stage/dex" "$stage/module"
"$tools/aapt2" compile --dir app/src/main/res -o "$stage/resources.zip"
"$tools/aapt2" link -o "$stage/unsigned.apk" -I "$android" \
    --manifest app/src/main/AndroidManifest.xml --java "$stage/generated" -A app/src/main/assets "$stage/resources.zip"
find app/src/main/java "build/$(basename "$stage")/generated" -name '*.java' | sort > "$stage/sources"
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -classpath "$android:$xposed" \
    -d "$stage/classes" @"$stage/sources"
jar cf "$stage/classes.jar" -C "$stage/classes" .
# Xposed API 仅用于编译，原生面板所需 API 由兼容框架在运行时提供。
"$tools/d8" --min-api 31 --lib "$android" --classpath "$xposed" \
    --output "$stage/dex" "$stage/classes.jar"
zip -q -j "$stage/unsigned.apk" "$stage/dex/classes.dex"
"$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"

# 保留本地签名文件以支持覆盖升级，发布包只包含签好的 APK。
keystore="$project/build/usage-panel-local.jks"
if [ ! -f "$keystore" ]; then
    keytool -genkeypair -keystore "$keystore" -alias cmcc-usage -storepass changeit \
        -keypass changeit -keyalg RSA -keysize 2048 -validity 9000 -dname 'CN=Local CMCC Usage'
    chmod 600 "$keystore"
fi
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias cmcc-usage --ks-pass pass:changeit \
    --out "$stage/module/usage.apk" "$stage/aligned.apk"
"$tools/apksigner" verify "$stage/module/usage.apk"

cp module/module.prop module/customize.sh module/action.sh module/uninstall.sh module/hotinstall.sh \
    module/service.sh module/boot-completed.sh module/reader.sh module/usage-monitor.sh "$stage/module/"
(cd "$stage/module" && zip -q -X "$stage/china-mobile-usage-ksu.zip" \
    module.prop customize.sh action.sh uninstall.sh hotinstall.sh service.sh boot-completed.sh \
    reader.sh usage-monitor.sh usage.apk)
unzip -tq "$stage/china-mobile-usage-ksu.zip"
version=$(sed -n 's/^version=//p' module/module.prop)
mv "$stage/china-mobile-usage-ksu.zip" "dist/china-mobile-usage-ksu-v$version.zip"
printf '%s\n' "$project/dist/china-mobile-usage-ksu-v$version.zip"
