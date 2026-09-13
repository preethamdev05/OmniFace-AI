import os
import json
import time
import math
from typing import Dict, List, Tuple, Any, Optional
import numpy as np
from PIL import Image
import torch

from training.unified.evaluation.lfw_evaluator import LFWEvaluator
from training.unified.datasets.nuaa_dataset import NUAAPadDataset

class ComprehensiveV2Evaluator:
    """
    Master Evaluation Engine for UnifiedFaceModel V2:
      Tier A: Closed-Set Generalization (Held-out images of seen identities)
      Tier B: True Zero-Shot Open-Set Generalization (Disjoint unseen identities)
      Tier C: Cross-Dataset Wild Generalization (Official LFW 6,000 pairs)
      PAD Benchmark: Dedicated ISO/IEC 30107-3 APCER/BPCER/ACER on NUAA
      Registration Benchmark: Multi-shot enrollment (1/3/5/10 shots) & centroid stability
      CavaFace vs V2 Comparative Telemetry
    """
    def __init__(
        self,
        device: torch.device = torch.device("cuda" if torch.cuda.is_available() else "cpu"),
        lfw_dir: str = "training/downloads/lfw-deepfunneled/lfw-deepfunneled",
        pairs_csv: str = "training/downloads/pairs.csv",
        nuaa_dir: str = "training/downloads/nuaa/raw"
    ):
        self.device = device
        self.lfw_evaluator = LFWEvaluator(lfw_dir=lfw_dir, pairs_csv=pairs_csv)
        self.nuaa_test_ds = NUAAPadDataset(base_dir=nuaa_dir, split="test")

    def _extract_embs(self, model: torch.nn.Module, image_list: List[torch.Tensor], batch_size: int = 64) -> np.ndarray:
        model.eval()
        embs = []
        with torch.no_grad():
            for i in range(0, len(image_list), batch_size):
                chunk = image_list[i:min(i + batch_size, len(image_list))]
                b_tensor = torch.stack(chunk).to(self.device)
                out = model(b_tensor)
                emb = out["identity_embedding"].cpu().numpy()
                embs.append(emb)
        return np.concatenate(embs, axis=0)

    def evaluate_identity_pairs(self, embs: np.ndarray, labels: np.ndarray, num_gen: int = 1000, num_imp: int = 5000, seed: int = 42) -> Dict[str, float]:
        """
        Computes genuine & impostor distributions, separation d-prime, TAR@1%, TAR@0.1%, and EER.
        """
        rng = np.random.RandomState(seed)
        unique_ids = np.unique(labels)
        gen_sims = []
        
        for uid in unique_ids:
            idx = np.where(labels == uid)[0]
            if len(idx) >= 2:
                for i in range(len(idx)):
                    for j in range(i + 1, len(idx)):
                        gen_sims.append(float(np.dot(embs[idx[i]], embs[idx[j]])))
                        if len(gen_sims) >= num_gen:
                            break
                    if len(gen_sims) >= num_gen:
                        break
            if len(gen_sims) >= num_gen:
                break
                
        all_indices = list(range(len(labels)))
        imp_sims = []
        while len(imp_sims) < num_imp:
            i1, i2 = rng.choice(all_indices, size=2, replace=False)
            if labels[i1] != labels[i2]:
                imp_sims.append(float(np.dot(embs[i1], embs[i2])))
                
        gen_sims = np.array(gen_sims)
        imp_sims = np.array(imp_sims)
        
        mu_gen, std_gen = float(np.mean(gen_sims)), float(np.std(gen_sims))
        mu_imp, std_imp = float(np.mean(imp_sims)), float(np.std(imp_sims))
        d_prime = (mu_gen - mu_imp) / max(1e-6, math.sqrt(0.5 * (std_gen**2 + std_imp**2)))
        
        # Operational points
        sorted_imp = np.sort(imp_sims)
        idx_1pct = max(0, int(len(sorted_imp) * 0.99))
        tau_1pct = float(sorted_imp[idx_1pct])
        tar_1pct = float(np.mean(gen_sims >= tau_1pct))
        
        idx_01pct = max(0, int(len(sorted_imp) * 0.999))
        tau_01pct = float(sorted_imp[idx_01pct])
        tar_01pct = float(np.mean(gen_sims >= tau_01pct))
        
        # Equal Error Rate (EER)
        # Find threshold where FAR == FRR
        thresholds = np.linspace(-0.5, 1.0, 1000)
        far_list = [np.mean(imp_sims >= t) for t in thresholds]
        frr_list = [np.mean(gen_sims < t) for t in thresholds]
        diffs = [abs(far - frr) for far, frr in zip(far_list, frr_list)]
        best_idx = int(np.argmin(diffs))
        eer = float((far_list[best_idx] + frr_list[best_idx]) / 2.0)
        eer_thresh = float(thresholds[best_idx])
        
        return {
            "genuine_mean": mu_gen,
            "genuine_std": std_gen,
            "impostor_mean": mu_imp,
            "impostor_std": std_imp,
            "d_prime": float(d_prime),
            "tar_at_far_1pct": tar_1pct,
            "tar_at_far_01pct": tar_01pct,
            "tau_far_1pct": tau_1pct,
            "tau_far_01pct": tau_01pct,
            "eer": eer,
            "eer_threshold": eer_thresh
        }

    def evaluate_pad(self, model: torch.nn.Module, max_samples: int = 2000, batch_size: int = 64) -> Dict[str, float]:
        """
        Evaluates PAD on NUAA test set (genuine faces vs printed photo attacks).
        Calculates APCER, BPCER, and ACER.
        """
        model.eval()
        n_eval = min(len(self.nuaa_test_ds), max_samples)
        
        apcer_total, apcer_errors = 0, 0
        bpcer_total, bpcer_errors = 0, 0
        
        with torch.no_grad():
            for i in range(0, n_eval, batch_size):
                chunk = [self.nuaa_test_ds[j] for j in range(i, min(i + batch_size, n_eval))]
                faces = torch.stack([s["face"] for s in chunk]).to(self.device)
                true_labels = [s["pad_label"].item() for s in chunk]
                
                out = model(faces)
                preds = torch.argmax(out["pad_logits"], dim=-1).cpu().numpy()
                
                for true_lbl, pred_lbl in zip(true_labels, preds):
                    if true_lbl == 0:  # Bona Fide (Live)
                        bpcer_total += 1
                        if pred_lbl != 0:
                            bpcer_errors += 1
                    else:  # Presentation Attack (Spoof)
                        apcer_total += 1
                        if pred_lbl == 0:
                            apcer_errors += 1
                            
        apcer = float(apcer_errors / max(1, apcer_total))
        bpcer = float(bpcer_errors / max(1, bpcer_total))
        acer = float((apcer + bpcer) / 2.0)
        
        return {
            "apcer": apcer,
            "bpcer": bpcer,
            "acer": acer,
            "evaluated_spoof_samples": apcer_total,
            "evaluated_live_samples": bpcer_total
        }

    def evaluate_multishot_registration(
        self,
        model: torch.nn.Module,
        test_images_by_id: Dict[int, List[torch.Tensor]],
        shots: List[int] = [1, 3, 5, 10]
    ) -> Dict[str, Any]:
        """
        Evaluates registration quality across enrollment shots (1, 3, 5, 10 images)
        with centroid fusion and measures probe verification margin Delta.
        """
        model.eval()
        results = {}
        
        for k in shots:
            margins = []
            probe_sims = []
            for uid, img_list in test_images_by_id.items():
                if len(img_list) <= k:
                    continue
                # Extract embeddings
                with torch.no_grad():
                    enroll_t = torch.stack(img_list[:k]).to(self.device)
                    probe_t = torch.stack(img_list[k:]).to(self.device)
                    
                    enroll_embs = model(enroll_t)["identity_embedding"].cpu().numpy()
                    probe_embs = model(probe_t)["identity_embedding"].cpu().numpy()
                    
                # Master Centroid fusion
                centroid = np.mean(enroll_embs, axis=0)
                norm = np.linalg.norm(centroid)
                if norm > 1e-6:
                    centroid /= norm
                    
                for pe in probe_embs:
                    sim = float(np.dot(centroid, pe))
                    probe_sims.append(sim)
                    
            if probe_sims:
                results[f"{k}_shot"] = {
                    "mean_probe_similarity": float(np.mean(probe_sims)),
                    "std_probe_similarity": float(np.std(probe_sims)),
                    "min_probe_similarity": float(np.min(probe_sims))
                }
        return results
