#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================="
echo "TextGrab - Automated Release APK Builder"
echo "=========================================="

KEY_ALIAS="my-key"
KEY_PASS="Sh@090609"
STORE_PASS="Sh@090609"

# 1. Look for existing .jks in root, or create one if missing
JKS_FILE=$(find "$SCRIPT_DIR" -maxdepth 1 -name "*.jks" | head -n 1 || true)

if [ -z "$JKS_FILE" ] || [ ! -f "$JKS_FILE" ]; then
    echo "==> No .jks keystore found in root. Generating release.jks..."
    JKS_FILE="$SCRIPT_DIR/release.jks"
    keytool -genkeypair \
        -v \
        -keystore "$JKS_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=TextGrab, OU=Release, O=TextGrab, L=City, ST=State, C=US"
    echo "==> Generated: $JKS_FILE"
else
    echo "==> Found existing root keystore: $(basename "$JKS_FILE")"
fi

JKS_NAME=$(basename "$JKS_FILE")

# 2. Ensure keystore properties directory and file are configured
mkdir -p "$SCRIPT_DIR/keystore"
cat <<EOF > "$SCRIPT_DIR/keystore/keystore.properties"
storeFile=$JKS_NAME
storePassword=$STORE_PASS
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASS
EOF

echo "==> Keystore configuration ready."

# 3. Resolve Gradle command
if [ -f "$SCRIPT_DIR/gradlew" ]; then
    chmod +x "$SCRIPT_DIR/gradlew" 2>/dev/null || true
fi

if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
elif [ -x "$SCRIPT_DIR/gradlew" ]; then
    GRADLE_CMD="$SCRIPT_DIR/gradlew"
else
    echo "Error: Neither 'gradle' nor './gradlew' is available." >&2
    exit 1
fi

echo "==> Using Gradle command: $GRADLE_CMD"

# 4. Clean previous release artifacts
rm -f "$SCRIPT_DIR/TextGrab-release-signed.apk"
rm -rf "$SCRIPT_DIR/app/build/outputs/apk/release"

# 5. Assemble Release APK
echo "==> Building release APK with Gradle..."
$GRADLE_CMD :app:assembleRelease

# 6. Locate generated APK
GENERATED_APK=$(find "$SCRIPT_DIR/app/build/outputs/apk/release" -name "*.apk" | head -n 1 || true)

if [ -z "$GENERATED_APK" ] || [ ! -f "$GENERATED_APK" ]; then
    echo "Error: Release build failed to produce an APK." >&2
    exit 1
fi

echo "==> Located APK at: $GENERATED_APK"

# 7. Find apksigner if available
APKSIGNER_BIN=""
if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER_BIN=$(command -v apksigner)
elif [ -n "$ANDROID_HOME" ] && find "$ANDROID_HOME/build-tools" -name apksigner 2>/dev/null | grep -q apksigner; then
    APKSIGNER_BIN=$(find "$ANDROID_HOME/build-tools" -name apksigner 2>/dev/null | sort -V | tail -n 1)
elif find /opt/android/sdk/build-tools -name apksigner 2>/dev/null | grep -q apksigner; then
    APKSIGNER_BIN=$(find /opt/android/sdk/build-tools -name apksigner 2>/dev/null | sort -V | tail -n 1)
fi

# 8. Check signature or sign if unsigned
if [ -n "$APKSIGNER_BIN" ]; then
    echo "==> Verifying APK signature with apksigner..."
    if ! "$APKSIGNER_BIN" verify "$GENERATED_APK" >/dev/null 2>&1; then
        echo "==> APK not yet signed. Signing with $JKS_NAME..."
        "$APKSIGNER_BIN" sign \
            --ks "$JKS_FILE" \
            --ks-pass "pass:$STORE_PASS" \
            --ks-key-alias "$KEY_ALIAS" \
            --key-pass "pass:$KEY_PASS" \
            "$GENERATED_APK"
    fi
    "$APKSIGNER_BIN" verify --verbose "$GENERATED_APK"
else
    echo "==> Note: apksigner not found in PATH; Gradle handled signing during assembleRelease."
fi

# 9. Deliver final signed release APK to root
OUTPUT_APK="$SCRIPT_DIR/TextGrab-release-signed.apk"
cp "$GENERATED_APK" "$OUTPUT_APK"
chmod 666 "$OUTPUT_APK"

echo "=========================================="
echo " SUCCESS! Signed Release APK created:"
echo " Path: $OUTPUT_APK"
echo " Size: $(du -h "$OUTPUT_APK" | cut -f1)"
if command -v sha256sum >/dev/null 2>&1; then
    echo " SHA256: $(sha256sum "$OUTPUT_APK" | cut -d' ' -f1)"
fi
echo "=========================================="
