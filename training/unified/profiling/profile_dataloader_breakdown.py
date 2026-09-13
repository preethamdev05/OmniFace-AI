import os
import sys
sys.path.insert(0, os.path.abspath("."))
import io
import time
import json
import numpy as np
from PIL import Image
import torch

from training.unified.datasets.webface_lfw_dataset import WebFaceDataset
from training.unified.data_cache.build_cavaface_cache import CavaFaceShardedCacheReader


def profile_sample_pipeline(dataset: WebFaceDataset, cache_reader: CavaFaceShardedCacheReader, num_samples: int = 1000):
    print("=" * 80)
    print(f"[*] Dissecting Per-Sample CPU Pipeline Across {num_samples} Images...")
    print("=" * 80)

    io_times = []
    decode_times = []
    resize_times = []
    norm_times = []
    tensor_times = []
    cache_times = []

    # Warmup
    for i in range(min(50, len(dataset))):
        _ = dataset[i]

    for idx in range(min(num_samples, len(dataset))):
        path, label = dataset.samples[idx]

        # 1. Raw Disk I/O
        t0 = time.perf_counter_ns()
        with open(path, "rb") as f:
            raw_bytes = f.read()
        t1 = time.perf_counter_ns()
        io_times.append((t1 - t0) / 1e6)

        # 2. JPEG Decompression
        t2 = time.perf_counter_ns()
        bio = io.BytesIO(raw_bytes)
        img = Image.open(bio)
        img_rgb = img.convert("RGB")
        t3 = time.perf_counter_ns()
        decode_times.append((t3 - t2) / 1e6)

        # 3. Resize if needed
        t4 = time.perf_counter_ns()
        if img_rgb.size != (112, 112):
            img_rgb = img_rgb.resize((112, 112), Image.Resampling.BILINEAR)
        t5 = time.perf_counter_ns()
        resize_times.append((t5 - t4) / 1e6)

        # 4. NumPy Normalization
        t6 = time.perf_counter_ns()
        arr = (np.array(img_rgb, dtype=np.float32) - 127.5) / 128.0
        t7 = time.perf_counter_ns()
        norm_times.append((t7 - t6) / 1e6)

        # 5. Tensor Transpose & Conversion
        t8 = time.perf_counter_ns()
        tensor = torch.from_numpy(arr.transpose(2, 0, 1))
        t9 = time.perf_counter_ns()
        tensor_times.append((t9 - t8) / 1e6)

        # 6. Cache reader lookup
        t10 = time.perf_counter_ns()
        _ = cache_reader.get_embedding(idx)
        t11 = time.perf_counter_ns()
        cache_times.append((t11 - t10) / 1e6)

    stats = {
        "raw_disk_io_ms": float(np.mean(io_times)),
        "jpeg_decode_ms": float(np.mean(decode_times)),
        "resize_ms": float(np.mean(resize_times)),
        "numpy_norm_ms": float(np.mean(norm_times)),
        "tensor_conv_ms": float(np.mean(tensor_times)),
        "cache_lookup_ms": float(np.mean(cache_times)),
    }
    stats["total_per_sample_cpu_ms"] = float(sum(stats.values()))
    
    total = stats["total_per_sample_cpu_ms"]
    stats["percentages"] = {k: round((v / total) * 100, 2) for k, v in stats.items() if k != "total_per_sample_cpu_ms"}

    return stats


def profile_batch_collation(batch_size: int = 256, num_batches: int = 30):
    print("=" * 80)
    print(f"[*] Profiling Batch Collation & Pinned Memory Staging (Batch Size: {batch_size})...")
    print("=" * 80)

    sample_tensors = [torch.randn(3, 112, 112, dtype=torch.float32) for _ in range(batch_size)]
    sample_embs = [torch.randn(512, dtype=torch.float16) for _ in range(batch_size)]
    sample_labels = [i % 64 for i in range(batch_size)]

    collate_times = []
    pin_times = []

    for _ in range(num_batches):
        t0 = time.perf_counter_ns()
        batch_images = torch.stack(sample_tensors, dim=0)
        batch_embs = torch.stack(sample_embs, dim=0)
        batch_labels_t = torch.tensor(sample_labels, dtype=torch.long)
        t1 = time.perf_counter_ns()
        collate_times.append((t1 - t0) / 1e6)

        t2 = time.perf_counter_ns()
        if torch.cuda.is_available():
            batch_images_pin = batch_images.pin_memory()
            batch_embs_pin = batch_embs.pin_memory()
            batch_labels_pin = batch_labels_t.pin_memory()
        t3 = time.perf_counter_ns()
        pin_times.append((t3 - t2) / 1e6)

    return {
        "collate_ms": float(np.mean(collate_times)),
        "pin_memory_ms": float(np.mean(pin_times)),
        "batch_size": batch_size
    }


def main():
    print("[*] Initializing WebFace dataset and offline cache reader...")
    base_ds = WebFaceDataset(split="train", train_class_count=8000)
    cache_reader = CavaFaceShardedCacheReader("training/unified/data_cache/cavaface_shards")

    sample_stats = profile_sample_pipeline(base_ds, cache_reader, num_samples=1000)
    batch_stats = profile_batch_collation(batch_size=256, num_batches=30)

    print("\n" + "=" * 80)
    print(" PER-SAMPLE CPU PIPELINE TIMING BREAKDOWN")
    print("=" * 80)
    for k, v in sample_stats.items():
        if k != "percentages":
            pct = sample_stats["percentages"].get(k, 100.0)
            print(f"  • {k:<25}: {v:8.4f} ms  ({pct:5.1f}%)")

    print("\n" + "=" * 80)
    print(" BATCH-LEVEL STAGING BREAKDOWN (Batch 256)")
    print("=" * 80)
    print(f"  • Collate / Stack 256 samples   : {batch_stats['collate_ms']:8.4f} ms")
    print(f"  • Pin Memory (Host DMA Staging) : {batch_stats['pin_memory_ms']:8.4f} ms")

    total_sample_cpu = sample_stats["total_per_sample_cpu_ms"]
    raw_256_cpu_ms = total_sample_cpu * 256
    worker_4_cpu_ms = raw_256_cpu_ms / 4.0

    print("\n" + "=" * 80)
    print(" BATCH 256 THEORETICAL & MEASURED WAIT TIME PROJECTION")
    print("=" * 80)
    print(f"  • Single-thread CPU time for 256 images : {raw_256_cpu_ms:8.2f} ms")
    print(f"  • 4 DataLoader workers theoretical ideal : {worker_4_cpu_ms:8.2f} ms")
    print(f"  • Staging (Collate + Pin)                : {batch_stats['collate_ms'] + batch_stats['pin_memory_ms']:8.2f} ms")

    os.makedirs("training/unified/profiling", exist_ok=True)
    out_file = "training/unified/profiling/dataloader_stall_breakdown.json"
    with open(out_file, "w") as f:
        json.dump({
            "sample_breakdown": sample_stats,
            "batch_staging": batch_stats,
            "projection": {
                "single_thread_256_ms": raw_256_cpu_ms,
                "four_workers_ideal_ms": worker_4_cpu_ms,
                "staging_ms": batch_stats["collate_ms"] + batch_stats["pin_memory_ms"]
            }
        }, f, indent=2)

    print(f"\n[+] Breakdown saved to: {out_file}")


if __name__ == "__main__":
    main()
