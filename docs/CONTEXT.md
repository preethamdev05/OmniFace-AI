# OmniFace AI — Domain Context & Architectural Glossary (`CONTEXT.md`)

This document defines the authoritative ubiquitous language, architectural seams, and domain contracts for **OmniFace AI**.

---

## 🏛️ Core Domain Model

### 1. Identity & Enrollment
- **IdentityTemplate**: The pure domain representation of an enrolled biometric subject.
  ```kotlin
  data class IdentityTemplate(
      val identityId: String,
      val displayName: String,
      val role: String,
      val embedding: FloatArray,
      val version: Long
  )
  ```
  Deterministic and free from persistence annotations. `version` is sourced directly from entity storage timestamps or revision numbers.
- **IdentityStore**: The pure persistence seam providing enrolled identities.
  ```kotlin
  interface IdentityStore {
      fun observeTemplates(): Flow<List<IdentityTemplate>>
  }
  ```
- **RoomIdentityStoreAdapter**: The production adapter that observes Room SQLite flows, decrypts hardware AES-256-GCM ciphertexts in memory, and translates `FaceTemplateEntity` into clean domain `IdentityTemplate` records.
- **FakeIdentityStore**: The test adapter providing in-memory static or mutable template lists for headless unit tests.

### 2. Visual Frame Input & Geometry
- **BiometricFrame**: An abstracted visual input frame wrapping CameraX `ImageProxy`, raw `Bitmap`, or test byte buffer with rotation/orientation metadata. Decouples the verification engine from AndroidX CameraX.
- **DomainFaceGeometry**: Pure domain representation of detected face topography:
  ```kotlin
  data class DomainFaceGeometry(
      val left: Float,
      val top: Float,
      val right: Float,
      val bottom: Float,
      val rollAngle: Float = 0f,
      val pitchAngle: Float = 0f,
      val yawAngle: Float = 0f
  )
  ```

### 3. Biometric Verification Engine
- **BiometricVerificationEngine**: The deep module orchestrating face tracking, thermal scaling, multi-modal anti-spoofing (MiniFASNetV2 passive PAD + FaceMap 3DMM depth variance + Eye Gaze + Blink EAR), and FAISS-equivalent cosine matching.
  ```kotlin
  interface BiometricVerificationEngine : AutoCloseable {
      suspend fun verifyFrame(
          frame: BiometricFrame,
          securityTier: SecurityTier
      ): VerificationDecision

      val telemetry: StateFlow<HardwareTelemetry>
      val transientEvents: SharedFlow<BiometricTransientEvent>
  }
  ```
- **VerificationDecision**: The compile-time exhaustive sealed domain decision hierarchy returned per frame:
  - `Idle`: No face detected in frame.
  - `Verified(identityId, displayName, role, confidence, liveness, leafHash)`: Genuine identity matched with confidence and margin above the calibrated security tier.
  - `Duplicate(identityId, remainingCooldownMs)`: Genuine identity recognized, but already marked present in the active cooldown window.
  - `Unknown(confidence)`: Genuine face detected, but no matching identity exists in the database.
  - `SpoofRejected(reason: SpoofReason)`: Presentation attack detected.
  - `PoorQuality(reason: QualityReason)`: Bounding box, illumination, or head pose ($> \pm 15^\circ$) exceeds biometric quality envelopes.

### 4. Hardware Telemetry & Execution Governance
- **HardwareTelemetry**: Strongly typed silicon telemetry:
  ```kotlin
  data class HardwareTelemetry(
      val backend: HardwareBackend,
      val thermalState: ThermalState,
      val latencyMs: Long,
      val activeModelName: String
  )
  ```
- **HardwareBackend**: `NPU_NNAPI`, `GPU_DELEGATE`, `CPU_XNNPACK`.

### 5. Durable Attendance & Transactional Outbox Pattern
- **AttendanceService**: The authoritative domain application service driving attendance persistence.
  - Upon receiving verified consensus, writes atomically within a single Room transaction:
    1. `AttendanceRecordEntity` (marked `CONFIRMED`)
    2. `AegisOutboxEntity` (marked `PENDING_MINT`)
  - **Durability Invariant**: Attendance persistence is completely isolated from in-memory event buses. Process death or garbage collection cannot cause attendance loss once consensus is reached.
- **AegisMintingWorker**: Asynchronous background worker polling `AegisOutboxEntity` rows:
  - Computes the Aegis SHA-256 blockchain hash: $H_i = \text{SHA-256}(H_{i-1} \parallel \text{identityId} \parallel \text{timestamp} \parallel \text{confidence})$.
  - Updates outbox status to `MINTED`.
  - **Aegis Non-Blocking Invariant**: Blockchain minting failures or delays will *never* invalidate or block attendance check-in.
- **BiometricTransientEvent**: Non-durable in-memory event bus for transient subscribers (Audio cues, UI toasts, Haptic feedback, Analytics).

---

## 📐 Architectural Seams & Invariants

```text
Camera
  ↓
BiometricVerificationEngine
  │
  ├── IdentityStore
  ├── ExecutionGovernor
  ├── Face Detection
  ├── Liveness
  ├── Vector Matching
  └── Temporal Consensus
  ↓
VerificationDecision
  ↓
Application Attendance Service
  ↓
Room Transaction
  ├── attendance_record
  └── aegis_outbox(pending)
  ↓
Background Workers
  ├── Aegis Mint
  └── Cloud Sync
```

### Architectural Invariants
1. **The Deletion Test**: Deleting intermediate coordination code must concentrate complexity inside the deep module, not disperse it across callers.
2. **Interface as Test Surface**: Tests exercise the full verification lifecycle through `verifyFrame()` without mocking internal Kalman trackers or intermediate sub-models.
3. **Pure Seams**: `IdentityStore` does not leak Room entities; `BiometricVerificationEngine` does not depend on CameraX or Room.
4. **Behavioral Equivalence**: Any refactored engine must produce identical decisions, identities, confidence bands, and spoof rejections as the legacy engine on identical frames.
5. **Durable Attendance**: Attendance persistence is guaranteed via transactional SQLite write before any transient events or asynchronous minting occurs.
