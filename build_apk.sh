#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "📱 BUILDING OMNIFACE AI PRODUCTION APK (ARM64 NATIVE)"
echo "=========================================================="
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME=/root/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64

cd "$DIR"

# Prepare asset metadata (Do not bundle heavy models — user downloads on-demand)
mkdir -p "$DIR/app/src/main/assets"
rm -f "$DIR/app/src/main/assets"/*.tflite
cp -v "$DIR"/models/class_labels.json "$DIR/app/src/main/assets/" 2>/dev/null || true

echo "[+] Executing Signed Production Gradle Assembly on Linux ARM64..."
bash ./gradlew assembleRelease --build-cache --parallel --stacktrace

if [ -f "$DIR/app/build/outputs/apk/release/app-release.apk" ]; then
    cp "$DIR/app/build/outputs/apk/release/app-release.apk" "$DIR/OmniFace-AI.apk"
    APK_SIZE=$(du -h "$DIR/OmniFace-AI.apk" | awk '{print $1}')
    echo ""
    echo "=========================================================="
    echo "✅ OMNIFACE AI SIGNED PRODUCTION APK READY: $DIR/OmniFace-AI.apk"
    echo "📦 Size: $APK_SIZE"
    echo "🔑 Signed by: Preetham N (preethamdev05) - Chamarajanagar, Karnataka, IN"
    echo "=========================================================="
    
    # Verify APK Signature with apksigner
    /root/Android/Sdk/build-tools/35.0.0/apksigner verify --verbose "$DIR/OmniFace-AI.apk" || true
fi
