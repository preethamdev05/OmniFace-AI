"""
OmniFace V2 — GPU Training Step Profiler
Measures granular stage-by-stage latencies (Data Loading, Teacher Inference,
Host-to-Device Transfer, Forward, Loss, Backward, Optimizer) using
torch.cuda.Event and high-resolution wall clocks.
"""

import os
import sys
sys.path.insert(0, os.path.abspath("."))
import time
import json
import argparse
import subprocess
import psutil
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from training.unified.datasets.webface_lfw_dataset import WebFaceDataset
from training.unified.samplers.pk_sampler import IdentityPKSampler
from training.unified.datasets.nuaa_dataset import NUAAPadDataset
from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.losses.subcenter_arcface_v2 import UnifiedIdentityLossV2
from training.unified.distillation.cavaface_cache_builder import CavaFaceFeatureExtractor


from training.unified.data_cache.build_cavaface_cache import CavaFaceShardedCacheReader


class CachedWebFaceDataset(torch.utils.data.Dataset):
    def __init__(self, base_dataset: WebFaceDataset, cache_reader: CavaFaceShardedCacheReader):
        self.base = base_dataset
        self.cache_reader = cache_reader
        self.num_samples = min(len(self.base), self.cache_reader.total_samples)
        self.labels = self.base.labels[:self.num_samples]
        self.num_classes = self.base.num_classes

    def __len__(self):
        return self.num_samples

    def __getitem__(self, idx):
        tensor, label, path = self.base[idx]
        teacher_emb = self.cache_reader.get_embedding(idx)
        return tensor, label, torch.from_numpy(teacher_emb), path


def query_nvidia_smi():
    try:
        cmd = ["nvidia-smi", "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        parts = [int(p.strip()) for p in res.stdout.strip().split(",")]
        return {
            "gpu_util_pct": parts[0],
            "gpu_temp_c": parts[1],
            "gpu_mem_used_mb": parts[2],
            "gpu_mem_total_mb": parts[3]
        }
    except Exception:
        return {"gpu_util_pct": 0, "gpu_temp_c": 0, "gpu_mem_used_mb": 0, "gpu_mem_total_mb": 0}


def run_profiler(
    mode="baseline",
    num_steps=50,
    warmup_steps=10,
    p_identities=16,
    k_images=4,
    num_workers=0,
    pin_memory=True,
    non_blocking=False,
    output_json=None
):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if device.type == "cuda":
        torch.set_float32_matmul_precision("high")
    print("=" * 80)
    print(f"[*] OmniFace V2 Step Profiler -- Mode: {mode.upper()}")
    print(f"[*] Device: {torch.cuda.get_device_name(0)} (VRAM: {torch.cuda.get_device_properties(0).total_memory / (1024**3):.2f} GB)")
    print(f"[*] Batch Size: {p_identities * k_images} ({p_identities} IDs x {k_images} samples)")
    print(f"[*] Profiling Steps: {num_steps} (Warmup: {warmup_steps})")
    print("=" * 80)

    # 1. Dataset & Sampler
    print("[*] Loading WebFace dataset...")
    train_dataset = WebFaceDataset(split="train", train_class_count=8000)

    if mode == "optimized":
        print("[*] Initializing offline CavaFace cache reader...")
        cache_reader = CavaFaceShardedCacheReader("training/unified/data_cache/cavaface_shards")
        train_dataset = CachedWebFaceDataset(train_dataset, cache_reader)

    pk_sampler = IdentityPKSampler(
        labels=train_dataset.labels,
        p_identities=p_identities,
        k_images=k_images,
        max_batches=(num_steps + warmup_steps + 10)
    )
    train_loader = DataLoader(
        train_dataset,
        batch_sampler=pk_sampler,
        num_workers=num_workers,
        pin_memory=(pin_memory and device.type == "cuda"),
        persistent_workers=(num_workers > 0)
    )

    # 2. NUAA Pad dataset
    nuaa_dataset = NUAAPadDataset(split="train")
    nuaa_loader = DataLoader(
        nuaa_dataset,
        batch_size=min(32, len(nuaa_dataset)),
        shuffle=True,
        num_workers=0,
        pin_memory=(pin_memory and device.type == "cuda")
    )
    nuaa_iter = iter(nuaa_loader)

    # 3. Model & Loss
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468).to(device)
    model.train()

    id_loss_fn = UnifiedIdentityLossV2(
        num_classes=train_dataset.num_classes,
        embedding_dim=512,
        sub_centers=3,
        scale=64.0,
        arc_margin=0.40,
        triplet_margin=0.20,
        lambda_distill=0.40,
        lambda_neg=0.20
    ).to(device)
    id_loss_fn.train()

    pad_ce_loss = nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(list(model.parameters()) + list(id_loss_fn.parameters()), lr=1e-4)
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    # 4. Teacher (Baseline mode only)
    cava_extractor = None
    if mode == "baseline" and os.path.exists("models_cache/cavaface.tflite"):
        print("[*] Initializing baseline CavaFace online CPU extractor...")
        cava_extractor = CavaFaceFeatureExtractor("models_cache/cavaface.tflite")

    # Metrics accumulators
    data_loading_times = []
    teacher_times = []
    h2d_times = []
    forward_times = []
    loss_times = []
    backward_times = []
    opt_times = []
    step_times = []

    # Reset peak memory
    torch.cuda.reset_peak_memory_stats(device)
    torch.cuda.synchronize()

    train_iter = iter(train_loader)
    print("\n[*] Starting execution loop...")

    # Events for GPU timing
    start_ev = torch.cuda.Event(enable_timing=True)
    h2d_done_ev = torch.cuda.Event(enable_timing=True)
    fwd_done_ev = torch.cuda.Event(enable_timing=True)
    loss_done_ev = torch.cuda.Event(enable_timing=True)
    bwd_done_ev = torch.cuda.Event(enable_timing=True)
    opt_done_ev = torch.cuda.Event(enable_timing=True)

    for step in range(1, num_steps + warmup_steps + 1):
        # 1. Measure Data Loading Wait Time
        t_data_start = time.perf_counter()
        try:
            train_batch = next(train_iter)
        except StopIteration:
            train_iter = iter(train_loader)
            train_batch = next(train_iter)

        try:
            nuaa_batch = next(nuaa_iter)
        except StopIteration:
            nuaa_iter = iter(nuaa_loader)
            nuaa_batch = next(nuaa_iter)
        t_data_end = time.perf_counter()
        data_loading_ms = (t_data_end - t_data_start) * 1000.0

        # 2. Teacher Handling
        t_teach_start = time.perf_counter()
        if mode == "optimized":
            batch_faces, batch_labels, batch_teacher_embs, batch_paths = train_batch
            teacher_embs = batch_teacher_embs.to(device, non_blocking=non_blocking)
            teacher_ms = 0.0
        else:
            batch_faces, batch_labels, batch_paths = train_batch
            teacher_embs = None
            if cava_extractor is not None and step % 2 == 0:
                try:
                    cava_list = []
                    for bp in batch_paths[:16]:
                        with Image.open(bp) as img:
                            cava_list.append(cava_extractor.extract_single(img))
                    t_embs = torch.from_numpy(np.array(cava_list)).to(device)
                    teacher_embs = torch.zeros((len(batch_faces), 512), device=device)
                    teacher_embs[:len(t_embs)] = t_embs
                except Exception:
                    teacher_embs = None
            t_teach_end = time.perf_counter()
            teacher_ms = (t_teach_end - t_teach_start) * 1000.0

        # 3. Step execution on GPU
        torch.cuda.synchronize()
        start_ev.record()

        # H2D
        batch_faces = batch_faces.to(device, non_blocking=non_blocking)
        batch_labels = batch_labels.to(device, non_blocking=non_blocking)
        nuaa_faces = nuaa_batch["face"].to(device, non_blocking=non_blocking)
        nuaa_pad_labels = nuaa_batch["pad_label"].to(device, non_blocking=non_blocking)
        h2d_done_ev.record()

        # Forward
        optimizer.zero_grad(set_to_none=True)
        with torch.amp.autocast("cuda", enabled=(device.type == "cuda")):
            id_preds = model(batch_faces)
            pad_preds = model(nuaa_faces)
        fwd_done_ev.record()

        # Loss
        with torch.amp.autocast("cuda", enabled=(device.type == "cuda")):
            loss_id, _ = id_loss_fn(id_preds["identity_embedding"], batch_labels, teacher_embs)
            loss_pad = pad_ce_loss(pad_preds["pad_logits"], nuaa_pad_labels)
            total_loss = loss_id + 0.30 * loss_pad
        loss_done_ev.record()

        # Backward
        scaler.scale(total_loss).backward()
        bwd_done_ev.record()

        # Optimizer
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(list(model.parameters()) + list(id_loss_fn.parameters()), 5.0)
        scaler.step(optimizer)
        scaler.update()
        opt_done_ev.record()

        torch.cuda.synchronize()

        # Calculate GPU latencies
        h2d_ms = start_ev.elapsed_time(h2d_done_ev)
        fwd_ms = h2d_done_ev.elapsed_time(fwd_done_ev)
        loss_ms = fwd_done_ev.elapsed_time(loss_done_ev)
        bwd_ms = loss_done_ev.elapsed_time(bwd_done_ev)
        opt_ms = bwd_done_ev.elapsed_time(opt_done_ev)
        gpu_step_ms = start_ev.elapsed_time(opt_done_ev)
        total_step_ms = data_loading_ms + teacher_ms + gpu_step_ms

        if step > warmup_steps:
            data_loading_times.append(data_loading_ms)
            teacher_times.append(teacher_ms)
            h2d_times.append(h2d_ms)
            forward_times.append(fwd_ms)
            loss_times.append(loss_ms)
            backward_times.append(bwd_ms)
            opt_times.append(opt_ms)
            step_times.append(total_step_ms)

            if (step - warmup_steps) % 10 == 0 or (step - warmup_steps) == num_steps:
                smi = query_nvidia_smi()
                throughput = (p_identities * k_images) / (total_step_ms / 1000.0)
                print(f"  [Step {step - warmup_steps:02d}/{num_steps}] Step: {total_step_ms:.1f}ms (Data: {data_loading_ms:.1f}ms, Teach: {teacher_ms:.1f}ms, GPU: {gpu_step_ms:.1f}ms) | {throughput:.1f} samp/s | GPU Util: {smi['gpu_util_pct']}% | Temp: {smi['gpu_temp_c']}C")

    # Summary statistics
    batch_size = p_identities * k_images
    mean_step_ms = float(np.mean(step_times))
    mean_data_ms = float(np.mean(data_loading_times))
    mean_teach_ms = float(np.mean(teacher_times))
    mean_h2d_ms = float(np.mean(h2d_times))
    mean_fwd_ms = float(np.mean(forward_times))
    mean_loss_ms = float(np.mean(loss_times))
    mean_bwd_ms = float(np.mean(backward_times))
    mean_opt_ms = float(np.mean(opt_times))
    mean_throughput = batch_size / (mean_step_ms / 1000.0)

    smi_final = query_nvidia_smi()
    allocated_mb = torch.cuda.memory_allocated() / (1024**2)
    max_allocated_mb = torch.cuda.max_memory_allocated() / (1024**2)
    reserved_mb = torch.cuda.memory_reserved() / (1024**2)
    cpu_util_pct = psutil.cpu_percent()

    gpu_compute_ms = mean_fwd_ms + mean_loss_ms + mean_bwd_ms + mean_opt_ms
    gpu_active_ratio = gpu_compute_ms / max(1e-6, mean_step_ms)
    dataloader_stall_pct = (mean_data_ms + mean_teach_ms) / max(1e-6, mean_step_ms) * 100.0

    results = {
        "mode": mode,
        "batch_size": batch_size,
        "p_identities": p_identities,
        "k_images": k_images,
        "num_workers": num_workers,
        "pin_memory": pin_memory,
        "non_blocking": non_blocking,
        "num_measured_steps": len(step_times),
        "mean_step_latency_ms": round(mean_step_ms, 2),
        "p50_step_latency_ms": round(float(np.median(step_times)), 2),
        "p95_step_latency_ms": round(float(np.percentile(step_times, 95)), 2),
        "throughput_samples_per_sec": round(mean_throughput, 2),
        "gpu_active_time_ratio": round(gpu_active_ratio, 4),
        "dataloader_stall_pct": round(dataloader_stall_pct, 2),
        "latency_breakdown_ms": {
            "data_loading_wait": round(mean_data_ms, 2),
            "teacher_cpu_inference": round(mean_teach_ms, 2),
            "host_to_device_h2d": round(mean_h2d_ms, 2),
            "forward_pass": round(mean_fwd_ms, 2),
            "loss_computation": round(mean_loss_ms, 2),
            "backward_pass": round(mean_bwd_ms, 2),
            "optimizer_step": round(mean_opt_ms, 2)
        },
        "percentage_breakdown": {
            "data_loading_pct": round((mean_data_ms / mean_step_ms) * 100, 1),
            "teacher_cpu_pct": round((mean_teach_ms / mean_step_ms) * 100, 1),
            "gpu_compute_pct": round((gpu_compute_ms / mean_step_ms) * 100, 1)
        },
        "telemetry": {
            "vram_allocated_mb": round(allocated_mb, 2),
            "vram_peak_allocated_mb": round(max_allocated_mb, 2),
            "vram_reserved_mb": round(reserved_mb, 2),
            "gpu_util_pct": smi_final["gpu_util_pct"],
            "gpu_temp_c": smi_final["gpu_temp_c"],
            "cpu_util_pct": cpu_util_pct
        }
    }

    if output_json:
        os.makedirs(os.path.dirname(output_json), exist_ok=True)
        with open(output_json, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2)
        print(f"[+] Profiler metrics saved to: {output_json}")

    print("\n" + "=" * 80)
    print(f" PROFILING RESULTS SUMMARY -- {mode.upper()}")
    print("=" * 80)
    print(f" Throughput:              {mean_throughput:.2f} samples/sec")
    print(f" Mean Step Latency:       {mean_step_ms:.2f} ms")
    print(f"   |-- Data Loading:       {mean_data_ms:.2f} ms ({results['percentage_breakdown']['data_loading_pct']}%)")
    print(f"   |-- Teacher CPU:        {mean_teach_ms:.2f} ms ({results['percentage_breakdown']['teacher_cpu_pct']}%)")
    print(f"   |-- Host-to-Device H2D: {mean_h2d_ms:.2f} ms")
    print(f"   |-- Forward Pass:       {mean_fwd_ms:.2f} ms")
    print(f"   |-- Loss Computation:   {mean_loss_ms:.2f} ms")
    print(f"   |-- Backward Pass:      {mean_bwd_ms:.2f} ms")
    print(f"   \\-- Optimizer Step:     {mean_opt_ms:.2f} ms")
    print(f" DataLoader Stall %:      {dataloader_stall_pct:.2f}%")
    print(f" GPU Active Time Ratio:   {gpu_active_ratio * 100:.2f}% ({gpu_compute_ms:.2f} ms / {mean_step_ms:.2f} ms)")
    print(f" GPU VRAM Peak:           {max_allocated_mb:.1f} MB / {smi_final['gpu_mem_total_mb']} MB")
    print(f" GPU Temperature:         {smi_final['gpu_temp_c']} C")
    print(f" GPU Core Utilization:    {smi_final['gpu_util_pct']}%")
    print("=" * 80)

    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="OmniFace V2 Step Profiler")
    parser.add_argument("--mode", type=str, default="baseline", choices=["baseline", "optimized"])
    parser.add_argument("--num_steps", type=int, default=50)
    parser.add_argument("--warmup_steps", type=int, default=10)
    parser.add_argument("--p_identities", type=int, default=16)
    parser.add_argument("--k_images", type=int, default=4)
    parser.add_argument("--num_workers", type=int, default=0)
    parser.add_argument("--pin_memory", action="store_true", default=True)
    parser.add_argument("--non_blocking", action="store_true", default=False)
    parser.add_argument("--output_json", type=str, default="training/unified/profiling/baseline_metrics.json")
    args = parser.parse_args()

    run_profiler(
        mode=args.mode,
        num_steps=args.num_steps,
        warmup_steps=args.warmup_steps,
        p_identities=args.p_identities,
        k_images=args.k_images,
        num_workers=args.num_workers,
        pin_memory=args.pin_memory,
        non_blocking=args.non_blocking,
        output_json=args.output_json
    )
