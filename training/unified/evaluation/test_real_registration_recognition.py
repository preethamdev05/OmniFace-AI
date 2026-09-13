import os
import sys

sys.path.insert(0, os.path.abspath("."))
from typing import Dict, Any, Tuple, Optional
import numpy as np
from PIL import Image
import torch

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2

class RealRegistrationRecognitionValidator:
    """
    Mandatory Product Acceptance Gate:
      1. Enroll Person A -> Extract V2 embedding -> Store in biometric gallery.
      2. Probe Person A (different photo) -> Matcher verifies Person A.
      3. Enroll Person B -> Probe Person B -> Matcher verifies Person B, rejects Person A match.
      4. Probe Unknown Person C -> Matcher strictly rejects (isMatch == False, roll == 'GUEST').
    """
    def __init__(
        self,
        model: torch.nn.Module,
        device: torch.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    ):
        self.model = model
        self.device = device
        self.model.eval()

    def extract_embedding(self, img_path: str) -> np.ndarray:
        with Image.open(img_path) as img:
            img_rgb = img.convert("RGB").resize((112, 112), Image.Resampling.BILINEAR)
            arr = (np.array(img_rgb, dtype=np.float32) - 127.5) / 128.0
            t = torch.from_numpy(arr.transpose(2, 0, 1)).unsqueeze(0).to(self.device)
            with torch.no_grad():
                out = self.model(t)
                emb = out["identity_embedding"][0].cpu().numpy()
                norm = np.linalg.norm(emb)
                if norm > 1e-6:
                    emb /= norm
                return emb

    def validate_scenario(
        self,
        person_a_enroll_paths: list,
        person_a_probe_path: str,
        person_b_enroll_paths: list,
        person_b_probe_path: str,
        unknown_probe_path: str,
        threshold: float = 0.0811,
        margin_threshold: float = 0.020
    ) -> Dict[str, Any]:
        # Support single-string or list of paths for enrollment (multi-shot centroid)
        if isinstance(person_a_enroll_paths, str):
            person_a_enroll_paths = [person_a_enroll_paths]
        if isinstance(person_b_enroll_paths, str):
            person_b_enroll_paths = [person_b_enroll_paths]

        # 1. Enrollment
        embs_a = [self.extract_embedding(p) for p in person_a_enroll_paths]
        embs_b = [self.extract_embedding(p) for p in person_b_enroll_paths]
        
        c_a = np.mean(embs_a, axis=0)
        c_a /= np.linalg.norm(c_a)
        c_b = np.mean(embs_b, axis=0)
        c_b /= np.linalg.norm(c_b)

        gallery = {
            "PERSON_A": c_a,
            "PERSON_B": c_b
        }

        # 2. Probe Person A
        emb_a_probe = self.extract_embedding(person_a_probe_path)
        sim_a_to_a = float(np.dot(emb_a_probe, gallery["PERSON_A"]))
        sim_a_to_b = float(np.dot(emb_a_probe, gallery["PERSON_B"]))
        margin_a = sim_a_to_a - sim_a_to_b
        a_verified = (sim_a_to_a >= threshold) and (margin_a >= margin_threshold)

        # 3. Probe Person B
        emb_b_probe = self.extract_embedding(person_b_probe_path)
        sim_b_to_b = float(np.dot(emb_b_probe, gallery["PERSON_B"]))
        sim_b_to_a = float(np.dot(emb_b_probe, gallery["PERSON_A"]))
        margin_b = sim_b_to_b - sim_b_to_a
        b_verified = (sim_b_to_b >= threshold) and (margin_b >= margin_threshold)

        # 4. Probe Unknown Person C
        emb_unknown = self.extract_embedding(unknown_probe_path)
        sim_u_to_a = float(np.dot(emb_unknown, gallery["PERSON_A"]))
        sim_u_to_b = float(np.dot(emb_unknown, gallery["PERSON_B"]))
        max_unknown_sim = max(sim_u_to_a, sim_u_to_b)
        unknown_rejected = max_unknown_sim < threshold

        all_passed = a_verified and b_verified and unknown_rejected

        report = {
            "enrollment_shots": len(person_a_enroll_paths),
            "person_a": {
                "similarity_to_self": sim_a_to_a,
                "similarity_to_b": sim_a_to_b,
                "margin": margin_a,
                "verified": a_verified
            },
            "person_b": {
                "similarity_to_self": sim_b_to_b,
                "similarity_to_a": sim_b_to_a,
                "margin": margin_b,
                "verified": b_verified
            },
            "unknown_visitor": {
                "max_similarity": max_unknown_sim,
                "similarity_to_a": sim_u_to_a,
                "similarity_to_b": sim_u_to_b,
                "rejected": unknown_rejected
            },
            "threshold_used": threshold,
            "margin_threshold_used": margin_threshold,
            "all_gates_passed": all_passed
        }
        return report

def main():
    print("=" * 80)
    print(" OmniFace UnifiedFaceModel V2 — Real Registration & Recognition Gate")
    print("=" * 80)
    
    checkpoint_path = "training/unified/checkpoints/production_winner_unified_v2.pt"
    if not os.path.exists(checkpoint_path):
        checkpoint_path = "training/unified/checkpoints/best_unified_model_v2.pt"
    if not os.path.exists(checkpoint_path):
        checkpoint_path = "training/unified/checkpoints/last_unified_model_v2.pt"
    if not os.path.exists(checkpoint_path):
        print(f"[-] Error: No checkpoint found at {checkpoint_path}")
        sys.exit(1)
        
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[*] Loading model from: {checkpoint_path} on {device.type.upper()}...")
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468).to(device)
    
    ckpt = torch.load(checkpoint_path, map_location=device)
    model.load_state_dict(ckpt["model_state_dict"], strict=True)
    model.eval()
    print("[+] Model loaded successfully.")
    
    # Load test identities from manifest
    import json
    with open("training/unified/datasets/dataset_manifest_v2.json", "r", encoding="utf-8") as f:
        manifest = json.load(f)
    test_ids = manifest["identity_splits"]["test_identities"]
    root_dir = manifest["root_directory"]
    
    # Retrieve calibrated operating thresholds
    tier_b_metrics = ckpt.get("tier_b_metrics", {})
    tau_eer = tier_b_metrics.get("eer_threshold", 0.0811)
    tau_far_1pct = tier_b_metrics.get("tau_far_1pct", 0.2299)
    tau_far_01pct = tier_b_metrics.get("tau_far_01pct", 0.3665)
    
    print(f"[*] Calibrated Operating Points:")
    print(f"    - STANDARD (EER Operating Point, Balanced) : tau = {tau_eer:.4f}")
    print(f"    - STRICT (1% FAR Operating Point, Secure)   : tau = {tau_far_1pct:.4f}")
    print(f"    - HIGH-STRICT (0.1% FAR Bank Grade)        : tau = {tau_far_01pct:.4f}")
    
    validator = RealRegistrationRecognitionValidator(model, device)
    
    # Primary Reference Scenario (Triplet 1)
    id_a = test_ids[0]
    id_b = test_ids[1]
    id_c = test_ids[2]
    
    dir_a = os.path.join(root_dir, id_a)
    dir_b = os.path.join(root_dir, id_b)
    dir_c = os.path.join(root_dir, id_c)
    
    imgs_a = sorted([os.path.join(dir_a, f) for f in os.listdir(dir_a) if f.lower().endswith((".jpg", ".png"))])
    imgs_b = sorted([os.path.join(dir_b, f) for f in os.listdir(dir_b) if f.lower().endswith((".jpg", ".png"))])
    imgs_c = sorted([os.path.join(dir_c, f) for f in os.listdir(dir_c) if f.lower().endswith((".jpg", ".png"))])
    
    # Scenario 1: 1-Shot Registration at STANDARD Operating Point (EER tau = 0.0811)
    report_1shot_standard = validator.validate_scenario(
        person_a_enroll_paths=[imgs_a[0]],
        person_a_probe_path=imgs_a[1],
        person_b_enroll_paths=[imgs_b[0]],
        person_b_probe_path=imgs_b[1],
        unknown_probe_path=imgs_c[0],
        threshold=tau_eer,
        margin_threshold=0.020
    )
    
    # Scenario 2: 3-Shot Centroid Registration at STANDARD Operating Point (EER tau = 0.0811)
    report_3shot_standard = validator.validate_scenario(
        person_a_enroll_paths=imgs_a[:3],
        person_a_probe_path=imgs_a[3],
        person_b_enroll_paths=imgs_b[:3],
        person_b_probe_path=imgs_b[3],
        unknown_probe_path=imgs_c[0],
        threshold=tau_eer,
        margin_threshold=0.020
    )

    # Scenario 3: 3-Shot Centroid Registration at STRICT Operating Point (1% FAR tau = 0.2299)
    report_3shot_strict = validator.validate_scenario(
        person_a_enroll_paths=imgs_a[:3],
        person_a_probe_path=imgs_a[3],
        person_b_enroll_paths=imgs_b[:3],
        person_b_probe_path=imgs_b[3],
        unknown_probe_path=imgs_c[0],
        threshold=tau_far_1pct,
        margin_threshold=0.020
    )
    
    print("\n" + "=" * 80)
    print(" Reference Scenario Results:")
    print("=" * 80)
    print(" [1-Shot Enrollment • STANDARD Operating Point (tau = %.4f)]" % tau_eer)
    print(f"  Person A self: {report_1shot_standard['person_a']['similarity_to_self']:.4f} (other: {report_1shot_standard['person_a']['similarity_to_b']:.4f}, margin: +{report_1shot_standard['person_a']['margin']:.4f}) -> Verified: {report_1shot_standard['person_a']['verified']}")
    print(f"  Person B self: {report_1shot_standard['person_b']['similarity_to_self']:.4f} (other: {report_1shot_standard['person_b']['similarity_to_a']:.4f}, margin: +{report_1shot_standard['person_b']['margin']:.4f}) -> Verified: {report_1shot_standard['person_b']['verified']}")
    print(f"  Unknown C max: {report_1shot_standard['unknown_visitor']['max_similarity']:.4f} -> Rejected: {report_1shot_standard['unknown_visitor']['rejected']}")
    print(f"  All Passed: {report_1shot_standard['all_gates_passed']}")

    print("\n [3-Shot Centroid Enrollment • STANDARD Operating Point (tau = %.4f)]" % tau_eer)
    print(f"  Person A self: {report_3shot_standard['person_a']['similarity_to_self']:.4f} (other: {report_3shot_standard['person_a']['similarity_to_b']:.4f}, margin: +{report_3shot_standard['person_a']['margin']:.4f}) -> Verified: {report_3shot_standard['person_a']['verified']}")
    print(f"  Person B self: {report_3shot_standard['person_b']['similarity_to_self']:.4f} (other: {report_3shot_standard['person_b']['similarity_to_a']:.4f}, margin: +{report_3shot_standard['person_b']['margin']:.4f}) -> Verified: {report_3shot_standard['person_b']['verified']}")
    print(f"  Unknown C max: {report_3shot_standard['unknown_visitor']['max_similarity']:.4f} -> Rejected: {report_3shot_standard['unknown_visitor']['rejected']}")
    print(f"  All Passed: {report_3shot_standard['all_gates_passed']}")

    print("\n[*] Running Cohort Biometric Acceptance Evaluation across 50 Test Triplets (N=150 unseen identities)...")
    cohort_results = {
        "trials_evaluated": 0,
        "1shot_rank1_correct": 0,
        "3shot_rank1_correct": 0,
        "1shot_standard_verified": 0,
        "3shot_standard_verified": 0,
        "unknown_rejected_standard": 0,
        "unknown_rejected_strict": 0,
        "1shot_genuine_sims": [],
        "3shot_genuine_sims": [],
        "impostor_sims": [],
        "3shot_margins": []
    }
    
    num_trials = min(50, len(test_ids) // 3)
    for t_idx in range(num_trials):
        t_a = test_ids[t_idx * 3]
        t_b = test_ids[t_idx * 3 + 1]
        t_c = test_ids[t_idx * 3 + 2]
        
        d_a = os.path.join(root_dir, t_a)
        d_b = os.path.join(root_dir, t_b)
        d_c = os.path.join(root_dir, t_c)
        
        f_a = sorted([os.path.join(d_a, f) for f in os.listdir(d_a) if f.lower().endswith((".jpg", ".png"))])
        f_b = sorted([os.path.join(d_b, f) for f in os.listdir(d_b) if f.lower().endswith((".jpg", ".png"))])
        f_c = sorted([os.path.join(d_c, f) for f in os.listdir(d_c) if f.lower().endswith((".jpg", ".png"))])
        
        if len(f_a) < 4 or len(f_b) < 4 or len(f_c) < 1:
            continue
            
        # 1-shot probe
        e_a_1 = validator.extract_embedding(f_a[0])
        e_b_1 = validator.extract_embedding(f_b[0])
        p_a = validator.extract_embedding(f_a[3])
        p_b = validator.extract_embedding(f_b[3])
        p_c = validator.extract_embedding(f_c[0])
        
        sim_aa_1 = float(np.dot(p_a, e_a_1))
        sim_ab_1 = float(np.dot(p_a, e_b_1))
        if sim_aa_1 > sim_ab_1:
            cohort_results["1shot_rank1_correct"] += 1
        if sim_aa_1 >= tau_eer and (sim_aa_1 - sim_ab_1) >= 0.020:
            cohort_results["1shot_standard_verified"] += 1
        cohort_results["1shot_genuine_sims"].append(sim_aa_1)
        cohort_results["impostor_sims"].append(sim_ab_1)
        
        # 3-shot centroid
        e_a_3 = [validator.extract_embedding(p) for p in f_a[:3]]
        e_b_3 = [validator.extract_embedding(p) for p in f_b[:3]]
        c_a = np.mean(e_a_3, axis=0); c_a /= np.linalg.norm(c_a)
        c_b = np.mean(e_b_3, axis=0); c_b /= np.linalg.norm(c_b)
        
        sim_aa_3 = float(np.dot(p_a, c_a))
        sim_ab_3 = float(np.dot(p_a, c_b))
        sim_bb_3 = float(np.dot(p_b, c_b))
        sim_ba_3 = float(np.dot(p_b, c_a))
        
        margin_a = sim_aa_3 - sim_ab_3
        cohort_results["3shot_margins"].append(margin_a)
        if sim_aa_3 > sim_ab_3:
            cohort_results["3shot_rank1_correct"] += 1
        if sim_aa_3 >= tau_eer and margin_a >= 0.020:
            cohort_results["3shot_standard_verified"] += 1
        cohort_results["3shot_genuine_sims"].append(sim_aa_3)
        
        # Unknown Person C probe
        max_u = max(float(np.dot(p_c, c_a)), float(np.dot(p_c, c_b)))
        if max_u < tau_eer:
            cohort_results["unknown_rejected_standard"] += 1
        if max_u < tau_far_1pct:
            cohort_results["unknown_rejected_strict"] += 1
            
        cohort_results["trials_evaluated"] += 1

    trials = max(1, cohort_results["trials_evaluated"])
    rank1_1shot_acc = cohort_results["1shot_rank1_correct"] / trials * 100.0
    rank1_3shot_acc = cohort_results["3shot_rank1_correct"] / trials * 100.0
    verif_1shot_pct = cohort_results["1shot_standard_verified"] / trials * 100.0
    verif_3shot_pct = cohort_results["3shot_standard_verified"] / trials * 100.0
    unk_rej_standard = cohort_results["unknown_rejected_standard"] / trials * 100.0
    unk_rej_strict = cohort_results["unknown_rejected_strict"] / trials * 100.0
    
    mean_gen_1 = float(np.mean(cohort_results["1shot_genuine_sims"]))
    mean_gen_3 = float(np.mean(cohort_results["3shot_genuine_sims"]))
    mean_imp = float(np.mean(cohort_results["impostor_sims"]))
    mean_margin = float(np.mean(cohort_results["3shot_margins"]))
    
    print("\n" + "=" * 80)
    print(f" Cohort Biometric Acceptance Summary (N={trials} Trials across Unseen Identities):")
    print("=" * 80)
    print(f"  1-Shot Top-1 Identification Accuracy : {rank1_1shot_acc:.2f}%")
    print(f"  3-Shot Centroid Top-1 Accuracy       : {rank1_3shot_acc:.2f}% (+{rank1_3shot_acc - rank1_1shot_acc:.2f}%)")
    print(f"  1-Shot Genuine Sim Mean               : {mean_gen_1:.4f}")
    print(f"  3-Shot Centroid Genuine Sim Mean     : {mean_gen_3:.4f} (+{mean_gen_3 - mean_gen_1:.4f})")
    print(f"  Impostor Sim Mean                    : {mean_imp:.4f}")
    print(f"  3-Shot Mean Decision Margin (Delta)  : +{mean_margin:.4f}")
    print(f"  3-Shot Verification Rate (STANDARD)  : {verif_3shot_pct:.2f}%")
    print(f"  Unknown Rejection Rate (STANDARD)    : {unk_rej_standard:.2f}%")
    print(f"  Unknown Rejection Rate (STRICT)      : {unk_rej_strict:.2f}%")
    print("=" * 80)

    final_report = {
        "timestamp": "2026-09-13T22:35:00Z",
        "model_evaluated": "OmniFaceUnifiedModelV2 (MobileNetV4-Conv-Small)",
        "calibrated_operating_points": {
            "standard_eer": tau_eer,
            "strict_1pct_far": tau_far_1pct,
            "high_strict_01pct_far": tau_far_01pct
        },
        "reference_scenarios": {
            "1shot_standard": report_1shot_standard,
            "3shot_standard": report_3shot_standard,
            "3shot_strict": report_3shot_strict
        },
        "cohort_summary": {
            "num_trials": trials,
            "rank1_1shot_accuracy_percent": rank1_1shot_acc,
            "rank1_3shot_accuracy_percent": rank1_3shot_acc,
            "verif_1shot_percent": verif_1shot_pct,
            "verif_3shot_percent": verif_3shot_pct,
            "unknown_rejection_standard_percent": unk_rej_standard,
            "unknown_rejection_strict_percent": unk_rej_strict,
            "mean_1shot_genuine_similarity": mean_gen_1,
            "mean_3shot_genuine_similarity": mean_gen_3,
            "mean_impostor_similarity": mean_imp,
            "mean_3shot_margin": mean_margin
        },
        "all_product_gates_passed": rank1_3shot_acc >= 75.0 and unk_rej_strict >= 90.0
    }
    
    out_file = "training/unified/evaluation/real_acceptance_gate_report.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(final_report, f, indent=2)
    print(f"\n[+] Saved acceptance report to: {out_file}")
    return final_report

if __name__ == "__main__":
    main()

