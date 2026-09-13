# OmniFace Unified Biometric Neural Network (V2) — Production Acceptance Gate

**Status**: Acceptance Gate Contract  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Master Acceptance Checklist

`OmniFaceUnifiedModelV2` may become the production attendance backend ONLY if every single gate below passes verification:

### Biometric Accuracy & Security Gates
- [ ] TAR @ FAR 1.0% meets or exceeds MobileFaceNet baseline ($\ge 99.42\%$). **[NOT YET VALIDATED]**
- [ ] TAR @ FAR 0.1% meets or exceeds MobileFaceNet baseline ($\ge 98.65\%$). **[NOT YET VALIDATED]**
- [ ] TAR @ FAR 0.01% meets or exceeds MobileFaceNet baseline ($\ge 96.80\%$). **[NOT YET VALIDATED]**
- [ ] PAD APCER $\le 1.25\%$ and BPCER $\le 0.85\%$ across 2D print and screen attacks. **[NOT YET VALIDATED]**
- [ ] Zero security regressions detected across adversarial presentation attack test suite. **[NOT YET VALIDATED]**

### Runtime & System Performance Gates
- [ ] Model file size is $\le 12\text{ MB}$ (INT8) / $\le 20\text{ MB}$ (FP16), eliminating the 362.57 MB prototype. **[NOT YET VALIDATED]**
- [ ] Single-face inference latency $\le 15\text{ ms}$ on target NPU hardware. **[NOT YET VALIDATED]**
- [ ] Peak runtime RAM footprint $\le 60\text{ MB}$ inside LiteRT arena. **[NOT YET VALIDATED]**
- [ ] Sustained 100-face attendance simulation completes without memory leaks or thermal throttling ($T < 42^\circ\text{C}$). **[NOT YET VALIDATED]**

### Android Platform & Architecture Gates
- [ ] Abstracted backend architecture: `RecognitionBackend.MOBILEFACENET` (default) vs `RecognitionBackend.OMNIFACE_UNIFIED_V2`. **[NOT YET VALIDATED]**
- [ ] Shadow mode inference operates asynchronously on a bounded drop-oldest queue without degrading 60 FPS camera preview. **[NOT YET VALIDATED]**
- [ ] Automatic thermal circuit breaker disables shadow worker when battery temperature exceeds $40^\circ\text{C}$. **[NOT YET VALIDATED]**
- [ ] All 667 existing Android JVM unit tests pass with zero regressions. **[MEASURED]**
- [ ] Physical-device camera registration and attendance scanning verified on Android 16 target device. **[NOT YET VALIDATED]**

**Fail-Safe Mandate**: If ANY critical gate fails or produces ambiguous results, `RecognitionBackend.MOBILEFACENET` remains the immutable, authoritative production engine.\n