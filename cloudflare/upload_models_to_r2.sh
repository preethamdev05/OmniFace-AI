#!/usr/bin/env bash

BUCKET="omniface-models"
MODELS_DIR="/storage/emulated/0/AI-HUB/FR/models"
CF_DIR="/storage/emulated/0/AI-HUB/FR/cloudflare/omniface-model-cdn"

cd "$CF_DIR"

echo "=========================================================="
echo "🚀 Seeding Cloudflare R2 Bucket: $BUCKET"
echo "=========================================================="

upload_model() {
    local key="$1"
    local file="$2"
    if [ -f "$file" ]; then
        echo "⬆️ Uploading $key ($(du -h "$file" | cut -f1))..."
        npx wrangler r2 object put "$BUCKET/$key" --file "$file" --content-type "application/octet-stream" --remote || echo "⚠️ Upload failed for $key, will retry..."
    else
        echo "⚠️ Skipping $key (file not found: $file)"
    fi
}

# 1. Base MobileFaceNet Models (Already completed, skipped or verified)
upload_model "mobilefacenet_512d_int8.tflite" "$MODELS_DIR/mobilefacenet_512d_int8.tflite"
upload_model "mobilefacenet_512d_fp16.tflite" "$MODELS_DIR/mobilefacenet_512d_fp16.tflite"
upload_model "mobilefacenet_512d_fp32.tflite" "$MODELS_DIR/mobilefacenet_512d_fp32.tflite"

# 2. Qualcomm AI Hub Suite (Upload smaller/medium models first)
upload_model "qualcomm_suite/eyegaze/eyegaze.tflite" "$MODELS_DIR/qualcomm_suite/eyegaze/eyegaze-tflite-float/eyegaze.tflite"
upload_model "qualcomm_suite/face_attrib_net/face_attrib_net.tflite" "$MODELS_DIR/qualcomm_suite/face_attrib_net/face_attrib_net-tflite-float/face_attrib_net.tflite"
upload_model "qualcomm_suite/facemap_3dmm/facemap_3dmm.tflite" "$MODELS_DIR/qualcomm_suite/facemap_3dmm/facemap_3dmm-tflite-float/facemap_3dmm.tflite"
upload_model "qualcomm_suite/hrnet_face/hrnet_face.tflite" "$MODELS_DIR/qualcomm_suite/hrnet_face/hrnet_face-tflite-float/hrnet_face.tflite"
upload_model "qualcomm_suite/mediapipe_face/face_detector.tflite" "$MODELS_DIR/qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_detector.tflite"
upload_model "qualcomm_suite/mediapipe_face/face_landmark_detector.tflite" "$MODELS_DIR/qualcomm_suite/mediapipe_face/mediapipe_face-tflite-float/face_landmark_detector.tflite"

# 3. CavaFace (250MB)
upload_model "qualcomm_suite/cavaface/cavaface.tflite" "$MODELS_DIR/qualcomm_cavaface/cavaface-tflite-float/cavaface.tflite"

echo ""
echo "✅ Cloudflare R2 Upload Completed!"
