# E2E Test Infra: OmniFace AI

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on internal implementation design.
- Methodology: Category-Partition + Boundary Value Analysis (BVA) + Pairwise Combinatorial Testing + Real-World Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---------|---------------------|:------:|:------:|:------:|
| 1 | Overview Dashboard | ORIGINAL_REQUEST §R1.1 | 5 | 5 | ✓ |
| 2 | Scanner & Viewfinder | ORIGINAL_REQUEST §R1.2 | 5 | 5 | ✓ |
| 3 | Student Directory & Profile Inspector | ORIGINAL_REQUEST §R1.3 | 5 | 5 | ✓ |
| 4 | Biometric Enrollment Studio | ORIGINAL_REQUEST §R1.4 | 5 | 5 | ✓ |
| 5 | Biometric Deduplication Studio | ORIGINAL_REQUEST §R1.5 | 5 | 5 | ✓ |
| 6 | Attendance Ledger & Proofs | ORIGINAL_REQUEST §R1.6 | 5 | 5 | ✓ |
| 7 | Modular Settings & UI Cleanup | ORIGINAL_REQUEST §R1.7, §R3 | 5 | 5 | ✓ |
| 8 | Audio & TTS Soundboard | ORIGINAL_REQUEST §R1.8 | 5 | 5 | ✓ |
| 9 | Cryptographic Storage & TEE | ORIGINAL_REQUEST §R1.9 | 5 | 5 | ✓ |
| 10 | BLE Fleet Mesh & Kiosk Controller | ORIGINAL_REQUEST §R1.10 | 5 | 5 | ✓ |
| 11 | App-Wide 10-Language Localization | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 12 | Automated Testing & Build Integrity | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |

## Test Architecture
- Test runner: `powershell -Command ".\gradlew.bat test --no-build-cache"`
- Compilation runner: `powershell -Command ".\gradlew.bat compileDebugKotlin compileDebugJavaWithJavac --warning-mode all"`
- Test suite layout:
  - `app/src/test/java/com/omniface/ai/e2e/Tier1FeatureCoverageTest.kt`
  - `app/src/test/java/com/omniface/ai/e2e/Tier2BoundaryEdgeTest.kt`
  - `app/src/test/java/com/omniface/ai/e2e/Tier3PairwiseInteractionTest.kt`
  - `app/src/test/java/com/omniface/ai/e2e/Tier4RealWorldWorkloadTest.kt`
  - Subsystem test suites for Cryptography, ML, Deduplication, Localization, Database, Soundboard.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Morning High-Throughput Doorway Kiosk Ingestion | F1, F2, F6, F8, F10 | High |
| 2 | Student Registration with 5-Angle Burst Centroid & Deduplication Check | F3, F4, F5, F9, F11 | High |
| 3 | Multi-Language Dynamic Switch during Live Scanning | F2, F7, F8, F11 | Medium |
| 4 | Blockchain Ledger Hash Chain Verification & DPDP Right-To-Forget Purge | F6, F9 | High |
| 5 | Turnstile Relay Pulse Trigger with HMAC-SHA256 & Emergency Evacuation Override | F2, F10 | Medium |
| 6 | Offline Multi-Kiosk BLE Mesh Peer Sync & Reconciliation | F10, F6, F9 | High |

## Coverage Thresholds
- Tier 1: ≥5 per feature (≥60 test cases)
- Tier 2: ≥5 boundary test cases per feature (≥60 test cases)
- Tier 3: Pairwise coverage across major feature interactions (≥12 test cases)
- Tier 4: ≥6 realistic application workload scenarios
