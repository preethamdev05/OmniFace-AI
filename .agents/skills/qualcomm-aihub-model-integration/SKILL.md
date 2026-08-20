---
name: qualcomm-aihub-model-integration
description: >-
  Step-by-step workflow for downloading, auditing, and integrating a new
  Qualcomm AI Hub TFLite model into the OmniFace AI QualcommFaceIntelligenceEngine.
  Use when the user provides a new aihub.qualcomm.com or GitHub Qualcomm AI Hub
  model URL to add to the Snapdragon inference suite.
---

# Skill: Qualcomm AI Hub Model Integration

## Overview

Each new Qualcomm AI Hub model integration follows this exact 6-phase ritual.
Always audit before coding. Never invent tensor shapes — read them from `metadata.json`.

---

## Phase 1 — Discover S3 Asset URL

The Qualcomm AI Hub public S3 bucket URL pattern is:

```
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/<model_id>/releases/v0.60.0/<model_id>-tflite-float.zip
```

Replace `<model_id>` with the snake_case model identifier from the GitHub path
(e.g. `mediapipe_face`, `hrnet_face`, `eyegaze`, `cavaface`).

> [!TIP]
> Verify the exact filename at:
> `https://github.com/qualcomm/ai-hub-models/tree/v0.60.0/src/qai_hub_models/models/<model_id>`
> before downloading.

---

## Phase 2 — Download & Extract

```bash
cd /storage/emulated/0/AI-HUB/FR/models/qualcomm_suite
mkdir -p <model_id>
curl -L "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/<model_id>/releases/v0.60.0/<model_id>-tflite-float.zip" \
  -o <model_id>.zip
unzip -o <model_id>.zip -d <model_id>/
ls -lhR /storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/<model_id>/
```

---

## Phase 3 — Audit metadata.json

Always read metadata.json before writing any Kotlin code:

```bash
cat /storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/<model_id>/<model_id>-tflite-float/metadata.json
```

Record:
- Input tensor name, shape [B, H, W, C], dtype, value_range (e.g. [0.0, 1.0] = divide pixel by 255)
- Output tensor name(s) and shape(s)
- Number of model files (some models like MediaPipe have 2: face_detector.tflite + face_landmark_detector.tflite)

---

## Phase 4 — Add to QualcommFaceIntelligenceEngine.kt

File: /storage/emulated/0/AI-HUB/FR/app/src/main/java/com/omniface/ai/ml/QualcommFaceIntelligenceEngine.kt

### 4a. Result Data Class (above the class definition)
```kotlin
data class <ModelName>Result(
    val <primaryOutput>: <Type>,
    // additional fields matching output tensor shapes
    val executionTimeMs: Float
)
```

### 4b. Path Constant (in companion object)
```kotlin
private const val <MODEL_ID>_PATH = "$BASE_SUITE_PATH/<model_id>/<model_id>-tflite-float/<model_id>.tflite"
```

### 4c. Interpreter Field
```kotlin
private var <modelId>Interpreter: Interpreter? = null
```

### 4d. Zero-GC ByteBuffer (pre-allocated, native order)
```kotlin
// Buffer size = 1 * H * W * C * 4 bytes (for float32 RGB)
private val buffer<H>x<W>: ByteBuffer = ByteBuffer.allocateDirect(1 * H * W * C * 4).apply {
    order(ByteOrder.nativeOrder())
}
```

### 4e. Output Arrays
```kotlin
// Shape must exactly match output tensor from metadata.json
private val out<ModelName> = Array(1) { /* e.g. FloatArray(N) or Array(H) { FloatArray(W) } */ }
```

---

## Phase 5 — Implement Inference Method

```kotlin
@Synchronized
fun estimate<ModelName>(faceBitmap: Bitmap): <ModelName>Result? {
    val interpreter = <modelId>Interpreter ?: return null
    val t0 = System.nanoTime()

    val resized = Bitmap.createScaledBitmap(faceBitmap, W, H, true)
    val pixels = IntArray(W * H)
    resized.getPixels(pixels, 0, W, 0, 0, W, H)
    if (resized != faceBitmap) resized.recycle()

    buffer<H>x<W>.rewind()
    for (p in pixels) {
        buffer<H>x<W>.putFloat(((p shr 16) and 0xFF) / 255.0f)
        buffer<H>x<W>.putFloat(((p shr 8) and 0xFF) / 255.0f)
        buffer<H>x<W>.putFloat((p and 0xFF) / 255.0f)
    }

    interpreter.run(buffer<H>x<W>, out<ModelName>)
    val durationMs = (System.nanoTime() - t0) / 1_000_000.0f

    return <ModelName>Result(/* map from out<ModelName> */, executionTimeMs = durationMs)
}
```

Special cases:
- GRAYSCALE inputs (e.g. EyeGaze [1,H,W]): omit G/B channels; buffer = H * W * 4 bytes
- MULTI-OUTPUT models (e.g. MediaPipe): use runForMultipleInputsOutputs(arrayOf(buf), hashMapOf(0 to scores, 1 to landmarks))
- HEATMAP models (e.g. HRNetFace [1,K,H,W]): spatial argmax per slice, normalize by H/64.0f

---

## Phase 6 — Wire Init and Close

### In initializeQualcommSuite() — append after last model block:
```kotlin
// N. Load <ModelName>
val <modelId>File = File(<MODEL_ID>_PATH)
if (<modelId>File.exists() && <modelId>File.canRead()) {
    val buf = mapFile(<modelId>File)
    <modelId>Interpreter = Interpreter(buf, options)
    Log.i(TAG, "✅ [QUALCOMM AI HUB] <ModelName> loaded (${<modelId>File.length() / 1024 / 1024} MB)")
}
// Also add: || <modelId>Interpreter != null  to isSuiteLoaded condition
```

### In close():
```kotlin
try { <modelId>Interpreter?.close() } catch (_: Throwable) {}
<modelId>Interpreter = null
```

---

## Phase 7 — Build & Verify

```bash
bash build_apk.sh
```

IMPORTANT: Always use `bash build_apk.sh` — never `./build_apk.sh`.
Files on /storage/emulated/0 are on a noexec mount and cannot be executed directly.

Expected:
  BUILD SUCCESSFUL in ~6m
  Verified using v2 scheme (APK Signature Scheme v2): true
  Verified using v3 scheme (APK Signature Scheme v3): true

---

## Appendix — Integrated Model Registry (v0.60.0)

| Model              | model_id         | Input Shape                              | Output                          | Size   |
|:-------------------|:-----------------|:-----------------------------------------|:--------------------------------|:-------|
| CavaFace           | cavaface         | [1,112,112,3] RGB [0,1]                  | [1,512] L2 embedding            | 250 MB |
| FaceMap 3DMM       | facemap_3dmm     | [1,128,128,3] RGB [0,1]                  | [1,265] shape params            | 21 MB  |
| FaceAttribNet      | face_attrib_net  | [1,128,128,3] RGB [0,1]                  | [1,5] attribute probs           | 42 MB  |
| EyeGaze            | eyegaze          | [1,96,160] grayscale [0,1]               | [1,2] pitch/yaw + [1,34,2] lmk  | 9.7 MB |
| HRNetFace          | hrnet_face       | [1,256,256,3] RGB [0,1]                  | [1,29,64,64] heatmaps           | 37 MB  |
| MediaPipe Face Mesh| mediapipe_face   | Detector:[1,256,256,3] Mesh:[1,192,192,3]| [1] score + [1,468,3] XYZ       | 2.9 MB |
