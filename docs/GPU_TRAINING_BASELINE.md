# 📊 OmniFace V2 — GPU Training Baseline Profile Report

> **Target Hardware:** NVIDIA GeForce RTX 5060 Laptop GPU (8GB GDDR6 VRAM, Ada Lovelace / Blackwell mobile platform)  
> **Environment:** Windows 11, PyTorch 2.11.0+cu128, Python 3.12.9  
> **Profile Timestamp:** 2026-09-13  
> **Evaluation Mode:** `BASELINE` (Unoptimized V2 Training Loop: Online CavaFace CPU inference, standard single-process PIL JPEG loading)

---

## 1. Executive Summary & The Core Bottleneck

An instrumented 30-step profile (with 5-step warmup) using `torch.cuda.Event` hardware timers and high-resolution wall clocks measured the exact latency breakdown of the baseline training pipeline.

The results provide **irrefutable empirical proof** of the system bottleneck:

* **Current Training Throughput:** **42.99 samples/sec**
* **Mean Step Latency:** **1488.62 ms**
* **DataLoader & CPU Stall Percentage:** **90.48%**
* **GPU Active Compute Time Ratio:** **9.42%** (140.26 ms / 1488.62 ms)
* **GPU Idle/Starvation Time:** **90.58%**

The RTX 5060 is sitting idle for **over 90% of every step**, starved directly by **online CavaFace CPU extraction (72.2%)** and **PIL JPEG decoding from disk (18.3%)**.

---

## 2. Granular Stage-by-Stage Latency Breakdown

| Execution Stage | Latency (ms) | Percentage of Step | Execution Location | Primary Bottleneck Root Cause |
| :--- | :--- | :--- | :--- | :--- |
| **Teacher CPU Extraction** | **1074.25 ms** | **72.2%** | CPU (LiteRT XNNPACK) | Online CavaFace single/multi-thread CPU inference on raw JPEGs. |
| **Data Loading & JPEG Decode** | **272.66 ms** | **18.3%** | CPU (PIL / Disk I/O) | Opening individual JPEGs per-sample in `__getitem__` with `num_workers=0`. |
| **Host-to-Device (H2D) Transfer** | **1.45 ms** | **0.10%** | PCIe Bus | Synchronous blocking transfers (`.to(device)`). |
| **Forward Pass (Identity + PAD)** | **23.3 ms** | **1.57%** | GPU (CUDA Tensor Cores) | Multi-task MobileNetV4 forward execution under AMP. |
| **Loss Computation (Sub-Center ArcFace)** | **24.38 ms** | **1.64%** | GPU (CUDA) | Sub-Center ArcFace margins, distillation cosine loss, and PAD CE. |
| **Backward Pass (Autograd)** | **82.15 ms** | **5.52%** | GPU (CUDA) | Backpropagation across MobileNetV4 trunk and loss weights. |
| **Optimizer Step & Grad Clip** | **10.43 ms** | **0.70%** | GPU (CUDA) | AdamW weight updates and gradient norm clipping. |
| **Total Step Latency** | **1488.62 ms** | **100.0%** | Combined | **42.99 samples/sec** |

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ BASELINE STEP TIME BREAKDOWN: 1488.62 ms                                               │
├───────────────────────────────────────────────────────────────────┬──────────┬─────────┤
│ Teacher CPU Extraction: 1074.25 ms (72.2%)                        │ Data:    │ GPU:    │
│                                                                   │ 272.7 ms │ 140.3ms │
│                                                                   │ (18.3%)  │ (9.4%)  │
└───────────────────────────────────────────────────────────────────┴──────────┴─────────┘
```

---

## 3. Hardware & Memory Telemetry

* **Peak Allocated VRAM:** **1193.38 MB** (out of 8,151 MB, ~14.6% utilization)
* **Total VRAM Headroom:** **6957.6 MB**
* **GPU Core Utilization:** **22%** (idle between sparse compute spikes)
* **GPU Temperature:** **60 °C** (well within safe thermal limits)
* **CPU Utilization:** **47.3%**

---

## 4. Immediate Optimization Imperative (Phase 2)

1. **Eliminate Online Teacher Inference (1,074 ms/step saving):**
   Precomputing CavaFace 512-D embeddings offline into a lightweight, sharded, memory-mapped cache (~256 MB total for 250k images) will immediately reduce step latency from ~1,488 ms to ~414 ms, projecting an instant **3.6× throughput gain**.
2. **Optimize Data Loading & Pinned Transfers (270 ms/step saving):**
   Switching to multi-worker prefetching (`num_workers=4`, `pin_memory=True`, `non_blocking=True`) will overlap data loading with GPU compute, projecting total step time to drop below **150 ms** (>400 samples/sec).
