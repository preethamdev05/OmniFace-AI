# 🚀 OmniFace AI — Final Android + ML Execution Plan (Frozen Baseline)
**Document ID:** `OMNIFACE-EXEC-PLAN-2026-FINAL`  
**Status:** FROZEN & ACTIVE FOR EXECUTION  
**Application:** OmniFace Native Kotlin Android Application (`com.omniface.ai`)  
**Baseline Git Commit:** `e449f19` (Clean worktree, 516/516 unit tests passing)  
**Date:** September 13, 2026  

---

## Product Goal & North Star
OmniFace operates as a reliable, hands-free edge attendance system:

```text
Camera
  ↓
Multi-face tracking
  ↓
Capture quality
  ↓
Multi-frame evidence
  ↓
Biometric verification
  ↓
Automation policy
  ↓
Auto-confirmation
  ↓
Atomic attendance
  ↓
Notification outbox
  ↓
Continue scanning
```

The operator primarily **holds/positions the phone**. Normal attendance requires zero screen tapping.

---

## The Three Critical Architectural Anchors

1. **NO Dynamic TFLite Batching**:
   Deployed LiteRT models (`mobilefacenet_512d_int8.tflite`, Qualcomm CavaFace) have a fixed static batch dimension of `1`. Dynamic runtime resizing to `[B, 112, 112, 3]` throws `UnsupportedOperationException` on Hexagon NPU / NNAPI delegates or causes 300ms–850ms shader recompilation lag on GPU delegates.
   **Enforced Solution:** Bounded sequential execution with a coroutine semaphore (1–3 worker slots) utilizing pre-allocated inference buffers.
2. **Multi-Subject Group Verification Contract**:
   Eliminate the scalar `topDecision` bottleneck. When multiple verified subjects enter the frame simultaneously, the pipeline returns `List<BiometricSynthesisDecision>` and commits all verified students atomically in SQLite via `AttendanceService.recordVerifiedBatch`.
3. **Consolidation of the "Ghost Seam"**:
   Merge `FaceSecurityPipeline.kt` into `BiometricVerificationEngineImpl.kt` after extracting Compose UI geometry projections into presentation adapters.

---

## The 18-Phase Execution Sequence

### PHASE 00 — Baseline and Permanent Benchmark
- **Objective:** Freeze baseline state; establish automated benchmark harness recording APK size, build status, unit tests, inference latency, memory, thermal behavior, FAR, FRR, TAR, group throughput, and frame drops.
- **Exit Condition:** Reproducible baseline exists and can be rerun after every phase.

### PHASE 01 — Low-Risk Modularization
- **Objective:** Reduce code complexity without altering runtime behavior.
- **Actions:**
  - Consolidate Person/Student DAO duplication.
  - Split `LocalizationManager` into focused string resolvers.
  - Relocate `FakeIdentityStore` to test source set.
  - Extract `ScannerDialogs` and UI-only components out of `Scanner.kt`.
  - **Constraint:** Do NOT refactor `Scanner.kt` and ML engine simultaneously.
- **Exit Condition:** 516/516 tests pass; behavior 100% unchanged.

### PHASE 02 — Attendance and Data Boundary Cleanup
- **Objective:** Establish `AttendanceService` as the single authoritative attendance transaction boundary.
- **Actions:**
  - Remove direct DAO queries (`studentDao`, `aegisLedgerDao`) from `Scanner.kt`.
  - Implement `recordVerifiedBatch(decisions: List<BiometricSynthesisDecision>)`.
  - Enforce deterministic composite idempotency key: `SHA256(tenantId:studentId:sessionDate:slotHash)`.
- **Exit Condition:** 1 person -> 1 record; 3 people -> 3 records; repeated detections -> zero duplicates.

### PHASE 03 — Empirical Threshold Calibration
- **Objective:** Evaluate deployed model on real distributions before any retraining.
- **Actions:**
  - Run empirical threshold sweep ($\tau = 0.05 \dots 0.95$) across 1,000 genuine & 10,000 impostor pairs.
  - Generate ROC, DET, FAR, FRR, and TAR curves.
  - Calibrate operating points:
    - **STANDARD:** $\tau = 0.120$ (FAR 1.0%, TAR 96.4%)
    - **HIGH:** $\tau = 0.158$ (FAR 0.1%, TAR 91.2%)
    - **STRICT:** $\tau = 0.220$ (FAR 0.01%, TAR 82.5%)
- **Exit Condition:** Documented operating threshold for each security tier supported by empirical ROC evidence.

### PHASE 04 — Rebuild the Training Pipeline
- **Objective:** Replace closed-set PINS 105 dataset with open-set identity-disjoint dataset at $\ge 10,000$ identities.
- **Actions:**
  - Enforce strict identity partitions: $\text{Train IDs} \cap \text{Val IDs} \cap \text{Test IDs} = \emptyset$.
  - Ingest deployment-relevant variation: cameras, distances, poses, lighting, motion, occlusion, compression.
- **Exit Condition:** Open-set training dataset configured with automated download verification.

### PHASE 05 — Checkpointing, Augmentation, and Model Deployment
- **Objective:** Fix Epoch-9 premature freezing bug; add realistic mobile degradations.
- **Actions:**
  - Checkpoint selection monitors open-set TAR @ FAR $= 0.1\%$ on unseen identities.
  - Add realistic smartphone augmentations: motion blur, downsampling, JPEG compression, sensor noise.
  - Export and validate per-channel INT8, FP16, and FP32 Flatbuffers.
- **Exit Condition:** Retrained model achieves $> 95\%$ TAR @ FAR $= 0.1\%$ with verified numerical parity.

### PHASE 05.1 — Biometric Model-Version Migration
- **Objective:** Prevent silent invalidation of existing enrolled biometric templates.
- **Actions:**
  - Add `model_version: String` to `students` database schema.
  - Implement dual-version compatibility during migration window: existing templates verified by old model; new enrollments by new model.
- **Exit Condition:** Zero existing enrolled users invalidated during model rollout.

### PHASE 06 — Multi-Frame Recognition
- **Objective:** Wire feature-level temporal pooling into the active verification path.
- **Actions:**
  - Connect `FaceTracker.getFusedTrackEmbedding()` into `FaceMatcher`.
  - Add track-swap guard: purge temporal history if $\Delta\text{sim} = \cos(e_t, e_{t-1}) < 0.60$.
  - Evaluate best-frame vs mean embedding vs quality-weighted embedding.
- **Exit Condition:** Recognition on walking subjects improves by $\ge 15\%$ over single-frame baseline.

### PHASE 07 — Group Recognition (Bounded Concurrency)
- **Objective:** Autonomous verification of 1–6+ simultaneous people without dynamic batching.
- **Actions:**
  - Implement `BoundedGroupInferenceScheduler` with 1–3 coroutine semaphore permits.
  - Return `List<BiometricSynthesisDecision>` from pipeline.
  - Atomic batch attendance ingestion via `AttendanceService.recordVerifiedBatch`.
  - 9 failure protections: identical twins, occlusion, track swaps, motion blur, liveness, unknown faces.
- **Exit Condition:** 3 simultaneous valid students yield 3 attendance records with zero dropped faces.

### PHASE 08 — Automatic Kiosk Operation + Adaptive Quality
- **Objective:** Combine multi-subject recognition with hands-free kiosk automation.
- **Actions:**
  - Add $3 \times 3$ Gaussian pre-filter before Laplacian variance to eliminate high-ISO sensor noise bypass.
  - Implement 3-tier adaptive quality:
    - BAD ($Q < 35$): Keep observing; do not infer.
    - BORDERLINE ($35 \le Q < 65$): Accumulate $K=3$ temporal frames.
    - GOOD ($Q \ge 65$): Single-frame verification permitted.
- **Exit Condition:** Zero false rejections from low-light sensor noise; hands-free kiosk automation.

### PHASE 09 — Hardware and Thermal Optimization
- **Objective:** Consolidate runtime governors without duplicating existing hardware frameworks.
- **Actions:**
  - Utilize existing `NpuHardwareDetector` and `ThermalGovernor`.
  - Scale concurrency ceiling and frame sampling dynamically based on thermal callbacks.
  - Pre-compile GPU OpenCL shaders during app splash screen.
- **Exit Condition:** 2-hour continuous scanning maintains battery temp $< 43^\circ\text{C}$.

### PHASE 10 — Vector Search Simplification
- **Objective:** Benchmark gallery sizes (250, 500, 1k, 2k, 5k, 10k) and select optimal search backend.
- **Actions:**
  - Retain Exact Linear Scan (ARMv8 NEON SIMD) as default for $N \le 2,000$.
  - Introduce common `VectorIndex` interface; activate HNSW only when $N > 2,000$.
- **Exit Condition:** Exact linear scan confirmed sub-0.35ms for all standard institutional tiers.

### PHASE 11 — Consolidate the Biometric Pipeline
- **Objective:** Eliminate the "Ghost Seam" duplicate implementation.
- **Actions:**
  - Extract Compose UI visual geometry rendering into `ScannerOverlayRenderer.kt`.
  - Merge `FaceSecurityPipeline.kt` into `BiometricVerificationEngineImpl.kt`.
  - Enforce behavioral-equivalence tests before deprecating old pipeline.
- **Exit Condition:** One authoritative biometric engine implementation; zero duplicated liveness/matcher instances.

### PHASE 12 — Scanner Modularization
- **Objective:** Refactor the 3,225-line `Scanner.kt` monolith into clean, single-responsibility modules.
- **Modules:**
  - `ScannerScreen.kt` (Compose UI root)
  - `ScannerViewModel.kt` (UI state & intent handling)
  - `CameraSourceManager.kt` (CameraX lifecycle & frame analysis)
  - `ScannerOverlayRenderer.kt` (Liquid Glass bounding boxes & pills)
  - `ScannerDialogs.kt` (Settings, PIN, manual search modals)
- **Exit Condition:** `Scanner.kt` LOC $\le 1,200$; zero DAO calls in UI layer.

### PHASE 13 — Reliability and Offline Operation
- **Objective:** Guarantee kiosk survivability under extreme operational conditions.
- **Guarantees:**
  - Attendance write never depends on network connectivity.
  - Notification dispatch is completely asynchronous via `event_outbox` and `WorkManager`.
  - Notification failure never rolls back verified attendance.
- **Exit Condition:** Zero lost records in 200-event offline stress test.

### PHASE 14 — Real-Device Validation
- **Objective:** Physical verification across Budget, Mid-Range, and Flagship device matrix.
- **Metrics Measured:** TAR, FAR, FRR, per-face latency, effective FPS, frame drops, RAM, battery temp.
- **Exit Condition:** All physical devices meet their respective latency and thermal targets.

### PHASE 15 — Real-World Pilot
- **Objective:** Classroom-scale operational validation (100 students in 120 seconds).
- **Metrics Measured:** Throughput, automatic success rate, zero false attendance, operator satisfaction.
- **Exit Condition:** Commercial pilot certification.

---

## Non-Negotiable Engineering Prohibitions

```text
DO NOT:
- Dynamically resize TFLite batch dimensions (`[B, 112, 112, 3]`).
- Create duplicate tracking or quality abstractions (`CaptureQuality`, `MultiFaceTracker`).
- Arbitrarily lower thresholds without empirical ROC curves.
- Perform network calls (WhatsApp, Cloudflare) on the camera analyzer thread.
- Bypass AttendanceService from UI composables.
- Retrain on the closed-set PINS 105 dataset.
- Invalidate existing encrypted templates without a dual-version migration path.
- Force HNSW or FAISS graph vector indexes for galleries under 2,000 identities.
- Refactor Scanner.kt and the ML inference engine simultaneously.
```

---
*OmniFace AI — Architecture & Machine Learning Engineering Team*
