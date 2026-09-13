# 🌟 CavaFace — Golden Baseline (Historical Reference Only)

**Status**: **GOLDEN BASELINE — HISTORICAL REFERENCE ONLY**  
**Access Tier**: **ARCHIVED / NON-PRODUCTION**  
**Role**: Baseline accuracy teacher & reference standard.

---

### ⚠️ Critical Safeguards
1. **Never Load in Production**: The Android application runtime MUST NEVER load this model.
2. **Never Alter**: The model file `cavaface.tflite` is immutable and preserved for teacher distillation, forensic comparisons, and historical benchmarking.
3. **No Mixed Matching**: Enrolled templates generated from CavaFace cannot be compared against UnifiedFaceModel V1 embeddings without explicit re-enrollment / migration.

---

### 📋 Model Provenance
- **Filename**: `cavaface.tflite`
- **Size**: 262,099,184 bytes (~250 MB)
- **SHA-256**: `68E545C47329B854C53DDB4767464EC5772F01DF65C258D5FE2CD316AA8213D2`
- **Origin**: Qualcomm AI Hub v0.60.0 release.
