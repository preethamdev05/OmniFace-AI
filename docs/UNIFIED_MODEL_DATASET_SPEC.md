# OmniFace Unified Biometric Neural Network (V2) — Dataset Specification

**Status**: Dataset Strategy Document  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Dataset Rejection & Anti-Pattern Elimination
- **Anti-Pattern**: Training exclusively on the historical 105-identity PINS closed-set dataset is strictly prohibited. **[MEASURED]**
- **Requirement**: Open-set evaluation with identity-disjoint train, validation, and test splits. **[NOT YET VALIDATED]**

## 2. Multi-Task Dataset Composition

| Task Domain | Primary Datasets | Subsets & Classes | Environmental Conditions |
| :--- | :--- | :--- | :--- |
| **Identity** | Glint360k-Mini / CASIA-WebFace / MS1MV3 | $\ge 10,000$ identities | Extreme poses ($\pm 45^\circ$), severe backlight, shadows, low-light |
| **Passive PAD** | CelebA-Spoof / CASIA-SURF / SiW-M | Live vs 2D Print vs LCD/OLED/Tablet | Defocus blur, oblique angles, glare, high-res screens |
| **Dense Mesh & Geometry** | 300W-LP / AFLW2000-3D / CelebA-HQ | 3D dense meshes | Occlusion, sunglasses, medical masks, yaw $> 30^\circ$ |
| **Gaze & Attention** | MPIIGaze / GazeCapture | Diverse pitch/yaw bins | Screen reflections, off-axis gaze, mobile handheld angles |
| **Quality** | WiderFace / LFW-Degraded | Low-res ($< 40\text{px}$), blur, motion | Sensor noise, compression artifacts, underexposed frames |

## 3. Sharded Storage & Metadata Architecture
Data is serialized into fast sharded storage (HDF5 / NumPy memory maps / WebDataset shards) with strict record schema:
- `sample_id`: Unique deterministic UUID.
- `dataset_id`: Source dataset provenance.
- `identity_id`: Disjoint integer identity label (or -1 for unlabeled/PAD sets).
- `image_hash`: SHA-256 hash of the aligned face crop.
- `teacher_model_version`: Exact commit/hash of the teacher model.
- `teacher_confidence`: Float [0.0, 1.0] indicating teacher certainty.
- `ground_truth_available`: Boolean indicating verified human annotation.
- `augmentation_version`: Schema version of the offline augmentation pipeline.\n