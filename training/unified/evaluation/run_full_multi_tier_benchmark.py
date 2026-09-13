import os
import sys
import json
import time
import math
from typing import Dict, List, Any

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

import numpy as np
import torch
from PIL import Image

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.evaluation.evaluate_v2_comprehensive import ComprehensiveV2Evaluator
from training.unified.evaluation.lfw_evaluator import LFWEvaluator
from training.unified.datasets.webface_lfw_dataset import WebFaceDataset

def run_comprehensive_benchmark():
    print("=" * 80)
    print(" OmniFace UnifiedFaceModel V2 — Full Multi-Tier Benchmark Suite")
    print("=" * 80)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[*] Evaluation Device: {device.type.upper()}", flush=True)

    # 1. Load V2 Model Checkpoint
    v2_ckpt_path = "training/unified/checkpoints/best_unified_model_v2.pt"
    if not os.path.exists(v2_ckpt_path):
        v2_ckpt_path = "training/unified/checkpoints/last_unified_model_v2.pt"
    if not os.path.exists(v2_ckpt_path):
        raise FileNotFoundError(f"V2 checkpoint not found at: {v2_ckpt_path}")

    print(f"[*] Loading V2 Model: {v2_ckpt_path}...", flush=True)
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468).to(device)
    ckpt = torch.load(v2_ckpt_path, map_location=device)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()
    print("[+] Model loaded successfully.", flush=True)

    evaluator = ComprehensiveV2Evaluator(device=device)

    # 2. Tier A: Closed-Set Generalization (Held-out images from training identities)
    print("\n--- Tier A: Closed-Set Generalization (Training Identities) ---", flush=True)
    train_ds = WebFaceDataset(split="train", max_images_per_class=10)
    tier_a_samples = []
    # Collect 3 images each from 50 training identities
    train_id_map = {}
    for p, lbl in train_ds.samples:
        if lbl not in train_id_map:
            train_id_map[lbl] = []
        if len(train_id_map[lbl]) < 3:
            train_id_map[lbl].append((p, lbl))
        if len(train_id_map) >= 50 and all(len(v) >= 3 for v in train_id_map.values()):
            break
    for v in train_id_map.values():
        tier_a_samples.extend(v)

    tier_a_faces = []
    tier_a_labels = []
    for p, lbl in tier_a_samples:
        with Image.open(p) as img:
            img = img.convert("RGB").resize((112, 112), Image.Resampling.BILINEAR)
            arr = (np.array(img, dtype=np.float32) - 127.5) / 128.0
            tier_a_faces.append(torch.from_numpy(arr.transpose(2, 0, 1)))
            tier_a_labels.append(lbl)
    tier_a_embs = evaluator._extract_embs(model, tier_a_faces)
    tier_a_res = evaluator.evaluate_identity_pairs(tier_a_embs, np.array(tier_a_labels), num_gen=150, num_imp=1000)
    print(f"  [Tier A] TAR@1% FAR : {tier_a_res['tar_at_far_1pct']*100:.2f}% | d': {tier_a_res['d_prime']:.3f}")
    print(f"  [Tier A] Genuine    : {tier_a_res['genuine_mean']:.4f} ± {tier_a_res['genuine_std']:.4f}")
    print(f"  [Tier A] Impostor   : {tier_a_res['impostor_mean']:.4f} ± {tier_a_res['impostor_std']:.4f}")

    # 3. Tier B: True Zero-Shot Open-Set Generalization (Test Identities from Manifest)
    print("\n--- Tier B: True Zero-Shot Open-Set Generalization (Unseen Identities) ---", flush=True)
    with open("training/unified/datasets/dataset_manifest_v2.json", "r", encoding="utf-8") as f:
        manifest = json.load(f)
    test_ids = manifest["identity_splits"]["test_identities"]
    root_dir = manifest["root_directory"]
    
    tier_b_faces = []
    tier_b_labels = []
    multishot_dict: Dict[int, List[torch.Tensor]] = {}
    
    # Sample 4 images each from 150 test identities
    test_count = 0
    for uid_idx, tid in enumerate(test_ids):
        folder = os.path.join(root_dir, tid)
        imgs = sorted([os.path.join(folder, f) for f in os.listdir(folder) if f.lower().endswith((".jpg", ".png"))])
        if len(imgs) >= 4:
            multishot_dict[uid_idx] = []
            for ip in imgs[:4]:
                with Image.open(ip) as img:
                    img = img.convert("RGB").resize((112, 112), Image.Resampling.BILINEAR)
                    arr = (np.array(img, dtype=np.float32) - 127.5) / 128.0
                    tensor = torch.from_numpy(arr.transpose(2, 0, 1))
                    tier_b_faces.append(tensor)
                    tier_b_labels.append(uid_idx)
                    multishot_dict[uid_idx].append(tensor)
            test_count += 1
            if test_count >= 150:
                break

    tier_b_embs = evaluator._extract_embs(model, tier_b_faces)
    tier_b_res = evaluator.evaluate_identity_pairs(tier_b_embs, np.array(tier_b_labels), num_gen=900, num_imp=5000)
    print(f"  [Tier B] TAR@1% FAR : {tier_b_res['tar_at_far_1pct']*100:.2f}% | TAR@0.1%: {tier_b_res['tar_at_far_01pct']*100:.2f}% | d': {tier_b_res['d_prime']:.3f}")
    print(f"  [Tier B] Genuine    : {tier_b_res['genuine_mean']:.4f} ± {tier_b_res['genuine_std']:.4f}")
    print(f"  [Tier B] Impostor   : {tier_b_res['impostor_mean']:.4f} ± {tier_b_res['impostor_std']:.4f}")
    print(f"  [Tier B] EER        : {tier_b_res['eer']*100:.2f}% (Threshold: {tier_b_res['eer_threshold']:.4f})")

    # 4. Tier C: Official LFW 6,000-Pair 10-Fold Benchmark
    print("\n--- Tier C: Official LFW 6,000-Pair Benchmark (10-Fold CV) ---", flush=True)
    lfw_res = evaluator.lfw_evaluator.evaluate(model, device=device, batch_size=64)
    print(f"  [Tier C] LFW Accuracy: {lfw_res['lfw_accuracy_mean']*100:.2f}% ± {lfw_res['lfw_accuracy_std']*100:.2f}%")
    print(f"  [Tier C] TAR@1% FAR  : {lfw_res['tar_at_far_1pct']*100:.2f}% | TAR@0.1%: {lfw_res['tar_at_far_01pct']*100:.2f}% | d': {lfw_res['d_prime']:.3f}")
    print(f"  [Tier C] Genuine     : {lfw_res['genuine_mean']:.4f} ± {lfw_res['genuine_std']:.4f}")
    print(f"  [Tier C] Impostor    : {lfw_res['impostor_mean']:.4f} ± {lfw_res['impostor_std']:.4f}")

    # 5. PAD Defense: NUAA Test Benchmark (Genuine vs Print Attacks)
    print("\n--- PAD Presentation Attack Defense (NUAA Test Set) ---", flush=True)
    pad_res = evaluator.evaluate_pad(model, max_samples=2000)
    print(f"  [PAD] APCER : {pad_res['apcer']*100:.2f}% (Attacks misclassified as live)")
    print(f"  [PAD] BPCER : {pad_res['bpcer']*100:.2f}% (Live faces misclassified as attacks)")
    print(f"  [PAD] ACER  : {pad_res['acer']*100:.2f}% (Average Classification Error Rate)")

    # 6. Multi-Shot Registration Benchmark (1, 3, 5, 10 shots)
    print("\n--- Multi-Shot Registration Benchmark ---", flush=True)
    mshot_res = evaluator.evaluate_multishot_registration(model, multishot_dict, shots=[1, 3, 5, 10])
    for s_key, s_data in mshot_res.items():
        print(f"  [{s_key}] Probe Similarity: {s_data['mean_probe_similarity']:.4f} ± {s_data['std_probe_similarity']:.4f} (min: {s_data['min_probe_similarity']:.4f})")

    # 6b. Unknown Visitor Rejection Audit
    print("\n--- Unknown Visitor Rejection Audit ---", flush=True)
    # Evaluate probes from unseen identities against centroids of other identities
    unknown_max_sims = []
    threshold_1pct = tier_b_res["tau_far_1pct"]
    for uid, img_list in multishot_dict.items():
        if len(img_list) >= 2 and uid + 1 in multishot_dict:
            # Centroid of uid
            with torch.no_grad():
                c_emb = model(torch.stack(img_list[:3]).to(device))["identity_embedding"].cpu().numpy()
            c_vec = np.mean(c_emb, axis=0)
            c_vec /= max(1e-6, np.linalg.norm(c_vec))
            # Unknown probe from (uid+1)
            with torch.no_grad():
                u_emb = model(torch.stack(multishot_dict[uid+1][:1]).to(device))["identity_embedding"].cpu().numpy()[0]
            u_emb /= max(1e-6, np.linalg.norm(u_emb))
            unknown_max_sims.append(float(np.dot(c_vec, u_emb)))
    unknown_rejection_rate = float(np.mean(np.array(unknown_max_sims) < threshold_1pct))
    print(f"  [Unknown Rejection] Rate @ 1% FAR Threshold ({threshold_1pct:.4f}): {unknown_rejection_rate*100:.2f}% (Mean Unknown Sim: {np.mean(unknown_max_sims):.4f} ± {np.std(unknown_max_sims):.4f})")

    # 6c. LFW with Test-Time Augmentation (Horizontal Flip TTA)
    print("\n--- Tier C: LFW with Horizontal Flip TTA ---", flush=True)
    lfw_tta_res = evaluator.lfw_evaluator.evaluate(model, device=device, batch_size=64, use_flip_tta=True)
    print(f"  [LFW + TTA] Accuracy : {lfw_tta_res['lfw_accuracy_mean']*100:.2f}% ± {lfw_tta_res['lfw_accuracy_std']*100:.2f}%")
    print(f"  [LFW + TTA] TAR@1% FAR: {lfw_tta_res['tar_at_far_1pct']*100:.2f}% | d': {lfw_tta_res['d_prime']:.3f}")

    # 7. Load CavaFace Teacher Baseline
    cava_path = "training/unified/evaluation/cavaface_lfw_baseline_results.json"
    cava_data = {}
    if os.path.exists(cava_path):
        with open(cava_path, "r", encoding="utf-8") as f:
            cava_data = json.load(f)

    # 8. Side-by-Side Comparison Table
    print("\n" + "=" * 80)
    print(" Master Architectural & Biometric Comparison Table")
    print("=" * 80)
    table_fmt = "{:<28} | {:<16} | {:<16} | {:<16}"
    print(table_fmt.format("Metric / Architecture", "CavaFace Teacher", "UnifiedModel V1", "UnifiedModel V2 (New)"))
    print("-" * 84)
    print(table_fmt.format("Model Parameters", "65.2M (IR-SE-100)", "3.48M (MNv4-Small)", "3.48M (MNv4-Small)"))
    print(table_fmt.format("FlatBuffer INT8 Size", "N/A", "3.58 MB", "3.58 MB"))
    print(table_fmt.format("Active Inference Heads", "1 (Identity)", "7 (Identity+PAD+Mesh...)", "7 (Identity+PAD+Mesh...)"))
    print(table_fmt.format("Training Identities", "MS-Celeb-1M", "105 (PINS)", "8,000 (CASIA-WebFace)"))
    print(table_fmt.format("Tier B (Unseen) TAR@1%", "N/A", "3.60%", f"{tier_b_res['tar_at_far_1pct']*100:.2f}%"))
    print(table_fmt.format("Tier B d' Separation", "N/A", "0.45", f"{tier_b_res['d_prime']:.3f}"))
    print(table_fmt.format("Tier C (LFW) Accuracy", f"{cava_data.get('lfw_accuracy_mean', 0.91)*100:.2f}%", "53.20%", f"{lfw_res['lfw_accuracy_mean']*100:.2f}%"))
    print(table_fmt.format("Tier C (LFW) TAR@1%", f"{cava_data.get('tar_at_far_1pct', 0.69)*100:.2f}%", "1.80%", f"{lfw_res['tar_at_far_1pct']*100:.2f}%"))
    print(table_fmt.format("PAD Test ACER (NUAA)", "N/A", "N/A (No Attacks)", f"{pad_res['acer']*100:.2f}%"))
    print("=" * 80)

    # 9. Save Complete Report
    final_report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "tier_a_closed_set": tier_a_res,
        "tier_b_unseen_identities": tier_b_res,
        "tier_c_lfw_benchmark": lfw_res,
        "tier_c_lfw_benchmark_tta": lfw_tta_res,
        "pad_defense_nuaa": pad_res,
        "multishot_registration": mshot_res,
        "teacher_baseline_cavaface": cava_data,
        "v1_baseline_reference": {
            "tier_b_tar_1pct": 0.0360,
            "tier_b_d_prime": 0.45,
            "lfw_accuracy": 0.5320
        },
        "target_passed": tier_b_res["tar_at_far_1pct"] > 0.0360
    }

    out_json = "training/unified/evaluation/v2_final_comprehensive_evaluation_report.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(final_report, f, indent=2)
    print(f"[+] Master evaluation report saved to: {out_json}")

    return final_report

if __name__ == "__main__":
    run_comprehensive_benchmark()
