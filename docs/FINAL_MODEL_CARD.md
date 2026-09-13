# 🪪 OmniFace AI — UnifiedFaceModel V1 Model Card

**Model Details**:
- **Model Name**: `UnifiedFaceModel V1` (`OmniFaceUnifiedModelV2`)
- **Version**: `1.0.0-production`
- **Release Date**: September 2026
- **Architecture**: Single Shared MobileNetV4-Conv-Small Backbone + 7 Specialized Heads
- **Parameters**: 3.24M parameters (Single Graph)
- **FLOPs**: 428 MFLOPs per single forward pass
- **Deployment Format**: LiteRT FlatBuffer (`.tflite`), FP32 (13.1 MB), FP16 (6.6 MB), INT8 (3.4 MB)
- **Primary Task**: Joint Edge Biometric Intelligence (Identity + PAD + Quality + 468-pt Mesh + 3DMM + Gaze + Attributes)

---

## 1. Intended Use & Boundaries

### Intended Use:
- Real-time high-throughput biometric enrollment and attendance verification for institutional and enterprise kiosks.
- Multi-modal Presentation Attack Detection (2D photo, electronic screen replay, and planar surface rejection).
- Edge-only processing on mobile hardware without streaming raw video to external cloud services (privacy-preserving by design).

### Out-of-Scope / Prohibited Uses:
- Mass surveillance without explicit subject consent or institutional authority.
- Emotion detection or cognitive state inference (gaze angle and openness are used solely for kiosk attention/presentation verification).
- Re-identification across incompatible latent spaces without cryptographic re-enrollment.

---

## 2. Multi-Teacher Distillation Sources

| Specialized Head | Supervision Teacher | Teacher Size | Teacher Source | Distillation Objective |
|:-----------------|:--------------------|:-------------|:---------------|:-----------------------|
| **Identity (512-D)** | Qualcomm CavaFace | 249.96 MB | Qualcomm AI Hub v0.60.0 | ArcFace ($s=64, m=0.50$) + MSE Feature Distillation |
| **PAD (3-Class)** | Silent-Face / MiniFASNetV2 | 1.77 MB | OpenCV / PyTorch Mobile | Focal Loss ($\gamma=2.0, \alpha=[0.5, 0.25, 0.25]$) |
| **Dense Mesh (468-pt)** | MediaPipe FaceMesh | 2.33 MB | Google MediaPipe | Smooth L1 Loss on 3D Coordinates |
| **Landmark Aux** | HRNet Face | 36.88 MB | Qualcomm AI Hub v0.60.0 | Heatmap Cross-Entropy Distillation |
| **3D Geometry (265-D)** | FaceMap 3DMM | 20.69 MB | Qualcomm AI Hub v0.60.0 | Huber Loss on 3DMM Coefficients |
| **Gaze (2-D)** | EyeGaze Landmark Net | 9.61 MB | Qualcomm AI Hub v0.60.0 | Cosine Angular Loss on Pitch/Yaw |
| **Attributes (5-D)** | Face Attribute Net | 41.30 MB | Qualcomm AI Hub v0.60.0 | Binary Cross-Entropy (BCE) with Logits |

---

## 3. Training & Evaluation Data

- **Primary Identity Corpus**: PINS Face Dataset (105 identities, 10,770 images) augmented with high-resolution synthetic look-alike clusters and cross-pose variations.
- **Identity-Disjoint Splitting**:
  - `TRAIN`: 75 identities (7,680 images)
  - `VALIDATION`: 15 identities (1,540 images)
  - `TEST`: 15 identities (1,550 images)
  - **Leakage Invariant**: Strict zero-identity overlap between TRAIN, VAL, and TEST.
- **Augmentation Pipeline**:
  - Photometric: Random brightness ($\pm 25\%$), contrast ($\pm 20\%$), Gaussian blur ($\sigma \in [0.1, 1.5]$).
  - Geometric: Random crop scaling ($0.95 - 1.05$), affine rotation ($\pm 15^\circ$), horizontal flip ($p=0.50$).
  - Adversarial: Moire pattern overlay for screen replay simulation, specular flare simulation.

---

## 4. Empirical Performance & Metrics

| Metric | Target Specification | Measured on FP16 Checkpoint | Measured on INT8 Quantized |
|:-------|:---------------------|:----------------------------|:---------------------------|
| **TAR @ FAR 1.0%** | $\ge 98.5\%$ | **$99.2\%$** | **$98.8\%$** |
| **TAR @ FAR 0.1%** | $\ge 96.0\%$ | **$97.4\%$** | **$96.9\%$** |
| **Equal Error Rate (EER)** | $\le 1.50\%$ | **$1.18\%$** | **$1.32\%$** |
| **PAD Bona Fide Accept (APCER)** | $\le 1.00\%$ | **$0.65\%$** | **$0.82\%$** |
| **PAD Attack Rejection (BPCER)** | $\le 1.00\%$ | **$0.40\%$** | **$0.55\%$** |
| **Mesh NME (Normalized Mean Error)** | $\le 3.50\%$ | **$2.84\%$** | **$3.10\%$** |
| **3DMM Parameter Pearson $r$** | $\ge 0.88$ | **$0.92$** | **$0.90$** |
| **Gaze Angular Error (Degrees)** | $\le 4.5^\circ$ | **$3.2^\circ$** | **$3.6^\circ$** |

---

## 5. Hardware Latency & Resource Footprint

| Hardware Delegate | Platform Tested | Forward Pass Latency | Peak Memory (RAM) | Thermal Throttle Delta |
|:------------------|:----------------|:---------------------|:------------------|:-----------------------|
| **NPU / NNAPI (INT8)** | Qualcomm Hexagon HTP (SM8650) | **$4.2\text{ ms}$** | $18.4\text{ MB}$ | $+0.8^\circ\text{C}$ after 1,000 frames |
| **NPU / NNAPI (INT8)** | MediaTek APU 890 (Dimensity 9300) | **$5.8\text{ ms}$** | $21.2\text{ MB}$ | $+1.1^\circ\text{C}$ after 1,000 frames |
| **Mobile GPU (FP16)** | Adreno 750 / Mali-G720 | **$8.4\text{ ms}$** | $28.6\text{ MB}$ | $+1.6^\circ\text{C}$ after 1,000 frames |
| **Multi-Core CPU** | 4-Thread XNNPACK FP32 | **$18.6\text{ ms}$** | $14.2\text{ MB}$ | $+2.4^\circ\text{C}$ after 1,000 frames |

---

## 6. Ethical Standards & Privacy Safeguards

1. **Hardware Keystore Protection**: Feature embeddings are encrypted using Android Keystore AES-256-GCM prior to SQLite persistence. Raw facial images are discarded immediately after embedding extraction.
2. **DPDP Act 2023 Compliance**: Built-in cryptographic right-to-forget cascade (`eraseIdentityBiometrics()`) zeroizes local vector indices and emits an Aegis SHA-256 audit block.
3. **No Closed-Vendor Cloud Lock-in**: Autonomous offline edge inference eliminates biometric telemetry leakage over public networks.
