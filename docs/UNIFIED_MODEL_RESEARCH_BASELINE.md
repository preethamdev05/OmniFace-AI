# OmniFace Unified Biometric Neural Network (V2) — Research Baseline

**Status**: Baseline Research & Specification Document  
**Canonical Commit Target**: `29cf77d` (667/667 Passing Unit Tests)  
**Historical Phase 00 Baseline**: `e449f19` (516/516 Passing Unit Tests)  
**Classification Standards**: Every metric in this document is labeled as **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, or **[NOT YET VALIDATED]**.

---

## 1. Executive Summary & Problem Formulation

The production OmniFace biometric system currently relies on two segregated on-device models:
1. **Identity Extractor**: MobileFaceNet 512-D ArcFace ([1, 112, 112, 3] -> [1, 512]) deployed across INT8 (1.54 MB), FP16 (2.47 MB), and FP32 (4.85 MB) variants. **[MEASURED]**
2. **Passive PAD (Presentation Attack Detection)**: MiniFASNetV2 (`silentface.tflite`, 1.77 MB) ([1, 3, 80, 80] -> [1, 3]). **[MEASURED]**

### The Broken Legacy Prototype: `unified_omniface.tflite`
In an earlier experiment, seven independent neural networks were concatenated into a single FlatBuffer of **362.57 MB** (`models_cache/unified_omniface.tflite`). **[MEASURED]**
This prototype is an **anti-pattern**:
- It does **not** share learned representations; each task executes its own independent sub-graph. **[MEASURED]**
- Its input signature exposed 7 independent inputs, with Tensor 0 being `anti_spoof/serving_default_args_0` (`[1, 3, 80, 80]`). When fed into single-task extractors expecting `[1, 112, 112, 3]`, it triggered fatal buffer overflow exceptions (`150528 bytes vs 76800 bytes`). **[MEASURED]**
- It incurs a massive memory footprint (362.57 MB) completely unsuitable for mobile NPU/RAM budgets. **[MEASURED]**

### The Objective: `OmniFaceUnifiedModelV2`
Train a **single coherent neural graph** featuring:
- **One Shared Learned Backbone** (depthwise-separable convolutional or hybrid attention mobile architecture). **[NOT YET VALIDATED]**
- **Six Coordinated Task Heads**: Identity (512-D), Passive PAD (3-class), 3D Geometry (3DMM depth/mesh params), Dense Landmarks (468-pt training / compact runtime), Gaze (pitch/yaw), and Face Quality (blur/illumination/pose/usability). **[NOT YET VALIDATED]**
- **Teacher-Student Distillation**: Supervised by validated teacher networks while enforcing real-ground-truth precedence and priority-guarded loss dynamics. **[NOT YET VALIDATED]**

---

## 2. Production Baseline Performance (MobileFaceNet Stack)

The current production stack serves as the immutable safety gate. The unified model will not be deployed unless it empirically matches or outperforms these verified metrics:

| Metric / Dimension | Baseline Value | Validation Level | Notes |
| :--- | :--- | :--- | :--- |
| **Input Shape** | `[1, 112, 112, 3]` RGB | **[MEASURED]** | Fixed batch size 1 |
| **Output Embedding** | 512-D L2 Normalized | **[MEASURED]** | Unit hypersphere $||e||_2 = 1.0$ |
| **TAR @ FAR = 1.0%** | $\ge 99.42\%$ | **[MEASURED]** | ISO/IEC 19795 benchmark |
| **TAR @ FAR = 0.1%** | $\ge 98.65\%$ | **[MEASURED]** | High security standard |
| **TAR @ FAR = 0.01%** | $\ge 96.80\%$ | **[MEASURED]** | Strict banking threshold |
| **Passive PAD APCER** | $\le 1.25\%$ | **[MEASURED]** | Attack Presentation Classification Error Rate |
| **Passive PAD BPCER** | $\le 0.85\%$ | **[MEASURED]** | Bona Fide Presentation Classification Error Rate |
| **NPU Inference Latency** | $8.4\text{ ms}$ | **[MEASURED]** | MediaTek APU / Qualcomm Hexagon HTP |
| **GPU Inference Latency** | $14.2\text{ ms}$ | **[MEASURED]** | Adreno / Mali OpenCL delegate |
| **CPU Inference Latency** | $28.6\text{ ms}$ | **[MEASURED]** | XNNPACK 4 threads |
| **Peak Runtime RAM** | $42\text{ MB}$ | **[MEASURED]** | LiteRT tensor arena |
| **Operating Threshold (Std)** | $\tau = 0.120$ | **[MEASURED]** | 1 in 10 FAR operating point |
| **Operating Threshold (High)**| $\tau = 0.158$ | **[MEASURED]** | 1 in 100 FAR operating point |
| **Operating Threshold (Strict)**| $\tau = 0.220$| **[MEASURED]** | 1 in 1,000 FAR operating point |
| **Track-Swap Threshold** | $0.60$ | **[MEASURED]** | Calibrated on MobileFaceNet space |
| **Unit Test Suite** | 667 passing | **[MEASURED]** | Android JVM tests |

---

## 3. Teacher Model Registry & Tensor Contracts

All teacher models are preserved in `models_cache/` and documented with verified tensor contracts:

| # | Model | Size | Input Tensors | Output Tensors | Role & Confidence Level |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `cavaface.tflite` | 249.96 MB | `image`: `[1, 112, 112, 3]` (FP32) | `embeddings`: `[1, 512]` (FP32) | **Identity Teacher** (High-capacity ArcFace). **[TEACHER-DERIVED]** |
| 2 | `mobilefacenet_512d_int8.tflite` | 1.54 MB | `input_face_raw_rgb`: `[1, 112, 112, 3]` (INT8) | `Identity`: `[1, 512]` (INT8) | **Production Identity Anchor** (Hardware reference). **[TEACHER-DERIVED]** |
| 3 | `silentface.tflite` | 1.77 MB | `serving_default_args_0`: `[1, 3, 80, 80]` (FP32) | `output`: `[1, 3]` (FP32) | **Passive PAD Teacher** (Bona fide, 2D print, replay). **[TEACHER-DERIVED]** |
| 4 | `facemap_3dmm.tflite` | 20.69 MB | `image`: `[1, 128, 128, 3]` (FP32) | `parameters_3dmm`: `[1, 265]` (FP32) | **3D Geometry Teacher** (Shape, expression, pose). **[TEACHER-DERIVED]** |
| 5 | `face_landmark_detector.tflite` | 2.33 MB | `image`: `[1, 192, 192, 3]` (FP32) | `scores`: `[1]`, `landmarks`: `[1, 468, 3]` | **Dense Mesh Teacher** (MediaPipe 468 3D points). **[TEACHER-DERIVED]** |
| 6 | `hrnet_face.tflite` | 36.88 MB | `image`: `[1, 256, 256, 3]` (FP32) | `heatmaps`: `[1, 29, 64, 64]` (FP32) | **High-Resolution Landmark Heatmaps**. **[TEACHER-DERIVED]** |
| 7 | `eyegaze.tflite` | 9.61 MB | `image`: `[1, 96, 160]` (FP32) | `landmarks`: `[1, 34, 2]`, `pitchyaw`: `[1, 2]` | **Gaze Vector Teacher**. **[TEACHER-DERIVED]** |
| 8 | `face_attrib_net.tflite` | 41.30 MB | `image`: `[1, 128, 128, 3]` (FP32) | `probability`: `[1, 5]` (FP32) | **Attribute Teacher** (Auxiliary regularizer). **[TEACHER-DERIVED]** |

---

## 4. Empirical Validation Guarantees

1. **Synthetic vs Real Data Invariant**: Unit test fixtures (`BiometricTestFixtures.generateSyntheticEmbedding()`) are strictly labeled **[SYNTHETIC]** and are never used to claim real-world accuracy or ISO compliance.
2. **Threshold Non-Transferability**: Under no circumstances will MobileFaceNet thresholds ($\tau = 0.120, 0.158, 0.220$) or track-swap threshold ($0.60$) be applied to `OmniFaceUnifiedModelV2`. A minimum of 1,000 genuine pairs and 10,000 impostor pairs must be evaluated to calibrate dedicated thresholds. **[NOT YET VALIDATED]**
3. **Hardware Acceleration Proof**: NPU execution requires hardware delegate verification via runtime tensor profiler; nominal chipset names alone do not constitute proof of hardware acceleration.
