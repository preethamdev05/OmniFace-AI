import os
from typing import List, Dict, Tuple, Optional
import numpy as np
from PIL import Image
import torch
from torch.utils.data import Dataset

class NUAAPadDataset(Dataset):
    """
    NUAA Face Anti-Spoofing Dataset Loader.
    Provides official client vs imposter disjoint train/test splits.
    Labels:
      0: Bona Fide (Live authentic human face)
      1: Printed Photograph Attack (2D presentation attack)
    """
    def __init__(
        self,
        base_dir: str = "training/downloads/nuaa/raw",
        split: str = "train",  # 'train' or 'test'
        img_size: Tuple[int, int] = (112, 112),
        silentface_model_path: Optional[str] = "models_cache/silentface.tflite"
    ):
        self.base_dir = base_dir
        self.split = split
        self.img_size = img_size
        self.samples: List[Dict[str, Any]] = []
        
        client_file = os.path.join(base_dir, f"client_{split}_raw.txt")
        imposter_file = os.path.join(base_dir, f"imposter_{split}_raw.txt")
        
        # Load Client (Live / Bona Fide)
        if os.path.exists(client_file):
            with open(client_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    # Replace remote kaggle prefix with local base path
                    rel_path = line.split("raw/")[-1].replace("\\", "/")
                    full_path = os.path.join(base_dir, rel_path)
                    if os.path.exists(full_path):
                        self.samples.append({
                            "path": full_path,
                            "label": 0,  # Live
                            "type": "live"
                        })
                        
        # Load Imposter (Print Attack)
        if os.path.exists(imposter_file):
            with open(imposter_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    rel_path = line.split("raw/")[-1].replace("\\", "/")
                    full_path = os.path.join(base_dir, rel_path)
                    if os.path.exists(full_path):
                        self.samples.append({
                            "path": full_path,
                            "label": 1,  # Print Attack
                            "type": "print_spoof"
                        })

        print(f"[+] Loaded NUAA {split.upper()} set: {len(self.samples)} samples (Live: {sum(1 for s in self.samples if s['label']==0)}, Print Spoof: {sum(1 for s in self.samples if s['label']==1)})")

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> Dict[str, torch.Tensor]:
        sample = self.samples[idx]
        with Image.open(sample["path"]) as img:
            img_rgb = img.convert("RGB").resize(self.img_size, Image.Resampling.BILINEAR)
            arr = (np.array(img_rgb, dtype=np.float32) - 127.5) / 128.0
            face_tensor = torch.from_numpy(arr.transpose(2, 0, 1))
            
        return {
            "face": face_tensor,
            "pad_label": torch.tensor(sample["label"], dtype=torch.long)
        }
