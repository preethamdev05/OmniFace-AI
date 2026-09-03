# Project: OmniFace AI

## Architecture
- **Layered Clean Architecture**:
  - `com.omniface.ai.data.local`: Room SQLite Database v6 (`AppDatabase.kt`), relational entities (`StudentEntity`, `FaceTemplateEntity`, `AttendanceRecordEntity`), and DAOs (`StudentDao`, `AttendanceDao`).
  - `com.omniface.ai.security`: Hardware KeyStore AES-256-GCM encryption (`AndroidSecurityUtils.kt`), Aegis SHA-256 blockchain hash chaining, Merkle tree root minting, Pedersen ZKP commitments (`ZkpPrivacyManager.kt`).
  - `com.omniface.ai.i18n`: 10-Language Dynamic Localization Manager (`LocalizationManager.kt`) supporting English, Hindi, Kannada, Tamil, Telugu, Malayalam, Bengali, Marathi, Gujarati, and Punjabi.
  - `com.omniface.ai.audio`: Acoustic Environment Soundboard & Android TTS Multilingual Engine (`BiometricSoundboard.kt`).
  - `com.omniface.ai.ml`: Multi-Tier TFLite Execution Ladder (NPU INT8 $\to$ GPU FP16 $\to$ CPU FP32 $\to$ Gradient Fallback), Qualcomm AI Hub 5-model suite (`QualcommFaceIntelligenceEngine.kt`), Umeyama 5-point alignment, Quality-Weighted Centroid calculation (`RegistrationQualityEvaluator.kt`), 3-Gate Security Pipeline (`BiometricDecisionEngine.kt`), MiniFASNet & Multi-Stage Passive PAD (`PassivePadEngine.kt`, `TemporalLivenessEngine.kt`), Deduplication Engine (`BiometricDeduplicationEngine.kt`).
  - `com.omniface.ai.hardware`: Direct `/proc/cpuinfo` ARM ISA Silicon Detection (`NpuHardwareDetector.kt`), Thermal Governor (`ThermalGovernor.kt`), HMAC-SHA256 Turnstile Pulse Relay (`TurnstileRelayController.kt`).
  - `com.omniface.ai.ui`: Jetpack Compose Liquid Glassmorphism UI tokens (`CupertinoGlass.kt`), 120Hz LTPO display pacing, 60/120 FPS Canvas visualizer (`FaceDiagnosticsOverlay.kt`), Overview Dashboard (`Dashboard.kt`), Scanner (`Scanner.kt`), Biometric Enrollment Studio (`Enrollment.kt`), Deduplication Studio (`BiometricDeduplicationStudio.kt`), Attendance Ledger (`Ledger.kt`), Modular Settings (`Settings.kt`, `AppearanceSettings.kt`, `BiometricSettings.kt`, `NeuralEngineSettings.kt`, `QualcommSuiteSettings.kt`, `KioskAccessSettings.kt`, `DataGovernanceSettings.kt`).
  - `com.omniface.ai.mesh`: BLE Fleet Mesh State Manager (`BleMeshSyncManager.kt`), Fleet Topology Manager (`FleetTopologyManager.kt`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Overview Dashboard | Reactive Room SQLite StateFlow observation, dynamic NPU TOPS/INT8 telemetry pill, Bézier velocity chart, semantic tier cards, 100% localization | M3 | ORIGINAL_REQUEST §R1.1 |
| 2 | Scanner & Viewfinder | CameraX front preview mirroring, 60/120 FPS Kalman tracking, 3-gate passive PAD consensus, HMAC turnstile pulse relay | M2, M3 | ORIGINAL_REQUEST §R1.2 |
| 3 | Student Directory & Profile Inspector | Reactive filtering by name/roll/dept, profile inspector modal with template status & direct enrollment trigger | M3 | ORIGINAL_REQUEST §R1.3 |
| 4 | Biometric Enrollment Studio | 5-angle multi-shot burst studio, Umeyama alignment, L2 normalized quality-weighted centroid synthesis, single-shot fallback & OCR auto-fill | M2 | ORIGINAL_REQUEST §R1.4 |
| 5 | Biometric Deduplication Studio | Pairwise O(N^2) cosine matrix scan on decrypted vectors, duplicate threshold gating, 1-tap merge/unlink/dismiss | M2 | ORIGINAL_REQUEST §R1.5 |
| 6 | Attendance Ledger & Proofs | Aegis SHA-256 blockchain hash chaining, Merkle tree root verification, DPDP Act 2023 purge, tamper-evident CSV export | M4 | ORIGINAL_REQUEST §R1.6 |
| 7 | Modular Settings & UI Cleanup | Categorized sub-screen navigation, removal of raw tau formulas (0.120, 0.158, 0.220), semantic tier enforcement, tokenless R2 edge safety | M3 | ORIGINAL_REQUEST §R1.7, §R3 |
| 8 | Audio & TTS Soundboard | 3 acoustic environments (Noisy Hallway, Quiet Classroom, Silent Vibration), 10-language TTS voice feedback, cold startup sync | M1 | ORIGINAL_REQUEST §R1.8 |
| 9 | Cryptographic Storage & TEE | Hardware KeyStore AES-256-GCM encryption for stored embeddings, zero plaintext at rest, encrypted database backup with WAL checkpointing | M1 | ORIGINAL_REQUEST §R1.9 |
| 10 | BLE Fleet Mesh & Kiosk Controller | P2P zero-trust sync graph state, PIN-protected admin lockout, emergency turnstile evacuation override | M4 | ORIGINAL_REQUEST §R1.10 |
| 11 | App-Wide 10-Language Localization | 10 Indian languages dictionary completion in LocalizationManager, full UI wiring, persistent preference & startup sync | M1, M3 | ORIGINAL_REQUEST §R2 |
| 12 | Automated Testing & Build Integrity | 0 errors and 0 warnings on Kotlin/Java compilation, 100% pass rate on test suites, final APK packaging at OmniFace-AI.apk | M5, E2E | ORIGINAL_REQUEST §R4 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Data, Storage, Cryptography & Localization Parity | `LocalizationManager.kt` dictionary parity (10 languages), `OmniFaceApplication.kt` startup TTS sync, `AndroidSecurityUtils.kt` encrypted backup with WAL checkpointing | none | COMPLETED |
| M2 | Biometric ML, Deduplication & Quality Centroid Pipeline | `RegistrationQualityEvaluator.kt` 5-angle centroid calculation, `BiometricDeduplicationEngine.kt` & `BiometricDeduplicationStudio.kt` deduplication matrix and layout optimization, `FaceRecognitionEngine.kt` delegate pipeline | M1 | COMPLETED |
| M3 | Presentation UI/UX Polish, Liquid Glassmorphism & Security Surface Cleanup | `BiometricSettings.kt` raw tau formula sanitization, Settings sub-screens full localization wiring, `Dashboard.kt`, `Scanner.kt`, `StudentDirectory.kt`, `CupertinoGlass.kt` button width constraints | M1, M2 | COMPLETED |
| M4 | Blockchain Ledger, Mesh Controller & Governance | `Ledger.kt` Aegis blockchain proofs, DPDP purge, tamper-evident CSV export, `BleMeshSyncManager.kt`, `FleetTopologyManager.kt`, `TurnstileRelayController.kt` | M1 | COMPLETED |
| M5 | Final Integration, E2E Test Pass (Tiers 1-4), Adversarial Hardening (Tier 5) & APK Packaging | Full integration verification, 100% E2E test pass, Tier 5 adversarial gap coverage, zero compiler warnings, APK output at `OmniFace-AI.apk` | M1, M2, M3, M4, E2E | COMPLETED |


## Interface Contracts
### Data & Localization ↔ Presentation Layer
- `LocalizationManager.get(key: StringKey): String`: Reactive composable string resolver observing `_currentLanguage`.
- `LocalizationManager.setLanguage(lang: AppLanguage)`: Persists language in SharedPreferences and updates `_currentLanguage`.
- `BiometricSoundboard.setLanguage(lang: AppLanguage)`: Sets TTS language and selects corresponding locale tag.
- `StudentDao.getAllStudentsFlow()` / `getStudentCountFlow()`: Emits reactive Room SQLite streams.
- `AttendanceDao.getTodayAttendanceCountFlow(todayDate)`: Emits real-time check-in count.

### Biometric Engine ↔ Security & Storage
- `AndroidSecurityUtils.encryptBiometricEmbedding(floatArray: FloatArray): String`: Encrypts 512-D float array using Hardware KeyStore AES-256-GCM. Returns Base64 ciphertext with 12-byte IV + 16-byte GCM tag.
- `AndroidSecurityUtils.decryptBiometricEmbedding(ciphertext: String): FloatArray`: Decrypts Base64 ciphertext in memory and returns 512-D float array.
- `BiometricDeduplicationEngine.scanForDuplicates(threshold: Float)`: Decrypts all local templates and computes $O(N^2)$ pairwise cosine similarity matrix.

### Blockchain Ledger ↔ Verification
- `AndroidSecurityUtils.computeAegisBlockHash(prevHash, studentRoll, timestamp, confidencePct)`: Returns SHA-256 hash string.
- `AndroidSecurityUtils.verifyAegisLedgerIntegrity(records)`: Traverses records and verifies linear hash continuity.
- `AndroidSecurityUtils.computeAegisMerkleRoot(hashes)`: Returns 32-byte Merkle tree root hash.

## Code Layout
- `app/src/main/java/com/omniface/ai/data/local/`: Room SQLite Database, Entities, DAOs.
- `app/src/main/java/com/omniface/ai/security/`: KeyStore AES-256-GCM, Aegis Blockchain, ZKP commitments.
- `app/src/main/java/com/omniface/ai/i18n/`: `LocalizationManager.kt` (10-Language dictionaries).
- `app/src/main/java/com/omniface/ai/audio/`: `BiometricSoundboard.kt` (TTS & acoustic environments).
- `app/src/main/java/com/omniface/ai/ml/`: Face recognition, deduplication, 3-gate pipeline, anti-spoofing.
- `app/src/main/java/com/omniface/ai/hardware/`: NPU hardware detection, thermal governor, turnstile controller.
- `app/src/main/java/com/omniface/ai/ui/`: Jetpack Compose screens, design tokens, navigation.
- `app/src/main/java/com/omniface/ai/mesh/`: BLE mesh synchronization.
- `app/src/test/java/com/omniface/ai/`: Unit and integration test suites.
