# 🔄 OmniFace AI — Biometric Database Migration Plan (CavaFace / MobileFaceNet → UnifiedFaceModel V1)

**Document Identifier**: `OMNIFACE_MIGRATION_PLAN_V1`  
**Classification**: **[ZERO-INTERRUPTION MIGRATION PROTOCOL]**  
**Lead Architect**: Lead ML Architect  
**Governing Principle**: Latent spaces between distinct biometric models are non-isometric and mathematically orthogonal. Vectors must NEVER be compared across model versions.

---

## 1. The Multi-Model Latent Incompatibility Invariant

$$
\langle \mathbf{e}_{\text{cavaface}}, \mathbf{e}_{\text{unified\_v1}} \rangle \approx 0.000 \pm 0.080 \quad (\text{Orthogonal})
$$
$$
\langle \mathbf{e}_{\text{mobilefacenet}}, \mathbf{e}_{\text{unified\_v1}} \rangle \approx 0.000 \pm 0.080 \quad (\text{Orthogonal})
$$

### Absolute Negative Rules:
1. **NO Silent Remapping**: An existing 512-D vector enrolled under `cavaface` or `v1.0_mobilefacenet_512d` cannot be used directly with `UnifiedFaceModel V1`.
2. **NO Cross-Model Distance Calculations**: The matcher must strictly isolate candidate comparisons to matching `model_version` pools.
3. **NO Production Interruption**: During migration, legacy enrolled identities must continue to authenticate seamlessly using the legacy engine until re-enrolled.

---

## 2. Controlled 3-Phase Migration Lifecycle

```text
       PHASE 1: SHADOW MODE
    ┌─────────────────────────┐
    │  Production: Legacy     │  (CavaFace / MobileFaceNet matches active DB)
    │  Shadow: Unified V1     │  (Computes shadow telemetry, zero user impact)
    └───────────┬─────────────┘
                │
                ▼
       PHASE 2: DUAL-RUN & OPPORTUNISTIC RE-ENROLLMENT
    ┌─────────────────────────┐
    │  Legacy Matches         │  -> Triggers Attendance + Silent Re-Enrollment
    │  Dual-Enrolled DB       │  -> Stores new template with model_version='UnifiedFaceModel_v1.0'
    └───────────┬─────────────┘
                │
                ▼
       PHASE 3: UNIFIED CUTOVER & LEGACY PRUNING
    ┌─────────────────────────┐
    │  100% Unified Model     │  -> Primary production engine
    │  Legacy Deprecated      │  -> Unused teacher models stripped from APK assets
    └─────────────────────────┘
```

### Phase 1: Non-Interfering Shadow Mode (Active State)
- The legacy `FaceRecognitionEngine` running MobileFaceNet/CavaFace remains the primary authoritative gatekeeper.
- `UnifiedShadowEvaluator` receives each verified face crop, runs `UnifiedFaceModelEngine`, logs cosine agreement and PAD agreement to telemetry, and reports thermal/latency metrics.
- Database records remain unchanged.

### Phase 2: Opportunistic In-Session Re-Enrollment
- When an enrolled subject (e.g., `ujk`) presents their face and is verified by the primary engine ($\ge 95\%$ confidence):
  1. The high-quality aligned face bitmap is passed to `UnifiedFaceModelEngine.processFace()`.
  2. A new 512-D embedding is extracted under `UnifiedFaceModel_v1.0`.
  3. A new row is inserted into SQLite `face_templates`:
     - `id`: `"tpl_unified_" + UUID`
     - `student_roll`: `subject_roll`
     - `angle_type`: `"FRONTAL"`
     - `model_version`: `"UnifiedFaceModel_v1.0"`
     - `embedding_encrypted_csv`: Encrypted with Android Keystore AES-256-GCM.
  4. The subject is now dual-enrolled with zero user friction (no manual re-registration studio needed).

### Phase 3: Final Production Cutover Gate
When the percentage of dual-enrolled active identities exceeds $99.5\%$:
1. A configuration flag `isUnifiedModelPrimary` is toggled to `true`.
2. `FaceSecurityPipeline` switches Gate 3 matcher to `UnifiedFaceModelEngine`.
3. Legacy model files (`mobilefacenet_512d_*.tflite`, `cavaface.tflite`) are deleted from APK assets, reducing binary size by over $100\text{ MB}$.
4. Legacy SQLite records (`model_version != 'UnifiedFaceModel_v1.0'`) are purged in accordance with DPDP Act 2023 data minimization requirements.

---

## 3. Database Schema Backward Compatibility

Room SQLite Entity `FaceTemplateEntity` already includes:
```kotlin
@ColumnInfo(name = "model_version", defaultValue = "v1.0_mobilefacenet_512d")
val modelVersion: String = "v1.0_mobilefacenet_512d"
```
During Gate 3 candidate scoring:
```kotlin
val eligibleTemplates = biometricCache.filter { 
    it.modelVersion == activeEngine.modelVersion 
}
```
This guarantees that candidate scoring never compares cross-space vectors.

---

## 4. Rollback Protocol

If runtime anomalies, unexpected False Rejections, or NPU driver panics are detected on any hardware tier during Phase 2 or 3:
1. `NeuralModelConfigManager.configState.update { it.copy(useUnifiedModel = false) }` immediately demotes the unified model back to shadow mode.
2. The legacy MobileFaceNet / CavaFace engine resumes $100\%$ primary verification within $< 10\text{ ms}$ with zero state corruption.
3. Incident telemetry is written to `AegisBlockchainLedger` for post-mortem analysis.
