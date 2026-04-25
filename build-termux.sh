#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="PhoneAIControl"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
FRAMEWORK_APK="${FRAMEWORK_APK:-/system/framework/framework-res.apk}"
SDK_BIN="$HOME/android-sdk/cmdline-tools/cmdline-tools/bin"
D8_SCRIPT="$SDK_BIN/d8"
BUILD_DIR="build"
GEN_DIR="$BUILD_DIR/gen"
CLS_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
APK_UNSIGNED="$BUILD_DIR/${APP_NAME}-unsigned.apk"
APK_FINAL="$BUILD_DIR/${APP_NAME}.apk"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$SDK_BIN:$PREFIX/bin:$PATH"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "Missing android.jar at: $ANDROID_JAR"
  exit 1
fi

if [ ! -f "$FRAMEWORK_APK" ]; then
  echo "Missing Android framework APK at: $FRAMEWORK_APK"
  exit 1
fi

if [ ! -f "$D8_SCRIPT" ]; then
  echo "Missing d8 script at: $D8_SCRIPT"
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$CLS_DIR" "$DEX_DIR"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$KEYSTORE_PASS" \
    -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASS" \
    -dname "CN=Android Debug,O=Termux,C=GB" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000
fi

aapt2 compile --dir res -o "$BUILD_DIR/res.zip"
aapt2 link \
  -I "$FRAMEWORK_APK" \
  --manifest AndroidManifest.xml \
  --java "$GEN_DIR" \
  -o "$APK_UNSIGNED" \
  "$BUILD_DIR/res.zip"

find src "$GEN_DIR" -name '*.java' | sort > "$BUILD_DIR/sources.list"
javac \
  -source 8 \
  -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$CLS_DIR" \
  @"$BUILD_DIR/sources.list"

jar cf "$BUILD_DIR/classes.jar" -C "$CLS_DIR" .
sh "$D8_SCRIPT" --lib "$ANDROID_JAR" --output "$DEX_DIR" "$BUILD_DIR/classes.jar"

cp "$DEX_DIR/classes.dex" "$BUILD_DIR/classes.dex"
(
  cd "$BUILD_DIR"
  zip -q -u "$(basename "$APK_UNSIGNED")" classes.dex
)

apksigner sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$APK_FINAL" \
  "$APK_UNSIGNED"

apksigner verify "$APK_FINAL"
echo "Built APK: $APK_FINAL"
