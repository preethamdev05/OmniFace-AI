#!/usr/bin/env bash
# ==============================================================================
# Autonomous Kaggle Artifact Fetcher & Android Asset Deployment Hook
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/kaggle_output"
BUNDLE_ZIP="${OUTPUT_DIR}/mobilefacenet_512d_deployment_bundle.zip"
KERNEL_SLUG="preethamvfx/pins-face-recognition-npu-gpu-train"

echo "=========================================================="
echo " [Phase 1] Checking Kaggle Training Kernel Status..."
echo "=========================================================="
kaggle kernels status "${KERNEL_SLUG}"

echo ""
echo "=========================================================="
echo " [Phase 2] Fetching Completed Deployment Bundle..."
echo "=========================================================="
mkdir -p "${OUTPUT_DIR}"
kaggle kernels output "${KERNEL_SLUG}" -p "${OUTPUT_DIR}"

if [ -f "${BUNDLE_ZIP}" ]; then
    echo "[✓] Deployment bundle found! Extracting into local FR workspace..."
    unzip -o "${BUNDLE_ZIP}" -d "${SCRIPT_DIR}"
    
    echo ""
    echo "=========================================================="
    echo " [Phase 3] Auto-Deploying .tflite Models to Android Assets..."
    echo "=========================================================="
    # Search for Android assets directory in workspace
    ANDROID_ASSETS=$(find "${SCRIPT_DIR}/.." -maxdepth 5 -type d -path "*/src/main/assets" 2>/dev/null | head -n 1)
    
    if [ -n "${ANDROID_ASSETS}" ] && [ -d "${ANDROID_ASSETS}" ]; then
        echo "[+] Discovered Android Assets Directory: ${ANDROID_ASSETS}"
        cp -v "${SCRIPT_DIR}/mobilefacenet_512d_int8.tflite" "${ANDROID_ASSETS}/" 2>/dev/null || true
        cp -v "${SCRIPT_DIR}/mobilefacenet_512d_fp16.tflite" "${ANDROID_ASSETS}/" 2>/dev/null || true
        cp -v "${SCRIPT_DIR}/mobilefacenet_512d_fp32.tflite" "${ANDROID_ASSETS}/" 2>/dev/null || true
        echo "[✓] Android Assets Synced Successfully! Ready for native zero-copy building."
    else
        echo "[*] No active Android project assets folder found. Artifacts preserved in ${SCRIPT_DIR}."
    fi
else
    echo "[-] Warning: ${BUNDLE_ZIP} not yet ready. Kernel may still be running."
fi
