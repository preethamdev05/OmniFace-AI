# OmniFace Unified Biometric Neural Network (V2) — Teacher Distillation

**Status**: Distillation Protocol Document  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Distillation Architecture & Supervision Flows

```text
                        ┌──────────────┐
                        │ Aligned Face │
                        └──────┬───────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌───────────────────────┐             ┌───────────────────────┐
│   Teacher Ensemble    │             │ Student Multi-Task V2 │
│ (CavaFace, MiniFASNet,│             │   (Shared Backbone)   │
│  MediaPipe, FaceMap)  │             └───────────┬───────────┘
└───────────┬───────────┘                         │
            │ Teacher Targets                     │ Student Predictions
            │ (offline cached)                    │
            └──────────────────┬──────────────────┘
                               ▼
                   Priority-Guarded Distillation
                    L_total = L_P0 + λ(t) * L_aux
```

## 2. Confidence-Aware Distillation Rules
1. **Ground Truth Precedence**: Where human ground-truth labels exist (e.g. Identity class IDs, bona-fide vs spoof attack labels), human labels take precedence over teacher logits. **[NOT YET VALIDATED]**
2. **Confidence-Weighted Distillation**: For teacher pseudo-labels (e.g. 3DMM shape parameters, gaze angles, dense mesh), loss is weighted by teacher confidence $c_t$:
   $$L_{\text{distill}}(y_s, y_t) = c_t \cdot \mathcal{D}(y_s, y_t)$$
3. **Teacher Disagreement Auditing**: If two teachers provide conflicting signals (e.g. CavaFace high confidence vs MobileFaceNet low similarity), samples are quarantined for inspection. **[NOT YET VALIDATED]**
4. **Deterministic Versioning**: Every cached target stores the teacher model version and input hash. Re-generating teacher targets without a documented version increment is prohibited. **[NOT YET VALIDATED]**\n