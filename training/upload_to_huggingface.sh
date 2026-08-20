#!/usr/bin/env bash
# ==============================================================================
# 🚀 Hugging Face Private Model Uploader for OmniFace AI
# ==============================================================================
set -e

WORKSPACE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_FILE="${WORKSPACE_DIR}/models/mobilefacenet_512d_fp16.tflite"

echo "=================================================================="
echo "🤗 OMNIFACE AI — HUGGING FACE PRIVATE HUB UPLOADER"
echo "=================================================================="

if [ ! -f "$MODEL_FILE" ]; then
    echo "❌ Error: AntelopeV2 FP16 model not found at $MODEL_FILE"
    exit 1
fi

MODEL_SIZE=$(ls -lh "$MODEL_FILE" | awk '{print $5}')
echo "📦 Found Model: $MODEL_FILE (${MODEL_SIZE})"

# Verify CLI
if ! command -v hf &> /dev/null; then
    echo "❌ 'hf' CLI is not found. Installing..."
    pip install -U "huggingface_hub[cli]" --break-system-packages
fi

# Check Login Status
echo "[+] Checking Hugging Face login state..."
if ! hf auth whoami &> /dev/null; then
    echo "⚠️ Not logged in to Hugging Face."
    echo "👉 Please run 'hf auth login' or enter your HF Write Token now:"
    read -rp "Enter HF Token (hf_...): " USER_TOKEN
    if [ -n "$USER_TOKEN" ]; then
        hf auth login --token "$USER_TOKEN"
    else
        echo "❌ Token is required. Exiting."
        exit 1
    fi
fi

CURRENT_USER=$(hf auth whoami 2>/dev/null | head -n 1 | tr -d '[:space:]')
echo "👤 Authenticated as: ${CURRENT_USER}"

DEFAULT_REPO="${CURRENT_USER}/omniface-antelopev2"
echo ""
read -rp "Enter Target Private Repo ID [Default: ${DEFAULT_REPO}]: " REPO_INPUT
TARGET_REPO="${REPO_INPUT:-$DEFAULT_REPO}"

echo ""
echo "[+] Creating / Checking Private Repository: ${TARGET_REPO}..."
hf repos create "$TARGET_REPO" --private 2>/dev/null || echo "ℹ️ Repository already exists or ready."

echo ""
echo "[+] Uploading ${MODEL_FILE} to https://huggingface.co/${TARGET_REPO}..."
hf upload "$TARGET_REPO" "$MODEL_FILE" "mobilefacenet_512d_fp16.tflite" --commit-message="Deploy AntelopeV2 Glint360K 512-D FP16 TFLite"

echo ""
echo "=================================================================="
echo "✅ UPLOAD COMPLETE!"
echo "🔗 Private URL: https://huggingface.co/${TARGET_REPO}/blob/main/mobilefacenet_512d_fp16.tflite"
echo "📱 In OmniFace App -> Settings -> Hugging Face Vault:"
echo "   • Set Repo ID: ${TARGET_REPO}"
echo "   • Set Token: Your fine-grained Read token"
echo "=================================================================="
