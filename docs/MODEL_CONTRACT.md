# 📜 OmniFace UnifiedFaceModel V1 — Immutable Neural Model Contract

**Model Identifier**: `UnifiedFaceModel_v1.0`  
**Target FlatBuffer**: `models/unified_face_v1_fp16.tflite` / `models/unified_face_v1_int8.tflite`  
**Classification**: **[IMMUTABLE SPECIFICATION]**  
**Governing Rule**: Registration and recognition MUST always execute against the exact same identity head and embedding space.

---

## 1. Input Tensor Specification

| Tensor Name | Shape | Datatype | Range | Color Order | Normalization Formula |
|:------------|:------|:---------|:------|:------------|:----------------------|
| `input_face_raw_rgb` | `[1, 112, 112, 3]` | `FLOAT32` | `[-1.0, 1.0]` | RGB | `x_norm = (pixel - 127.5) / 128.0` |

### Preprocessing Invariants:
1. **Full-Frame Face Detection**: Performed by external dedicated BlazeFace / MediaPipe detector ($0.57\text{ MB}$).
2. **Landmark Alignment**: 5-point similarity transformation (eyes, nose tip, mouth corners) to standard canonical face coordinates.
3. **Bounding Box Aspect Ratio**: Fixed $1:1$ square crop centered on canonical landmarks.
4. **Color Space**: Strictly RGB. If ingesting OpenCV frames (BGR), swap channels $0 \leftrightarrow 2$ prior to inference.

---

## 2. Multi-Task Output Head Specifications

The model contains **exactly ONE shared neural backbone** (MobileNetV4-Conv-Small) feeding seven specialized task heads in a single forward pass:

```text
                               Input: [1, 112, 112, 3]
                                         │
                             MobileNetV4-Conv Backbone
                                         │
                     Shared Feature Tensor: [1, 512, 7, 7]
                                         │
    ┌────────────┬───────────┬───────────┼───────────┬───────────┬───────────┐
    ▼            ▼           ▼           ▼           ▼           ▼           ▼
Identity        PAD       Quality      Mesh      Geometry      Gaze      Attributes
[1, 512]       [1, 3]      [1, 4]    [1, 1404]   [1, 265]     [1, 2]       [1, 5]
```

### Head 0: Identity Recognition Head (Primary Priority)
- **Tensor Name**: `identity_embedding`
- **Output Shape**: `[1, 512]`
- **Datatype**: `FLOAT32`
- **Postprocessing**: Mandatory L2 normalization:
  $$
  \hat{\mathbf{e}} = \frac{\mathbf{e}}{||\mathbf{e}||_2}
  $$
- **Cosine Similarity Range**: $[-1.0, 1.0]$
- **Separation Criteria**:
  - Genuine Match: $\cos(\mathbf{e}_1, \mathbf{e}_2) \ge \tau_{calibrated}$
  - Impostor Rejection: $\cos(\mathbf{e}_1, \mathbf{e}_2) < \tau_{calibrated}$

### Head 1: Presentation Attack Detection (PAD / Anti-Spoof) Head
- **Tensor Name**: `pad_logits`
- **Output Shape**: `[1, 3]`
- **Datatype**: `FLOAT32`
- **Semantics**:
  - Index 0: `BONA_FIDE_LIVE` (Real Human Face)
  - Index 1: `PRINT_ATTACK` (2D Paper Print Spoof)
  - Index 2: `SCREEN_REPLAY` (Electronic Display Spoof)
- **Activation**: Softmax:
  $$
  P(\text{class}_i) = \frac{e^{z_i}}{\sum_{j=0}^{2} e^{z_j}}
  $$
- **Decision Rule**: Live if $P(\text{class}_0) \ge 0.50$ and $P(\text{class}_1) < 0.35$ and $P(\text{class}_2) < 0.35$.

### Head 2: Face Quality Assessment Head
- **Tensor Name**: `quality_scores`
- **Output Shape**: `[1, 4]`
- **Datatype**: `FLOAT32` (Sigmoid activated $[0.0, 1.0]$)
- **Semantics**:
  - Index 0: `sharpness_score` (1.0 = sharp, 0.0 = motion blurred)
  - Index 1: `illumination_score` (1.0 = balanced, 0.0 = underexposed/overexposed)
  - Index 2: `pose_score` (1.0 = frontal, 0.0 = extreme yaw/pitch)
  - Index 3: `overall_usability` (Composite enrollment/recognition gate)
- **Rejection Gate**: Reject enrollment if `overall_usability` $< 0.40$ or `sharpness_score` $< 0.30$.

### Head 3: Dense Facial Mesh Head
- **Tensor Name**: `mesh_landmarks`
- **Output Shape**: `[1, 1404]` (Flattened $468 \times 3$)
- **Datatype**: `FLOAT32`
- **Semantics**: $468$ canonical 3D facial landmarks ($x, y, z$) in normalized face crop coordinates $[0.0, 1.0]$.
- **Supervision**: Distilled from MediaPipe FaceMesh teacher.

### Head 4: 3D Morphable Model (Geometry) Head
- **Tensor Name**: `geom_3dmm`
- **Output Shape**: `[1, 265]`
- **Datatype**: `FLOAT32`
- **Semantics**: 3DMM shape parameters ($199$-D), expression parameters ($29$-D), pose parameters ($7$-D), illumination/albedo ($30$-D).
- **Physical Check**: Real skull depth variance:
  $$
  \text{Var}(z_{3dmm}) > 0.0015 \implies \text{True 3D Human Surface}
  $$

### Head 5: Eye Gaze Head
- **Tensor Name**: `gaze_angles`
- **Output Shape**: `[1, 2]`
- **Datatype**: `FLOAT32`
- **Semantics**: `[pitch, yaw]` in degrees.
- **Attention Check**: Kiosk engagement validated if $|\text{pitch}| < 20^\circ$ and $|\text{yaw}| < 25^\circ$.

### Head 6: Facial Attributes Head (Optional / Supporting)
- **Tensor Name**: `attribute_probs`
- **Output Shape**: `[1, 5]`
- **Datatype**: `FLOAT32` (Sigmoid activated $[0.0, 1.0]$)
- **Semantics**:
  - Index 0: `left_eye_open`
  - Index 1: `right_eye_open`
  - Index 2: `eyeglasses_present` (Clear spectacles — allow enrollment)
  - Index 3: `face_mask_present` (Mask detected — trigger removal prompt if $> 0.70$)
  - Index 4: `sunglasses_present` (Dark eyewear — trigger removal prompt if $> 0.70$)

---

## 3. Storage and Template Invariants

Every biometric record stored in SQLite Room `face_templates` MUST adhere to this contract:
1. `model_id`: `"omniface_unified_v1"`
2. `model_version`: `"UnifiedFaceModel_v1.0"`
3. `embedding_dimension`: `512`
4. `is_encrypted`: `true` (Hardware Keystore AES-256-GCM)
5. `quality_score`: Must be derived from Head 2 (`overall_usability * 100.0f`).

**No Mixed-Space Operations**: If an enrolled template has `model_version != UnifiedFaceModel_v1.0`, the matcher MUST flag `INCOMPATIBLE_TEMPLATE_VERSION` and require migration/re-enrollment rather than computing invalid cross-model cosine distances.
