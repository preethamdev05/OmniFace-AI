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
- **Integration Workflow**:
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

## 🏗️ Zero-Stub Biometric Engineering & Verification Standards

1. **Zero Simulated/Mock Vectors in Production Pipelines**:
   - Face enrollment studios must ingest real CameraX video frames and extract genuine 512-D feature embeddings directly via TFLite/ArcFace engines.
   - Biometric matching against local SQLite/Room records must transparently decrypt hardware Keystore AES-256-GCM ciphertexts in memory before computing cosine distance.
   - Dashboard actions (CSV export, DPDP Act 2023 purge, manual overrides) must connect directly to active REST endpoints and Aegis SHA-256 blockchain minting.

2. **TFLite Multi-Tier Hardware Delegate Pipeline**:
   - **Primary (NPU / NNAPI)**: `mobilefacenet_512d_int8.tflite` for sub-10ms neural execution.
   - **Fallback 1 (Mobile GPU)**: `mobilefacenet_512d_fp16.tflite` via `GpuDelegate`.
   - **Fallback 2 (Multi-Core CPU)**: `mobilefacenet_512d_fp32.tflite` via Multi-Threaded XNNPACK (4 threads).

3. **Multi-Decade Calibrated Decision Gates**:
   - **STANDARD** ($\tau = 0.240$, $1\text{ in }10\text{ FAR}$): Doorway kiosks.
   - **HIGH** ($\tau = 0.416$, $1\text{ in }100\text{ FAR}$): ISO/IEC standard operating point.
   - **STRICT** ($\tau = 0.500$, $1\text{ in }1,000\text{ FAR}$): High-security / banking access.

---

## ⚡ Non-Interactive Execution Rules
- Always append non-interactive flags (`--yes`, `--non-interactive`, `--no-daemon`) to CLI commands to prevent terminal hangs.
