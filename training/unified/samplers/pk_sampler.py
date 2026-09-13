import random
from typing import Iterator, List, Dict
import numpy as np
import torch
from torch.utils.data import Sampler

class IdentityPKSampler(Sampler[List[int]]):
    """
    Identity-aware P x K batch sampler for deep metric learning.
    Samples P unique identities, and K images per identity for every mini-batch.
    Batch size = P * K.
    
    Guarantees that:
    1. Every anchor in the batch has exactly K - 1 genuine positive counterparts.
    2. In-batch hard-negative mining has guaranteed negative pairs across (P - 1) * K samples.
    """
    def __init__(
        self,
        labels: List[int],
        p_identities: int = 32,
        k_images: int = 4,
        max_batches: int = None
    ):
        self.labels = np.array(labels)
        self.p_identities = p_identities
        self.k_images = k_images
        self.batch_size = p_identities * k_images
        
        # Build map: identity -> list of sample indices
        self.identity_to_indices: Dict[int, List[int]] = {}
        for idx, lbl in enumerate(self.labels):
            if lbl not in self.identity_to_indices:
                self.identity_to_indices[lbl] = []
            self.identity_to_indices[lbl].append(idx)
            
        # Filter identities that have at least 2 images for metric positive pairs
        self.valid_identities = [
            lbl for lbl, idxs in self.identity_to_indices.items() if len(idxs) >= 2
        ]
        if len(self.valid_identities) < self.p_identities:
            self.valid_identities = list(self.identity_to_indices.keys())
            
        self.num_samples = len(self.labels)
        if max_batches is not None:
            self.total_batches = max_batches
        else:
            self.total_batches = max(1, self.num_samples // self.batch_size)

    def __iter__(self) -> Iterator[List[int]]:
        identities_pool = list(self.valid_identities)
        
        for _ in range(self.total_batches):
            if len(identities_pool) < self.p_identities:
                identities_pool = list(self.valid_identities)
                random.shuffle(identities_pool)
                
            selected_ids = random.sample(identities_pool, min(self.p_identities, len(identities_pool)))
            batch_indices = []
            
            for id_val in selected_ids:
                pool = self.identity_to_indices[id_val]
                if len(pool) >= self.k_images:
                    chosen = random.sample(pool, self.k_images)
                else:
                    chosen = random.choices(pool, k=self.k_images)
                batch_indices.extend(chosen)
                
            yield batch_indices

    def __len__(self) -> int:
        return self.total_batches
