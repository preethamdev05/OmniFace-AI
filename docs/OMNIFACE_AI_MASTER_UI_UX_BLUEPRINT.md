# 🏛️ OmniFace AI — Master UI/UX Redesign & Qualcomm Visual Intelligence Platform Specification

---

## 1. Complete UX Audit

### 1.1 Cognitive Load & Mental Model Analysis
Traditional biometric attendance applications present an opaque "black-box" paradigm: a camera viewfinder, a momentary spinner, and a binary "Verified" or "Failed" prompt. This interaction pattern causes user distrust because when a failure or misrecognition occurs (such as sibling false accepts or poor illumination rejection), the user has zero visibility into why the model reached that determination.

### 1.2 Information Architecture Assessment
Prior navigation centered narrowly around a basic two-tab "Attendance/Scanner" and "Studio/Enroll" mental model. Crucial enterprise capabilities—such as real-time silicon NPU telemetry, 3D face mesh validation, multi-angle template consistency matrices, and biometric dataset evaluation—were either hidden in obscure debug menus or absent from user viewports.

### 1.3 Progressive Disclosure Evaluation
The system previously lacked distinct user viewports, exposing raw mathematical thresholds (e.g., $\tau = 0.158$) to end users while hiding actionable geometric diagnostics (e.g., "Face tilted 14° upwards; pitch down 4° to align").

---

## 2. Current UX Problems & Root-Cause Matrix

| ID | Domain | Existing Problem | Root Cause | Redesign Solution |
|:---|:---|:---|:---|:---|
| **UX-01** | **Feedback** | Black-box "Scan Failed" message without reason | Monolithic error handler discarding granular rejection codes | 17-code machine-readable rejection engine (`E01`–`E17`) with localized spoken TTS guidance |
| **UX-02** | **Registration** | Single-angle capture susceptible to pose variance | Lack of structured multi-angle guidance sequence | 5-phase guided 3D trajectory (0°, ±22.5° Yaw, ±16° Pitch) with quality-weighted centroid fusion |
| **UX-03** | **Explainability** | Users cannot see what the neural network detects | Viewfinder rendered only standard 2D rectangles | Visible AI HUD rendering 3D spatial pose axes, EyeGaze ray, and 3DMM depth topography |
| **UX-04** | **Hardware Status** | Users unaware if silicon NPU acceleration is active | Lack of device capability introspection | Dedicated Qualcomm Intelligence Dashboard showing Hexagon HTP TOPS & delegate status |
| **UX-05** | **Navigation** | Overcrowded screen logic with mixed responsibilities | Flat navigation structure | 8-destination modular Information Architecture with Adaptive Rail on tablets |

---

## 3. Information Architecture Redesign

The redesigned Information Architecture structures OmniFace AI into 8 distinct functional domains:

```
                               ┌─────────────────────────┐
                               │   OmniFace AI Master    │
                               └────────────┬────────────┘
                                            │
       ┌───────────┬───────────┬────────────┼────────────┬───────────┬───────────┬───────────┐
       ▼           ▼           ▼            ▼            ▼           ▼           ▼           ▼
   ┌───────┐   ┌────────┐  ┌────────┐  ┌─────────┐  ┌───────────┐┌────────┐ ┌────────┐ ┌───────────┐
   │ HOME  │   │REGISTER│  │RECOGNIZE│ │ANALYTICS│  │IDENTITIES ││ MODELS │ │SETTINGS│ │DIAGNOSTICS│
   └───────┘   └────────┘  └────────┘  └─────────┘  └───────────┘└────────┘ └────────┘ └───────────┘
```

1. **HOME (Mission Control)**: Real-time attendance KPIs, active silicon NPU state, hardware health, and recent event feeds.
2. **REGISTER (Enrollment Studio)**: Guided 5-phase 3D biometric enrollment with multi-factor quality scoring.
3. **RECOGNIZE (Vision Scanner)**: 60 FPS live recognition engine with explainable multi-stage decision gates.
4. **ANALYTICS (Biometric Intelligence)**: Attendance trends, ROC/DET curves, TAR@FAR operating points, and failure distributions.
5. **IDENTITIES (Directory Ledger)**: Enrolled student database, template health inspection, cryptographic status, and DPDP Act compliance.
6. **MODELS (Neural Hub)**: Explorer for Qualcomm AI Hub models (CavaFace, FaceMap 3DMM, FaceAttribNet, EyeGaze, HRNetFace, MediaPipe).
7. **SETTINGS (System Config)**: Security tier presets (Standard/High/Strict), cloud sync, camera selector, and theme tokens.
8. **DIAGNOSTICS (Silicon Lab)**: Developer-only real-time frame timeline, thermal tracking, memory footprint, and NPU jitter telemetry.

---

## 4. Navigation Redesign

### 4.1 Mobile Form Factor (Handheld)
A floating **Cupertino Glass Dock** docked at the bottom of the viewport featuring liquid refraction, directional specular reflection, and spring-damped tactile icons:

```
┌────────────────────────────────────────────────────────┐
│                      [SCREEN CONTENT]                  │
│                                                        │
│                                                        │
├────────────────────────────────────────────────────────┤
│  ╭──────────────────────────────────────────────────╮  │
│  │  [Home]   [Register]  [Scan]  [Models] [Settings]│  │
│  ╰──────────────────────────────────────────────────╯  │
└────────────────────────────────────────────────────────┘
```

### 4.2 Tablet, Foldable & Desktop Form Factor (Adaptive Rail)
When `WindowWidthSizeClass == Expanded`, the navigation dynamically migrates to a persistent lateral **Navigation Rail** with expandable labels and system health indicators.

---

## 5. Screen-by-Screen Wireframes

### 5.1 Home Screen (Mission Control)
```
┌────────────────────────────────────────────────────────┐
│ ≡ OmniFace AI                     [●] Hexagon NPU 45T  │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │  TODAY'S ATTENDANCE             RECOGNITION RATE   │ │
│ │  1,428 / 1,500 (95.2%)          99.4% (ISO/IEC)    │ │
│ └────────────────────────────────────────────────────┘ │
│ ┌──────────────────────┐  ┌──────────────────────────┐ │
│ │ ACTIVE BACKEND       │  │ AVG RECOGNITION LATENCY  │ │
│ │ Qualcomm Hexagon INT8│  │ 5.4 ms (Zero Jitter)     │ │
│ └──────────────────────┘  └──────────────────────────┘ │
│ RECENT BIOMETRIC VERIFICATIONS                         │
│ ┌────────────────────────────────────────────────────┐ │
│ │ [✓] 09:42:15 • Preetham N (Roll #101) • 96.4% Sim │ │
│ │ [✓] 09:41:50 • Ananya Rao (Roll #102) • 94.8% Sim │ │
│ │ [✓] 09:40:12 • Vikram Seth (Roll #103)• 97.1% Sim │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 5.2 Register Screen (Scientific Biometric Studio)
```
┌────────────────────────────────────────────────────────┐
│ ← Biometric Enrollment Studio               Step 3/5   │
├────────────────────────────────────────────────────────┤
│                    ╭──────────────╮                    │
│                    │  \   ▲    /  │                    │
│                    │   \  │   /   │                    │
│                    │    (•) (•)   │ ➔ 3D Pose Target   │
│                    │      ▼       │   Yaw: +22.5°      │
│                    ╰──────────────╯                    │
│           "Turn head slightly to the Right (→)"        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ BIOMETRIC QUALITY MATRIX                           │ │
│ │ Sharpness: 242.0 (✓)    Lighting: 148 Luma (✓)     │ │
│ │ 3D Depth: 0.042 (✓)     Attentiveness: 98% (✓)     │ │
│ │ OVERALL QUALITY SCORE: 96 / 100 [EXCELLENT]        │ │
│ └────────────────────────────────────────────────────┘ │
│ [ 0° Front ] [ 22° Left ] [ 22° Right ] [ Up ] [ Down ]│
└────────────────────────────────────────────────────────┘
```

### 5.3 Recognize Screen (Visible AI Vision Scanner)
```
┌────────────────────────────────────────────────────────┐
│ [●] Snapdragon 8 Elite • HTP INT8 • 60 FPS • 5.4ms     │
├────────────────────────────────────────────────────────┤
│                    ┌──────────────┐                    │
│                    │ PREETHAM N   │ ➔ Matched 96.2%    │
│                    │ (•)---►(•)   │ ➔ Gaze Ray Active  │
│                    │   [ +XYZ ]   │ ➔ 3D Spatial Axis  │
│                    └──────────────┘                    │
│ ┌────────────────────────────────────────────────────┐ │
│ │ DECISION GATE EXPLAINABILITY                       │ │
│ │ Match Score: 96.2%    Top-2 Margin: +0.24 (Strong) │ │
│ │ Liveness: 3D Depth Topography Verified             │ │
│ │ Attendance Marked: CS-VI • 09:45:12 AM             │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

---

## 6. Complete Design System Tokens

### 6.1 Color Palette (Obsidian Liquid Glass)
- `OmniBackground`: `#0B0F19` (Pure Deep Obsidian)
- `OmniSurfaceDark`: `#1E293B` (Refraction Glass Base)
- `OmniAccentEmerald`: `#10B981` (Verification Active)
- `OmniAccentCyan`: `#06B6D4` (Neural Processing)
- `OmniAccentAmber`: `#F59E0B` (Warning / Repositioning)
- `OmniAccentRose`: `#EF4444` (Rejection / Security Alert)
- `OmniSpecularHighlight`: `#FFFFFF` with `0.15` alpha gradient

### 6.2 Typography Tokens (Apple SF Pro & JetBrains Mono)
- `DisplayLarge`: `32sp` / LineHeight `40sp` / SemiBold
- `HeadlineMedium`: `20sp` / LineHeight `26sp` / Medium
- `BodyLarge`: `15sp` / LineHeight `22sp` / Regular
- `CaptionTechnical`: `11sp` / LineHeight `14sp` / Bold (Monospace for Latency/TOPS/Sim)

### 6.3 Spacing & Shape Tokens
- Base Grid: `4dp` (`4dp`, `8dp`, `12dp`, `16dp`, `24dp`, `32dp`)
- Radius Tokens: `Small = 10dp`, `Medium = 16dp`, `Large = 24dp`, `Pill = 999dp`
- Hairline Borders: `0.75dp` Linear Specular Gradient

---

## 7. Component Library Specification

1. **`IOSCard`**: Translucent liquid container with hardware blur (`RenderEffect.createBlurEffect(16.dp, 16.dp)` on Android 12+) and top-left specular hairline highlight.
2. **`CupertinoButton`**: Spring press animation (`1.0f` $\to$ `0.97f`), haptic feedback on touch down, and high-contrast accessibility labeling.
3. **`CupertinoSegmentedControl`**: Sliding pill selector with spring damping (`DampingRatioMediumBouncy`) for mode switching.
4. **`DynamicIslandCapsule`**: Morphing HUD pill at the top of the viewport signaling system hardware events (NPU tier switch, camera change, match confirmation).
5. **`BiometricQualityRadar`**: Real-time multi-dimensional radar chart rendering Sharpness, Pose, Lighting, Eye Gaze, and 3DMM depth.

---

## 8. Guided Biometric Registration Workflow

```
[Start Studio]
      │
      ▼
[Phase 1: Frontal 0°]  ──(Quality Evaluator)──► [Pass: Capture S1]
      │                                                │
      ▼                                                ▼
[Phase 2: Left 22.5°]  ──(Pose Envelope)──────► [Pass: Capture S2]
      │                                                │
      ▼                                                ▼
[Phase 3: Right 22.5°] ──(Pose Envelope)──────► [Pass: Capture S3]
      │                                                │
      ▼                                                ▼
[Phase 4: Pitch Up]    ──(Pose Envelope)──────► [Pass: Capture S4]
      │                                                │
      ▼                                                ▼
[Phase 5: Pitch Down]  ──(Pose Envelope)──────► [Pass: Capture S5]
      │
      ▼
[Pairwise Consistency Validation: M(i,j) ≥ 0.78]
      │
      ▼
[Compute Quality-Weighted Master Centroid Template C]
      │
      ▼
[StrongBox AES-256-GCM Encryption] ──► [Room SQLite Commit]
```

---

## 9. Explainable Recognition Pipeline

Every inference cycle exposes a 6-phase pipeline visible to users in real time:

1. **Frame Ingestion**: Zero-copy YUV analyzer buffer (60 FPS).
2. **Face Detection & Tracking**: ML Kit / Qualcomm MediaPipe detector with EMA bounding box stabilization ($\alpha = 0.65$).
3. **Liveness & 3DMM Analysis**: FaceMap 3DMM depth variance check ($\sigma > 0.005$) + EyeGaze attentiveness check ($\ge 85\%$).
4. **Canonical Alignment**: Umeyama 5-point similarity transformation to $112 \times 112$ canonical space.
5. **Silicon NPU Inference**: Hexagon INT8 inference generating 512-D L2-normalized vector ($<6\text{ ms}$).
6. **Margin-Verified Cosine Matching**: Two-pass average student scoring + Top-1 vs Top-2 separation margin verification ($\Delta \ge 0.08$).

---

## 10. Analytics Experience

- **Attendance Trends**: Daily, weekly, and monthly attendance velocity heatmaps.
- **Biometric Calibration Curves**: On-device ROC and DET curves generated by [`DatasetEvaluator.kt`](file:///storage/emulated/0/AI-HUB/FR/app/src/main/java/com/omniface/ai/ml/DatasetEvaluator.kt).
- **Failure Taxonomy**: Breakdown of rejections by reason code (`E01`–`E17`) to identify environmental issues (e.g., poor lighting at entrance door).

---

## 11. Qualcomm Intelligence Experience

### 11.1 Silicon Introspection
The app queries `/proc/cpuinfo` and `ro.soc.model` via [`NpuHardwareDetector.kt`](file:///storage/emulated/0/AI-HUB/FR/app/src/main/java/com/omniface/ai/hardware/NpuHardwareDetector.kt) to detect:
- **Processor**: Snapdragon 8 Elite, 8 Gen 3, 8 Gen 2, 8 Gen 1, 888.
- **Neural Engine**: Qualcomm Hexagon NPU (HTP Tensor Accelerator, up to 45.0 TOPS).
- **Execution Delegates**: NNAPI INT8 (Primary), GPU Delegate FP16 (Fallback 1), XNNPACK FP32 (Fallback 2).

### 11.2 Qualcomm AI Hub Suite Registry
- **CavaFace**: $112 \times 112 \times 3 \to 512\text{-D}$ L2 Embedding ($250\text{ MB}$).
- **FaceMap 3DMM**: $128 \times 128 \times 3 \to 265\text{-D}$ 3D Shape Params ($21\text{ MB}$).
- **FaceAttribNet**: $128 \times 128 \times 3 \to 5$ Attribute Probabilities ($42\text{ MB}$).
- **EyeGaze**: $96 \times 160 \times 1 \to \text{Gaze Vector} + 34\text{ Landmarks}$ ($9.7\text{ MB}$).
- **HRNetFace**: $256 \times 256 \times 3 \to 29 \times 64 \times 64\text{ Heatmaps}$ ($37\text{ MB}$).
- **MediaPipe Face**: Detector ($0.57\text{ MB}$) + Mesh ($2.4\text{ MB}$).

---

## 12. Diagnostics Dashboard (Developer Mode)

- **Real-Time Frame Graph**: Latency breakdown across Camera Ingest, Preprocessing, NPU Inference, and Vector Matching.
- **Thermal & Battery Profiler**: Live battery temperature and CPU throttling indicators.
- **Memory & GC Monitor**: Heap allocation and garbage collection event counters.

---

## 13. Face Mesh Visualization

- **Simple Mode**: Minimal facial contour wireframe with eye and mouth boundary highlights.
- **Advanced Mode**: 3D geometric depth wireframe with color-coded elevation contours.
- **Developer Mode**: 468 dense 3D XYZ vertices with vertex index labels and normal vectors.

---

## 14. Eye Tracking Visualization

- **Gaze Ray Vector**: 3D vector projected from the ocular center showing the user's line of sight.
- **Attentiveness Gauge**: Percentage score indicating alignment with the camera optical axis ($\cos(\psi) \cdot \cos(\theta)$).
- **Blink & Openness Detector**: Independent left and right eye openness telemetry ($0\dots 100\%$).

---

## 15. Head Pose Visualization

- **3D Spatial Axes**: Orthogonal coordinate axes rendered on the facial center ($X=\text{Red / Yaw}$, $Y=\text{Green / Pitch}$, $Z=\text{Cyan / Optical Normal}$).
- **Pose Tolerance Reticle**: Target alignment circle turning from Amber to Emerald when head orientation enters the required capture envelope.

---

## 16. Recognition Confidence Visualization

- **Similarity Meter**: Radial progress bar displaying true cosine similarity ($0.000\dots 1.000$).
- **Top-1 vs Top-2 Margin**: Visual indicator showing separation margin $\Delta$ against the next most similar identity in the database.
- **Template Health**: Number of active multi-angle templates contributing to the match.

---

## 17. Multi-Face Tracking

- **Concurrent Processing**: Tracks up to 3 faces simultaneously in the viewfinder.
- **Independent State Buffers**: Each face maintains its own EMA bounding box, tracking ID, liveness status, and recognition pill.
- **Anti-Collision**: Prevents overlapping bounding boxes and prevents cross-identity false matches.

---

## 18. Motion Design System

- **Spring Dynamics**: All Compose animations use `spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)`.
- **Tactile Touch Feedback**: Press scaling to `0.97f` with instant `ViewConfiguration.KEYPRESS_VIBRATION_EFFECT` haptic feedback.
- **State Morphing**: Smooth `AnimatedContent` crossfades between camera viewfinder, diagnostic HUD, and confirmation banners.

---

## 19. Accessibility System

- **Dynamic Type**: Fully scalable text across all SP typography tokens.
- **TalkBack Integration**: Descriptive accessibility labels (`contentDescription`) for all biometric state changes and rejection reasons.
- **Color-Blind Friendly**: Dual encoding using both color and distinct geometric iconography (Checkmark for success, Cross for failure, Triangle for warnings).
- **High-Contrast Surfaces**: Contrast ratios exceeding $7:1$ across all text elements against translucent backdrops.

---

## 20. Jetpack Compose Architecture

- **Unidirectional Data Flow**: Strict `UiState`, `UiEvent`, `UiEffect` separation.
- **Recomposition Stability**: All state classes marked with `@Immutable` or `@Stable`.
- **Zero Allocations in Render Loop**: `Canvas` drawing functions reuse static `Paint`, `Path`, and `Matrix` instances.
- **Hardware Layer Isolation**: Camera viewfinder isolated on separate graphics layer to prevent invalidating the diagnostic overlay during recomposition.

---

## 21. Refactoring Strategy

- **Phase 1**: Establish centralized design system tokens in `ui/theme/` and components in `ui/components/CupertinoGlass.kt`.
- **Phase 2**: Implement core CV/ML modules (`UmeyamaSimilarityTransform`, `RegistrationQualityEvaluator`, `FaceAnalysisFusionEngine`, `FaceDiagnosticsOverlay`).
- **Phase 3**: Migrate ViewModels to immutable state flows and wire reactive database flows.
- **Phase 4**: Build native Linux ARM64 production APK and verify zero errors with `bash build_apk.sh`.

---

## 22. Package Structure

```
com.omniface.ai/
├── OmniFaceApplication.kt
├── data/
│   ├── local/
│   │   ├── dao/          (StudentDao, AttendanceDao, FaceTemplateDao)
│   │   ├── db/           (OmniFaceDatabase, RoomMigrations)
│   │   └── entity/       (StudentEntity, AttendanceEntity, FaceTemplateEntity)
│   └── repository/       (BiometricRepository, AttendanceRepository)
├── hardware/             (NpuHardwareDetector, SiliconPlatform)
├── ml/                   (FaceRecognitionEngine, QualcommFaceIntelligenceEngine,
│                          UmeyamaSimilarityTransform, RegistrationQualityEvaluator,
│                          FaceAnalysisFusionEngine, DatasetEvaluator, LivenessDetector)
├── security/             (AndroidSecurityUtils, KeyStoreCrypto, TamperDetector)
└── ui/
    ├── components/       (CupertinoGlass, DynamicIsland, FaceDiagnosticsOverlay)
    ├── dashboard/        (DashboardScreen, DashboardViewModel)
    ├── enrollment/       (EnrollmentScreen, EnrollmentViewModel)
    ├── models/           (ModelExplorerScreen, ModelExplorerViewModel)
    ├── navigation/       (OmniNavigation, NavRoutes)
    ├── scanner/          (ScannerScreen, ScannerViewModel)
    ├── settings/         (SettingsScreen, SettingsViewModel)
    └── theme/            (Color, Theme, Type)
```

---

## 23. UI State Architecture

```kotlin
@Immutable
data class ScannerUiState(
    val isScanning: Boolean = true,
    val securityTier: SecurityTier = SecurityTier.HIGH,
    val activeHardwareTier: HardwareTier = HardwareTier.NNAPI_NPU_INT8,
    val verifiedCountToday: Int = 0,
    val isQualcommDevice: Boolean = false,
    val diagnosticMode: Boolean = false,
    val activeFaces: List<FaceAnalysisResult> = emptyList(),
    val matchConfirmation: MatchConfirmation? = null,
    val isTampered: Boolean = false
)

sealed interface ScannerUiEvent {
    data class OnFaceDetected(val faces: List<Face>, val bitmap: Bitmap) : ScannerUiEvent
    data class OnTierSelected(val tier: SecurityTier) : ScannerUiEvent
    object ToggleDiagnosticMode : ScannerUiEvent
    object ToggleCamera : ScannerUiEvent
}

sealed interface ScannerUiEffect {
    data class ShowToast(val message: String) : ScannerUiEffect
    data class PlaySound(val soundType: SoundType) : ScannerUiEffect
    object TriggerHaptic : ScannerUiEffect
}
```

---

## 24. Performance Optimization Plan

1. **Zero-Allocation Camera Ingest**: ByteBuffers allocated once in companion object and reused across frames via `.rewind()`.
2. **SIMD Vectorization**: 8-way unrolled FloatArray dot product loop for sub-microsecond cosine similarity searches.
3. **In-Memory Biometric Cache**: Decrypted templates held in secure volatile memory (`FloatArray` format) to prevent SQLite disk queries during real-time 60 FPS video loops.
4. **NPU Priority Scheduling**: Qualcomm Hexagon INT8 model given Tier #1 priority, achieving steady $5.4\text{ ms}$ inference time.

---

## 25. Production-Ready UI Blueprint Summary

The OmniFace AI platform combines on-device neural acceleration, Apple-inspired liquid glassmorphism, and explainable biometric AI. The system provides total transparency into every computer vision decision while maintaining enterprise-grade security and sub-10ms recognition performance.
