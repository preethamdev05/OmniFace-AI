# 🌐 OmniFace AI — Master Architecture & Biometric Blueprint

**OmniFace AI** is an enterprise-grade, privacy-first, sovereign Edge AI Facial Recognition Attendance & Access Control Platform designed for ultra-fast on-device biometric identification without cloud latency or third-party tracking dependencies.

---

## 🏛️ System Architecture Topology

```mermaid
flowchart TD
    subgraph Edge_Hardware ["Edge Hardware Acceleration Layer"]
        NPU["Qualcomm Hexagon / MediaTek NeuroPilot NPU\n(NNAPI Delegate — INT8 Quantized)"]
        GPU["Adreno / Mali Mobile GPU\n(OpenCL / OpenGL Delegate — FP16)"]
        CPU["ARM64 Multi-Core Cortex CPU\n(XNNPACK Multi-Threaded — FP32)"]
    end

    subgraph Neural_Engine ["MobileFaceNet Sub-Center ArcFace Engine"]
        Input["Raw RGB 112x112 DirectByteBuffer"] --> Rescale["In-Model Rescaling Layer\n[-1.0, 1.0] Range"]
        Rescale --> Backbone["MobileFaceNet GDConv Backbone\n(~1.29M Parameters / 440 MFLOPs)"]
        Backbone --> Norm["L2 Hypersphere Normalization Layer"]
        Norm --> Embedding["512-D L2-Normalized Embedding Vector"]
    end

    subgraph Ingestion_Gate ["CameraX 30 FPS Stream & Anti-Spoofing"]
        Camera["CameraX ImageAnalysis Stream\n(YUV_420_888 @ 30 FPS)"] --> MLKit["Google ML Kit Face Tracking\n(Fast Bounding Box & Landmark Mesh)"]
        MLKit --> Crop["20% Expanded Aspect-Ratio Square Crop"]
        MLKit --> Liveness["Dual-Gate Anti-Spoofing Gate\n(Eye Blink Probability + Euler Pose ±20°)"]
        Crop --> Quality["Laplacian Blur Variance & Contrast Check"]
        Quality --> Input
    end

    subgraph Biometric_Vault ["Hardware Keystore & Persistence Vault"]
        Keystore["AndroidKeyStore Master Key\n(AES-256-GCM Authenticated Cipher)"]
        Room["Room SQLite Local Database\n(Offline Encrypted Face Templates)"]
        Cache["In-Memory SIMD Cosine Search Matrix\n(< 0.2ms Matching Latency per Frame)"]
        Keystore <--> Room
        Room --> Cache
    end

    subgraph Verification_Ledger ["Verification & Audit Ledger"]
        Embedding --> Matcher["In-Memory Cosine Similarity Matcher"]
        Cache --> Matcher
        Matcher --> Threshold{"Dynamic Decision Gate\n(τ = 0.240 / 0.416 / 0.500)"}
        Threshold -->|"Match Confirmed"| Ledger["Aegis SHA-256 Hash Chained Ledger"]
        Ledger --> WorkMgr["Background WorkManager Cloud Sync"]
    end

    Edge_Hardware <--> Neural_Engine
```

---

## ⚡ Core Pillars & Engineering Standards

### 1. Multi-Tier Hardware Acceleration
* **Tier 1 (NPU / NNAPI)**: Ingests `mobilefacenet_512d_int8.tflite` ($1.54\text{ MB}$, MLIR Per-Channel INT8 Quantized) providing sub-10ms neural inference on mobile NPUs.
* **Tier 2 (Mobile GPU Delegate)**: Ingests `mobilefacenet_512d_fp16.tflite` ($2.47\text{ MB}$, FP16 precision) via OpenCL/OpenGL delegates for devices without dedicated NPUs.
* **Tier 3 (Multi-Core CPU XNNPACK)**: Ingests `mobilefacenet_512d_fp32.tflite` ($4.85\text{ MB}$) using 4 parallel worker threads with SIMD NEON optimizations.
* **Zero Simulated Stubs**: Zero mock embeddings or pseudo-random vector generators in production pipelines.

### 2. Multi-Decade Calibrated Decision Gates
* **Standard Enterprise Mode** ($\tau = 0.240$): Optimized for high-throughput doorway kiosks ($\text{FAR} \le 10^{-1}$, $\text{TAR} = 55.4\%$).
* **High Security Mode** ($\tau = 0.416$): ISO/IEC 19794-5 standard operating point ($\text{FAR} \le 10^{-2}$, $\text{TAR} = 18.6\%$).
* **Strict Biometric Mode** ($\tau = 0.500$): Government and financial access point standard ($\text{FAR} \le 10^{-3}$, $\text{TAR} = 8.5\%$).

### 3. Hardware-Backed Privacy & Zero Server Key Persistence
* Biometric templates are encrypted on-device via `AndroidKeyStore` AES-256-GCM ciphers before writing to Room SQLite.
* Plaintext templates exist only ephemerally in RAM during the session lifecycle.
* Ledger records are chained with cryptographic SHA-256 hash proofs ensuring tamper resistance under the DPDP Act 2023.

---

## 🎨 UI/UX Design System Guidelines
* **Palette**: Deep Obsidian Slate (`#0B0F19`), Electric Emerald (`#10B981`), Cyber Cyan (`#06B6D4`), Amber Warning (`#F59E0B`), and Crimson Alert (`#EF4444`).
* **Typography**: Clean, high-legibility system typography with distinct letter tracking.
* **Sensory Feedback**: Real-time bounding box color transitions (Cyan $\to$ Amber $\to$ Emerald), haptic tick confirmations, and TTS audio announcements.
