# OmniFace Unified Biometric Neural Network (V2) — Training Plan

**Status**: Training Execution Plan  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Priority-Guarded Multi-Task Objective

The total training loss enforces strict protection on security-critical tasks:
$$L_{\text{total}} = \lambda_{\text{id}} L_{\text{identity}} + \lambda_{\text{pad}} L_{\text{PAD}} + \sum_{k \in \{\text{mesh, geom, gaze, quality}\}} \frac{1}{2 \sigma_k^2} L_k + \log \sigma_k$$

- **Priority 0 (Protected)**:
  - $\lambda_{\text{id}} \ge 1.0$: ArcFace / Sub-Center ArcFace loss with feature distillation from CavaFace.
  - $\lambda_{\text{pad}} \ge 1.0$: Hard-example mining cross-entropy + MiniFASNet KL-divergence.
  - **Invariant**: The optimizer must **reject** any auxiliary task improvement that causes a statistically significant regression in TAR @ FAR 0.1% or PAD APCER/BPCER. **[NOT YET VALIDATED]**
- **Priority 1 & 2 (Auxiliary)**:
  - Dynamically weighted via homoscedastic uncertainty parameters $\sigma_k$ or gradient projection (PCGrad). **[NOT YET VALIDATED]**

## 2. Optimization Schedule
- **Optimizer**: AdamW ($\beta_1 = 0.9, \beta_2 = 0.999, \text{weight\_decay} = 10^{-4}$).
- **Learning Rate**: Warmup for 5 epochs to $10^{-3}$, followed by Cosine Annealing to $10^{-6}$ across 60 epochs.
- **Hardware Target**: Kaggle Tesla P100 / Dual T4 GPUs (batch size 128 per GPU) with mixed-precision (AMP).
- **Sanity Checks**: All modules run local CPU test harnesses (batch size 2) to assert gradient flow and finite loss values before remote dispatch.\n