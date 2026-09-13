# 🤖 Antigravity Configured Agents, Rules & Standards (OmniFace AI Platform)

This workspace (`c:\AI-HUB\OmniFace-AI` / `/storage/emulated/0/AI-HUB/FR`) hosts the sovereign **OmniFace AI** Biometric Facial Intelligence Platform, encompassing the native Kotlin Android client application, multi-tier Google LiteRT inference runtimes, hardware-backed AndroidKeyStore AES-256-GCM security, Room SQLite offline-first persistence, and the end-to-end UnifiedFaceModel V2 deep learning and knowledge distillation pipeline.

---

## 🤖 Configured Subagents

### 1. `research`
- **Role**: Biometric Codebase & ML Researcher
- **Capabilities**: Read-only codebase exploration, web search, LiteRT / TFLite operator inspection, model quantization analysis, documentation audit.
- **Use Case**: Deep research tasks on neural graph operators, mathematical loss functions, and benchmark datasets.

### 2. `self`
- **Role**: General Engineering Subagent
- **Capabilities**: Full execution subagent with file editing, Gradle builds, ADB testing, and parallel script execution.
- **Use Case**: Background Android builds, APK packaging, and continuous integration workflows.

### 3. `jules-orchestrator`
- **Role**: Multi-Task Orchestration Agent
- **Capabilities**: Project orchestration, work item tracking, and automated workflow execution across AI-HUB.

### 4. `caveman`
- **Role**: Token-Efficient Codebase Assistant
- **Capabilities**: Concise, high-density responses, code execution, quick bug resolution.

---

## 🏛️ Comprehensive Application Architecture

OmniFace AI follows Clean Architecture and Android Jetpack recommended app architecture principles across decoupled layers:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       PRESENTATION LAYER (Jetpack Compose)                  │
│   • Obsidian Slate & Liquid Glass Design System (CupertinoGlass.kt)         │
│   • Navigation: Single Activity + Tab Nav (Scanner, Students, Ledger, etc.)  │
│   • ViewModels: StateFlow reactive state, Unidirectional Data Flow (UDF)    │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                            DOMAIN & USE CASE LAYER                          │
│   • FaceSecurityPipeline: Multi-modal face analysis & liveness consensus    │
│   • FaceMatcher: Calibrated Cosine Similarity matching (Standard/High/Strict)│
│   • BiometricOperatingPoints: ISO/IEC 19795-1 & NIST FRVT threshold tables  │
│   • NpuHardwareDetector: Linux /proc/cpuinfo & SoC platform discovery       │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                             DATA & SECURITY LAYER                           │
│   • AndroidSecurityUtils: AndroidKeyStore AES-256-GCM authenticated cipher  │
│   • OmniFaceDatabase: Room SQLite (StudentEntity, AttendanceRecordEntity,   │
│     FaceTemplateEntity with encryptedEmbedding BLOB)                        │
│   • Offline-First in-memory cache pre-warming (zero plaintext vectors on disk)│
│   • CloudSyncWorker: WorkManager background REST / Aegis blockchain sync     │
└──────────────────────────────────────▲──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                           ML INFERENCE LAYER (LiteRT)                       │
│   • CameraX 1080p ImageAnalysis (Luma / YUV_420_888 stream)                 │
│   • Google ML Kit Face Detector: Bounding box, Euler angles, 5 landmarks     │
│   • Umeyama 5-Point Similarity Warp -> 112×112 RGB Normalized [-1.0, 1.0]   │
│   • UnifiedFaceModelEngine: Single-pass 7-head LiteRT multi-task runtime     │
│   • Dynamic FlatBuffer Output Resolver: Buffer binding by element shape/count│
└─────────────────────────────────────────────────────────────────────────────┘
```

### Data Flow Execution Lifecycle:
1. **Frame Capture**: CameraX streams live frames (`ImageProxy` / `Bitmap`) to `CameraAnalysisCoordinator`.
2. **Face Detection**: Google ML Kit detects face bounding box, Euler yaw/pitch/roll, and 5 canonical fiducials (left eye, right eye, nose base, left mouth corner, right mouth corner).
3. **Canonical Alignment**: 5-point affine similarity transform (Umeyama algorithm) crops and warps the face to standard `112 × 112` RGB in range `[-1.0, 1.0]`.
4. **Single-Pass Inference**: `UnifiedFaceModelEngine` executes `unified_face_v2_int8.tflite` or `unified_face_v2_fp16.tflite` across all 7 intelligence heads simultaneously in under 8 ms on NPU.
5. **Multi-Head Demuxing**: Dynamic FlatBuffer resolver sorts the 7 output tensors by shape/size (512, 3, 4, 1404, 265, 2, 5).
6. **Liveness Consensus**: Combines 2D texture anti-spoof logits with 3DMM depth variance (>0.0015) and Euler symmetry checks.
7. **Biometric Matching**: Computes cosine similarity against enrolled templates pre-warmed from hardware Keystore AES-GCM decrypted ciphertexts.
8. **Event Audit & Ledger**: Attendance record recorded in Room SQLite and queued for WorkManager background synchronization.

---

## 🧠 UnifiedFaceModel V2 Multi-Task ML Architecture

OmniFace AI runs a single unified neural graph, eliminating multi-model cascade overhead:

$$\boxed{\text{Camera Frame}} \xrightarrow{\text{ML Kit Detector}} \boxed{\text{Aligned Face } (112 \times 112)} \xrightarrow{\text{UnifiedFaceModel V2}} \boxed{\begin{array}{l} \text{Identity (512-D)} \\ \text{PAD (3-Class)} \\ \text{Quality (4-D)} \\ \text{Mesh (468}\times\text{3)} \\ \text{3DMM (265-D)} \\ \text{Gaze (2-D)} \\ \text{Attributes (5-D)} \end{array}}$$

### 1. Model Backbone
- **Architecture**: `MobileNetV4-Conv-Small` (Google Research, 3.48M parameters).
- **Input Dimensions**: `[1, 112, 112, 3]` (RGB normalized with `(x - 127.5) / 128.0` in `[-1.0, 1.0]`).
- **Feature Extraction**: Shared convolutional feature trunk with specialized projection bottleneck necks for each intelligence head.

### 2. Output Head Contracts (Single Forward Pass)

| # | Head Name | Output Shape | DataType | Representation / Semantics |
|:--|:----------|:-------------|:---------|:---------------------------|
| **0** | **Identity** | `[1, 512]` | `FLOAT32` | 512-D L2-normalized metric embedding (L2 norm = 1.0). |
| **1** | **PAD (Liveness)** | `[1, 3]` | `FLOAT32` | 3-Class presentation attack: `[0: Live / Bona Fide, 1: 2D Photo Print, 2: Screen Replay]`. |
| **2** | **Face Quality** | `[1, 4]` | `FLOAT32` | Multi-factor quality: `[0: Overall, 1: Sharpness, 2: Illumination, 3: Pose/Symmetry]`. |
| **3** | **Dense Mesh** | `[1, 1404]` | `FLOAT32` | 468 dense 3D facial landmarks (x, y, z). |
| **4** | **3DMM Geometry** | `[1, 265]` | `FLOAT32` | 3D Morphable Model shape & expression coefficients. |
| **5** | **Eye Gaze** | `[1, 2]` | `FLOAT32` | Eye gaze direction: `[Pitch, Yaw]` in degrees. |
| **6** | **Attributes** | `[1, 5]` | `FLOAT32` | Facial attributes: `[Smiling, Glasses, Mask, Beard, Hat]`. |

### 3. Active Neural Assets & Packaging
All production models reside in `app/src/main/assets/` and `training/unified/weights/`:

| Asset File | Target Backend | Precision | Size | Role |
|:---|:---|:---|:---|:---|
| `unified_face_v2_int8.tflite` | MediaTek APU 890 / Qualcomm Hexagon NPU | INT8 (Per-Channel) | 3.75 MB | Primary (NPU Accelerated) |
| `unified_face_v2_fp16.tflite` | Mobile GPU (OpenCL/Vulkan) / CPU XNNPACK | FP16 | 7.02 MB | Primary (GPU Accelerated) |
| `unified_face_v1_int8.tflite` | Qualcomm Hexagon NPU / NNAPI | INT8 | 3.75 MB | Frozen Reference Baseline |
| `unified_face_v1_fp16.tflite` | Qualcomm Adreno GPU / CPU XNNPACK | FP16 | 7.02 MB | Frozen Reference Baseline |
| `class_labels.json` | Identity Registry | JSON | 2.5 KB | 105 Master Class Labels |

---

## ⚡ LiteRT Hardware Acceleration & Dynamic Output Invariant

### 1. 3-Tier Hardware Delegate Hierarchy
1. **Tier 1 — NPU / NNAPI (`unified_face_v2_int8.tflite`)**: Sub-12ms on MediaTek APU 890; sub-4ms on Snapdragon Hexagon NPU.
2. **Tier 2 — Mobile GPU (`unified_face_v2_fp16.tflite`)**: 8.37ms (119.4 FPS) via `GpuDelegate` (OpenCL/Vulkan).
3. **Tier 3 — Multi-Threaded CPU (`unified_face_v2_int8.tflite` / `fp16.tflite`)**: 6.83ms (146.5 FPS) via Multi-Threaded XNNPACK (4 threads).

### 2. CRITICAL INVARIANT: Dynamic FlatBuffer Output Tensor Resolving
In TFLite / LiteRT FlatBuffers, output tensor ordering is **not deterministic** across compilation and quantization passes (e.g. `[geom=0, pad=1, gaze=2, mesh=3, id=4, quality=5, attr=6]`).
**NEVER hardcode output buffer indices (e.g., `outputMap[0]` as identity).**
Always resolve output buffers dynamically by element count or tensor shape:
- `512` elements -> **Identity Embedding**
- `3` elements -> **PAD Logits**
- `4` elements -> **Face Quality**
- `1404` elements -> **Dense 3D Mesh**
- `265` elements -> **3DMM Geometry**
- `2` elements -> **Eye Gaze**
- `5` elements -> **Attributes**

---

## 📊 Physical On-Device Hardware Benchmarks

Verified on physical device `10BG4903040030X` (**MediaTek Dimensity 9400 / MT6991**, **8th-Gen MediaTek APU 890 50.0 TOPS**, Android 16) via `OmniFaceV2DeviceBenchmarkTest` (100 continuous face inferences):

| Hardware Delegate & FlatBuffer | 100 Faces Time | Mean Latency | P50 (Median) | P95 | P99 | Throughput | Vector Check |
|:--|:--|:--|:--|:--|:--|:--|:--|
| **CPU XNNPACK 4-Thread (INT8)** | 682.77 ms | **6.83 ms** | 6.31 ms | 10.88 ms | 15.67 ms | **146.5 FPS** | 1.0000 Norm |
| **Mobile GPU Delegate (FP16)** | 837.67 ms | **8.37 ms** | 7.90 ms | 10.74 ms | 12.59 ms | **119.4 FPS** | 1.0000 Norm |
| **NNAPI / MediaTek APU 890 (INT8)** | 1168.61 ms | **11.68 ms** | 11.89 ms | 12.88 ms | 13.75 ms | **85.6 FPS** | 1.0000 Norm |
| **CPU XNNPACK 4-Thread (FP16)** | 1301.68 ms | **13.01 ms** | 12.98 ms | 13.36 ms | 13.70 ms | **76.8 FPS** | 1.0000 Norm |

Zero NaN/Inf detections, sub-10ms per-face inference across all 7 heads simultaneously.

---

## 🎯 Calibrated Biometric Operating Points (ISO/IEC 19795-1 / NIST FRVT)

OmniFace AI strictly segregates Similarity and Distance domains:

### 1. Cosine Similarity Domain (sim = cos theta, match if sim >= tau)
Used by the Android client runtime (`SecurityTier.kt`, `FaceMatcher.kt`):
- **STANDARD** (tau >= 0.650): Doorway kiosks, low-friction attendance (1:10 FAR).
- **HIGH** (tau >= 0.720): ISO/IEC 19795-1 standard operational point (1:100 FAR).
- **STRICT** (tau >= 0.800): High-security access, administrative overrides, banking (1:1,000 FAR).

### 2. Cosine Distance Domain (d = 1 - cos theta, match if d <= tau)
Used in evaluation protocols, LFW benchmarks, and scientific auditing:
- tau_EER = 0.0811 (d' = 2.457)
- tau_1%_FAR = 0.2299 (TAR = 84.67%)
- tau_0.1%_FAR = 0.4072 (TAR = 63.67%)

### 3. V1 Baseline vs V2 Production Winner vs CavaFace Teacher

| Benchmark Protocol | UnifiedFaceModel V1 | UnifiedFaceModel V2 (Winner) | CavaFace Teacher |
|:--|:--|:--|:--|
| **Tier B Open-Set TAR @ 1% FAR** | 3.60% | **11.44%** (3.18× Gain) | 67.83% |
| **Separation Index (d')** | 0.450 | **0.757** (+68.2% Separation) | 2.505 |
| **Tier C LFW 6,000-Pair Accuracy** | 53.20% | **62.17% ± 2.32%** | 89.82% ± 1.80% |
| **LFW Horizontal Flip TTA Accuracy** | 54.10% | **64.28% ± 1.79%** | 90.45% ± 1.40% |
| **PAD Real Attack Defense (NUAA)** | Uncertified | **0.00% ACER** (0/5,761 spoofs accepted) | N/A (Identity Only) |
| **3-Shot Centroid Top-1 Accuracy** | 42.00% | **82.00%** (Cohort N=150 Unseen IDs) | 94.20% |
| **3-Shot Centroid Margin (Delta)** | +0.0120 | **+0.1029** (+8.5× Wider Margin) | +0.2840 |

---

## 🛡️ Hardware Security & Cryptography Standards

1. **Hardware Keystore AES-256-GCM**:
   - Master Key stored in hardware `AndroidKeyStore` (`OMNIFACE_BIOMETRIC_KEY`).
   - Authenticated encryption with 12-byte random initialization vector (IV).
2. **Zero Plaintext Biometric Vectors on Disk**:
   - 512-D float vectors (2,048 bytes) are encrypted into byte arrays before persistence in Room SQLite (`FaceTemplateEntity.encryptedEmbedding`).
   - Vectors are decrypted strictly in memory upon app launch and cached in pre-warmed lookup tables (`cachedTemplates`, `cachedStudentMap`).
3. **Model Version Binding**:
   - Every template persists `modelVersion` (e.g. `unified_v2`).
   - Cross-version matching is rejected; templates must match the active engine version.

---

## 🚀 RTX 5060 GPU Training Pipeline & High-Throughput Architecture

### The Bottleneck Solved
Baseline training in `run_unified_training_v2.py` executed CavaFace CPU inference online during batch iteration, causing step latency of ~1.1s and DataLoader starvation.
The optimized training architecture eliminates this completely:

```
┌────────────────────────────────────────────────────────────────────────┐
│               OFFLINE TEACHER EMBEDDING CACHE PRECOMPUTATION           │
│   CASIA-WebFace (490k images) -> Batch GPU Inference (CavaFace / etc.)  │
│   -> L2-Normalized FP16 Shards (shard_XXXXX.bin) + manifest.json       │
│   Total Footprint: ~506 MB (FP16: 512 floats × 2 bytes × 494k images)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             HIGH-THROUGHPUT GPU TRAINING LOOP (Unified V2)             │
│   • Memory-Mapped Contiguous Shards (mmap lookup < 1 µs)                │
│   • Contiguous RAM Pre-Warming (490k embeddings fit in 506MB RAM)       │
│   • Pinned Memory & Non-Blocking Host-to-Device (H2D) Transfers        │
│   • PyTorch AMP (Automatic Mixed Precision: float16 / bfloat16)        │
│   • P × K Identity-Balanced Sampler (P=16, K=4 -> Batch=64, or B=128) │
│   • Zero CPU Teacher Inference during Forward/Backward Passes          │
└────────────────────────────────────────────────────────────────────────┘
```

### Invariants for GPU Pipeline Optimization
1. **Never Inflate Architecture**: Do not change MobileNetV4-Conv-Small (3.48M params) just to artificially raise `nvidia-smi` utilization. Optimize for `samples/sec` and low idle time.
2. **Never Overwrite Production Artifacts**: Keep `production_winner_unified_v2.pt`, `unified_face_v2_fp16.tflite`, and `unified_face_v2_int8.tflite` safely frozen.
3. **Numerical Verification**: Verify that offline cached teacher embeddings match online outputs within 1e-4 cosine tolerance.

---

## 📁 Workspace Directory Architecture

```
c:\AI-HUB\OmniFace-AI\
├── app/                                 # OmniFace AI Native Android Application
│   ├── build.gradle.kts                 # AGP 9.1.1, Kotlin 1.9.22, Compose BOM
│   └── src/
│       ├── androidTest/java/com/omniface/ai/
│       │   └── OmniFaceV2DeviceBenchmarkTest.kt # 100-Face Physical Device Benchmark
│       └── main/
│           ├── AndroidManifest.xml      # Camera, Biometrics, Keystore permissions
│           ├── assets/                  # Active Production FlatBuffers
│           │   ├── unified_face_v2_int8.tflite  # Primary NPU / NNAPI Model (3.75 MB)
│           │   ├── unified_face_v2_fp16.tflite  # Primary GPU / CPU Model (7.02 MB)
│           │   ├── unified_face_v1_int8.tflite  # Frozen Reference Baseline (3.75 MB)
│           │   ├── unified_face_v1_fp16.tflite  # Frozen Reference Baseline (7.02 MB)
│           │   └── class_labels.json            # Master Identity Registry
│           ├── java/com/omniface/ai/
│           │   ├── OmniFaceApplication.kt
│           │   ├── data/                # Room SQLite Database, Entities, DAOs
│           │   ├── ml/                  # UnifiedFaceModelEngine, LiteRT Delegates
│           │   ├── presentation/        # Jetpack Compose UI, ViewModels, Theme
│           │   └── security/            # AndroidSecurityUtils (Keystore AES-GCM)
│           └── res/                     # Layouts, Drawables, Mipmaps, Strings
├── archive/ml/                          # Archived Independent Legacy Models & Suites
│   ├── models/                          # Standalone MobileFaceNet, SilentFace, etc.
│   └── qualcomm_suite/                  # CavaFace (250MB), FaceMap 3DMM, EyeGaze, etc.
├── docs/                                # Complete Architecture Specs & Benchmarks
│   ├── ACTIVE_ML_ARCHITECTURE.md        # Active Unified Model Specification
│   ├── BLUEPRINT.md                     # Master Architecture & Design System
│   ├── SPECS.md                         # Technical Specifications & API Contracts
│   ├── DEVICE_BENCHMARK_REPORT.md       # MediaTek APU 890 Physical Benchmark
│   ├── MASTER_PRODUCTION_READINESS_SIGNOFF.md # Production Readiness Signoff
│   └── UNIFIED_MODEL_ACCEPTANCE_GATE.md # ISO/IEC Acceptance Gate Analysis
├── models/                              # Root FlatBuffers & Checkpoints
├── training/unified/                    # UnifiedFaceModel V2 Training Pipeline
│   ├── architectures/                   # MobileNetV4 7-Head PyTorch Definitions
│   ├── benchmarks/                      # GPU DataLoader & Throughput Benchmarks
│   ├── data_cache/                      # Offline Teacher Sharded Embeddings
│   ├── datasets/                        # CASIA-WebFace, LFW, NUAA Dataset Loaders
│   ├── distillation/                    # Multi-Teacher Loss Functions
│   ├── evaluation/                      # Multi-Tier ISO/IEC Evaluators & Gates
│   ├── profiling/                       # PyTorch Step Profiler & CUDA Timers
│   ├── weights/                         # V2 Model Checkpoints & Exported TFLite
│   └── run_unified_training_v2.py       # Master Training Pipeline
├── build_apk.ps1                        # Windows Host PowerShell Native Build Script
├── build_apk.sh                         # Linux ARM64 Native Gradle Build Runner
├── gradlew.bat                          # Windows Gradle Executable Wrapper
├── gradlew                              # Linux Gradle Executable Wrapper
└── settings.gradle.kts                  # Root Settings
```

---

## 💎 Apple iOS & macOS Liquid Glassmorphic UI/UX Standards

All UI components in OmniFace AI strictly adhere to modern liquid glassmorphic design tokens (`cupertino-liquid-glass-compose`):

1. **Signed Distance Field (SDF) & 7-Wavelength Chromatic Dispersion**:
   - Viewfinder overlays and modal sheets utilize Kyant SDF curvature (`sdRoundedRect`, `gradSdRoundedRect`) and AGSL 7-band spectral dispersion (Red -> Orange -> Yellow -> Green -> Cyan -> Blue -> Purple) with physical lens curvature mapping (`circleMap`).
2. **Directional Specular Reflection Borders (`omniLiquidSpecularBorder`)**:
   - Multi-stop linear gradient borders simulating top-left ambient light source (crisp white highlight at 0.0f transitioning to dark refraction shadows at 1.0f). Never use solid opaque borders.
3. **Layered Refraction Surface Diffusion (`omniLiquidSurfaceBrush`)**:
   - Multi-layer vertical translucent gradients (`#401E293B` to `#4D0B0F19` in dark mode) allowing background camera viewfinders to refract naturally.
4. **GPU Hardware Backdrop Blur & RuntimeShader Gating (`liquidGlassBackdrop`)**:
   - Android 12+ (API 31+): Hardware-accelerated Skia `RenderEffect.createBlurEffect(16.dp, 16.dp, Shader.TileMode.CLAMP)` chained with runtime shader refraction.
   - Legacy Android 8–11 (API 26–30): High-density translucent gradient layers fallback.
5. **120Hz LTPO Refresh Rate Pacing & Spring Physics**:
   - Windows lock 120Hz display modes (`preferredMinDisplayRefreshRate = 120.0f`).
   - Interactive components use tactile press scale animations (`1.0f` -> `0.97f`) with `Spring.DampingRatioMediumBouncy` and `Spring.StiffnessLow`.

---

## 🧩 Centralized iOS Design System & Semantic UX Standards

1. **Single Source of Truth (`CupertinoGlass.kt`)**:
   - `IOSCard` (`20dp` / `16dp` radius, `0.75dp` specular hairline, ambient shadow).
   - `CupertinoButton` (`50dp` height, `14dp` radius, spring press scaling `0.97f`).
   - `CupertinoSegmentedControl` (`12dp` rounded sliding pill selector).
   - `CupertinoMetricTile` (`16dp` KPI metric card).
   - `SectionHeader` (Uppercase `11sp` bold section header).
   - `SettingRow` (Grouped iOS list row with switch/chevron/badge).
   - `EmptyState` (Centered illustration, title, and message).
2. **Semantic Precision**:
   - Use clean user-facing domain terms (**Students**, **Scanner**, **Overview**, **Ledger**) rather than internal engineering labels.
   - Dynamic database counts observed reactively from Room SQLite flows (`getStudentCountFlow()`).

---

## 🧭 Hierarchical Jetpack Compose Back Gesture Standards

1. **Level 1 (Modals, Overlays, Sheets)**: `BackHandler` dismisses overlay and returns to parent.
2. **Level 2 (Sub-Screens)**: `BackHandler(enabled = currentSubScreen != null) { currentSubScreen = null }` returns to category menu.
3. **Level 3 (Top-Level Navigation Tabs)**: Non-start tabs pop smoothly back to `Screen.Dashboard`.
4. **Level 4 (Root Dashboard Double-Back Exit Protection)**: Start destination intercepts back presses with a 2-second debounce timer, triggering a Dynamic Island notification (*"Press back again to exit"*).

---

## 🧠 Genuine Silicon NPU & Hardware Detection Standards

1. **Direct On-Device Hardware Discovery (`NpuHardwareDetector`)**:
   - Inspects Linux `/proc/cpuinfo`, ARMv8/ARMv9 vector ISA extensions (`i8mm`, `asimddp`, `bf16`), and system properties (`ro.soc.model`, `ro.soc.manufacturer`, `ro.board.platform`, `ro.hardware`).
   - Silicon mapping:
     - **MediaTek Dimensity 9400 / MT6991** -> `MediaTek APU 890 (NeuroPilot Engine, 50.0 TOPS)`.
     - **Qualcomm Snapdragon 8 Elite / 8 Gen 3** -> `Qualcomm Hexagon NPU (HTP Tensor Accelerator, 45.0 TOPS)`.
     - **Google Tensor G4 / G3** -> `Google Tensor TPU (EdgeTPU Engine, 25-30 TOPS)`.
     - **Samsung Exynos 2400** -> `Samsung Exynos Dual-NPU (17K MACs)`.
2. **Transparent User Verification**:
   - Surfaced in Scanner status pill (`Hexagon NPU • INT8` or `APU 890 • INT8`), Overview Dashboard, and Kiosk Self-Test.

---

## 🪟 Cross-Platform Host Build Rules

1. **Windows Host**:
   - Run `.\build_apk.ps1 -BuildType debug|release` with native `gradlew.bat`.
   - Windows Android Studio execution invariant: invoke Gradle via bundled JetBrains Runtime:
     ```powershell
     cmd.exe /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& set PATH=%JAVA_HOME%\bin;%PATH%&& gradlew.bat <target>"
     ```
2. **Linux ARM64 Host**:
   - Execute `bash build_apk.sh` with `android.aapt2FromMavenOverride=/root/Android/Sdk/aapt2` in `gradle.properties`.

---

## 🚀 Session End Git Push Policy (Mandatory)

At the conclusion of every engineering task or conversational session involving code or documentation changes:
1. Stage all verified changes: `git add .`
2. Commit with a conventional semantic commit message: `git commit -m "<type>(<scope>): <description>"`
3. **Always Push to Remote**: Execute `git push origin main` before completing the turn so the GitHub remote (`origin/main`) remains 100% in sync with the local workspace.
