# 🌐 OmniFace AI — Sovereign Biometric Identity Platform Blueprint

**OmniFace AI** is an enterprise-grade, privacy-first, sovereign Edge AI Facial Recognition Identity Platform designed for high-integrity on-device biometric identification, access control, and workforce verification without cloud latency or third-party tracking dependencies.

---

## 🏛️ System Architecture Topology

```mermaid
flowchart TD
    subgraph Ingestion_Layer ["CameraX 30–60 FPS Stream & Hardware Ingestion"]
        Camera["CameraX ImageAnalysis (YUV_420_888 / RGBA)"] --> MLKit["Google ML Kit / MediaPipe Face Tracking\n(Fast Bounding Box & 3D Landmark Mesh)"]
        MLKit --> Crop["Aspect-Ratio Preserved Biometric Crop (1.3x)"]
    end

    subgraph Quality_Gate ["Multi-Factor Registration & Quality Gate"]
        Crop --> Brightness["Luma Brightness Analysis (35 ≤ Luma ≤ 230)"]
        Crop --> Blur["Discrete Laplacian Variance (Sharpness Score ≥ 5.0)"]
        MLKit --> Pose["Head Pose Bounds (|Yaw| ≤ 35°, |Pitch| ≤ 30°, |Roll| ≤ 15°)"]
        MLKit --> EyeOpen["Blink & Eye Openness Probability (≥ 0.35)"]
        Brightness & Blur & Pose & EyeOpen --> QualityScore["Comprehensive RegistrationQualityScore (0–100%)"]
    end

    subgraph Anti_Spoofing ["Multi-Signal Anti-Spoofing Engine"]
        Crop --> Parallax["3D Non-Rigid Landmark Parallax Analysis"]
        Crop --> Moire["High-Frequency Spatial Texture & Moiré Detection"]
        Crop --> Specular["Specular Reflection Cluster Analysis"]
        Crop --> RPPG["Forehead Hemoglobin Blood Volume Pulse (rPPG)"]
        MLKit --> MicroMotion["Micro-Motion Temporal Variance Buffer (10-frame window)"]
        Crop --> FaceMap3DMM["Qualcomm FaceMap 3DMM Depth Topography (265-D)"]
        Crop --> EyeGaze["Qualcomm EyeGaze Attentiveness & Pupil Vector"]
        Parallax & Moire & Specular & RPPG & MicroMotion & FaceMap3DMM & EyeGaze --> LivenessDiag["LivenessDiagnostic & Composite Score"]
    end

    subgraph Inference_Backend_Hierarchy ["InferenceBackend Hierarchy"]
        IB_Interface["InferenceBackend (Contract)"]
        IB_Interface --> QualcommBackend["QualcommBackend\n(Adreno GPU / Hexagon HTP NPU • FP16/INT8)"]
        IB_Interface --> ONNXBackend["ONNXBackend\n(ONNX Runtime Graph Execution)"]
        IB_Interface --> CPUBackend["CpuBackend\n(Multi-Core CPU XNNPACK • 4 Threads FP32)"]
        IB_Interface --> FallbackBackend["FallbackBackend\n(NNAPI Delegate / CPU Reference Fallback)"]
    end

    subgraph Neural_Extraction ["Deep Hypersphere Embedding Extraction"]
        Crop --> NeuralArbiter["InferenceBackend Arbiter & Runtime Capability Detector"]
        NeuralArbiter --> Inference_Backend_Hierarchy
        Inference_Backend_Hierarchy --> ArcFace["MobileFaceNet / Qualcomm CavaFace IR-SE-100"]
        ArcFace --> L2Norm["Unit L2 Hypersphere Normalization (||v||₂ = 1.0)"]
        L2Norm --> QueryEmb["512-D L2-Normalized Feature Vector"]
    end

    subgraph Persistence_Vault ["Hardware Keystore & Persistence Vault"]
        Keystore["AndroidKeyStore Master Key (AES-256-GCM)"]
        RoomDB["Room SQLite Database v4\n(Encrypted Face Templates + Quality Scores)"]
        SIMDCache["Decrypted Biometric Matrix Cache (Fast SIMD Dot Product)"]
        Keystore <--> RoomDB
        RoomDB --> SIMDCache
    end

    subgraph Decision_Engine ["Anti-Impersonation Decision Engine"]
        QueryEmb & SIMDCache --> TwoPassMatcher["2-Pass Intra-Identity Score Aggregator"]
        TwoPassMatcher --> CandidateRanker["Ranked Candidates (Top-1 vs Top-2)"]
        CandidateRanker --> MarginCalc["Decision Margin Δ = Top1_Sim - Top2_Sim"]
        
        QualityScore & LivenessDiag & MarginCalc --> ZoneClassifier{"Confidence Zone Classifier"}
        ZoneClassifier -->|"Sim ≥ τ AND Δ ≥ 0.035 AND Live"| ZoneAccept["ACCEPT ZONE\n(Door Unlock + Aegis SHA-256 Block Minted)"]
        ZoneClassifier -->|"Δ < 0.035 OR Borderline Sim"| ZoneReview["REVIEW ZONE\n(Secondary Check / Admin Review Required)"]
        ZoneClassifier -->|"Sim < τ OR Spoof Detected"| ZoneReject["REJECT ZONE\n(Access Denied + Audit Alert)"]
    end
```

---

## ⚡ Core Engineering Standards

### 1. Identity Integrity over Recognition Convenience
- **Zero Ambiguous Auto-Accepts**: Even if an individual surpasses the raw threshold $\tau$, if their decision margin over the second-best identity $\Delta = S_1 - S_2 < 0.035$, the match is flagged into the **REVIEW ZONE** to eliminate sibling and lookalike misidentifications.
- **Explainable Biometrics**: Every decision produces an explicit `AttendanceDecision` report detailing:
  - Top-1 Similarity & Roll Number
  - Top-2 Similarity & Roll Number
  - Decision Margin $\Delta$
  - Quality Score & Laplacian Sharpness
  - Multi-Signal Liveness Diagnostic
  - Active Inference Backend & Latency (ms)
  - Full natural-language decision justification.

### 2. Multi-Signal Anti-Spoofing Architecture
Clearly separates **Quality Assessment** from **Spoof Detection** from **Identity Recognition**:
1. **3D Non-Rigid Landmark Parallax**: Quantifies geometric depth shift between nose centroid and inter-pupillary axis (defeats 2D flat paper photos).
2. **Spatial Frequency Laplacian Texture**: Detects high-frequency subpixel Moiré grid aliasing (defeats iPad/smartphone digital replays).
3. **Specular Glare Clustering**: Identifies blown-out polarized saturation highlights from glass and glossy print surfaces.
4. **Physiological rPPG Hemoglobin Pulse**: Measures rolling green-channel reflectance pulse variance with 3-point moving average AC flicker filtering.
5. **Micro-Motion Temporal Variance**: Analyzes yaw, pitch, and eye openness over 10-frame time series to detect static cutouts.
6. **Qualcomm AI Hub Neural Topography**: Leverages FaceMap 3DMM shape parameters and EyeGaze pupil fixation vectors on Snapdragon silicon.

### 3. Registration Excellence & Multi-Sample Consistency
- **5-Angle Biometric Studio**: Captures Frontal (0°), Left (22.5°), Right (22.5°), Up (16°), and Down (16°).
- **Pairwise Consistency Matrix**: Computes $M_{ij} = \mathbf{e}_i \cdot \mathbf{e}_j$ across all 5 samples.
- **Outlier Sample Rejection**: Requires $\min(M_{ij}) \ge 0.78$; rejects unstable or mixed identity registrations.
- **Quality-Weighted Master Centroid**: Generates $\mathbf{c} = \frac{\sum w_k \mathbf{e}_k}{\|\sum w_k \mathbf{e}_k\|_2}$ stored in Room SQLite schema v4.

### 4. InferenceBackend Hierarchy
- **`QualcommBackend`**: Qualcomm Adreno GPU / Hexagon HTP NPU acceleration for CavaFace (65.5M IR-SE-100) and QAI Hub suite models.
- **`ONNXBackend`**: Dedicated ONNX graph execution runner with direct tensor mapping.
- **`CpuBackend`**: Multi-Core 4-thread XNNPACK FP32 reference execution.
- **`FallbackBackend`**: Zero-dependency asset-bundled NNAPI and CPU fallback.

