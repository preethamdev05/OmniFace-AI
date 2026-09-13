# 📦 Legacy Unified OmniFace Model (Historical / Non-Production)

**Status**: **LEGACY / HISTORICAL / NON-PRODUCTION**  
**Access Tier**: **ARCHIVED**  
**Role**: Pre-unification multi-graph artifact (superseded by single-graph UnifiedFaceModel V1).

---

### ⚠️ Critical Safeguards
1. **Never Load in Production**: The Android application runtime MUST NEVER automatically discover or load this model.
2. **Never Confuse with V1**: Do NOT call or treat this model as the production unified model. The production unified model is `UnifiedFaceModel V1` (`unified_face_v1_fp16.tflite` / `unified_face_v1_int8.tflite`).
3. **Multi-Graph Stitched Container**: This legacy artifact contains stitched subgraphs rather than a single shared backbone.

---

### 📋 Model Provenance
- **Filename**: `unified_omniface.tflite`
- **Size**: 380,182,456 bytes (~362 MB)
- **SHA-256**: `DA27E41EDE2EC43DC003A9655DBC3C79D8EDC471987AFA9F221A959A46A677BD`
