# 📐 OmniFace AI — Technical Specifications & API Contracts
## UnifiedFaceModel V1 Production Engine Specification

---

## 1. Input Tensor Specification

| Attribute | Specification |
| :--- | :--- |
| **Tensor Name** | `serving_default_input_face:0` / `input_face` |
| **Tensor Shape** | `[1, 112, 112, 3]` (Batch Size: 1, Height: 112, Width: 112, Channels: 3) |
| **Color Order** | RGB (Red, Green, Blue) |
| **Data Type** | `FLOAT32` (`float32`) |
| **Value Range** | Normalized to $[-1.0, 1.0]$ via $x_{\text{norm}} = \frac{x - 127.5}{128.0}$ |
| **Alignment Constraint** | 5-point affine transformation matching canonical landmarks: Left Eye `(38.29, 51.70)`, Right Eye `(73.53, 51.50)`, Nose Tip `(56.03, 71.74)`, Left Mouth Corner `(41.55, 92.36)`, Right Mouth Corner `(70.73, 92.20)` |

---

## 2. Output Tensors Specification

`UnifiedFaceModel V1` provides **7 simultaneous task outputs** in a single graph execution:

| # | Head / Tensor Name | Tensor Shape | Rank | Output Description & Interpretation |
| :--- | :--- | :--- | :--- | :--- |
| **0** | `identity_embedding` | `[1, 512]` | 2 | 512-dimensional $L_2$-normalized identity representation vector ($\|v\|_2 = 1.0$). Matching distance: Cosine distance $d = 1.0 - \vec{u} \cdot \vec{v}$. |
| **1** | `pad_logits` | `[1, 3]` | 2 | Presentation Attack Detection logits / probabilities. Index 0: Bona Fide (Live), Index 1: Printed Photo, Index 2: Replay Screen. Gate: Live if $p_0 \ge 0.70$. |
| **2** | `quality_scores` | `[1, 4]` | 2 | Perceptual face quality indicators $\in [0.0, 1.0]$. Index 0: Overall Quality, Index 1: Sharpness, Index 2: Illumination, Index 3: Pose Symmetry. |
| **3** | `mesh_landmarks` | `[1, 1404]` | 2 | Dense 3D facial mesh consisting of 468 vertices $(x, y, z)$ flattened into 1404 values, normalized to $[0.0, 1.0]$. |
| **4** | `geometry_3dmm` | `[1, 265]` | 2 | 3D Morphable Model parameters: 199 identity shape bases, 29 expression blendshapes, 37 landmark vertex offsets. Gate: Depth variance $> 0.0015$. |
| **5** | `gaze_angles` | `[1, 2]` | 2 | Eye gaze orientation vector. Index 0: Pitch angle (radians), Index 1: Yaw angle (radians). |
| **6** | `attribute_probs` | `[1, 5]` | 2 | Binary attribute probabilities $\in [0.0, 1.0]$. Index 0: Eyeglasses, Index 1: Facial Hair/Beard, Index 2: Face Mask, Index 3: Headwear/Hat, Index 4: Smile. |

---

## 3. Kotlin Android API Contract: `UnifiedFaceModelEngine`

```kotlin
package com.omniface.ai.ml.unified

import android.content.Context
import android.graphics.Bitmap

/**
 * Result data contract for single-graph unified inference.
 */
data class UnifiedInferenceResult(
    val identityEmbedding: FloatArray,    // Size: 512
    val padProbabilities: FloatArray,     // Size: 3 [Live, Print, Screen]
    val qualityScores: FloatArray,        // Size: 4 [Overall, Sharpness, Illum, Pose]
    val meshLandmarks: FloatArray,        // Size: 1404 (468 * 3)
    val geometry3DMM: FloatArray,         // Size: 265
    val gazeAngles: FloatArray,           // Size: 2 [Pitch, Yaw]
    val attributeProbabilities: FloatArray,// Size: 5 [Glasses, Beard, Mask, Hat, Smile]
    val latencyMs: Long,
    val hardwareDelegate: String
)

class UnifiedFaceModelEngine(context: Context) : AutoCloseable {
    val isReady: Boolean
    fun processFace(alignedBitmap: Bitmap): UnifiedInferenceResult?
    override fun close()
}
```

---

## 4. SQLite / Room Biometric Schema Specification

### `students` (Table)
- `roll_number`: `TEXT PRIMARY KEY` (Unique Institutional ID)
- `full_name`: `TEXT NOT NULL`
- `department`: `TEXT NOT NULL`
- `semester`: `TEXT NOT NULL`
- `role`: `TEXT DEFAULT 'STUDENT'`
- `created_at`: `INTEGER NOT NULL` (UNIX Milliseconds)

### `face_templates` (Table)
- `id`: `TEXT PRIMARY KEY` (UUID string)
- `student_roll`: `TEXT NOT NULL` (Foreign Key referencing `students.roll_number` ON DELETE CASCADE)
- `angle_type`: `TEXT NOT NULL` (`FRONTAL`, `LEFT_15`, `RIGHT_15`, `UP_10`, `DOWN_10`)
- `embedding_encrypted_csv`: `TEXT NOT NULL` (AES-256-GCM encrypted ciphertext base64/hex)
- `is_encrypted`: `INTEGER NOT NULL DEFAULT 1`
- `quality_score`: `REAL DEFAULT 100.0`
- `model_version`: `TEXT DEFAULT 'UnifiedFaceModel_v1.0'`
- `created_at`: `INTEGER NOT NULL`

### `attendance_records` (Table)
- `record_id`: `TEXT PRIMARY KEY`
- `student_roll`: `TEXT NOT NULL` (Foreign Key referencing `students.roll_number` ON DELETE CASCADE)
- `student_name`: `TEXT NOT NULL`
- `session_date`: `TEXT NOT NULL` (`YYYY-MM-DD`)
- `timestamp`: `INTEGER NOT NULL`
- `confidence_pct`: `REAL NOT NULL`
- `security_tier`: `TEXT NOT NULL` (`STANDARD`, `HIGH`, `STRICT`)
- `sha256_hash`: `TEXT NOT NULL` (Cryptographic verification digest)
- `is_synced`: `INTEGER NOT NULL DEFAULT 0`

---

## 5. Decision Gates & Verification Thresholds

```text
=================================================================================
GATE                      THRESHOLD (τ)       FAR TARGET            ACTION
=================================================================================
STANDARD                  0.5874              1 in 10 (10%)         Classroom / Kiosk
HIGH (ISO/IEC Default)    0.2885              1 in 100 (1%)         Campus Gate Access
STRICT                    0.2184              1 in 1,000 (0.1%)     Exam Hall / Vault
TRACK-SWAP GATE           0.9169              Re-ID Reset           Drift Reset
LIVENESS BONA FIDE        0.7000              Softmax p(live)       Reject Spoofer
3DMM DEPTH VARIANCE       0.0015              Shape Disparity       Reject 2D Photo
=================================================================================
```
