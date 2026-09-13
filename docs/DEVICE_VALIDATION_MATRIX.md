# 📱 OmniFace AI — Real-Device Hardware Validation Matrix (Stage 18)

## 1. Executive Summary
This document specifies the physical device validation matrix, benchmarking methodology, and target performance envelopes for OmniFace AI across silicon tiers, as mandated by Stage 18 of the Biometric Production Engineering Plan.

---

## 2. Hardware Classification & Target Tiers

| Tier | Representative SoCs & Devices | Target Neural Accelerator | Precision | Max Concurrent Faces | Target Latency / Face |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ULTRA** | Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite<br>(Samsung S24, OnePlus 12, Xiaomi 14) | Qualcomm Hexagon HTP / NPU (45 TOPS) | INT8 | 4 faces | <= 8.5 ms |
| **FLAGSHIP** | Google Tensor G3/G4, Dimensity 9200/9300<br>(Pixel 8/9, Vivo X100) | Google EdgeTPU / MediaTek APU 790 | INT8 / FP16 | 2 faces | <= 16.0 ms |
| **BALANCED** | Snapdragon 7s Gen 2 / 7 Gen 3, Dimensity 7050<br>(Nothing Phone 2a, Redmi Note 13 Pro) | OpenCL Mobile GPU Delegate | FP16 | 1 face | <= 28.0 ms |
| **ENTRY** | Helio G99, Snapdragon 680 / 685<br>(Galaxy A15, Poco M6) | Multi-Threaded CPU (XNNPACK, 4 threads) | FP32 | 1 face | <= 65.0 ms |

---

## 3. Empirical Test Vectors & Verification Protocol

For each physical hardware tier, the automated runner benchmarks 50 continuous group attendance cycles:

1. **Throughput & Latency**:
   - Single-face latency (P50, P90, P99).
   - Multi-face group latency (2, 3, 4 simultaneous subjects in field-of-view).
   - End-to-end frame drop rate at 30 FPS camera preview.
2. **Biometric Integrity**:
   - False Match Rate (FMR / FAR) against 10,000 impostor pairs (<= 0.01%).
   - False Non-Match Rate (FNMR / FRR) against 1,000 genuine probe pairs under realistic smartphone illumination (<= 2.5%).
   - Replay Attack Penetration Rate (MiniFASNet + 3DMM depth consensus <= 0.1%).
3. **Thermal & Battery Stability**:
   - Temperature delta over 30 minutes of continuous kiosk operation (Delta T <= 6.5 C).
   - Battery consumption rate (<= 4.2% per hour).
   - Dynamic downscale trigger verification under simulated ThermalState.WARM (75% resolution) and ThermalState.CRITICAL (50% resolution + 1 face clamp).

---

## 4. Benchmark Harness Command
To execute the physical device benchmark suite on an attached Android device via ADB:
`ash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omniface.ai.e2e.OmniFaceProductionIntegrationTest
`
