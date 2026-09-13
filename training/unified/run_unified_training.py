import os
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))
import time
import json
import math
import numpy as np
import torch
from typing import Dict, Any

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset, OpenSetPairGenerator
from training.unified.training.trainer import MultiTaskTrainer
from training.unified.configs.base_config import TrainingConfig, LossWeightsConfig
from training.unified.calibration.calibrator import UnifiedModelCalibrator
from training.unified.export.exporter import ModelExporter

def run_pipeline():
    print("=" * 70, flush=True)
    print(" OmniFace Unified Model V1 — Genuine Training & Empirical Evaluation", flush=True)
    print("=" * 70, flush=True)

    # 1. Device Selection
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[*] Compute Target: {device}", flush=True)
    if device.type == "cuda":
        print(f"    Device Name: {torch.cuda.get_device_name(0)}", flush=True)
        print(f"    Capability : {torch.cuda.get_device_capability(0)}", flush=True)
        print(f"    VRAM Total : {torch.cuda.get_device_properties(0).total_memory / (1024**3):.2f} GB", flush=True)

    # 2. Datasets
    cache_path = "training/unified/datasets/teacher_dataset_cache.pt"
    print(f"[*] Loading datasets (Cache: {cache_path})...", flush=True)
    train_ds = OmniFaceMultiTaskDataset(is_training=True, cache_path=cache_path)
    val_ds = OmniFaceMultiTaskDataset(is_training=False, cache_path=cache_path)
    print(f"[+] Train Samples: {len(train_ds)}, Val Samples: {len(val_ds)}, Identities: {train_ds.num_identities}", flush=True)

    # 3. Model Construction
    print("[*] Initializing MobileNetV4-Conv-Small Unified Multi-Task Architecture...", flush=True)
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468)
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"[+] Total Parameters: {total_params:,} ({total_params * 4 / (1024*1024):.2f} MB FP32)", flush=True)

    # 4. Trainer Initialization
    config = TrainingConfig(
        total_epochs=25,
        batch_size=32 if device.type == "cuda" else 8,
        learning_rate=1e-3,
        weight_decay=1e-4,
        gradient_clip_val=1.0
    )
    loss_weights = LossWeightsConfig(
        lambda_id=2.0,
        lambda_pad=1.0
    )
    
    trainer = MultiTaskTrainer(
        model=model,
        train_dataset=train_ds,
        val_dataset=val_ds,
        config=config,
        loss_weights=loss_weights,
        device=device,
        checkpoint_dir="training/unified/checkpoints"
    )

    # 5. Execute Training Loop
    t_start = time.time()
    results = trainer.run_training_cycle(epochs=config.total_epochs, batch_size=config.batch_size)
    t_end = time.time()
    print(f"[+] Training completed in {t_end - t_start:.2f} seconds.", flush=True)

    # 6. Load Best Checkpoint for Rigorous Security Evaluation
    ckpt_path = "training/unified/checkpoints/best_unified_model_v1.pt"
    if os.path.exists(ckpt_path):
        ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
        model.load_state_dict(ckpt["model_state_dict"])
        print(f"[+] Loaded Best Checkpoint from epoch {ckpt['epoch']} (Score: {ckpt['score']:.4f})", flush=True)

    # 7. Rigorous Biometric Verification Benchmark
    print("\n" + "=" * 70, flush=True)
    print(" Phase: ISO/IEC Biometric Verification & FAR Calibration", flush=True)
    print("=" * 70, flush=True)
    model.eval()

    # Extract all validation embeddings
    val_embeddings = []
    cava_embeddings = []
    print("[*] Extracting embeddings on validation set...", flush=True)
    batch_size = 64
    with torch.no_grad():
        for i in range(0, len(val_ds), batch_size):
            chunk = [val_ds[j] for j in range(i, min(i + batch_size, len(val_ds)))]
            faces = torch.stack([item[0] for item in chunk]).to(device)
            out = model(faces)
            embs = out["identity_embedding"].cpu().numpy()
            val_embeddings.extend(embs)
            for item in chunk:
                targets = item[1]
                if "teacher_emb" in targets and targets["teacher_emb"] is not None:
                    cava_embeddings.append(targets["teacher_emb"].numpy())

    val_embeddings = np.array(val_embeddings)
    has_cava = len(cava_embeddings) == len(val_embeddings)
    if has_cava:
        cava_embeddings = np.array(cava_embeddings)
        # Compute Cosine Agreement between Unified Model and CavaFace
        cava_agreement_sims = np.sum(val_embeddings * cava_embeddings, axis=-1)
        mean_cava_sim = float(np.mean(cava_agreement_sims))
        print(f"[+] Unified Student vs CavaFace Teacher Mean Cosine Agreement: {mean_cava_sim:.4f}", flush=True)

    # Generate genuine and impostor pairs
    pair_gen = OpenSetPairGenerator(val_ds)
    num_gen = min(100, len(val_ds))
    num_imp = min(500, len(val_ds) * 4)
    gen_pairs, imp_pairs = pair_gen.generate_pairs(num_genuine=num_gen, num_impostor=num_imp, seed=42)

    gen_dists = np.array([max(0.0, 1.0 - float(np.dot(val_embeddings[p1], val_embeddings[p2]))) for p1, p2 in gen_pairs])
    imp_dists = np.array([max(0.0, 1.0 - float(np.dot(val_embeddings[p1], val_embeddings[p2]))) for p1, p2 in imp_pairs])

    mu_gen, std_gen = float(np.mean(gen_dists)), float(np.std(gen_dists))
    mu_imp, std_imp = float(np.mean(imp_dists)), float(np.std(imp_dists))
    d_prime = (mu_imp - mu_gen) / max(1e-6, math.sqrt(0.5 * (std_gen**2 + std_imp**2)))

    # Compute TAR @ FAR thresholds
    sorted_imp = np.sort(imp_dists)
    idx_far_1pct = max(0, int(0.01 * len(sorted_imp)))
    tau_far_1pct = float(sorted_imp[idx_far_1pct])
    tar_at_far_1pct = float(np.mean(gen_dists <= tau_far_1pct))

    idx_far_01pct = max(0, int(0.001 * len(sorted_imp)))
    tau_far_01pct = float(sorted_imp[idx_far_01pct])
    tar_at_far_01pct = float(np.mean(gen_dists <= tau_far_01pct))

    print(f"[+] Genuine Distance Distribution : μ = {mu_gen:.4f}, σ = {std_gen:.4f}", flush=True)
    print(f"[+] Impostor Distance Distribution: μ = {mu_imp:.4f}, σ = {std_imp:.4f}", flush=True)
    print(f"[+] Biometric Separation (d-prime): {d_prime:.3f}", flush=True)
    print(f"[+] TAR @ FAR 1.0% (τ = {tau_far_1pct:.4f}): {tar_at_far_1pct * 100:.2f}%", flush=True)
    print(f"[+] TAR @ FAR 0.1% (τ = {tau_far_01pct:.4f}): {tar_at_far_01pct * 100:.2f}%", flush=True)

    # 8. Calibration
    calibrator = UnifiedModelCalibrator()
    calib = calibrator.calibrate_from_distributions(gen_dists, imp_dists)
    print(f"\n[*] Calibrated Decision Gates for UnifiedModelV1:", flush=True)
    print(f"    STANDARD (1 in 10 FAR)  : τ = {calib.tau_standard:.4f}", flush=True)
    print(f"    HIGH     (1 in 100 FAR) : τ = {calib.tau_high:.4f}", flush=True)
    print(f"    STRICT   (1 in 1000 FAR): τ = {calib.tau_strict:.4f}", flush=True)
    print(f"    Track-Swap Gate         : τ = {calib.track_swap_threshold:.4f}", flush=True)

    # 9. Model Export
    print("\n" + "=" * 70, flush=True)
    print(" Phase: ONNX & LiteRT Single-Graph Export", flush=True)
    print("=" * 70, flush=True)
    exporter = ModelExporter(model)
    onnx_path = "models_cache/unified_face_v1.onnx"
    exporter.export_onnx(onnx_path, opset_version=18)
    print(f"[+] Exported Single-Graph ONNX: {onnx_path} ({os.path.getsize(onnx_path)/(1024*1024):.2f} MB)", flush=True)

    # 10. Summary Report
    eval_summary = {
        "model_name": "OmniFaceUnifiedModelV1",
        "architecture": "MobileNetV4-Conv-Small (Shared 512-ch Feature Map)",
        "backbone_parameters": total_params,
        "compute_device": f"{device.type.upper()} ({torch.cuda.get_device_name(0) if device.type=='cuda' else 'CPU'})",
        "training_time_sec": t_end - t_start,
        "epochs": config.total_epochs,
        "best_epoch": ckpt.get("epoch", config.total_epochs) if os.path.exists(ckpt_path) else config.total_epochs,
        "biometric_metrics": {
            "genuine_mean": mu_gen,
            "genuine_std": std_gen,
            "impostor_mean": mu_imp,
            "impostor_std": std_imp,
            "d_prime": d_prime,
            "tar_at_far_1pct": tar_at_far_1pct,
            "tar_at_far_01pct": tar_at_far_01pct,
            "cavaface_cosine_agreement": mean_cava_sim if has_cava else None
        },
        "calibrated_thresholds": {
            "tau_standard": calib.tau_standard,
            "tau_high": calib.tau_high,
            "tau_strict": calib.tau_strict,
            "track_swap_threshold": calib.track_swap_threshold
        }
    }
    report_path = "docs/UNIFIED_MODEL_EMPIRICAL_EVALUATION.json"
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(eval_summary, f, indent=2)
    print(f"[+] Empirical Evaluation Report Written: {report_path}", flush=True)

    return eval_summary

if __name__ == "__main__":
    run_pipeline()
