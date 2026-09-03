# 🤖 Antigravity Configured Agents, Rules & Standards (OmniFace AI / FR Workspace)

This directory (`/storage/emulated/0/AI-HUB/FR`) hosts the sovereign **OmniFace AI** Facial Recognition Platform, including the 10-phase model training pipeline, multi-tier TFLite Flatbuffers, and the production native Kotlin Android application.

---

## 🤖 Configured Subagents

### 1. `research`
- **Role**: Biometric Codebase & ML Researcher
- **Capabilities**: Read-only codebase exploration, web search, TFLite operator inspection, model quantization analysis, documentation audit.
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

## 📁 Workspace Directory Architecture

```
/storage/emulated/0/AI-HUB/FR/
├── docs/                                # Architecture blueprints, technical specs & reports
│   ├── BLUEPRINT.md                     # Master Architecture & Design System
│   ├── SPECS.md                         # Technical Specifications & API Contracts
│   ├── AGENTS.md                        # Workspace Rules & Agent Configurations
│   ├── RUN_SUMMARY.md                   # 25-Epoch Training & Benchmarks Summary
│   ├── FACE_RECOGNITION_ARCHITECTURE_BLUEPRINT.md
│   ├── verification_report.json         # ISO/IEC & NIST Operating Points JSON
│   └── biometric_training_dashboard.html# Standalone Interactive HTML Telemetry
├── models/                              # Trained Flatbuffers & Keras Master Checkpoints
│   ├── mobilefacenet_512d_int8.tflite   # 1.54 MB (NPU / NNAPI MLIR Per-Channel INT8)
│   ├── mobilefacenet_512d_fp16.tflite   # 2.47 MB (Mobile GPU Delegate FP16)
│   ├── mobilefacenet_512d_fp32.tflite   # 4.85 MB (CPU XNNPACK Threadpool FP32)
│   ├── mobilefacenet_512d_deployment_bundle.zip # 28.38 MB Master CRC32 Zip Bundle
│   ├── best_mobilefacenet_arcface.keras # 5.52 MB Standalone Embedding Extractor
│   ├── best_mobilefacenet_full_trainer.keras # 16.59 MB Full Sub-Center ArcFace Trainer
│   └── class_labels.json                # 105 Identity Class Mappings
├── training/                            # Training pipelines & execution logs
│   ├── train_mobilefacenet_arcface.py   # Single Master 10-Phase Training Script
│   ├── training_metrics.csv             # 25-Epoch Convergence Telemetry
│   ├── kernel-metadata.json             # Kaggle Tesla P100 Execution Config
│   ├── download_dataset.sh              # PINS 105-Class Dataset Downloader
│   ├── fetch_trained_models.sh          # Kaggle Bundle Fetcher & Integrity Gate
│   └── requirements.txt                 # Python Dependencies
├── app/                                 # OmniFace AI Native Android Application Module
│   ├── build.gradle.kts                 # Application Build Configuration
│   └── src/main/
│       ├── AndroidManifest.xml          # Permissions & Activities
│       ├── assets/                      # Embedded TFLite Flatbuffers
│       ├── java/com/omniface/ai/        # Clean Kotlin Architecture
│       │   ├── OmniFaceApplication.kt
│       │   ├── ml/                      # Multi-Tier TFLite Inference & Liveness
│       │   ├── security/                # AndroidKeyStore AES-256-GCM Cryptography
│       │   ├── data/                    # Room SQLite Database & DAOs
│       │   ├── presentation/            # Master Dashboard, Scanner, Enrollment Studio
│       │   └── sync/                    # WorkManager Background Cloud Sync Worker
│       └── res/                         # Obsidian Slate UI Layouts, Icons & Styles
├── gradle/                              # Gradle 8.6 Wrapper
├── build.gradle.kts                     # Root Project Gradle Build
├── settings.gradle.kts                  # Root Project Settings
├── gradle.properties                    # AndroidX, JVMArgs & Native AAPT2 Override
├── local.properties                     # Android SDK Path
├── gradlew                              # Gradle Executable Wrapper
├── build_apk.sh                         # Linux ARM64 Native Gradle Build Runner
└── OmniFace-AI.apk                      # Output Production APK Binary
```

---

## 💎 Apple iOS & macOS Liquid Glassmorphic UI/UX Standards (Skill: `cupertino-liquid-glass-compose`)

All UI components and screens in OmniFace AI must strictly adhere to the modern liquid glassmorphism design tokens inspired by [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass/tree/kmp/androidApp) and [`philipplackner/LiquidGlassKMP`](https://github.com/philipplackner/LiquidGlassKMP.git) as codified in the `cupertino-liquid-glass-compose` skill:

1. **Signed Distance Field (SDF) & 7-Wavelength Chromatic Dispersion**:
   - Every card, viewfinder overlay, and modal sheet utilizes Kyant SDF curvature (`sdRoundedRect`, `gradSdRoundedRect`) and AGSL 7-band spectral dispersion ($\text{Red} \to \text{Orange} \to \text{Yellow} \to \text{Green} \to \text{Cyan} \to \text{Blue} \to \text{Purple}$) with physical lens curvature mapping (`circleMap`).

2. **Directional Specular Reflection Borders (`omniLiquidSpecularBorder`)**:
   - Every card, dialog, button, and navigation dock must feature multi-stop linear gradient borders simulating a top-left ambient light source (crisp white specular highlight at 0.0f transitioning to dark refraction shadows at 1.0f).
   - Never use solid opaque borders.

3. **Layered Refraction Surface Diffusion (`omniLiquidSurfaceBrush`)**:
   - Backgrounds and containers must utilize multi-layer vertical translucent gradients (`#401E293B` to `#4D0B0F19` in dark mode, `#F0FFFFFF` to `#C8F1F5F9` in light mode) allowing background camera viewfinders and canvas animations to refract naturally.

4. **GPU Hardware Backdrop Blur & RuntimeShader Gating (`liquidGlassBackdrop`)**:
   - On Android 12+ (API 31+ / Android S) & Android 13+ (API 33+ / Tiramisu AGSL), enable hardware-accelerated Skia `RenderEffect.createBlurEffect(16.dp, 16.dp, Shader.TileMode.CLAMP)` chained with runtime shader refraction overlays (`RenderEffect.createChainEffect`).
   - On legacy Android 8–11 (API 26–30), smoothly fall back to high-density translucent gradient layers (`omniLiquidSurfaceBrush`) to guarantee zero crashes.

5. **True 120Hz LTPO Refresh Rate Pacing & Spring-Damped Tactile Physics**:
   - Windows must lock 120Hz display modes (`preferredDisplayModeId`, `preferredMinDisplayRefreshRate = 120.0f`, `preferredMaxDisplayRefreshRate = 120.0f`) and trigger SurfaceFlinger 120 FPS vsync pacing.
   - Interactive components (`FrostedGlassCard`, `CupertinoButton`, `CupertinoSegmentedControl`, `CupertinoTabBar`, `DynamicIslandCapsule`) must incorporate tactile press scale animations (`1.0f` -> `0.965f`/`0.98f`) with `Spring.DampingRatioMediumBouncy` and `Spring.StiffnessLow`.

---

## 📱 Linux ARM64 Native Android Build Rules

1. **Linux ARM64 Target by Default**:
   - Use standard Linux toolchain paths: `$HOME/Android/Sdk` or `/root/Android/Sdk` and `/usr/lib/jvm/java-17-openjdk-arm64`.
   - Never use Termux conventions unless explicitly requested.

2. **Storage Mount Execution Guardrail (`noexec`)**:
   - Files stored on `/storage/emulated/0` cannot be executed directly (`./gradlew` fails with `Permission denied`).
   - Always invoke shell scripts and wrappers explicitly: `bash ./gradlew <tasks>` or `bash build_apk.sh`.

3. **AAPT2 Native ARM64 Override**:
   - For AGP/Gradle builds on Linux `aarch64`, always configure native ARM64 `aapt2` in `gradle.properties`:
     `android.aapt2FromMavenOverride=/root/Android/Sdk/aapt2`

---

## 🪟 Windows Host & Android CLI Cross-Platform Build Rules

1. **Dual Host Support (Windows & Linux ARM64)**:
   - **Windows Host**: Use standard PowerShell scripts `setup_windows.ps1` and `build_apk.ps1` (`.\build_apk.ps1 -BuildType debug|release`) and native `gradlew.bat`.
   - **Linux ARM64 Host**: Use `setup_armdroid64.sh` and `bash build_apk.sh` with native ARM64 aapt2 overrides.

2. **Windows SDK & `local.properties` Invariants**:
   - On Windows, `local.properties` must point to the local SDK path using forward slashes or escaped backslashes (e.g. `sdk.dir=C\:/Users/ARAWIND07/AppData/Local/Android/Sdk`).
   - Do NOT include `android.aapt2FromMavenOverride` on Windows x86_64, as AAPT2 is resolved natively via Maven.

3. **Gradle Wrapper & AGP 9.1.1 Alignment**:
   - AGP 9.1.1 requires Gradle 9.3.1+. Use Gradle 9.5.0 wrapper distribution (`gradle-9.5.0-bin.zip`) for fast, pre-cached local builds with `networkTimeout=60000`.

4. **Android CLI (`android`) Integration**:
   - Installed at `C:\Users\ARAWIND07\AppData\AndroidCLI\android.exe`.
   - Use `android describe --project_dir="."` to verify project metadata and build targets.
   - Use `android layout --pretty` and `android screen capture --annotate` for zero-friction UI and layout verification during device/emulator testing.

---

## 🏗️ Zero-Stub Biometric Engineering & Verification Standards

1. **Zero Simulated/Mock Vectors in Production Pipelines**:
   - Face enrollment studios must ingest real CameraX video frames and extract genuine 512-D feature embeddings directly via TFLite/ArcFace engines.
   - Biometric matching against local SQLite/Room records must transparently decrypt hardware Keystore AES-256-GCM ciphertexts in memory before computing cosine distance.
   - Dashboard actions (CSV export, DPDP Act 2023 purge, manual overrides) must connect directly to active REST endpoints and Aegis SHA-256 blockchain minting.

2. **LiteRT Multi-Tier Hardware Delegate Pipeline**:
   - **Primary (NPU / NNAPI)**: `mobilefacenet_512d_int8.tflite` for sub-10ms neural execution.
   - **Fallback 1 (Mobile GPU)**: `mobilefacenet_512d_fp16.tflite` via `GpuDelegate`.
   - **Fallback 2 (Multi-Core CPU)**: `mobilefacenet_512d_fp32.tflite` via Multi-Threaded XNNPACK (4 threads).

3. **Multi-Decade Calibrated Decision Gates**:
   - **STANDARD** ($\tau = 0.120$, $1\text{ in }10\text{ FAR}$): Doorway kiosks.
   - **HIGH** ($\tau = 0.158$, $1\text{ in }100\text{ FAR}$): ISO/IEC standard operating point.
   - **STRICT** ($\tau = 0.220$, $1\text{ in }1,000\text{ FAR}$): High-security / banking access.

---

## ⚡ Non-Interactive Execution Rules
- Always append non-interactive flags (`--yes`, `--non-interactive`, `--no-daemon`) to CLI commands to prevent terminal hangs.

---

## 📐 3-Stage Engineering Pipeline ("Audit First, Design Second, Implement Third")

1. **Phase 1 — Complete Codebase Audit**:
   - Never invent architecture, files, APIs, features, or state that do not exist.
   - Always audit real entities, DAOs, ViewModel `StateFlow` models, ML inference pipelines, and security layers before refactoring or adding UI/UX features.
2. **Phase 2 — Centralized Design System Architecture**:
   - Design reusable components and tokens that consume real state.
   - Reusable components must reside in centralized component layers (`CupertinoGlass.kt`) rather than duplicated inside individual screens.
3. **Phase 3 — Implementation & Validation**:
   - Ensure all screens strictly inherit from the centralized design system.
   - Run native Linux ARM64 Gradle builds (`bash build_apk.sh`) to verify zero compilation errors and zero deprecation warnings.

---

## 🧩 Centralized iOS Design System & Semantic UX Standards

1. **Single Source of Truth (`CupertinoGlass.kt`)**:
   - Group all reusable building blocks into `CupertinoGlass.kt`:
     - `IOSCard` (`20dp` / `16dp` radius, `0.75dp` specular hairline, ambient shadow).
     - `CupertinoButton` (`50dp` height, `14dp` radius, spring press scaling `0.97f`).
     - `CupertinoSegmentedControl` (`12dp` rounded sliding pill selector).
     - `CupertinoMetricTile` (`16dp` KPI metric card).
     - `SectionHeader` (Uppercase `11sp` bold section header).
     - `SettingRow` (Grouped iOS list row with switch/chevron/badge).
     - `EmptyState` (Centered illustration, title, and message).
   - Never create ad-hoc cards, custom button heights, or scattered hardcoded colors/radii across individual screens.

2. **Semantic Precision (User-Facing vs Engineering Labels)**:
   - Use clean, user-facing domain terms (e.g. **Students**, **Scanner**, **Overview**, **Ledger**) rather than internal engineering labels (e.g. "Studio").
   - Hide raw mathematical/technical thresholds (e.g. $\tau = 0.158$) on primary user viewports; present semantic tiers (**Standard**, **High**, **Strict**).
   - Dynamic database counts must always be observed reactively from Room SQLite flows (`getStudentCountFlow()`) rather than hardcoded.

3. **Jetpack Compose BOM & Icon Compatibility**:
   - For Jetpack Compose BOM `2024.02.00` and Material Icons Extended, standard icons (such as `ShowChart` and `ReceiptLong`) belong to `Icons.Default.*` / `Icons.Filled.*`. Avoid unverified AutoMirrored icon variants.

---

## 🧭 Hierarchical Jetpack Compose Back Gesture & Navigation Standards

1. **4-Tier Back Gesture Hierarchy (`BackHandler`)**:
   - **Level 1 (Modals, Overlays, Bottom Sheets & Studios)**:
     - Component layers (`FaceRegistrationComponent`, `BiometricDeduplicationStudio`, `ModalBottomSheet`, `AlertDialog`) must register an explicit `BackHandler` that dismisses the overlay and returns to the parent viewport.
   - **Level 2 (Categorized Sub-Screens)**:
     - Sub-screens (such as `SettingsCategory` sub-pages) must register `BackHandler(enabled = currentSubScreen != null) { currentSubScreen = null }` to animate back to the category menu.
   - **Level 3 (Top-Level Navigation Tabs)**:
     - Non-start tabs (Scanner, Students, Ledger, Settings) pop smoothly back to the root `Screen.Dashboard`.
   - **Level 4 (Root Dashboard Double-Back Exit Protection)**:
     - The start destination must intercept back presses with a 2-second debounce timer, triggering a Dynamic Island notification (*"Press back again to exit"*) and Toast prompt before finishing the Activity.

---

## ⚡ Google LiteRT Runtime & Machine Learning Standards

1. **LiteRT Package Invariant**:
   - Always use official Google LiteRT packages (`com.google.ai.edge.litert:litert`, `com.google.ai.edge.litert:litert-gpu`, `com.google.ai.edge.litert:litert-support`) instead of legacy `org.tensorflow:tensorflow-lite:*` dependencies.
   - Prevents duplicate manifest namespace warnings in AGP 9.1+ and aligns with the latest Android 15/16 NNAPI/NPU runtime.

---

## 🎯 Single Source of Truth Entry Points & Feature Gating

1. **Single Entry Point Invariant**:
   - Ensure each primary destination (e.g. Settings, Scanner, Ledger) has exactly one authoritative entry point in the navigation bar. Do not duplicate floating gear buttons or top-bar shortcuts that create fragmented state or redundant modal sheets.
2. **Semantic "Coming Soon" Badging**:
   - Hardware-dependent stubs or future cloud services (e.g. BLE Fleet Mesh, remote Cloudflare/S3 sync) must be badged with localized `IOSGlassPill` ("Coming Soon") tokens across all 10 supported Indian languages rather than fake active toggles.

---

## 🧠 Genuine Silicon NPU & Hardware Detection Standards

1. **Direct On-Device Hardware Discovery (`NpuHardwareDetector`)**:
   - Inspects Linux `/proc/cpuinfo`, ARMv8/ARMv9 vector ISA extensions (`i8mm`, `asimddp`, `bf16`), and system properties (`ro.soc.model`, `ro.soc.manufacturer`, `ro.board.platform`, `ro.hardware`) to identify the exact physical NPU co-processor.
   - Maps silicon models to their true neural accelerator hardware:
     - **Qualcomm Snapdragon** (SM8650 / SM8550 / SM8450) $\to$ `Qualcomm Hexagon NPU (HTP Tensor Accelerator, 45.0 TOPS)`.
     - **Google Tensor** (G4 / G3 / G2 / G1) $\to$ `Google Tensor TPU (EdgeTPU Engine, 25-30 TOPS)`.
     - **MediaTek Dimensity** (9300 / 9200 / 8200) $\to$ `MediaTek APU 790 / 690 (NeuroPilot Engine, 30-46 TOPS)`.
     - **Samsung Exynos** (2400 / 2200) $\to$ `Samsung Exynos Dual-NPU (17K MACs)`.
     - **ARM NEON / Matrix** $\to$ `ARMv8/v9 Neural Matrix Engine (DotProd/I8MM)`.

2. **Transparent User Verification**:
   - The verified NPU name and peak TOPS rating are surfaced directly in the Scanner status pill (`Hexagon NPU • INT8`), the Overview Dashboard, the Kiosk Self-Test diagnostic suite, and the progressive disclosure panel in Settings.
   - Prevents fake/simulated claims by attaching genuine Linux kernel and hardware platform signatures.

---

## 🤖 Qualcomm AI Hub Face Intelligence Suite Registry

All models downloaded from `qaihub-public-assets.s3.us-west-2.amazonaws.com` at release `v0.60.0`.
Engine: [`QualcommFaceIntelligenceEngine.kt`](app/src/main/java/com/omniface/ai/ml/QualcommFaceIntelligenceEngine.kt)
Target hardware: Snapdragon® 8 Elite, 8 Gen 3, 8 Gen 2, 8 Gen 1, 888.

| # | Model | `model_id` | Input Shape | `value_range` | Output Shape | Size |
|:--|:------|:-----------|:------------|:--------------|:-------------|:-----|
| 1 | **CavaFace** | `cavaface` | `[1,112,112,3]` RGB | `[0.0, 1.0]` | `[1,512]` L2 embedding | 250 MB |
| 2 | **FaceMap 3DMM** | `facemap_3dmm` | `[1,128,128,3]` RGB | `[0.0, 1.0]` | `[1,265]` 3D shape params | 21 MB |
| 3 | **FaceAttribNet** | `face_attrib_net` | `[1,128,128,3]` RGB | `[0.0, 1.0]` | `[1,5]` attribute probs | 42 MB |
| 4 | **EyeGaze** | `eyegaze` | `[1,96,160]` grayscale | `[0.0, 1.0]` | `[1,2]` pitch/yaw + `[1,34,2]` eye landmarks | 9.7 MB |
| 5 | **HRNetFace** | `hrnet_face` | `[1,256,256,3]` RGB | `[0.0, 1.0]` | `[1,29,64,64]` heatmaps | 37 MB |
| 6 | **MediaPipe Face Mesh** | `mediapipe_face` | Detector: `[1,256,256,3]` RGB; Mesh: `[1,192,192,3]` RGB | `[0.0, 1.0]` | `[1]` face score + `[1,468,3]` XYZ landmarks | 2.9 MB |

### Model Paths on Device
```
/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/
├── cavaface/cavaface-tflite-float/cavaface.tflite
├── facemap_3dmm/facemap_3dmm-tflite-float/facemap_3dmm.tflite
├── face_attrib_net/face_attrib_net-tflite-float/face_attrib_net.tflite
├── eyegaze/eyegaze-tflite-float/eyegaze.tflite
├── hrnet_face/hrnet_face-tflite-float/hrnet_face.tflite
└── mediapipe_face/mediapipe_face-tflite-float/
    ├── face_detector.tflite         (0.57 MB)
    └── face_landmark_detector.tflite (2.4 MB)
```

### Integration Workflow
When adding a new Qualcomm AI Hub model, use the `qualcomm-aihub-model-integration` skill.
The S3 URL pattern is:
```
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/<model_id>/releases/v0.60.0/<model_id>-tflite-float.zip
```

---

## 🎨 Google Labs Stitch Skills Registry (`google-labs-code/stitch-skills`)

The workspace is equipped with the complete Google Labs Stitch AI UI/UX Toolchain:

### 1. `stitch-design` Suite
- `stitch-generate-design`: Generate multi-viewport UI designs and high-fidelity screens from natural language prompts.
- `stitch-code-to-design`: Ingest HTML, Tailwind, or React components and generate design systems and Stitch screens.
- `stitch-extract-design-md`: Extract unified design tokens, typography, and color schemes into `DESIGN.md`.
- `stitch-extract-static-html`: Extract and package interactive HTML/CSS prototypes from Stitch screens.
- `stitch-manage-design-system`: Manage, update, and apply custom design systems (`Aetheric Biometrics`).
- `stitch-upload-to-stitch`: Upload design blueprints and screenshots to Stitch canvas.

### 2. `stitch-utilities` Suite
- `stitch-design-md`: Parse, validate, and convert design system markdown tokens.
- `stitch-enhance-prompt`: Enhance UI prompts with design keywords, component patterns, and visual themes.
- `stitch-site-md`: Multi-page site graph and screen relationship specification.
- `stitch-loop`: Iterative design refinement loop for high-fidelity interactive prototyping.
- `stitch-taste-design`: Apply professional design taste principles and aesthetic guidelines.

### 3. `stitch-build` Suite
- `stitch-react-components`: Convert Stitch screens to modular React/Tailwind components.
- `stitch-react-native`: Convert Stitch designs to mobile React Native / Expo components.
- `stitch-react-vite-dashboard`: Scaffold full-stack Vite dashboards from Stitch designs.
- `stitch-shadcn-ui`: Generate Shadcn/UI component trees from Stitch screens.


