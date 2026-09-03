# 🛡️ OmniFace AI — Privacy Policy & Biometric Data Governance

**Last Updated**: September 3, 2026  
**Effective Date**: September 3, 2026  
**Platform**: Sovereign OmniFace AI Biometric Attendance & Identity Verification Platform  
**Package**: `com.omniface.ai`  
**Governing Regulations**: Google Play Developer Policies (Biometrics & Data Safety), Digital Personal Data Protection (DPDP) Act 2023 (India), General Data Protection Regulation (GDPR), California Consumer Privacy Act (CCPA), Family Educational Rights and Privacy Act (FERPA).

---

## 1. Introduction & Core Privacy Commitment

OmniFace AI ("we", "our", or "the Application") is an offline-first, sovereign biometric facial recognition and attendance verification system developed for educational institutions, enterprise facilities, and high-security access control.

> ### 🔒 Core Privacy Guarantee: Zero Cloud Biometrics
> **OmniFace AI processes all biometric facial recognition 100% locally on your device.**  
> Raw facial photographs and mathematical feature embeddings are **NEVER** transmitted to cloud servers, external analytics providers, advertising networks, or third-party brokers. Your biometrics remain strictly isolated within your device's hardware-encrypted enclave.

---

## 2. Categories of Data Processed

### A. Biometric Data (On-Device Only)
- **Mathematical Facial Embeddings**: 512-dimensional normalized unit vectors generated via deep convolutional neural inference (MobileFaceNet / ArcFace). These numbers are mathematical feature abstractions that **cannot be reverse-engineered into human-recognizable photographs**.
- **Facial Landmark Geometry**: 5 canonical coordinate fiducials (left eye, right eye, nose tip, left mouth corner, right mouth corner) and 468-point 3D mesh tessellations used exclusively for real-time pose estimation, alignment, and Presentation Attack Detection (PAD).
- **Liveness Telemetry**: Head Euler rotation angles (Pitch, Yaw, Roll), optical eye gaze vectors, and temporal micro-motion variance to defeat photo, 4K screen replay, and 3D mask spoof attacks.
- **Storage & Lifecycle**: Biometric templates are stored inside an on-device SQLite database encrypted with **AES-256-GCM** utilizing keys sealed inside the hardware **AndroidKeyStore**.

### B. Personal & Academic Directory Data
- **Student / Personnel Identifiers**: Full Name, Roll Number / Employee ID, Department, Semester / Section.
- **Purpose**: Correlating authenticated biometric scans with institutional attendance logs.

### C. Attendance & Cryptographic Audit Ledger Data
- **Attendance Records**: Session date, timestamp (UTC/Local), match confidence score (0–100%), applied security tier (Standard, High, Banking), and deterministic SHA-256 Merkle leaf block hashes.
- **Aegis Ledger Continuity**: Immutable cryptographic chaining ensuring attendance logs cannot be altered, forged, or backdated.

### D. Hardware Performance & Diagnostic Metrics
- **Device Silicon Metrics**: Neural Processing Unit (NPU) TOPS capability, GPU model, thermal throttling state, and inference latency (ms).
- **Purpose**: Local hardware adaptation (e.g., auto-scaling camera resolution and selecting between Qualcomm Hexagon NPU, Adreno GPU, and CPU XNNPACK delegates). **Never exported or linked to individual identities.**

### E. Contactless rPPG Pulse Telemetry (Vitality & Anti-Spoofing)
- **Technical Scope**: Remote Photoplethysmography (rPPG) extracts capillary hemoglobin light absorption frequencies (0.75 Hz – 3.0 Hz, corresponding to 45 – 180 BPM) using the Chrominance (CHROM) algorithm.
- **Sole Purpose**: Presentation Attack Detection (PAD). Real human beings exhibit microscopic capillary arterial pulse waves; 2D paper photographs, digital displays, and silicone masks do not.
- **Non-Medical Notice**: This feature operates purely as an anti-spoofing security countermeasure and is **NOT intended, certified, or used as a medical device or health diagnostic tool**. Pulse telemetry is processed ephemerally in RAM and is never stored or transmitted.

### F. User-Owned Google Drive Cloud Backups (Zero Cloud Liability)
- **Zero Developer Access**: OmniFace AI allows administrators to optionally back up their student roster, 512-D biometric vector templates, and attendance records directly to the **administrator's own personal Google Drive account**.
- **End-to-End Encryption**: Before transmission, the backup archive is encrypted locally using **AES-256-GCM** with a 256-bit key derived via **PBKDF2-HMAC-SHA256 (10,000 iterations)** from a custom administrator PIN.
- **Hidden Sandbox**: Stored directly into the Google Drive `appDataFolder` sandbox via TLS 1.3. Neither Google, OmniFace developers, nor any third party can inspect or decrypt the biometric templates without the administrator's PIN.

---

## 3. Device Permissions & Purpose Specification

OmniFace AI requests only the minimal set of Android runtime permissions required for its security functions:

| Permission | Category | Technical Purpose & Scope |
|:---|:---|:---|
| `android.permission.CAMERA` | Dangerous (Runtime) | Captures live camera frames for on-device face detection, landmark alignment, and biometric verification. Frames are processed in volatile RAM buffers and never saved to public media galleries. Preceded by an explicit in-app prominent disclosure dialog. |
| `android.permission.VIBRATE` | Normal | Provides sensory tactile haptic feedback during Face ID reticle alignment and verification passes. |
| `android.permission.INTERNET`<br>`android.permission.ACCESS_NETWORK_STATE`<br>`android.permission.ACCESS_WIFI_STATE` | Normal | Used solely for optional user-owned Google Drive encrypted backups, emergency evacuation webhooks, and Hugging Face model repository downloads if configured by the administrator. |

---

## 4. Google Play Data Safety Disclosure Matrix

For transparency in Google Play Console Data Safety filings:

```
┌────────────────────────┬───────────┬──────────┬────────────────────────┐
│ Data Type              │ Collected │ Shared   │ Processing & Security  │
├────────────────────────┼───────────┼──────────┼────────────────────────┤
│ Photos and Videos      │ NO*       │ NO       │ Ephemeral RAM only;    │
│ (Raw Face Images)      │           │          │ never stored in gallery│
├────────────────────────┼───────────┼──────────┼────────────────────────┤
│ Biometric Data         │ YES       │ NO       │ 100% On-Device Local;  │
│ (512-D Vectors)        │ (Local)   │          │ AES-256-GCM Encrypted  │
├────────────────────────┼───────────┼──────────┼────────────────────────┤
│ Personal Info          │ YES       │ NO**     │ Stored in local DB;    │
│ (Name, Student ID)     │           │          │ Optional enterprise sync│
├────────────────────────┼───────────┼──────────┼────────────────────────┤
│ Financial Info         │ NO        │ NO       │ Not collected          │
├────────────────────────┼───────────┼──────────┼────────────────────────┤
│ Precise Location       │ NO        │ NO       │ Never requested        │
└────────────────────────┴───────────┴──────────┴────────────────────────┘
```
*\* Raw face frames are processed ephemerally in RAM and recycled immediately.*  
*\*\* Zero third-party sharing. Synchronized only to the customer's own institutional endpoint if explicitly enabled.*

### Security Practices
- **Data Encrypted in Transit**: All optional network synchronization requires TLS 1.3 encryption with strict HTTPS validation.
- **Data Encrypted at Rest**: All biometric templates and attendance records are stored with AES-256-GCM hardware key encryption. `android:allowBackup="false"` prevents extraction via USB ADB backup.
- **Data Deletion Mechanism**: Users and administrators can delete individual biometric profiles, trigger a 90-day retention purge, or perform a total cryptographic wipe directly in-app.

---

## 5. Compliance with Global Privacy Frameworks

### A. Digital Personal Data Protection (DPDP) Act 2023 (India)
- **Notice & Consent**: Clear visual disclosure before capturing facial biometric templates.
- **Right to Access & Rectification**: Administrators and students can review enrolled angles and update identification records.
- **Right to Erasure (Right to be Forgotten)**: Single-tap biometric template purge that irreversibly wipes all associated embeddings and logs an Aegis burn transaction.
- **Data Retention Limit**: Automated 90-day retention purge removes historical attendance logs older than 90 days.

### B. General Data Protection Regulation (GDPR)
- **Article 9 Special Category Data**: Facial templates are classified as special category biometric data. Processing occurs under explicit consent (Article 9(2)(a)) and employment/educational legal obligation (Article 9(2)(b)).
- **Data Minimization**: High-resolution camera frames are reduced to abstract 512-D vectors; images are not retained.

### C. FERPA & Student Privacy
- OmniFace AI does not create public directories or monetize student biometric records. Records remain under institutional educational agency control.

---

## 6. Data Deletion & Right-to-Forget Instructions

To request or execute biometric data deletion:
1. **In-App Self-Service**:
   - Navigate to **Settings -> Data Governance & Privacy**.
   - Under **Complete Biometric Ledger Wipe**, tap **Wipe All** and authenticate using device credentials (fingerprint/PIN).
   - Alternatively, open the **Student Directory**, swipe left on the student profile, and select **Delete Biometric Profile**.
2. **Institutional Administrator Request**:
   - Contact your campus or facility security administrator to request immediate profile removal.

---

## 7. Contact & Data Protection Officer

If you have questions, concerns, or requests regarding this Privacy Policy or biometric data handling:

- **Entity**: OmniFace AI Engineering Team
- **Data Protection Officer (DPO)**: `privacy@omniface.ai`
- **Security Inquiries**: `security@omniface.ai`
- **Response SLA**: All formal data subject inquiries are acknowledged within 48 hours and resolved within 30 days.
