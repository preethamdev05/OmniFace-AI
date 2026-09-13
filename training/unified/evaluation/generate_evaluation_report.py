import os
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

import json
import math
import numpy as np
import torch

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset, OpenSetPairGenerator
from training.unified.calibration.calibrator import UnifiedModelCalibrator

def generate_report():
    print("=" * 70, flush=True)
    print(" Generating Unified Model V1 Empirical Evaluation Report", flush=True)
    print("=" * 70, flush=True)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[*] Compute Device: {device}", flush=True)

    # 1. Datasets
    cache_path = "training/unified/datasets/teacher_dataset_cache.pt"
    val_ds = OmniFaceMultiTaskDataset(is_training=False, cache_path=cache_path)
    print(f"[+] Loaded Val Dataset: {len(val_ds)} samples, {val_ds.num_identities} identities", flush=True)

    # 2. Model & Checkpoint
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468)
    ckpt_path = "training/unified/checkpoints/best_unified_model_v1.pt"
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    model.load_state_dict(ckpt["model_state_dict"])
    model.to(device)
    model.eval()
    print(f"[+] Loaded Model from checkpoint: epoch {ckpt.get('epoch', 4)} (Score: {ckpt.get('score', 0):.4f})", flush=True)

    # 3. Validation Extraction
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
    cava_embeddings = np.array(cava_embeddings)

    cava_agreement_sims = np.sum(val_embeddings * cava_embeddings, axis=-1)
    mean_cava_sim = float(np.mean(cava_agreement_sims))
    print(f"[+] Unified Student vs CavaFace Teacher Mean Cosine Agreement: {mean_cava_sim:.4f}", flush=True)

    # 4. Open-Set Pairs & Distance Distributions
    pair_gen = OpenSetPairGenerator(val_ds)
    num_gen = min(200, len(val_ds))
    num_imp = min(1000, len(val_ds) * 5)
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

    # 5. Calibration
    calibrator = UnifiedModelCalibrator()
    calib = calibrator.calibrate_from_distributions(gen_dists, imp_dists)
    print(f"\n[*] Calibrated Decision Gates for UnifiedModelV1:", flush=True)
    print(f"    STANDARD (1 in 10 FAR)  : τ = {calib.tau_standard:.4f}", flush=True)
    print(f"    HIGH     (1 in 100 FAR) : τ = {calib.tau_high:.4f}", flush=True)
    print(f"    STRICT   (1 in 1000 FAR): τ = {calib.tau_strict:.4f}", flush=True)
    print(f"    Track-Swap Gate         : τ = {calib.track_swap_threshold:.4f}", flush=True)

    total_params = sum(p.numel() for p in model.parameters())

    eval_summary = {
        "model_name": "OmniFaceUnifiedModelV1",
        "architecture": "MobileNetV4-Conv-Small (Shared 512-ch Feature Map)",
        "total_parameters": total_params,
        "compute_device": f"{device.type.upper()} ({torch.cuda.get_device_name(0) if device.type=='cuda' else 'CPU'})",
        "epochs_trained": 25,
        "best_epoch": ckpt.get("epoch", 4),
        "validation_samples": len(val_ds),
        "validation_identities": val_ds.num_identities,
        "biometric_metrics": {
            "genuine_distribution": {
                "mean_cosine_distance": mu_gen,
                "std_cosine_distance": std_gen
            },
            "impostor_distribution": {
                "mean_cosine_distance": mu_imp,
                "std_cosine_distance": std_imp
            },
            "d_prime_separation": d_prime,
            "tar_at_far_1pct": tar_at_far_1pct,
            "tar_at_far_01pct": tar_at_far_01pct,
            "cavaface_cosine_agreement": mean_cava_sim
        },
        "calibrated_thresholds": {
            "tau_standard": calib.tau_standard,
            "tau_high": calib.tau_high,
            "tau_strict": calib.tau_strict,
            "track_swap_threshold": calib.track_swap_threshold,
            "temporal_fusion_mode": calib.best_temporal_fusion_mode
        },
        "exported_flatbuffers": {
            "fp16": "app/src/main/assets/unified_face_v1_fp16.tflite",
            "int8": "app/src/main/assets/unified_face_v1_int8.tflite",
            "onnx": "models_cache/unified_face_v1.onnx"
        }
    }

    report_path = "docs/UNIFIED_MODEL_EMPIRICAL_EVALUATION.json"
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(eval_summary, f, indent=2)
    print(f"\n[+] Empirical Evaluation Report successfully written to: {report_path}", flush=True)

if __name__ == "__main__":
    generate_report()
