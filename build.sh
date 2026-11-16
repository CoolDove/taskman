#!/usr/bin/env bash
# 无 Gradle 的极简构建：aapt2 + javac + d8 + zipalign + apksigner
# 用法：./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
DEPS="$ROOT/libs/api-classes.jar:$ROOT/libs/provider-classes.jar:$ROOT/libs/shared-classes.jar:$ROOT/libs/aidl-classes.jar"

# 手机上只有 arm64 的 aapt2 / zipalign（Debian 打包版）
AAPT2="${AAPT2:-/usr/lib/android-sdk/build-tools/debian/aapt2}"
ZIPALIGN="${ZIPALIGN:-/usr/lib/android-sdk/build-tools/debian/zipalign}"
APKSIGNER="${APKSIGNER:-/usr/lib/android-sdk/build-tools/debian/apksigner}"

PKG=app.dsh.running
OUT="$ROOT/build"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 编译资源"
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res"

echo "==> 链接 manifest + 资源"
sed "s|<manifest |<manifest package=\"$PKG\" |" \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    -R "$OUT"/res/*.flat \
    --auto-add-overlay

echo "==> javac"
javac --release 17 -encoding UTF-8 -classpath "$JAR:$DEPS" -d "$OUT/classes" \
    $(find "$OUT/gen" "$ROOT/app/src/main/java" -name '*.java')

echo "==> d8 (dex)"
"$BT/d8" --release --min-api 23 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') \
    "$ROOT/libs/api-classes.jar" "$ROOT/libs/provider-classes.jar" "$ROOT/libs/shared-classes.jar" "$ROOT/libs/aidl-classes.jar"

echo "==> 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
if command -v zip >/dev/null 2>&1; then
    (cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
else
    # 没装 zip 时用 Python 把 dex 塞进 APK 根目录
    python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
fi
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=DSH Debug,O=DSH,C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/TaskMan-debug.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/TaskMan-debug.apk" >/dev/null

echo "==> 完成"
ls -lh "$OUT/TaskMan-debug.apk"
