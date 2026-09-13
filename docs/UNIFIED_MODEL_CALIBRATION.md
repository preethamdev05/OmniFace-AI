# OmniFace Unified Biometric Neural Network (V2) — Calibration Protocol

**Status**: Calibration Protocol Specification  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Independent Threshold Calibration Invariant
**Strict Rule**: MobileFaceNet thresholds ($\tau = 0.120, 0.158, 0.220$) and track-swap threshold ($0.60$) belong exclusively to the legacy embedding space. **[MEASURED]**
Applying legacy thresholds to `OmniFaceUnifiedModelV2` is strictly prohibited. A completely new threshold configuration must be computed from empirical similarity distributions:

```text
Cosine Distance Distribution:
Genuine Pairs:   μ_gen  ~ 0.12 - 0.25
Impostor Pairs:  μ_imp  ~ 0.65 - 0.85

Calibrated Gates:
STANDARD:  τ_std  at FAR = 1 in 10
HIGH:      τ_high at FAR = 1 in 100
STRICT:    τ_str  at FAR = 1 in 1,000
```

## 2. Track-Swap Calibration Protocol
1. Feed continuous video sequences with overlapping identities.
2. Plot cosine distance between consecutive track frames for the same subject vs track switches to a different subject.
3. Compute the EER (Equal Error Rate) threshold for track identity preservation.
4. Record the calibrated threshold in `BiometricVerificationConfig`. **[NOT YET VALIDATED]**

## 3. Temporal Feature Fusion Calibration
Benchmark four temporal fusion strategies on the new embedding space:
1. **Best-Frame Selection** (highest quality score).
2. **Mean Vector Fusion** with L2 re-normalization.
3. **Quality-Weighted Vector Fusion**: $e_{\text{fused}} = \text{normalize}(\sum w_i e_i)$.
4. **Multi-Frame Score Fusion**: $\text{score} = \max_i S(e_i, e_{\text{gallery}})$.
The optimal strategy will be selected via empirical TAR/FAR maximization, not assumptions. **[NOT YET VALIDATED]**\n