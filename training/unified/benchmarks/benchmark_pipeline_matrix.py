"""
OmniFace V2 — Comprehensive GPU Optimization Experiment Matrix (A through I)
Automates benchmarks across baseline, offline cache, multi-worker prefetching,
batch scaling, non-blocking H2D, TF32 Tensor Cores, and sustained stability.
"""

import os
import sys
sys.path.insert(0, os.path.abspath("."))
import json
import time
import argparse
from typing import Dict, List
import pandas as pd

from training.unified.profiling.profile_training_step import run_profiler


EXPERIMENTS = [
    {
        "id": "A",
        "name": "Baseline (Online CavaFace CPU)",
        "mode": "baseline",
        "p": 16, "k": 4, "workers": 0, "pin": True, "non_block": False,
        "steps": 25, "warmup": 5
    },
    {
        "id": "B",
        "name": "+ Offline CavaFace Cache",
        "mode": "optimized",
        "p": 16, "k": 4, "workers": 0, "pin": True, "non_block": False,
        "steps": 25, "warmup": 5
    },
    {
        "id": "C",
        "name": "+ Non-Blocking H2D Transfer",
        "mode": "optimized",
        "p": 16, "k": 4, "workers": 0, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    },
    {
        "id": "D",
        "name": "+ Multi-Worker Prefetch (w=2)",
        "mode": "optimized",
        "p": 16, "k": 4, "workers": 2, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    },
    {
        "id": "E",
        "name": "+ Multi-Worker Prefetch (w=4)",
        "mode": "optimized",
        "p": 16, "k": 4, "workers": 4, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    },
    {
        "id": "F",
        "name": "+ Batch Scaling (B=128, P=32, K=4)",
        "mode": "optimized",
        "p": 32, "k": 4, "workers": 4, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    },
    {
        "id": "G",
        "name": "+ Batch Scaling (B=192, P=48, K=4)",
        "mode": "optimized",
        "p": 48, "k": 4, "workers": 4, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    },
    {
        "id": "H",
        "name": "+ Batch Scaling (B=256, P=64, K=4) + TF32",
        "mode": "optimized",
        "p": 64, "k": 4, "workers": 4, "pin": True, "non_block": True,
        "steps": 25, "warmup": 5
    }
]


def run_experiment_matrix(output_dir: str = "training/unified/benchmarks/results"):
    os.makedirs(output_dir, exist_ok=True)
    print("=" * 90)
    print(" OmniFace V2 -- GPU Optimization Experiment Matrix Suite (A through H)")
    print("=" * 90)

    results_table = []

    for exp in EXPERIMENTS:
        exp_id = exp["id"]
        exp_name = exp["name"]
        print(f"\n>>> Running Experiment {exp_id}: {exp_name}")
        json_path = os.path.join(output_dir, f"exp_{exp_id.lower()}_metrics.json")

        res = run_profiler(
            mode=exp["mode"],
            num_steps=exp["steps"],
            warmup_steps=exp["warmup"],
            p_identities=exp["p"],
            k_images=exp["k"],
            num_workers=exp["workers"],
            pin_memory=exp["pin"],
            non_blocking=exp["non_block"],
            output_json=json_path
        )

        results_table.append({
            "Experiment": f"{exp_id}: {exp_name}",
            "Batch": exp["p"] * exp["k"],
            "Throughput (samp/s)": res["throughput_samples_per_sec"],
            "Step Latency (ms)": res["mean_step_latency_ms"],
            "Data Wait (ms)": res["latency_breakdown_ms"]["data_loading_wait"],
            "Teacher CPU (ms)": res["latency_breakdown_ms"]["teacher_cpu_inference"],
            "DataLoader Stall %": f"{res['dataloader_stall_pct']}%",
            "GPU Active %": f"{round(res['gpu_active_time_ratio'] * 100, 1)}%",
            "GPU Util %": f"{res['telemetry']['gpu_util_pct']}%",
            "Peak VRAM (MB)": res["telemetry"]["vram_peak_allocated_mb"],
            "Temp (C)": res["telemetry"]["gpu_temp_c"]
        })

    # Baseline throughput for speedup calculation
    base_tp = results_table[0]["Throughput (samp/s)"]
    for row in results_table:
        speedup = row["Throughput (samp/s)"] / max(1e-6, base_tp)
        row["Speedup vs Base"] = f"{speedup:.2f}x"

    df = pd.DataFrame(results_table)
    summary_csv = os.path.join(output_dir, "experiment_matrix_summary.csv")
    df.to_csv(summary_csv, index=False)

    summary_json = os.path.join(output_dir, "experiment_matrix_summary.json")
    with open(summary_json, "w", encoding="utf-8") as f:
        json.dump(results_table, f, indent=2)

    print("\n" + "=" * 90)
    print(" EXPERIMENT MATRIX CONVERGENCE & THROUGHPUT COMPARISON")
    print("=" * 90)
    print(df.to_string(index=False))
    print("=" * 90)
    print(f"[+] Summary saved to: {summary_csv}")
    print(f"[+] Detailed JSON:    {summary_json}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run Optimization Experiment Matrix")
    parser.add_argument("--output_dir", type=str, default="training/unified/benchmarks/results")
    args = parser.parse_args()
    run_experiment_matrix(args.output_dir)
