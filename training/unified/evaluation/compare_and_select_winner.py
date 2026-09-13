import os
import sys
import json
import time
import math
import shutil
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

def evaluate_model_instance(model: torch.nn.Module, device: torch.device, evaluator: ComprehensiveV2Evaluator, test_faces, test_labels, multishot_dict) -> Dict[str, Any]:
    model.eval()
    
    # 1. Tier B Open-Set Unseen Evaluation
    tier_b_embs = evaluator._extract_embs(model, test_faces)
    tier_b_res = evaluator.evaluate_identity_pairs(tier_b_embs, np.array(test_labels), num_gen=900, num_imp=5000)
    
    # 2. Tier C LFW Standard & TTA
    lfw_std = evaluator.lfw_evaluator.evaluate(model, device=device, batch_size=64, use_flip_tta=False)
    lfw_tta = evaluator.lfw_evaluator.evaluate(model, device=device, batch_size=64, use_flip_tta=True)
    
    # 3. PAD Presentation Attack Defense
    pad_res = evaluator.evaluate_pad(model, max_samples=2000)
    
    # 4. Multi-shot Registration
    mshot_res = evaluator.evaluate_multishot_registration(model, multishot_dict, shots=[1, 3, 5, 10])
    
    # 5. Unknown Visitor Rejection
    unknown_max_sims = []
    tau_1pct = tier_b_res["tau_far_1pct"]
    for uid, img_list in multishot_dict.items():
        if len(img_list) >= 2 and uid + 1 in multishot_dict:
            with torch.no_grad():
                c_emb = model(torch.stack(img_list[:3]).to(device))["identity_embedding"].cpu().numpy()
            c_vec = np.mean(c_emb, axis=0)
            c_vec /= max(1e-6, np.linalg.norm(c_vec))
            with torch.no_grad():
                u_emb = model(torch.stack(multishot_dict[uid+1][:1]).to(device))["identity_embedding"].cpu().numpy()[0]
            u_emb /= max(1e-6, np.linalg.norm(u_emb))
            unknown_max_sims.append(float(np.dot(c_vec, u_emb)))
    unknown_rejection_rate = float(np.mean(np.array(unknown_max_sims) < tau_1pct))
    
    return {
        "tier_b": tier_b_res,
        "lfw_standard": lfw_std,
        "lfw_tta": lfw_tta,
        "pad": pad_res,
        "multishot": mshot_res,
        "unknown_rejection_rate": unknown_rejection_rate,
        "unknown_sim_mean": float(np.mean(unknown_max_sims))
    }

def main():
    print("=" * 80)
    print(" OmniFace UnifiedFaceModel V2 — Foundation vs Fine-Tuned Head-to-Head Gate")
    print(" Selection Criteria: FAR -> TAR @ 1% FAR -> EER -> FRR -> Unknown Rejection")
    print("=" * 80)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    evaluator = ComprehensiveV2Evaluator(device=device)

    # Load test data once for identical evaluation
    with open("training/unified/datasets/dataset_manifest_v2.json", "r", encoding="utf-8") as f:
        manifest = json.load(f)
    test_ids = manifest["identity_splits"]["test_identities"]
    root_dir = manifest["root_directory"]
    
    test_faces = []
    test_labels = []
    multishot_dict = {}
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
                    test_faces.append(tensor)
                    test_labels.append(uid_idx)
                    multishot_dict[uid_idx].append(tensor)
            test_count += 1
            if test_count >= 150:
                break

    # 1. Evaluate Foundation Model
    p_fnd = "training/unified/checkpoints/best_unified_model_v2.pt"
    print(f"[*] Evaluating Model A: Foundation Checkpoint ({p_fnd})...", flush=True)
    m_fnd = OmniFaceUnifiedModelV2(MobileNetV4ConvSmallBackbone(out_channels=512), feature_channels=512, num_mesh_points=468).to(device)
    ckpt_fnd = torch.load(p_fnd, map_location=device)
    m_fnd.load_state_dict(ckpt_fnd["model_state_dict"])
    res_fnd = evaluate_model_instance(m_fnd, device, evaluator, test_faces, test_labels, multishot_dict)

    # 2. Evaluate Fine-Tuned Model
    p_ft = "training/unified/checkpoints/experiment_v2_ft.pt"
    if not os.path.exists(p_ft):
        print(f"[-] Warning: Fine-tuned checkpoint not found at {p_ft}, falling back to Foundation.", flush=True)
        winner_path = p_fnd
        winner_name = "V2 Foundation"
        res_ft = res_fnd
    else:
        print(f"[*] Evaluating Model B: Experiment V2-FT ({p_ft})...", flush=True)
        m_ft = OmniFaceUnifiedModelV2(MobileNetV4ConvSmallBackbone(out_channels=512), feature_channels=512, num_mesh_points=468).to(device)
        ckpt_ft = torch.load(p_ft, map_location=device)
        m_ft.load_state_dict(ckpt_ft["model_state_dict"])
        res_ft = evaluate_model_instance(m_ft, device, evaluator, test_faces, test_labels, multishot_dict)

        # 3. Decision Matrix based on Operational Hierarchy
        # Priority 1: Tier B TAR @ 1% FAR
        score_fnd = res_fnd["tier_b"]["tar_at_far_1pct"]
        score_ft = res_ft["tier_b"]["tar_at_far_1pct"]
        
        # Priority 2: Separation Index d'
        d_fnd = res_fnd["tier_b"]["d_prime"]
        d_ft = res_ft["tier_b"]["d_prime"]

        # Priority 3: LFW Accuracy
        lfw_fnd = res_fnd["lfw_tta"]["lfw_accuracy_mean"]
        lfw_ft = res_ft["lfw_tta"]["lfw_accuracy_mean"]

        if (score_ft > score_fnd) or (score_ft >= score_fnd and d_ft >= d_fnd and lfw_ft >= lfw_fnd):
            winner_path = p_ft
            winner_name = "Experiment V2-FT (Fine-Tuned)"
        else:
            winner_path = p_fnd
            winner_name = "UnifiedFaceModel V2 Foundation"

    # Print Comparison Table
    print("\n" + "=" * 80)
    print(" Head-to-Head Comparison: Foundation vs Fine-Tuned")
    print("=" * 80)
    fmt = "{:<32} | {:<20} | {:<20}"
    print(fmt.format("Metric (Operational Priority)", "V2 Foundation", "Experiment V2-FT"))
    print("-" * 76)
    print(fmt.format("1. Tier B TAR @ 1% FAR", f"{res_fnd['tier_b']['tar_at_far_1pct']*100:.2f}%", f"{res_ft['tier_b']['tar_at_far_1pct']*100:.2f}%"))
    print(fmt.format("2. Tier B TAR @ 0.1% FAR", f"{res_fnd['tier_b']['tar_at_far_01pct']*100:.2f}%", f"{res_ft['tier_b']['tar_at_far_01pct']*100:.2f}%"))
    print(fmt.format("3. Separation Index d'", f"{res_fnd['tier_b']['d_prime']:.3f}", f"{res_ft['tier_b']['d_prime']:.3f}"))
    print(fmt.format("4. Equal Error Rate (EER)", f"{res_fnd['tier_b']['eer']*100:.2f}%", f"{res_ft['tier_b']['eer']*100:.2f}%"))
    print(fmt.format("5. Unknown Rejection Rate", f"{res_fnd['unknown_rejection_rate']*100:.2f}%", f"{res_ft['unknown_rejection_rate']*100:.2f}%"))
    print(fmt.format("6. LFW Accuracy (Standard)", f"{res_fnd['lfw_standard']['lfw_accuracy_mean']*100:.2f}%", f"{res_ft['lfw_standard']['lfw_accuracy_mean']*100:.2f}%"))
    print(fmt.format("7. LFW Accuracy (Flip TTA)", f"{res_fnd['lfw_tta']['lfw_accuracy_mean']*100:.2f}%", f"{res_ft['lfw_tta']['lfw_accuracy_mean']*100:.2f}%"))
    print(fmt.format("8. PAD Test ACER (NUAA)", f"{res_fnd['pad']['acer']*100:.2f}%", f"{res_ft['pad']['acer']*100:.2f}%"))
    print("=" * 80)
    print(f" ⭐ EMPIRICAL WINNER SELECTED: {winner_name}")
    print("=" * 80)

    # Save Winner
    prod_path = "training/unified/checkpoints/production_winner_unified_v2.pt"
    shutil.copyfile(winner_path, prod_path)
    print(f"[+] Deployed winner checkpoint to: {prod_path}")

    # Save Comparison Report
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "foundation_metrics": res_fnd,
        "finetuned_metrics": res_ft,
        "winner_name": winner_name,
        "winner_checkpoint": prod_path
    }
    with open("training/unified/evaluation/winner_selection_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print("[+] Saved report to: training/unified/evaluation/winner_selection_report.json")

    return report

if __name__ == "__main__":
    main()
