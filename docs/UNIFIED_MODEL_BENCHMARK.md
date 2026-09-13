# OmniFace Unified Biometric Neural Network (V2) — Benchmark Protocols

**Status**: Benchmarking Specification  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Verification Benchmarking (Open-Set)
- **Pair Sets**: Minimum $\ge 1,000$ genuine pairs and $\ge 10,000$ impostor pairs across diverse ethnicities and lighting conditions. **[NOT YET VALIDATED]**
- **Evaluation Curves**: Full Receiver Operating Characteristic (ROC) and Detection Error Trade-off (DET) plots.
- **Mandatory Operating Metrics**:
  - TAR @ FAR = 1.0% (Doorway standard)
  - TAR @ FAR = 0.1% (ISO/IEC baseline)
  - TAR @ FAR = 0.01% (High-security banking)
  - Rank-1 and Rank-5 closed-set identification accuracy.

## 2. PAD Presentation Attack Benchmarking
- Evaluated against ISO/IEC 30107-3 metrics:
  - **APCER**: Attack Presentation Classification Error Rate (target $\le 1.0\%$).
  - **BPCER**: Bona Fide Presentation Classification Error Rate (target $\le 0.5\%$).
  - **ACER**: Average Classification Error Rate: $\frac{\text{APCER} + \text{BPCER}}{2}$.

## 3. Group Workload & Hardware Scalability
- **Concurrency Test Points**: 1, 2, 4, 6, 10, 20, 40, 60, 80, 100 simultaneous faces.
- **Model Invariant**: Fixed tensor input $[1, 112, 112, 3]$; dynamic batch tensors ($[100, 112, 112, 3]$) are strictly prohibited. Concurrency is handled via scheduler-level queues. **[MEASURED]**
- **Telemetry**: Latency distribution ($p50, p95, p99$), peak RAM allocation, CPU/GPU/NPU temperature, frame drops.\n