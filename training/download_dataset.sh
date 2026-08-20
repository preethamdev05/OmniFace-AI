#!/usr/bin/env bash
# ==============================================================================
# Final Face Recognition Dataset Setup Script
# Dataset: hereisburak/pins-face-recognition (Kaggle)
# ==============================================================================

set -euo pipefail

TARGET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET_DIR="${TARGET_DIR}/dataset"
ZIP_NAME="pins-face-recognition.zip"

echo "========================================================="
echo " Face Recognition Dataset Download & Verification Pipeline"
echo " Target Location: ${DATASET_DIR}"
echo "========================================================="

# 1. Check Kaggle CLI
if ! command -v kaggle &> /dev/null; then
    echo "[-] Kaggle CLI not found in PATH."
    echo "    Please verify ~/.kaggle/kaggle.json or access_token exists."
    exit 1
fi

# 2. Check Kaggle Credentials
if [ ! -f ~/.kaggle/kaggle.json ] && [ ! -f /root/.kaggle/kaggle.json ] && [ ! -f ~/.kaggle/access_token ] && [ ! -f /root/.kaggle/access_token ]; then
    echo "[!] Warning: Kaggle API token not found at ~/.kaggle/kaggle.json or access_token."
    echo "    Please place your kaggle.json in ~/.kaggle/ and set permissions: chmod 600 ~/.kaggle/kaggle.json"
fi

cd "${TARGET_DIR}"

# 3. Download Dataset via Kaggle CLI
if [ ! -f "${ZIP_NAME}" ] && [ ! -d "${DATASET_DIR}" ]; then
    echo "[+] Downloading dataset 'hereisburak/pins-face-recognition' via Kaggle CLI..."
    kaggle datasets download -d hereisburak/pins-face-recognition -p "${TARGET_DIR}"
else
    echo "[*] Dataset archive or extracted directory already present. Skipping download."
fi

# 4. Extract Dataset
if [ -f "${ZIP_NAME}" ] && [ ! -d "${DATASET_DIR}" ]; then
    echo "[+] Extracting ${ZIP_NAME} into ${DATASET_DIR}..."
    mkdir -p "${DATASET_DIR}"
    unzip -q "${ZIP_NAME}" -d "${DATASET_DIR}"
    echo "[+] Extraction complete."
fi

# 5. Normalize Directory Structure if nested (e.g. 105_classes_pins_dataset or Raw Images)
if [ -d "${DATASET_DIR}/105_classes_pins_dataset" ]; then
    echo "[+] Moving nested folders to dataset root..."
    mv "${DATASET_DIR}/105_classes_pins_dataset/"* "${DATASET_DIR}/"
    rmdir "${DATASET_DIR}/105_classes_pins_dataset" 2>/dev/null || true
fi

# 6. Verify Dataset Structure
echo "========================================================="
echo " Dataset Statistics & Directory Inspection:"
echo "========================================================="
NUM_CLASSES=$(find "${DATASET_DIR}" -mindepth 1 -maxdepth 1 -type d | wc -l)
TOTAL_IMAGES=$(find "${DATASET_DIR}" -type f \( -iname "*.jpg" -o -iname "*.png" -o -iname "*.jpeg" \) | wc -l)

echo "[+] Total identity classes found : ${NUM_CLASSES}"
echo "[+] Total face images found      : ${TOTAL_IMAGES}"
echo "[+] Sample identity directories:"
find "${DATASET_DIR}" -mindepth 1 -maxdepth 1 -type d | head -n 10

echo "========================================================="
echo "[✓] Dataset ready for Phase 3 (Dataset Creation & Training)"
echo "========================================================="
