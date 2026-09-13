# 🏛️ OmniFace AI — ML Historical Archive Master Index

> **Archive Status:** HISTORICAL REFERENCE / FORENSIC PRESERVATION ONLY  
> **Production Status:** EXCLUDED FROM PRODUCTION RUNTIME  
> **Last Updated:** 2026-09-13

---

## 1. Overview & Isolation Policy

All previous ML models, old inference engines, old model registries, obsolete recognition pipelines, old TFLite assets, and superseded ML code have been safely preserved in this `archive/ml/` directory.

### Isolation Rules
1. **Zero Runtime Loading:** The Android application APK asset bundle contains strictly `unified_face_v1_fp16.tflite`, `unified_face_v1_int8.tflite`, and `class_labels.json`. No code in `app/src/main` opens or searches `archive/`.
2. **Preserved for Teacher Training:** Models in `archive/ml/models/` serve as teachers for distillation pipelines under `training/unified/teachers/teacher_registry.py`.
3. **Forensic & Rollback Safe:** All SHA-256 hashes and weights are preserved identically.

---

## 2. Archived Model Families Master Catalog

| # | Family / ID | Role | Archive Location | SHA-256 Checksum | Teacher Status |
|---|---|---|---|---|---|
| 1 | **CavaFace** (`cavaface`) | Golden Baseline (Snapdragon IR-SE-100) | `archive/ml/models/cavaface/cavaface.tflite` | `e9714eb84e55d5069f1bfda725fe5a60e0a5ff42907fb2cb95304192b0c16b9b` | Identity Teacher |
| 2 | **MobileFaceNet INT8** (`mobilefacenet_512d_int8`) | Historical Reference (Per-channel INT8) | `archive/ml/models/mobilefacenet/mobilefacenet_512d_int8.tflite` | `9b36d649ae56b73bc31a47395027581fb1ba7d5668351bfe958cb53d1000693a` | - |
| 3 | **MobileFaceNet FP16** (`mobilefacenet_512d_fp16`) | Historical Reference (GPU FP16) | `archive/ml/models/mobilefacenet/mobilefacenet_512d_fp16.tflite` | `04d603a116b47926715fbc7ba50eebfc486ccf54bf693630f5bca7fc34ee0362` | - |
| 4 | **MobileFaceNet FP32** (`mobilefacenet_512d_fp32`) | Historical Reference (CPU FP32) | `archive/ml/models/mobilefacenet/mobilefacenet_512d_fp32.tflite` | `5c285fa4c7b8dbff2249e0ca14ebdfa5f4f89d3131ba526017bb529243788ff3` | - |
| 5 | **FaceNet-512** (`facenet512`) | Historical Reference (Keras/TFLite 512D) | `archive/ml/models/facenet512/facenet512.tflite` | `e69e340a6bbf0832c321711dddbfc0264104d5386dbb5a937a5448375e88e89f` | - |
| 6 | **SilentFace MiniFASNet** (`silentface`) | Passive RGB Anti-Spoofing | `archive/ml/models/silentface/silentface.tflite` | `50dc5d7fb705886d267885b5d84a737f5d496a40a8a7ca226d9c6e3b5e40e891` | PAD Teacher |
| 7 | **FaceMap 3DMM** (`facemap_3dmm`) | 3D Shape Coefficients | `archive/ml/models/facemap_3dmm/facemap_3dmm.tflite` | `b68453416c1483321db8c812d458ca6ff96e838ee84518385dd2a1db3ce00c6d` | 3DMM Teacher |
| 8 | **EyeGaze** (`eyegaze`) | Gaze Direction (Pitch/Yaw) | `archive/ml/models/eyegaze/eyegaze.tflite` | `d55ea9741f23ff142858da39fb4a59bb5f25bf6033bbca894cfdc8289ea6be25` | Gaze Teacher |
| 9 | **MediaPipe Face Mesh** (`mediapipe_face`) | Dense 468 3D Mesh | `archive/ml/models/landmarks/face_landmark_detector.tflite` | `3788ff6c7cb365ee15ba0cbfa17cf17b9c97b87fa13511eb0cbfba8654c6014e` | Mesh Teacher |
| 10| **MediaPipe Detector** (`mediapipe_detector`) | Front Face Detector | `archive/ml/models/landmarks/face_detector.tflite` | `bb5ec325e00fb38ae0e9cb2b6e50f5c09e3e70757754d9200fa4ca924b105d15` | - |
| 11| **HRNet Face** (`hrnet_face`) | High-Resolution Landmarks | `archive/ml/models/hrnet/hrnet_face.tflite` | `9d554a9388df6ea8e6022839b85c18ff2ea349d799f2e379d74df1cf4ee960d7` | - |
| 12| **Face Attributes** (`face_attrib_net`) | Attribute Predictions | `archive/ml/models/attributes/face_attrib_net.tflite` | `d4e58a741364ff3f3b60c0422d3b25d1e21b764c92eebe665efbfa9f4ae0d970` | Attributes Teacher |
| 13| **Unified OmniFace Legacy** (`unified_omniface_legacy`) | Experimental Multi-Graph FlatBuffer | `archive/ml/models/unified_omniface_legacy/unified_omniface.tflite` | `e2a4bebe3d9a101b44ec41be7b11d8d2da9d268fcdd658f8b3400d3d528b1e4f` | Proof of Concept (Superseded) |

---

## 3. Archived Android ML Source Code

| Module | Archived Path | Original Description |
| :--- | :--- | :--- |
| `FaceRecognitionEngine.kt` | `archive/ml/android/engines/FaceRecognitionEngine.kt` | 1049-line engine loading standalone MobileFaceNet / CavaFace. |
| `PassivePadEngine.kt` | `archive/ml/android/antispoof/PassivePadEngine.kt` | 328-line MiniFASNet SilentFace loader. |
| `FaceQualityEngine.kt` | `archive/ml/android/quality/FaceQualityEngine.kt` | Standalone Laplacian blur and lighting evaluator. |
| `UnifiedBiometricEngineV2.kt` | `archive/ml/android/engines/UnifiedBiometricEngineV2.kt` | Multi-delegate prototype for legacy multi-graph flatbuffer. |
| `MultiStageLivenessEngine.kt` | `archive/ml/android/antispoof/MultiStageLivenessEngine.kt` | Hybrid texture / reflection / neural liveness pipeline. |
| `ShadowBiometricWorker.kt` | `archive/ml/android/recognition/ShadowBiometricWorker.kt` | Background shadow verification testing worker. |

---

## 4. Archived Training & Historical Scripts

| Script Name | Archive Location | Description |
| :--- | :--- | :--- |
| `train_mobilefacenet_arcface.py` | `archive/ml/training/historical_scripts/train_mobilefacenet_arcface.py` | 10-phase ArcFace training pipeline on PINS-105. |
| `benchmark_multi_models.py` | `archive/ml/training/historical_scripts/benchmark_multi_models.py` | Latency benchmark across multiple TFLite models. |
| `benchmark_pipeline.py` | `archive/ml/training/historical_scripts/benchmark_pipeline.py` | Full pipeline execution profiler. |
| `inspect_flatbuffers.py` | `archive/ml/training/historical_scripts/inspect_flatbuffers.py` | Schema analyzer for legacy FlatBuffers. |
| `quantize_models.py` | `archive/ml/training/historical_scripts/quantize_models.py` | Standalone INT8/FP16 converter. |
| `build_unified_flatbuffer.py` | `archive/ml/training/historical_scripts/build_unified_flatbuffer.py` | Script used to pack legacy multi-graph flatbuffers. |
| `verify_pipeline_parity.py` | `archive/ml/training/historical_scripts/verify_pipeline_parity.py` | Parity verification between CavaFace and MobileFaceNet. |
