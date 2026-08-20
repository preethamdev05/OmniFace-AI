# 📋 OmniFace AI — Technical Specifications & API Contracts

**Package**: `com.omniface.ai`  
**Target SDK**: Android API 34 (Android 14)  
**Min SDK**: Android API 26 (Android 8.0 Oreo)  
**Runtime Architecture**: ARM64 (`arm64-v8a`), ARM32 (`armeabi-v7a`)  
**Language**: Kotlin 1.9.22 + Java 17  
**Build System**: Gradle 8.4 (AGP 8.2.2)

---

## 🧠 Biometric Engine Specification

| Property | Value / Standard | Description |
|---|---|---|
| **Model Architecture** | MobileFaceNet GDConv | 7x7 Global Depthwise Convolutional Backbone (~1.29M params) |
| **Input Shape** | `[1, 112, 112, 3]` | Fixed Batch Concrete Tensor Signature |
| **Input DType** | `int8` (NPU) / `float32` (GPU/CPU) | Raw RGB Bytes $[0, 255]$ with in-graph $[-1.0, 1.0]$ normalization |
| **Output Shape** | `[1, 512]` | 512-Dimensional Hypersphere Vector |
| **Normalization** | Unit L2 Hypersphere | $\|v\|_2 = 1.0 \pm 10^{-6}$ |
| **Distance Metric** | Cosine Similarity | $\text{Sim}(a, b) = a \cdot b = \sum_{i=1}^{512} a_i b_i$ |
| **Inference Time** | $< 8\text{ms}$ (NPU) / $< 22\text{ms}$ (GPU) | 30 FPS Real-Time Camera Stream Saturation |

---

## 🔒 Security & Data Encryption Specification

### 1. `AndroidKeyStore` Configuration
* **Key Alias**: `OmniFaceMasterKey`
* **Algorithm**: `KeyProperties.KEY_ALGORITHM_AES` (256-bit)
* **Block Mode**: `KeyProperties.BLOCK_MODE_GCM`
* **Padding**: `KeyProperties.ENCRYPTION_PADDING_NONE`
* **Authentication**: 128-bit GCM Auth Tag + 12-byte random Initialization Vector (IV).

### 2. Room SQLite Schema

#### Table: `students`
```sql
CREATE TABLE students (
    roll_number TEXT PRIMARY KEY NOT NULL,
    full_name TEXT NOT NULL,
    department TEXT NOT NULL,
    semester TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
```

#### Table: `face_templates`
```sql
CREATE TABLE face_templates (
    id TEXT PRIMARY KEY NOT NULL,
    student_roll TEXT NOT NULL,
    angle_type TEXT NOT NULL, -- 'FRONTAL', 'LEFT_15', 'RIGHT_15'
    embedding_encrypted_csv TEXT NOT NULL, -- AES-256-GCM ciphertext
    is_encrypted INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(student_roll) REFERENCES students(roll_number) ON DELETE CASCADE
);
```

#### Table: `attendance_records`
```sql
CREATE TABLE attendance_records (
    record_id TEXT PRIMARY KEY NOT NULL,
    student_roll TEXT NOT NULL,
    student_name TEXT NOT NULL,
    session_date TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    confidence_pct REAL NOT NULL,
    security_tier TEXT NOT NULL, -- 'STANDARD', 'HIGH', 'STRICT'
    sha256_hash TEXT NOT NULL, -- Chained verification hash
    is_synced INTEGER NOT NULL DEFAULT 0
);
-- Indices:
-- INDEX index_attendance_records_session_date_timestamp ON attendance_records (session_date, timestamp);
-- INDEX index_attendance_records_session_date_student_roll ON attendance_records (session_date, student_roll);
-- INDEX index_attendance_records_student_roll ON attendance_records (student_roll);
-- INDEX index_attendance_records_is_synced ON attendance_records (is_synced);
```

---

## 📡 REST API Sync Specification (WorkManager Worker)

### `POST /api/v1/attendance/sync`
**Headers**:
```
Content-Type: application/json
X-Device-Fingerprint: <Hardware_Keystore_SHA256>
```
**Payload**:
```json
{
  "device_id": "OMNIFACE-TERMINAL-01",
  "records": [
    {
      "record_id": "rec_98a72b",
      "student_roll": "21BCA042",
      "student_name": "Preetham",
      "timestamp": 1723968400000,
      "confidence_pct": 94.2,
      "security_tier": "STANDARD",
      "sha256_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
  ]
}
```
**Response (`200 OK`)**:
```json
{
  "status": "SUCCESS",
  "synced_count": 1,
  "server_signature": "aegis_sha256_block_minted"
}
```
