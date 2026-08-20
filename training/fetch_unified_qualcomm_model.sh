#!/usr/bin/env bash
# ==============================================================================
# Fetch Unified Qualcomm Multi-Task NPU Model from Kaggle & Deploy to Assets
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/kaggle_unified_output"
BUNDLE_ZIP="${OUTPUT_DIR}/qualcomm_unified_npu_bundle.zip"
KERNEL_SLUG="preethamvfx/omniface-ai-qualcomm-unified-multi-task-npu-model"
MODELS_DIR="/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite"
ASSETS_DIR="/storage/emulated/0/AI-HUB/FR/app/src/main/assets"

mkdir -p "${MODELS_DIR}" "${ASSETS_DIR}" "${OUTPUT_DIR}"

echo "=========================================================="
echo " [Phase 1] Checking Kaggle Unified Model Kernel Status..."
echo "=========================================================="
kaggle kernels status "${KERNEL_SLUG}"

echo ""
echo "=========================================================="
echo " [Phase 2] Fetching Compiled Unified Flatbuffers..."
echo "=========================================================="
kaggle kernels output "${KERNEL_SLUG}" -p "${OUTPUT_DIR}"

if [ -f "${OUTPUT_DIR}/qualcomm_unified_face_npu.tflite" ]; then
    echo "[✓] Master Unified Flatbuffer Found!"
    cp -v "${OUTPUT_DIR}/qualcomm_unified_face_npu.tflite" "${MODELS_DIR}/"
    cp -v "${OUTPUT_DIR}/qualcomm_unified_face_npu.tflite" "${ASSETS_DIR}/"
    [ -f "${OUTPUT_DIR}/qualcomm_unified_face_npu_fp16.tflite" ] && cp -v "${OUTPUT_DIR}/qualcomm_unified_face_npu_fp16.tflite" "${MODELS_DIR}/"
    [ -f "${OUTPUT_DIR}/qualcomm_unified_face_npu_int8.tflite" ] && cp -v "${OUTPUT_DIR}/qualcomm_unified_face_npu_int8.tflite" "${MODELS_DIR}/"
    echo "[✓] Qualcomm Unified NPU Model Successfully Deployed to Assets!"
else
    echo "[-] Output not yet ready. Kernel is still generating flatbuffers."
fi
