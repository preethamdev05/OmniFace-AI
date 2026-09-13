import os
import torch
from torch.utils.data import Dataset
import numpy as np
import random
import hashlib
from typing import Dict, List, Tuple, Optional
from training.unified.preprocessing.transforms import BiometricAugmentationPipeline

class OmniFaceMultiTaskDataset(Dataset):
    """
    Multi-Task Biometric Dataset supporting open-set training,
    confidence-aware teacher supervision, and presentation attack labels.
    """
    def __init__(
        self,
        num_samples: int = 1000,
        num_identities: int = 105,
        is_training: bool = True,
        num_mesh_points: int = 468,
        cache_path: Optional[str] = "training/unified/datasets/teacher_dataset_cache.pt"
    ):
        self.is_training = is_training
        self.num_mesh_points = num_mesh_points
        self.transforms = BiometricAugmentationPipeline(is_training=is_training)
        self.records = None

        if cache_path and os.path.exists(cache_path):
            try:
                cache = torch.load(cache_path, weights_only=False)
                data_list = cache["train_data"] if is_training else cache["val_data"]
                if len(data_list) > 0:
                    self.records = data_list
                    self.num_identities = cache.get("num_classes", num_identities)
                    self.num_samples = len(self.records)
                    self.sample_ids = [r["sample_id"] for r in self.records]
                    self.identity_labels = [r["identity_label"].item() for r in self.records]
                    self.pad_labels = [r["pad_label"].item() for r in self.records]
                    return
            except Exception:
                self.records = None

        self.num_samples = num_samples
        self.num_identities = num_identities
        self.sample_ids = [f"sample_{i:06d}" for i in range(num_samples)]
        self.identity_labels = [i % num_identities for i in range(num_samples)]
        self.pad_labels = [(i % 3) for i in range(num_samples)]  # 0: bona fide, 1: print, 2: replay

    def __len__(self) -> int:
        return self.num_samples

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, Dict[str, torch.Tensor], Dict[str, str]]:
        if self.records is not None:
            r = self.records[idx]
            face = r["face"]
            if self.is_training:
                face = self.transforms(face)
            targets = {
                "identity_label": r["identity_label"],
                "pad_label": r["pad_label"],
                "mesh_landmarks": r["mesh_landmarks"],
                "geom_3dmm": r["geom_3dmm"],
                "quality_scores": r["quality_scores"],
                "gaze_angles": r["gaze_angles"],
                "attribute_probs": r["attribute_probs"],
                "teacher_emb": r["teacher_emb"],
                "teacher_pad_logits": r["pad_logits"],
                "teacher_confidence": torch.tensor(0.98, dtype=torch.float32)
            }
            meta = {
                "sample_id": r["sample_id"],
                "image_hash": hashlib.sha256(face.numpy().tobytes()).hexdigest(),
                "ground_truth_available": "true" if r["pad_label"].item() == 0 else "false"
            }
            return face, targets, meta

        # Deterministic synthetic seed per sample for reproducible testing
        rng = np.random.RandomState(idx)
        
        # Base face crop: [3, 112, 112] normalized [0, 1]
        raw_pixels = rng.uniform(0.1, 0.9, (3, 112, 112)).astype(np.float32)
        face_tensor = torch.from_numpy(raw_pixels)
        face_tensor = self.transforms(face_tensor)
        
        identity_label = torch.tensor(self.identity_labels[idx], dtype=torch.long)
        pad_label = torch.tensor(self.pad_labels[idx], dtype=torch.long)
        
        mesh_landmarks = torch.from_numpy(rng.randn(self.num_mesh_points, 3).astype(np.float32))
        geom_3dmm = torch.from_numpy(rng.randn(265).astype(np.float32))
        quality_scores = torch.from_numpy(rng.uniform(0.5, 0.98, 4).astype(np.float32))
        gaze_angles = torch.from_numpy(rng.uniform(-25.0, 25.0, 2).astype(np.float32))
        attribute_probs = torch.from_numpy(rng.uniform(0.0, 1.0, 5).astype(np.float32))
        
        targets = {
            "identity_label": identity_label,
            "pad_label": pad_label,
            "mesh_landmarks": mesh_landmarks,
            "geom_3dmm": geom_3dmm,
            "quality_scores": quality_scores,
            "gaze_angles": gaze_angles,
            "attribute_probs": attribute_probs,
            "teacher_confidence": torch.tensor(0.95, dtype=torch.float32)
        }
        
        meta = {
            "sample_id": self.sample_ids[idx],
            "image_hash": hashlib.sha256(raw_pixels.tobytes()).hexdigest(),
            "ground_truth_available": "true" if self.pad_labels[idx] == 0 else "false"
        }
        
        return face_tensor, targets, meta

class OpenSetPairGenerator:
    """
    Generates identity-disjoint genuine and impostor pairs for ISO/IEC verification curves (ROC/DET).
    """
    def __init__(self, dataset: OmniFaceMultiTaskDataset):
        self.dataset = dataset
        self.id_to_indices: Dict[int, List[int]] = {}
        for idx, id_label in enumerate(dataset.identity_labels):
            self.id_to_indices.setdefault(id_label, []).append(idx)

    def generate_pairs(self, num_genuine: int = 1000, num_impostor: int = 10000, seed: int = 42) -> Tuple[List[Tuple[int, int]], List[Tuple[int, int]]]:
        rng = random.Random(seed)
        genuine_pairs = []
        impostor_pairs = []
        
        # 1. Genuine Pairs (same identity, different samples)
        candidate_ids = [k for k, v in self.id_to_indices.items() if len(v) >= 2]
        while len(genuine_pairs) < num_genuine and candidate_ids:
            ident = rng.choice(candidate_ids)
            indices = self.id_to_indices[ident]
            i1, i2 = rng.sample(indices, 2)
            genuine_pairs.append((i1, i2))
                
        # 2. Impostor Pairs (different identities)
        identities = list(self.id_to_indices.keys())
        while len(impostor_pairs) < num_impostor and len(identities) >= 2:
            id_a, id_b = rng.sample(identities, 2)
            idx_a = rng.choice(self.id_to_indices[id_a])
            idx_b = rng.choice(self.id_to_indices[id_b])
            impostor_pairs.append((idx_a, idx_b))
            
        return genuine_pairs, impostor_pairs
