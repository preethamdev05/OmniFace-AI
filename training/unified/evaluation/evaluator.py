import torch
import numpy as np
from typing import Dict, List, Tuple
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset, OpenSetPairGenerator

class BiometricEvaluator:
    """
    Evaluates open-set verification (TAR @ FAR), PAD (APCER/BPCER), and auxiliary heads.
    """
    def __init__(self, model: torch.nn.Module, device: torch.device):
        self.model = model
        self.device = device

    @torch.no_grad()
    def evaluate_verification(self, dataset: OmniFaceMultiTaskDataset, num_genuine=100, num_impostor=500) -> Dict[str, float]:
        self.model.eval()
        generator = OpenSetPairGenerator(dataset)
        genuine_pairs, impostor_pairs = generator.generate_pairs(num_genuine=num_genuine, num_impostor=num_impostor)
        
        # Extract embeddings for all dataset samples in batches
        embeddings_list = []
        batch_size = 64
        for i in range(0, len(dataset), batch_size):
            chunk = [dataset[j][0] for j in range(i, min(i + batch_size, len(dataset)))]
            batch_imgs = torch.stack(chunk).to(self.device)
            out = self.model(batch_imgs)
            embeddings_list.append(out["identity_embedding"].cpu().numpy())
        embeddings = np.concatenate(embeddings_list, axis=0)
            
        # Compute Cosine Distances: 1.0 - (u . v)
        genuine_dists = []
        for i1, i2 in genuine_pairs:
            sim = np.dot(embeddings[i1], embeddings[i2])
            dist = max(0.0, 1.0 - float(sim))
            genuine_dists.append(dist)
            
        impostor_dists = []
        for i1, i2 in impostor_pairs:
            sim = np.dot(embeddings[i1], embeddings[i2])
            dist = max(0.0, 1.0 - float(sim))
            impostor_dists.append(dist)
            
        genuine_dists = np.array(genuine_dists)
        impostor_dists = np.array(impostor_dists)
        
        # Sort impostor distances to determine FAR thresholds
        sorted_impostor = np.sort(impostor_dists)
        
        # Threshold at FAR = 1%
        idx_1pct = max(0, int(0.01 * len(sorted_impostor)))
        thresh_1pct = sorted_impostor[idx_1pct]
        tar_1pct = float(np.mean(genuine_dists <= thresh_1pct))
        
        # Threshold at FAR = 0.1%
        idx_01pct = max(0, int(0.001 * len(sorted_impostor)))
        thresh_01pct = sorted_impostor[idx_01pct]
        tar_01pct = float(np.mean(genuine_dists <= thresh_01pct))
        
        return {
            "mean_genuine_dist": float(np.mean(genuine_dists)),
            "mean_impostor_dist": float(np.mean(impostor_dists)),
            "tar_at_far_1pct": tar_1pct,
            "tar_at_far_01pct": tar_01pct,
            "threshold_far_1pct": float(thresh_1pct),
            "threshold_far_01pct": float(thresh_01pct)
        }

    @torch.no_grad()
    def evaluate_pad(self, dataset: OmniFaceMultiTaskDataset) -> Dict[str, float]:
        self.model.eval()
        apcer_count, apcer_total = 0, 0
        bpcer_count, bpcer_total = 0, 0
        batch_size = 64
        
        for i in range(0, len(dataset), batch_size):
            indices = list(range(i, min(i + batch_size, len(dataset))))
            batch_imgs = torch.stack([dataset[j][0] for j in indices]).to(self.device)
            true_labels = [dataset[j][1]["pad_label"].item() for j in indices]
            out = self.model(batch_imgs)
            pred_labels = torch.argmax(out["pad_logits"], dim=-1).cpu().numpy()
            
            for true_label, pred_label in zip(true_labels, pred_labels):
                if true_label == 0:  # Bona fide
                    bpcer_total += 1
                    if pred_label != 0:
                        bpcer_count += 1
                else:  # Presentation attack (print or screen)
                    apcer_total += 1
                    if pred_label == 0:
                        apcer_count += 1
                    
        apcer = (apcer_count / max(1, apcer_total))
        bpcer = (bpcer_count / max(1, bpcer_total))
        acer = (apcer + bpcer) / 2.0
        
        return {
            "apcer": float(apcer),
            "bpcer": float(bpcer),
            "acer": float(acer)
        }
