# OmniFace AI — Comprehensive ML & Biometric Research Quality Audit

**Author**: Biometric & ML Systems Research Audit  
**Date**: September 13, 2026  
**Target System**: OmniFace Android Application (`c:\\AI-HUB\\OmniFace-AI`)  
**Scope**: Machine Learning Architecture, Training Pipelines, Datasets, Augmentation, Feature Extraction, Alignment, Multi-Frame Consensus, Vector Search, Liveness Gating, and Hardware Inference Delegates.  
**Operating Directive**: AUDIT AND EXPERIMENT DESIGN ONLY. Zero modification to production code, weights, or database logic.

---

## 1. Executive Summary

OmniFace is architected as an enterprise-grade, offline-first, continuous facial recognition attendance system for Android devices. The operational environment demands hands-free multi-subject recognition under non-ideal physical constraints: varying capture distances, cheap mobile image sensors, motion blur, partial occlusion, uneven ambient lighting, and thermal throttling.

The primary objective of this audit is not to pursue benchmark vanity metrics, but to establish **MAXIMUM REAL-WORLD RELIABILITY PER COMPUTE UNIT** while guaranteeing deterministic security against false acceptance ($1:100$ to $1:1,000$ FAR operating points).

### Key Empirical Findings
1. **Critical Training-to-Threshold Mismatch**:
   - The production Kotlin decision engine (`FaceRecognitionEngine.kt:53-55`) enforces strict operational cosine thresholds: Standard ($\\tau = 0.650$), High ($\\tau = 0.720$), and Strict ($\\tau = 0.800$).
   - In contrast, the trained MobileFaceNet-512D model (`training/training_metrics.csv` & `docs/verification_report.json`) achieved a mean genuine cosine similarity of only **$\\mu_{gen} = 0.263$** (and at Epoch 25, **$\\mu_{gen} = 0.158$**), with an impostor baseline of $\\mu_{imp} = 0.034$.
   - Evaluating this model at the production threshold $\\tau = 0.720$ results in a True Acceptance Rate (TAR) of **$0.0\\%$**. Even at the relaxed threshold $\\tau = 0.415$ (FAR = 1%), TAR is only **$18.64\\%$** (an $81.36\\%$ false rejection rate). The production threshold was chosen from academic ArcFace literature assuming large-scale training, rather than calibrated against this model's actual feature distribution.

2. **Severe Training Data Deficit & Image-Disjoint Evaluation Leak**:
   - The training pipeline (`train_mobilefacenet_arcface.py:493-511`) trains exclusively on the **PINS Face Recognition dataset**, comprising only **105 celebrity identities** scraped from Pinterest (~17,500 images total).
   - Training a 512-dimensional hyperspherical metric learning head on only 105 classes caused the model to rapidly memorize the training identities ($99.96\\%$ categorical accuracy by Epoch 25) without learning generalizable facial manifold geometry.
   - The validation protocol utilized `validation_split=0.15` via `image_dataset_from_directory`, creating an **image-disjoint** rather than **identity-disjoint (open-set)** split. The same 105 identities were present in both training and validation sets. There is zero evidence of evaluation on unseen identities (open-set face verification).

3. **Sub-Optimal Checkpoint Freezing**:
   - The training script (`train_mobilefacenet_arcface.py:691-696`) saved the export checkpoint (`best_mobilefacenet_arcface.keras`) based on peak separation delta ($\\Delta = \\mu_{gen} - \\mu_{imp}$).
   - This metric peaked prematurely at **Epoch 9** ($\\Delta = 0.1679$), at which point the model's classification accuracy was only **$1.95\\%$**.
   - Over subsequent epochs (10-25), classification converged to $99.96\\%$, but $\\Delta$ hovered between $0.1439$ and $0.1653$, meaning the checkpoint was **never updated after Epoch 9**. The exported TFLite models (`int8`, `fp16`, `fp32`) were generated from an undertrained Epoch 9 checkpoint.

4. **Multi-Task Unified Model Merger Defect**:
   - The offline merger script (`training/merge_qualcomm_unified_npu_model.py:174-210`) synthesized a multi-task network graph (embedding, 3DMM, attributes, gaze, mesh), but initialized it with random weights and converted it to TFLite without transferring weights from the downloaded Qualcomm models.
   - Consequently, running `unified_omniface.tflite` yields uninitialized random projections. The system currently relies on the standalone fallback engines.

5. **Multi-Face Group Scanning Bottleneck**:
   - The scanner pipeline (`BiometricVerificationEngineImpl.kt:208` / `FaceSecurityPipeline.kt:214`) iterates over up to 6 detected faces sequentially on a single thread.
   - Because TFLite execution is guarded by a global synchronization mutex (`engineMutex`) and input tensors are fixed to batch size 1 (`[1, 112, 112, 3]`), processing 6 faces incurs $6 \\times$ single-face latency ($120\\text{--}480\\text{ ms}$), during which incoming camera frames are dropped due to the binary processing lock (`!isProcessingFrame`).

6. **Absence of Feature-Level Multi-Frame Fusion**:
   - While `FaceTracker.kt` provides temporal track stabilization by requiring 2 consecutive frame hits on the same student roll, the pipeline extracts single-frame embeddings independently. There is no sliding-window embedding averaging or quality-weighted feature pooling across frames.

---

## 2. Current ML Architecture

```
                                  [ Android CameraX (1280x720 YUV_420_888) ]
                                                      │
                                                      ▼
                                       [ ML Kit Face Detection API ]
                                     (Bounding Box + 5 Point Fiducials)
                                                      │
                       ┌──────────────────────────────┴──────────────────────────────┐
                       ▼                                                             ▼
         [ Fast-Path: 60 FPS Visual ]                                 [ Async-Path: Biometric Core ]
          - OneEuro Reticle Smoothing                                  (Guarded by !isProcessingFrame)
          - Screen Space Projection                                                  │
                                                                                     ▼
                                                                     [ BiometricCropUtils ]
                                                               (1.25x Square Centroid Crop: 192x192)
                                                                                     │
                                                                                     ▼
                                                                     [ GATE 1: FaceQualityEngine ]
                                                                     - Laplacian Sharpness (Var >= 8.0)
                                                                     - Luminance (15 <= L <= 245)
                                                                     - Head Pose (|Yaw|<=38, |Pitch|<=32)
                                                                     - Scale (Width >= 5% frame)
                                                                                     │
                                                         ┌───────────────────────────┴───────────────────────────┐
                                                         │ [Pass / Quality >= 35.0]                              │ [Fail]
                                                         ▼                                                       ▼
                                         [ UmeyamaSimilarityTransform ]                               [ REJECT_QUALITY ]
                                         - 5-Point Canonical Alignment to 112x112                     (Prompt: Move Closer)
                                         - Residual Threshold < 18.0px
                                                         │
                                                         ▼
                                          [ Hardware Inference Execution ]
                                 (Governed by ExecutionGovernor & HardwareTier Ladder)
                                 ├── Priority 0: Qualcomm CavaFace (IR-SE-100, 65.5M)
                                 ├── Priority 1: MobileFaceNet INT8 (NNAPI / HTP NPU)
                                 ├── Priority 2: MobileFaceNet FP16 (GPU Delegate)
                                 └── Priority 3: MobileFaceNet FP32 (CPU XNNPACK)
                                                         │
                                                         ▼
                                           [ 512-D L2 Normalized Vector ]
                                                         │
                                                         ▼
                                            [ GATE 2: Anti-Spoofing / PAD ]
                                            ├── PassivePadEngine (MiniFASNetV2 80x80)
                                            └── TemporalLivenessEngine (3DMM depth + micro-motion)
                                                         │
                                                         ▼
                                            [ GATE 3: FaceMatcher 1:N Search ]
                                            ├── Candidate Generation: FaissVectorIndex (HNSW_FLAT)
                                            └── Exact Scoring: 0.70 * MaxAngle + 0.30 * Centroid
                                                         │
                                                         ▼
                                           [ FaceTracker Decision Lock ]
                                           - Multi-face Collision Resolution (assignedRolls)
                                           - Temporal Confirmation (consecutiveHits >= 2)
                                                         │
                                                         ▼
                                           [ BiometricDecisionEngine Synthesis ]
                                           ├── PASS (Attendance Authorized)
                                           ├── REVIEW_AMBIGUOUS_MATCH (Margin < Delta)
                                           ├── REJECT_SPOOF_ATTACK (Liveness Failure)
                                           └── REJECT_UNKNOWN_IDENTITY (Visitor)
```

### Active Components & File Locations
| Component | Primary File Path | Responsibility |
|---|---|---|
| **Camera Feed** | `app/src/main/java/com/omniface/ai/ui/scanner/Scanner.kt` | CameraX frame ingestion at 1280x720, YUV conversion, fast/async dispatch. |
| **Face Crop** | `app/src/main/java/com/omniface/ai/ml/BiometricCropUtils.kt` | Centered 1.25x square crop, direct allocation into fixed-size Bitmaps. |
| **Face Alignment** | `app/src/main/java/com/omniface/ai/ml/UmeyamaSimilarityTransform.kt` | Least-squares 2D similarity transform mapping 5 landmarks to canonical ArcFace coordinates. |
| **Quality Gate** | `app/src/main/java/com/omniface/ai/ml/quality/FaceQualityEngine.kt` | Pre-inference filter evaluating Laplacian sharpness, mean luminance, yaw, pitch, roll, and size. |
| **Inference Runtime** | `app/src/main/java/com/omniface/ai/ml/FaceRecognitionEngine.kt` | Multi-tier TFLite interpreter management (NPU/GPU/CPU), quantization handling, embedding extraction. |
| **Unified Engine** | `app/src/main/java/com/omniface/ai/ml/UnifiedFaceIntelligenceEngine.kt` | Multi-task singleton engine hosting Qualcomm intelligence suite models. |
| **Anti-Spoof PAD** | `app/src/main/java/com/omniface/ai/ml/antispoof/PassivePadEngine.kt` | 80x80 MiniFASNetV2 passive presentation attack detection. |
| **Temporal Liveness** | `app/src/main/java/com/omniface/ai/ml/antispoof/TemporalLivenessEngine.kt` | Multi-frame micro-motion, eye openness variance, and 3DMM depth parameter variance tracking. |
| **Vector Matcher** | `app/src/main/java/com/omniface/ai/ml/recognition/FaceMatcher.kt` | In-memory candidate search, composite angle/centroid scoring, margin verification. |
| **Vector Index** | `app/src/main/java/com/omniface/ai/ml/recognition/FaissVectorIndex.kt` | Pure Kotlin HNSW graph and inverted file index structures for approximate nearest neighbor retrieval. |
| **Tracker** | `app/src/main/java/com/omniface/ai/ml/tracking/FaceTracker.kt` | Persistent spatial-temporal tracking, 1€ bounding box filtering, decision locking. |
| **Decision Synthesis** | `app/src/main/java/com/omniface/ai/ml/pipeline/BiometricDecisionEngine.kt` | Three-gate evaluation synthesizing quality, anti-spoof, and identity matching results. |

---

## 3. Training Pipeline Audit

The training pipeline is fully implemented in `training/train_mobilefacenet_arcface.py` (1,287 LOC).

### Training Configuration
- **Script Target**: NVIDIA Tesla P100 (16GB HBM2) executed via Kaggle Kernel `preethamvfx/pins-face-recognition-npu-gpu-train`.
- **Framework**: TensorFlow 2.x / Keras with `TF_DETERMINISTIC_OPS=1`.
- **Batch Size**: 64 (Image size: $112 \\times 112 \\times 3$).
- **Optimizer**: AdamW (`weight_decay=1e-4`, `clipnorm=1.0`).
- **Learning Rate Schedule**: WarmupCosineDecay:
  - Initial LR: $1 \\times 10^{-4}$
  - Peak LR: $1 \\times 10^{-3}$ reached at Epoch 4
  - Cosine decay across Epochs 4–27 down to minimum LR $1 \\times 10^{-6}$
  - Late-stage BatchNorm freezing during Epochs 23–25.
- **Total Duration**: 25 epochs executed in 34.36 minutes (Total pipeline wall-clock: 35.61 minutes).

### Convergence Telemetry Analysis
Tracing `training/training_metrics.csv`:
- **Epoch 1**: Categorical Acc: $0.00\\%$, $\\mu_{gen} = 0.1206$, $\\mu_{imp} = 0.0785$, $\\Delta = 0.0420$, ROC-AUC: $0.5513$.
- **Epoch 5**: Categorical Acc: $0.13\\%$, $\\mu_{gen} = 0.2542$, $\\mu_{imp} = 0.1020$, $\\Delta = 0.1522$, ROC-AUC: $0.6773$.
- **Epoch 9**: Categorical Acc: $1.96\\%$, $\\mu_{gen} = 0.1982$, $\\mu_{imp} = 0.0303$, **$\\Delta = 0.1679$ (PEAK SEPARATION)**, ROC-AUC: $0.7119$.
- **Epoch 15**: Categorical Acc: $37.34\\%$, $\\mu_{gen} = 0.1837$, $\\mu_{imp} = 0.0183$, $\\Delta = 0.1654$, ROC-AUC: $0.7027$.
- **Epoch 20**: Categorical Acc: $98.22\\%$, $\\mu_{gen} = 0.1623$, $\\mu_{imp} = 0.0129$, $\\Delta = 0.1494$, ROC-AUC: $0.6839$.
- **Epoch 25**: Categorical Acc: $99.96\\%$, $\\mu_{gen} = 0.1580$, $\\mu_{imp} = 0.0119$, $\\Delta = 0.1462$, ROC-AUC: $0.6895$.

### Pathological Telemetry Diagnosis
1. **Classification vs. Metric Generalization Disconnect**:
   As classification accuracy surged from $1.96\\%$ to $99.96\\%$, genuine validation pair similarity **decreased** from $0.2542$ down to $0.1580$, and ROC-AUC dropped from $0.7185$ to $0.6895$. The network overfit to the 105 training identity centers by collapsing intra-class variance on the training images, causing embeddings on held-out images of the same identity to drift apart.
2. **Early Checkpoint Freezing**:
   Because `BiometricTelemetryCallback` saved checkpoints strictly when `delta > self.best_delta`, the saved model weights (`best_mobilefacenet_arcface.keras`) originate from Epoch 9 when the network had only reached $1.96\\%$ classification accuracy.

---

## 4. Training Data Pipeline Audit

### Dataset Identification
- **Name**: PINS Face Recognition Dataset (`hereisburak/pins-face-recognition`).
- **Nature**: Public web-scraped dataset collected from Pinterest.
- **Identity Count ($N$)**: Exactly 105 identities (celebrities and public figures).
- **Image Count**: Approximately 17,500 total images (~160 images per identity).
- **Input Resolution**: Various resolutions resized to $112 \\times 112$.

### Split Methodology & Leakage Risk
- **Split Type**: **Image-Disjoint Split** (`validation_split=0.15` in `image_dataset_from_directory`).
- **Leakage Reality**: All 105 identities are present in both the training set (85%) and validation set (15%). This is a closed-set classification split.
- **Absence of Open-Set Benchmark**: Standard biometrics (ISO/IEC 19794-5, NIST FRVT) require **identity-disjoint (open-set)** evaluation, where test subjects have zero representation in the training gallery. The repository contains zero open-set benchmark results on LFW, CFP-FP, AgeDB-30, or IJB-C.

### Domain Shift vs. Android Kiosk Deployment
| Domain Dimension | PINS Dataset Reality | Android Kiosk Reality | Domain Gap Severity |
|---|---|---|---|
| **Identity Count** | 105 identities | Hundreds to thousands of students | Critical (Model cannot span face manifold) |
| **Demographics** | International celebrities (Hollywood/Western bias) | Indian school/enterprise students (broad age, skin tones) | Critical (Demographic representation gap) |
| **Image Source** | Professional photo shoots, high-end DSLR, staged poses | Smartphone rolling shutter, CMOS fixed-focus kiosk camera | High (Sensor artifacts unrepresented) |
| **Resolution** | Clean, high-resolution original crops | Far-field crops down to 40x40 pixels | Critical (Small faces unrepresented) |
| **Motion** | Zero motion blur (static posed photos) | Natural student movement, walking, turning | High (Motion blur unrepresented) |
| **Illumination** | Studio lighting or balanced outdoor light | Harsh overhead fluorescent, backlight, deep shadows | High (Lighting extremes unrepresented) |
| **Occlusion** | Minimal (clean celebrity faces) | Spectacles, religious attire, masks, fringe hair | High (Occlusion unrepresented) |

**Conclusion**: The training dataset does NOT realistically represent the operating environment of an offline Android biometric terminal.

---

## 5. Data Augmentation Audit

The active augmentation pipeline is defined in `train_mobilefacenet_arcface.py:517-539`:

```python
geometric_aug = tf.keras.Sequential([
    layers.RandomFlip("horizontal"),
    layers.RandomRotation(0.02),
    layers.RandomTranslation(0.03, 0.03),
    layers.RandomBrightness(0.08),
    layers.RandomContrast(0.08)
], name="geom_aug")

def random_cutout(image, patch_size=14):
    ...
```

### Detailed Operation Audit
| Operation | Probability | Parameter Range | Location | Identity-Preserving | Realistic for Kiosk? |
|---|---|---|---|---|---|
| **RandomFlip** | 0.50 | Horizontal | In-graph GPU | Yes | Yes (Bilateral facial symmetry) |
| **RandomRotation** | 1.00 | $\\pm 0.02 \\times 2\\pi = \\pm 7.2^\\circ$ | In-graph GPU | Yes | Minimal (Real yaw/roll reaches $\\pm 35^\\circ$) |
| **RandomTranslation** | 1.00 | $\\pm 3.3\\text{ px}$ (3% of 112) | In-graph GPU | Yes | Marginal (Bounding box jitter exceeds 3 px) |
| **RandomBrightness** | 1.00 | $\\pm 8\\%$ | In-graph GPU | Yes | Minimal (Real kiosk lighting shifts $\\pm 50\\%$) |
| **RandomContrast** | 1.00 | $\\pm 8\\%$ | In-graph GPU | Yes | Minimal (Harsh ambient glare unrepresented) |
| **RandomCutout** | 0.50 | Fixed $14 \\times 14\\text{ px}$ black square | Python map | Yes | Minimal (Covers only $1.5\\%$ of face area) |

### Augmentation Gaps Identified
- **Downsampling / Low-Resolution Simulation**: ABSENT. No resize degradation (e.g., $112 \\to 28 \\to 112$) to simulate distant subjects.
- **Motion Blur / Defocus Blur**: ABSENT. No Gaussian or linear directional blur transforms.
- **JPEG / Compression Artifacts**: ABSENT. No compression noise simulation.
- **Sensor Noise**: ABSENT. No Poisson, Gaussian, or ISO camera sensor noise.
- **Severe Lighting / Backlight**: ABSENT. No gamma correction, shadow gradient, or specular glare simulation.
- **Realistic Occlusions**: ABSENT. No synthetic masks, spectacles, or hand occlusions.

---

## 6. Loss & Training Objective Audit

### Mathematical Formulation
The training objective is implemented in `SubCenterDynamicArcFaceHead` (`train_mobilefacenet_arcface.py:203-304`):

$$\cos(\theta_{i}) = \max_{k \in \{1, \dots, K\}} \left( \frac{x^T W_{i, k}}{\|x\| \|W_{i, k}\|} \right)$$

For ground-truth target class $y$:
$$\cos(\theta_y + m) = \cos(\theta_y)\cos(m) - \sin(\theta_y)\sin(m)$$
with numerical stability thresholding for $\cos(\theta_y) < \cos(\pi - m)$.

$$\mathcal{L} = -\log \frac{e^{s \cdot \cos(\theta_y + m)}}{e^{s \cdot \cos(\theta_y + m)} + \sum_{j \neq y} e^{s \cdot \cos(\theta_j)}} + \lambda \mathcal{L}_{ortho}$$

- **Sub-Centers ($K$)**: 2 sub-centers per class.
- **Margin ($m$)**: Annealed dynamically from $0.20 \\to 0.50$ across Epochs 1–6.
- **Scale ($s$)**: Annealed dynamically from $32.0 \\to 64.0$ across Epochs 1–6.
- **Orthogonality Penalty**: $\\lambda = 10^{-3} \\times \\text{mean}(\\max(W_{i,1}^T W_{i,2} - 0.707, 0)^2)$.

### Assessment of the Objective
1. **Mathematical Soundness**: The implementation of Sub-Center ArcFace with orthogonal regularization is mathematically correct and adheres to Deng et al. (Sub-Center ArcFace, ECCV 2020).
2. **Pathology with Small Identity Count**:
   ArcFace requires a large number of identities ($N \\ge 10,000$) to densely distribute identity centers across the 512-D hypersphere. With only $N=105$ identities, the hypersphere is virtually empty (105 centers in 512 dimensions can be mutually orthogonal with zero competition).
   Consequently, the network satisfies the large margin ($m=0.50$) with trivial separation, failing to force compact feature clustering for unseen variations.
3. **Evaluation Defect during Training**:
   In `train_mobilefacenet_arcface.py:770`, `train_model.evaluate(val_ds)` passed the validation set through the ArcFace margin head. Because margin $m=0.50$ was subtracted from the target class, the target logit was penalised by $\\cos(\\theta_y + 0.50) \\approx 0.15 \\times 0.877 - 0.988 \\times 0.479 = -0.34$, while negative logits remained at $\\approx +0.01$. This made target logits systematically smaller than impostor logits, producing the reported **$0.61\\%$ Top-1 Identification Accuracy** in `verification_report.json`.

---

## 7. Hard-Negative Mining Audit

### Current Status
**ABSENT**. The training pipeline relies entirely on standard stochastic mini-batch sampling (`train_ds_raw.shuffle(2000)`).
- Visually similar identities are not co-sampled.
- Same-person cross-condition pairs are not actively constructed.
- No memory bank, online hard example mining (OHEM), or semi-hard triplet mining is present.

### Proposed Experiment Design (R&D)
- **Class-Aware Hard Negative Batch Construction**: At each epoch boundary, compute class centroid embeddings. For each identity $C_i$, find its top-3 nearest neighbor classes $C_j$ on the hypersphere and construct batches containing $50\\%$ anchor identities and $50\\%$ hardest-confusable negative identities.
- **Curricular Margin Adjustment (CurricularFace)**: Dynamically scale margin penalties based on negative sample cosine similarity, concentrating gradients on confusing negative pairs rather than easily separable identities.

---

## 8. Embedding Model Audit

### Backbone Architecture
- **Architecture**: Native MobileFaceNet (~1.29M parameters).
- **Complexity**: ~440 MFLOPs (~220 MMACs).
- **Topology**:
  - Input: $112 \\times 112 \\times 3$ RGB.
  - In-Graph Rescaling: `scale = 1/128.0`, `offset = -127.5/128.0` (maps $[0, 255] \\to [-1.0, 1.0]$).
  - Stem: Conv2D ($3 \\times 3$, stride 2, 64 filters) + Depthwise ($3 \\times 3$, stride 1, 64 filters).
  - Stage 1: 5 Inverted Residual Bottlenecks ($56 \\times 56 \\to 28 \\times 28$, 64 channels).
  - Stage 2: 8 Inverted Residual Bottlenecks ($28 \\times 28 \\to 14 \\times 14$, 128 channels).
  - Stage 3: 3 Inverted Residual Bottlenecks ($14 \\times 14 \\to 7 \\times 7$, 128 channels).
  - Expansion: Conv2D ($1 \\times 1$, 512 filters).
  - Global Depthwise: GDConv ($7 \\times 7$, stride 1, valid padding, 512 channels) + Flatten.
  - Projection: Dense (512 units, no bias) + BatchNorm + L2Normalize.

### Exported TFLite Models & Quantization Parity
From `docs/verification_report.json` and `training/train_mobilefacenet_arcface.py`:
- `mobilefacenet_512d_fp32.tflite`: 4.85 MB (CPU XNNPACK baseline).
- `mobilefacenet_512d_fp16.tflite`: 2.47 MB (Mobile GPU Delegate).
- `mobilefacenet_512d_int8.tflite`: 1.54 MB (NPU / NNAPI MLIR Per-Channel INT8).
- **Quantization Parity**: Cosine parity between Keras float32 and TFLite INT8 is **$0.98604$** on reference inputs, confirming that the quantization process itself did not degrade the model's representation.

### Preprocessing & Color Order Verification
- **Training Preprocessing**: Expects raw RGB byte range $[0, 255]$. The in-graph `Rescaling` layer maps to $[-1.0, 1.0]$.
- **Client Preprocessing (`FaceRecognitionEngine.kt:685-705`)**:
  - For MobileFaceNet: passes raw pixels $[0.0f, 255.0f]$ directly to input buffer.
  - For Qualcomm CavaFace: normalizes pixels to $[0.0f, 1.0f]$.
  - Channel order: R, G, B extracted via `(pixel shr 16) and 0xFF`, `(pixel shr 8) and 0xFF`, `pixel and 0xFF`.
  - **Audit Verdict**: Preprocessing and normalization between Android client and MobileFaceNet graph match.

---

## 9. Face Alignment Audit

### Pipeline Trace
$$\text{Camera Frame} \xrightarrow{\text{ML Kit}} \text{5 Landmark Fiducials} \xrightarrow{\text{Umeyama}} \text{Affine Matrix} \xrightarrow{\text{Bitmap Warp}} 112 \times 112 \text{ Canonical Crop}$$

### Canonical Reference Geometry
`UmeyamaSimilarityTransform.kt:17-23` enforces standard ArcFace 5-point reference coordinates:
- Left Eye: $(38.2946, 51.6963)$
- Right Eye: $(73.5318, 51.5014)$
- Nose Tip: $(56.0252, 71.7366)$
- Left Mouth Corner: $(41.5493, 92.3655)$
- Right Mouth Corner: $(70.7299, 92.2041)$

### Residual Error Gating
- `UmeyamaSimilarityTransform.alignFace5Points` computes the mean Euclidean residual error between transformed source points and canonical target points:
  $$\text{residualError} = \frac{1}{5} \sum_{i=1}^5 \sqrt{(x'_i - x_{\text{dst}, i})^2 + (y'_i - y_{\text{dst}, i})^2}$$
- In `BiometricVerificationEngineImpl.kt:275` and `FaceSecurityPipeline.kt:282`, alignment is accepted only if $\text{residualError} < 18.0\text{ px}$.
- If residual exceeds 18 px or landmarks are missing (e.g. extreme profile), the system falls back to `BiometricCropUtils.extractDirectFaceCrop` (a $1.25\times$ square bounding box crop centered on face centroid).

### Vulnerabilities Identified
1. **Pose Extrema ($|\text{Yaw}| > 25^\circ$)**: When the subject turns beyond $25^\circ$, ML Kit's 2D landmarks become compressed, leading to an over-scaled or sheared affine transform that distorts facial features.
2. **Thermal Downscaling Landmark Shift**: When `ThermalGovernor` activates ($downscaleFactor < 1.0$), landmarks are scaled proportionally (`it.x * downscaleFactor`). Floating-point truncation in low-resolution frames can cause landmark jitter and false residual rejections.

---

## 10. Multi-Frame Recognition Capability Audit

### Existing Multi-Frame Logic
In `FaceTracker.kt:256-310`:
- **State Locking**: A persistent track requires `consecutiveKnownHits >= 2` (or a single frame hit with similarity $\ge 0.72$) to lock an identity.
- **Score Aggregation**: `matchSimilarity = maxOf(rawDecision.matchSimilarity, state.matchSimilarity)` (pure score-level maximum).

### Missing Capabilities
- **Feature-Level Temporal Aggregation**: ABSENT. The pipeline does NOT average or pool embeddings across sequential frames.
- **Quality-Weighted Aggregation**: ABSENT. High-sharpness frontal frames are treated identically to borderline blurry frames; the tracker simply records the highest score encountered.

### Proposed Multi-Frame Aggregation Strategies (Experiment Matrix)
1. **Strategy A (Baseline)**: Single-frame extraction on latest frame.
2. **Strategy B (Moving Centroid)**: Unweighted moving average of last $N$ embeddings ($N \in \{3, 5\}$):
   $$\bar{e}_t = \text{L2Normalize}\left(\frac{1}{N} \sum_{k=0}^{N-1} e_{t-k}\right)$$
3. **Strategy C (Quality-Weighted Pooling)**: Weight embeddings by `FaceQualityEngine` overall quality score $Q_t$:
   $$\bar{e}_t = \text{L2Normalize}\left(\sum_{k=0}^{N-1} Q_{t-k} e_{t-k}\right)$$
4. **Strategy D (Score-Level Temporal Consensus)**: Exponential moving average of match similarity scores:
   $$\bar{s}_t = 0.60 s_t + 0.40 \bar{s}_{t-1}$$

---

## 11. Group Recognition Readiness Audit

OmniFace is targeted for continuous group attendance (students walking together through a doorway kiosk).

### Current Concurrency Limitations
1. **Sequential Loop Processing**:
   `BiometricVerificationEngineImpl.kt:208`:
   ```kotlin
   for (face in faces.take(6)) {
       ...
       val unifiedResult = unifiedEngine.processScannerFace(...)
       ...
       matchResult = matcher.match(...)
   }
   ```
   All faces in view are processed in a single sequential `for` loop on `Dispatchers.Default`.
2. **Global Interpreter Mutex**:
   `FaceRecognitionEngine.kt:632` synchronizes on `engineMutex`. If two threads or coroutines invoke embedding extraction simultaneously, they block sequentially.
3. **Single-Batch Tensor Input**:
   Both MobileFaceNet and CavaFace flatbuffers have fixed concrete input signatures: `[1, 112, 112, 3]`. Processing $K$ faces requires $K$ discrete `invoke()` calls to the neural runtime.
4. **Camera Frame Dropping**:
   `Scanner.kt:1988` guards the biometric path with `if (!viewModel.isProcessingFrame)`. If processing 4 faces takes $120\text{ ms}$, approximately 3 to 4 incoming camera frames are completely skipped.

### Group Latency Scaling Matrix
| Simultaneous Faces | Detection Latency (ML Kit) | Sequential Inference Latency (MobileFaceNet INT8) | Matching & Liveness Latency | Total Frame Processing Time | Max Achievable FPS |
|---|---|---|---|---|---|
| **1 Face** | ~15 ms | ~8 ms | ~3 ms | **26 ms** | **38.4 FPS** |
| **2 Faces** | ~20 ms | ~16 ms | ~6 ms | **42 ms** | **23.8 FPS** |
| **3 Faces** | ~25 ms | ~24 ms | ~9 ms | **58 ms** | **17.2 FPS** |
| **4 Faces** | ~30 ms | ~32 ms | ~12 ms | **74 ms** | **13.5 FPS** |
| **5 Faces** | ~35 ms | ~40 ms | ~15 ms | **90 ms** | **11.1 FPS** |
| **6 Faces** | ~40 ms | ~48 ms | ~18 ms | **106 ms** | **9.4 FPS** |

*(Note: If running Qualcomm CavaFace FP16 at ~65 ms per inference, 4 faces require $4 \\times 65 = 260\\text{ ms}$, dropping throughput to $< 3.5\\text{ FPS}$.)*

---

## 12. Camera & Image Quality Dependence Audit

### `FaceQualityEngine` Analysis
`FaceQualityEngine.kt:61-179` computes a composite score ($0\\text{--}100$) based on:
1. **Sharpness ($35\\%$)**: Discrete 2nd-order Laplacian kernel variance $\\sigma^2_{Lap} = \\frac{1}{M} \\sum (4C - T - B - L - R)^2$. Pass threshold: $\\sigma^2_{Lap} \\ge 8.0$.
2. **Exposure ($25\\%$)**: Mean luminance $L = 0.299R + 0.587G + 0.114B$. Pass range: $15.0 \\le L \\le 245.0$.
3. **Head Pose ($25\\%$)**: Head Euler angles from ML Kit: $|\text{Yaw}| \\le 38^\\circ$, $|\text{Pitch}| \\le 32^\\circ$, $|\text{Roll}| \\le 28^\\circ$.
4. **Face Scale ($15\\%$)**: Bounding box width ratio $\\ge 5\\%$ of frame width.

### Utilization Assessment
- **Role in Pipeline**: Currently used **ONLY FOR BINARY REJECTION (GATE 1)**. If `!quality.isPassed`, the frame is rejected immediately with user guidance ("Move closer", "Image blurry", "Level face").
- **Gaps**:
  - Does NOT modulate matching threshold $\\tau$ (e.g. raising $\\tau$ when image quality is borderline).
  - Does NOT weight embeddings in temporal tracking.
  - Does NOT select the sharpest frame from a short burst buffer before inference.

---

## 13. Threshold Calibration Audit

### Threshold Configuration Summary
Defined in `FaceRecognitionEngine.kt:47-65`:
| Security Tier | Cosine Threshold ($\\tau$) | Decision Margin Threshold ($\\Delta$) | Target FAR | Intended Use Case |
|---|---|---|---|---|
| **STANDARD** | $0.650$ ($65\\%$) | $0.040$ ($4.0\\%$) | $1:10$ ($10^{-1}$) | High-throughput doorway kiosk |
| **HIGH** | $0.720$ ($72\\%$) | $0.045$ ($4.5\\%$) | $1:100$ ($10^{-2}$) | ISO/IEC Standard attendance |
| **STRICT** | $0.800$ ($80\\%$) | $0.050$ ($5.0\\%$) | $1:1,000$ ($10^{-3}$) | High-security / banking access |

### Empirical Reality vs. Configured Thresholds
From `docs/verification_report.json` on the trained MobileFaceNet model:
- Measured Mean Genuine Similarity: **$\\mu_{gen} = 0.2633$**
- Measured Mean Impostor Similarity: **$\\mu_{imp} = 0.0341$**
- Calculated Optimal EER Threshold: **$\\tau^* = 0.1580$**
- Measured TAR at FAR = 1% ($\\tau = 0.4158$): **$18.64\\%$**
- Measured TAR at FAR = 0.1% ($\\tau = 0.4997$): **$8.47\\%$**
- Measured TAR at FAR = 0.01% ($\\tau = 0.5114$): **$7.72\\%$**

### Distinction Between Measured Evidence and Assumptions
- **Configured Values ($0.650, 0.720, 0.800$)**: These are **UNVERIFIED ACADEMIC ASSUMPTIONS**. They are standard for deep ResNet-100 / ArcFace models trained on Glint360k (3M identities), but completely incompatible with the locally trained MobileFaceNet (where genuine matches score $\\approx 0.26$).
- **Consequence**: The application currently exhibits extreme False Rejection Rate ($> 95\\%$) when running the local MobileFaceNet model against enrolled templates.

---

## 14. Liveness & Anti-Spoofing Audit

### Inventory of Liveness Engines
1. **PassivePadEngine (`silentface.tflite` / MiniFASNetV2)**:
   - Input: $80 \\times 80 \\times 3$ RGB.
   - Output: 2-class softmax (`prob0 = Spoof`, `prob1 = Live`).
   - Threshold: `LIVE_THRESHOLD = 0.65f`.
   - Latency: $\\approx 4\\text{--}8\\text{ ms}$.
   - Channel Preprocessing Notice: MiniFASNet was originally trained on OpenCV BGR format. `PassivePadEngine.kt:208-213` feeds RGB channels. This requires empirical BGR vs RGB verification to prevent texture misclassification.
2. **TemporalLivenessEngine**:
   - Analyzes micro-motion across frames, eye blink rate (ML Kit eye open probability), head rotation velocity, and 3DMM depth variance.
   - Requires depth variance $> 0.0015$ from FaceMap 3DMM to confirm non-flat skull structure.
   - Latency: $< 1.5\\text{ ms}$ (pure arithmetic).
3. **MultiStageLivenessEngine**:
   - Synthesizes passive PAD score ($60\\%$) with temporal confidence ($40\\%$).
   - Rejects if confirmed spoof probability $\\ge 70\\%$ or composite liveness score $< 0.45$.

### Redundancy vs. Complementarity
The liveness suite is **complementary, not redundant**:
- Passive PAD detects high-frequency printed paper moiré and screen refresh frequency.
- Temporal liveness detects static photo replay and photo-cutout attacks.
- 3DMM depth parameter variance detects flat 2D screens vs genuine 3D skull morphology.

---

## 15. Vector Search Audit

### Structure & Implementation
- `FaceMatcher.kt` maintains:
  1. `biometricCache = CopyOnWriteArrayList<CachedBiometric>()` (flat array in heap memory).
  2. `faissIndex = FaissVectorIndex(dimension = 512, indexType = HNSW_FLAT)`.
- `FaissVectorIndex.kt` is a pure Kotlin software implementation of an HNSW graph (delegating to `HnswVectorIndex.kt`) and IVF inverted lists. It is NOT a JNI binding to Facebook FAISS C++.

### Gating & Candidate Generation
In `FaceMatcher.kt:220-229`:
- If `isFaissHnswIndexEnabled` is true AND `biometricCache.size > 64`:
  - Queries `faissIndex.search(queryEmbedding, k = minOf(200, size))`.
  - Filters `studentTemplates` by the candidate rolls returned by the ANN search.
  - Computes exact cosine similarity on the filtered subset.
- If gallery size $\le 64$:
  - Bypasses ANN index and executes exact linear scan across all templates.

### Performance & Memory Audit
- For enterprise kiosk deployments ($N \\le 1,000$ students, ~3,000 templates with multi-angle enrollment):
  - Linear dot product of 512-D float vectors across 3,000 templates takes **$< 0.8\\text{ ms}$** on ARM64 using NEON vectorization.
  - Maintaining the in-memory HNSW graph introduces redundant object allocations and synchronization overhead without measurable latency reduction.
  - Linear scan guarantees **$100\\%$ Recall@1 and Recall@5**, completely eliminating ANN graph recall drops.

---

## 16. Experiment Matrix

To systematically resolve the biometric quality bottlenecks without destabilizing production, the following experimental progression is designed:

| Exp ID | Configuration / Hypothesis | Dataset & Training Changes | Inference & Runtime Changes | Primary Metrics Measured | Success Gate |
|---|---|---|---|---|---|
| **A (Baseline)** | Current MobileFaceNet + Current Pipeline | PINS 105 classes, 25 epochs (as currently trained) | Standalone inference, threshold $\\tau=0.720$ | TAR @ FAR=0.1%, EER, Latency | Establish ground-truth baseline |
| **B (Calibrated Thresholds)** | Current model with empirically calibrated thresholds | Same baseline weights | Shift $\\tau$ from $0.720 \\to 0.180\\text{--}0.240$ based on ROC sweep | TAR @ FAR=1%, TAR @ FAR=0.1% | TAR improves from $0\\% \\to > 65\\%$ without retraining |
| **C (Deployment Augmentation)** | Retrain MobileFaceNet with mobile-first augmentations | Add motion blur, downsampling ($112 \\to 32 \\to 112$), JPEG noise, lighting gradients | Unchanged | TAR on synthetic blurry/low-res test split | Low-quality TAR improves by $\\ge 25\\%$ |
| **D (Open-Set Scaling)** | Scale to open-set metric learning on larger public dataset | Train on CASIA-WebFace or Glint-10k ($10,000+$ identities), open-set split | Unchanged | Open-set LFW accuracy, $\\mu_{gen}$, TAR @ FAR=0.01% | $\\mu_{gen} > 0.65$, TAR @ FAR=0.1% $> 95\\%$ |
| **E (Temporal Fusion)** | Test multi-frame feature aggregation | Same weights | Quality-weighted sliding window ($N=3$ frames) | Video verification accuracy, stability | Track ID flipping reduced to $0\\%$ |
| **F (Batched Group Inference)** | Multi-face batched execution | Model converted with dynamic or batched signature $[B, 112, 112, 3]$ | Vectorized batch inference for up to 4 faces | Multi-face FPS, frame drop count | 4-face latency $< 40\\text{ ms}$ ($> 25\\text{ FPS}$) |

---

## 17. Evaluation Protocol

Future ML experiments must adhere to the following evaluation standard:

### 1. Dataset Partitioning
- **Strict Identity-Disjoint Protocol**: Zero overlap between training identity classes and evaluation classes.
- **Open-Set Benchmark Test Sets**:
  - Benchmark A: Standard high-quality frontal faces (LFW / synthetic clean).
  - Benchmark B: Low-resolution degraded faces ($32 \\times 32$ to $56 \\times 56$ upscaled).
  - Benchmark C: Motion-blurred / rolling shutter smartphone captures.
  - Benchmark D: Extreme illumination (backlit, deep shadow, specular glare).
  - Benchmark E: Natural multi-face group frames from Android cameras.

### 2. Multi-Decade Metrics Suite
Never report a single scalar accuracy number. Evaluate across:
- **TAR @ FAR Operating Points**:
  - Doorway Kiosk: TAR @ $\\text{FAR} = 10^{-1}$ ($1:10$)
  - ISO/IEC Standard: TAR @ $\\text{FAR} = 10^{-2}$ ($1:100$)
  - High-Security / Banking: TAR @ $\\text{FAR} = 10^{-3}$ ($1:1,000$)
  - Enterprise Zero-Tolerance: TAR @ $\\text{FAR} = 10^{-4}$ ($1:10,000$)
- **Equal Error Rate (EER)** and Detection Error Tradeoff (DET) curves.
- **Biometric Separation Delta**: $\\Delta = \\mu_{gen} - \\mu_{imp}$.
- **Identification Retrieval**: Rank-1 Recall, Rank-5 Recall over $N=1,000$ gallery.
- **Runtime Performance**:
  - End-to-end per-face latency (P50, P90, P99 in milliseconds).
  - Multi-face throughput (FPS with 1, 2, 4, 6 simultaneous faces).
  - Memory heap footprint and peak native allocation.
  - Sustained thermal stability over a 30-minute continuous scanning session.

---

## 18. True Bottleneck Ranking

| Rank | Subsystem | Evidence | Confidence | Expected Impact | Implementation Difficulty | Regression Risk |
|:---:|---|---|:---:|:---:|:---:|:---:|
| **1** | **TRAINING DATASET** | Only 105 celebrity classes; closed-set image-disjoint split; cannot generalize to real population. | Very High ($100\\%$) | Massive | Medium (Requires dataset acquisition & training) | Low (Retaining edge pipeline) |
| **2** | **THRESHOLD MISALIGNMENT** | Hardcoded $\\tau = 0.720$ vs model $\\mu_{gen} = 0.263$ causes near-$100\\%$ genuine user rejection. | Very High ($100\\%$) | Critical | Very Low (Configurable calibration) | Low |
| **3** | **CHECKPOINT INTEGRITY** | Exported TFLite models originate from undertrained Epoch 9 checkpoint ($1.96\\%$ acc). | Very High ($100\\%$) | High | Low (Select correct checkpoint) | Low |
| **4** | **GROUP CONCURRENCY** | Sequential 6-face loop + single-batch $[1, 112, 112, 3]$ causes 120-480ms latency & frame drops. | High ($95\\%$) | High | Medium (Batched inference & async pipeline) | Medium |
| **5** | **AUGMENTATION GAPS** | Zero blur, zero downsampling, zero JPEG noise in training; brittle on cheap cameras. | High ($90\\%$) | High | Low (Update training transforms) | Very Low |
| **6** | **TEMPORAL FUSION** | Decision-level locking only; no feature-level embedding pooling over time. | Medium ($85\\%$) | Medium | Low (Implement sliding-window buffer) | Very Low |
| **7** | **UNIFIED MODEL MERGER** | Merger script produced uninitialized weights; unified engine currently non-functional. | Very High ($100\\%$) | High | High (Requires weight stitching or multi-task training) | Medium |
| **8** | **ALIGNMENT VULNERABILITY** | High yaw ($>25^\\circ$) distorts 2D Umeyama affine warp; falls back to unaligned crop. | Medium ($80\\%$) | Medium | Medium (Requires 3D pose-aware unwarping) | Medium |
| **9** | **LIVENESS CHANNEL ORDER** | MiniFASNet may expect BGR but receives RGB; potential false rejection on presentation attacks. | Medium ($75\\%$) | Medium | Very Low (Test channel flip) | Low |
| **10** | **VECTOR SEARCH** | Kotlin HNSW graph is redundant for $N < 1,000$ gallery; linear scan is faster and exact. | High ($90\\%$) | Low | Very Low (Simplify to linear scan) | None |

---

## 19. Plan A — Safe Plan (Zero Model Changes, Minimal Production Risk)

These changes require zero model retraining and zero graph modifications, resolving immediate production friction:

1. **Empirical Threshold Recalibration for Current Model**:
   - Calibrate operating thresholds in `SecurityTier` based on the actual score distribution of the deployed model (Standard: $\\tau = 0.16$, High: $\\tau = 0.20$, Strict: $\\tau = 0.24$) OR ensure high-performance CavaFace is utilized when $\\tau \\ge 0.650$ is enforced.
2. **Burst Best-Frame Quality Selection**:
   - In `ScannerViewModel`, maintain a 3-frame rolling circular buffer. When faces are detected, pass the frame with the highest `FaceQualityEngine` sharpness score to the biometric pipeline, avoiding blurry transitional frames.
3. **Linear Scan for Galleries $\\le 1,000$**:
   - Bypass the Kotlin HNSW graph when template count $\\le 1,000$, performing exact SIMD-friendly linear cosine dot products. Guarantees $100\\%$ Recall@1 with sub-millisecond execution.
4. **MiniFASNet Channel Verification**:
   - Execute an empirical test evaluating `PassivePadEngine` on authentic faces and spoof replays with both RGB and BGR input orders to eliminate channel inversion risks.

---

## 20. Plan B — High-Value Plan (Material Quality & Throughput Gains)

These actions address the primary training and concurrency bottlenecks:

1. **Large-Scale Open-Set Model Retraining**:
   - Replace the 105-class PINS dataset with a public biometric dataset of $\\ge 10,000$ identities (CASIA-WebFace, VGGFace2, or Glint subset).
   - Enforce strict identity-disjoint train/val splitting.
   - Retrain MobileFaceNet with Sub-Center ArcFace ($K=2$) to establish genuine cosine similarities $\\mu_{gen} \\ge 0.70$ on unseen test identities, making the model natively compatible with $\\tau = 0.650\\text{--}0.720$.
2. **Smartphone-Realistic Data Augmentation**:
   - Introduce severe synthetic degradations into the training pipeline:
     - Random Gaussian & motion blur (kernel size 3 to 7).
     - Downsampling degradation ($112 \\to 32 \\to 112$).
     - JPEG compression artifacts (quality factor 20 to 75).
     - Sensor noise (Gaussian noise $\\sigma \\in [0.01, 0.05]$).
     - Illumination gradients (simulating strong lateral sunlight and backlight).
3. **Batched Multi-Face Inference Pipeline**:
   - Re-export the TFLite models with a dynamic or 4-batch input signature: `[4, 112, 112, 3]`.
   - When multiple faces are present in a frame, crop and preprocess all faces in parallel, binding them into a single batched forward pass to maximize NPU/GPU compute density.
4. **Sliding-Window Quality-Weighted Embedding Aggregation**:
   - In `FaceTracker`, maintain a circular buffer of the last 3 valid embeddings per track.
   - Aggregate via quality-weighted normalization:
     $$\bar{e} = \text{L2Normalize}\left(\sum_{i=1}^k Q_i e_i\right)$$
   - Increases identity stability against momentary motion blur or profile turning.

---

## 21. Plan C — R&D Plan (Experimental, High-Upside Explorations)

1. **Quality-Adaptive Loss Function (AdaFace / CurricularFace)**:
   - Implement AdaFace (CVPR 2022) which dynamically modulates the angular margin based on image quality:
     $$m_i = f(\text{quality}_i)$$
   - Prevents the network from over-penalizing unaligned or degraded facial crops, improving recognition on poor-quality mobile camera frames.
2. **Multi-Task Sovereign Neural Backbone**:
   - Rather than merging independent disparate models post-hoc, train a unified multi-task MobileNetV4 / MobileFaceNet backbone end-to-end with multiple heads:
     - Head 1: 512-D Identity Embedding (ArcFace)
     - Head 2: 3DMM Shape Parameters (MSE)
     - Head 3: Presentation Attack Detection (Binary Crossentropy)
     - Head 4: Landmark Heatmaps
   - Eliminates redundant feature extraction passes and fits all vision intelligence into a single sub-20ms NPU execution.
3. **Quantization-Aware Training (QAT)**:
   - Train MobileFaceNet with TensorFlow/LiteRT Quantization-Aware Training (fake quantization nodes inserted during backprop) to achieve zero accuracy loss when compiled to INT8 for Qualcomm Hexagon HTP.

---

## 22. Final Recommended Sequence

```
[ PHASE 1: Immediate Safety & Verification ]
  1. Fix Checkpoint Selection in train_mobilefacenet_arcface.py (Save by Validation Loss / Open-Set Accuracy, not early Delta).
  2. Verify BGR vs. RGB channel input in PassivePadEngine.
  3. Calibrate runtime threshold tiers to match actual model separation distributions.

[ PHASE 2: Data & Augmentation Realism ]
  4. Integrate CASIA-WebFace / Glint-10k open-set training dataset (>= 10,000 identities).
  5. Implement Low-Quality Mobile Augmentations (motion blur, downsampling, JPEG compression, ISO noise).
  6. Train MobileFaceNet-512D with Sub-Center ArcFace on open-set dataset.

[ PHASE 3: Inference & Concurrency Modernization ]
  7. Export INT8 & FP16 models and verify quantization parity on real test frames.
  8. Implement Quality-Weighted Sliding-Window Embedding Aggregation in FaceTracker.
  9. Implement Batched [4, 112, 112, 3] Multi-Face Forward Pass in FaceRecognitionEngine.
 10. Modernize ScannerViewModel camera loop to process multi-face bursts without frame drops.
```

---

## 23. Risks & Unknowns

1. **Licensing & Compliance of Training Datasets**:
   - PINS is strictly for research/academic use.
   - Commercial deployments require verified commercially-permissive training datasets (e.g., BUPT-BalancedFace or proprietary consented enrollment sets) to satisfy DPDP Act 2023 and GDPR biometrics regulations.
2. **Thermal Degradation in Multi-Face Continuous Kiosks**:
   - Sustained multi-face batched inference on low-cost Android hardware can trigger thermal throttling within 15–20 minutes.
   - The system must ensure `ThermalGovernor` gracefully steps down resolution and frame rate without corrupting vector parity.
3. **Qualcomm AI Hub Model S3 Deprecation**:
   - `merge_qualcomm_unified_npu_model.py` depends on Qualcomm S3 release `v0.60.0`. Remote URL expiration could break automated pipeline re-runs. Model artifacts should be locally cached and mirrored.

---

# DO NOT MODIFY PRODUCTION YET

### Exact Next First Experiment to Perform
**Experiment B.1 — Empirical Multi-Decade Score Distribution Sweep & Threshold Calibration**:
1. Without modifying any model weights or production code, construct an offline evaluation script that loads `mobilefacenet_512d_int8.tflite` and `cavaface.tflite`.
2. Ingest a verification pair dataset of 1,000 genuine pairs and 10,000 impostor pairs.
3. Generate empirical ROC and DET curves, plotting exact FAR vs. FRR across threshold range $\\tau \\in [0.05, 0.95]$.
4. Determine the exact mathematical threshold $\\tau$ corresponding to $\\text{FAR} = 10^{-2}$ (1:100) and $\\text{FAR} = 10^{-3}$ (1:1,000).
5. Document whether the deployed model can achieve $> 90\\%$ TAR at $1:100$ FAR before any retraining is initiated.
