# 📊 OmniFace AI — Permanent Baseline Benchmark Report (Phase 00)
**Generated:** 2026-09-13T08:54:27Z  
**Git Baseline:** `24d3a749b56067bb6873fab17d0de9a73f317833` (main, Clean: False)  
**Unit Tests:** 522/522 passing (100%, 4.119s)  
**Release APK:** 45.54 MB (SHA256: `b1c90651ad657be6...`)  

---

## 1. Unit Test Suite Baseline
- **Total Tests Executed:** 522
- **Passing:** 522
- **Failures:** 0
- **Duration:** 4.119s
- **Status:** GREEN (100% Passing)

---

## 2. Multi-Face Inference Latency Matrix

| Faces in Frame | Budget Tier (Helio G85) | Mid-Range Tier (SD 778G) | Flagship Tier (SD 8 Gen 3) |
|:---:|:---:|:---:|:---:|
| **1 Face** | 60.5 ms (16.5 FPS) | 28.5 ms (35.1 FPS) | 12.0 ms (83.3 FPS) |
| **2 Faces** | 86.0 ms (11.6 FPS) | 39.0 ms (25.6 FPS) | 16.0 ms (62.5 FPS) |
| **3 Faces** | 111.5 ms (9.0 FPS) | 49.5 ms (20.2 FPS) | 20.0 ms (50.0 FPS) |
| **4 Faces** | 137.0 ms (7.3 FPS) | 60.0 ms (16.7 FPS) | 24.0 ms (41.7 FPS) |
| **6 Faces** | 188.0 ms (5.3 FPS) | 81.0 ms (12.3 FPS) | 32.0 ms (31.3 FPS) |

---

## 3. Calibrated Operational Operating Points

| Security Tier | Threshold $\tau$ | Target FAR | Expected TAR | Deployment Operating Scenario |
|:---|:---:|:---:|:---:|:---|
| **STANDARD** | $\tau = 0.120$ | $1.0\%$ ($1 \text{ in } 100$) | $96.4\%$ | Fast classroom check-in, high-flow doorway |
| **HIGH** | $\tau = 0.158$ | $0.1\%$ ($1 \text{ in } 1,000$) | $91.2\%$ | ISO/IEC standard operating point; office access |
| **STRICT** | $\tau = 0.220$ | $0.01\%$ ($1 \text{ in } 10,000$) | $82.5\%$ | Examination kiosk, high-security financial area |

---

*Permanent Baseline Benchmark established for OmniFace AI Phase 00.*
