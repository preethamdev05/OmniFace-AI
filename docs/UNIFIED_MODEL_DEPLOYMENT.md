# OmniFace Unified Biometric Neural Network (V2) — Mobile Deployment Specification

**Status**: Mobile Deployment Specification  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Quantization & Export Targets
- **Export Framework**: `ai-edge-torch` / LiteRT Converter.
- **Model Tiers**:
  1. **INT8 (Primary NPU)**: Full integer quantization with per-channel convolution weights and INT8 activations, calibrated via representative dataset of 500 diverse faces. Target size: $\le 4.5\text{ MB}$. **[NOT YET VALIDATED]**
  2. **FP16 (Mobile GPU)**: Float16 weights with FP32 fallback for high-precision GPU delegates. Target size: $\le 8.5\text{ MB}$. **[NOT YET VALIDATED]**
  3. **FP32 (CPU Fallback)**: Full precision for multi-threaded XNNPACK CPU inference. Target size: $\le 16.5\text{ MB}$. **[NOT YET VALIDATED]**

## 2. Hardware Tier Validation Matrix
Testing must be performed on actual physical silicon across three device tiers:
- **Tier 1 (Budget)**: MediaTek Helio G85 / G99 or Dimensity 6020 (ARM Cortex-A76/A55, Mali-G57). Target latency: $< 25\text{ ms}$. **[NOT YET VALIDATED]**
- **Tier 2 (Mid-Range)**: Qualcomm Snapdragon 778G / 7+ Gen 2 (Hexagon NPU, Adreno 642L). Target latency: $< 12\text{ ms}$. **[NOT YET VALIDATED]**
- **Tier 3 (Flagship)**: Qualcomm Snapdragon 8 Gen 2 / 8 Gen 3 / MediaTek Dimensity 9300 (HTP Tensor Accelerator / NeuroPilot). Target latency: $< 7\text{ ms}$. **[NOT YET VALIDATED]**

Delegate acceptance must be verified via LiteRT log inspection:
`INFO: Created TensorFlow Lite NNAPI / XNNPACK / GPU delegate for ...`\n