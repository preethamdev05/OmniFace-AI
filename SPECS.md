# 📋 OmniFace AI — Technical Specifications & API Contracts

**Package**: `com.omniface.ai`  
**Target SDK**: Android API 34 (Android 14)  
**Min SDK**: Android API 26 (Android 8.0 Oreo)  
**Runtime Architecture**: ARM64 (`arm64-v8a`), ARM32 (`armeabi-v7a`)  
**Language**: Kotlin 1.9.22 + Java 17  
**Build System**: Gradle 8.4 (AGP 8.2.2)

---

## 🧠 Biometric Engine Specification

| Property | Standard Model (MobileFaceNet) | Qualcomm Suite Model (CavaFace) |
|---|---|---|
| **Architecture** | MobileFaceNet GDConv Backbone | ResNet-100 IR-SE (ArcFace Loss) |
| **Parameters** | ~1.29M parameters (440 MFLOPs) | 65.5M parameters (24 GFLOPs) |
| **Input Shape** | `[1, 112, 112, 3]` RGB | `[1, 112, 112, 3]` RGB |
| **Input Value Range** | `[-1.0, 1.0]` (In-Graph Rescale) | `[0.0, 1.0]` Normalized Float |
| **Output Shape** | `[1, 512]` L2-Normalized Vector | `[1, 512]` L2-Normalized Vector |
| **Inference Time** | $< 7\text{ ms}$ (NPU) / $< 18\text{ ms}$ (GPU) | $< 12\text{ ms}$ (Hexagon HTP / Adreno GPU) |
| **Decision Margins** | $\Delta = S_1 - S_2 \ge 0.035$ | $\Delta = S_1 - S_2 \ge 0.035$ |

---

## 🛡️ Three-Tier Confidence Zones

```
+---------------------------------------------------------------------------------------+
|  ACCEPT ZONE (Green)   : Sim >= tau AND Delta >= 0.035 AND Liveness Passed             |
|  REVIEW ZONE (Amber)   : Sim >= tau with Delta < 0.035 (Ambiguous) OR Near-Threshold  |
|  REJECT ZONE (Red)     : Sim < tau - 0.025 OR Liveness Failed OR Quality Failed       |
+---------------------------------------------------------------------------------------+
```

---

## 🔒 Security & Persistence Specification (Room DB v4)

### Table: `students`
```sql
CREATE TABLE students (
    roll_number TEXT PRIMARY KEY NOT NULL,
    full_name TEXT NOT NULL,
    department TEXT NOT NULL,
    semester TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
```

### Table: `face_templates` (Schema Version 4)
```sql
CREATE TABLE face_templates (
    id TEXT PRIMARY KEY NOT NULL,
    student_roll TEXT NOT NULL,
    angle_type TEXT NOT NULL, -- 'FRONTAL', 'LEFT_22', 'RIGHT_22', 'UP_16', 'DOWN_16', 'MASTER_CENTROID'
    embedding_encrypted_csv TEXT NOT NULL, -- AES-256-GCM ciphertext
    is_encrypted INTEGER NOT NULL DEFAULT 1,
    quality_score REAL NOT NULL DEFAULT 95.0,
    sharpness_score REAL NOT NULL DEFAULT 90.0,
    lighting_score REAL NOT NULL DEFAULT 90.0,
    consistency_score REAL NOT NULL DEFAULT 100.0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(student_roll) REFERENCES students(roll_number) ON DELETE CASCADE
);
```

### Table: `attendance_records`
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
      "security_tier": "HIGH",
      "decision_margin": 0.245,
      "confidence_zone": "ACCEPT",
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

