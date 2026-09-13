import os
from typing import List, Dict, Tuple, Optional
import numpy as np
from PIL import Image
import torch
from torch.utils.data import Dataset

class WebFaceDataset(Dataset):
    """
    CASIA-WebFace 112x112 Dataset Loader.
    Loads pre-aligned 112x112 RGB face images from webface_112x112 directory.
    Supports splitting into:
      - 'train': Primary training identities (e.g., first N identities)
      - 'open_set_val': Disjoint unseen identities for zero-shot open-set validation (Tier B)
    """
    def __init__(
        self,
        root_dir: str = "training/downloads/webface/webface_112x112",
        split: str = "train",
        train_class_count: int = 8000,
        manifest_path: Optional[str] = "training/unified/datasets/dataset_manifest_v2.json",
        max_images_per_class: Optional[int] = 50,
        transform = None
    ):
        self.root_dir = root_dir
        self.split = split
        self.transform = transform
        
        # Check if root directory or nested directory exists
        if not os.path.exists(self.root_dir):
            nested = "training/downloads/webface"
            if os.path.exists(nested):
                sub = os.path.join(nested, "webface_112x112")
                if os.path.exists(sub):
                    self.root_dir = sub
                else:
                    self.root_dir = nested

        # If manifest exists, use strictly disjoint verified splits
        if manifest_path and os.path.exists(manifest_path):
            import json
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            splits = manifest.get("identity_splits", {})
            if split == "train":
                self.class_dirs = splits.get("train_identities", [])[:train_class_count]
            elif split in ("open_set_val", "val"):
                self.class_dirs = splits.get("val_identities", [])
            elif split == "test":
                self.class_dirs = splits.get("test_identities", [])
            else:
                self.class_dirs = splits.get("val_identities", [])
        else:
            all_dirs = sorted([
                d for d in os.listdir(self.root_dir)
                if os.path.isdir(os.path.join(self.root_dir, d))
            ])
            if split == "train":
                self.class_dirs = all_dirs[:train_class_count]
            else:
                self.class_dirs = all_dirs[train_class_count:]
            
        self.samples: List[Tuple[str, int]] = []
        self.class_to_idx = {d: i for i, d in enumerate(self.class_dirs)}
        
        for d in self.class_dirs:
            c_idx = self.class_to_idx[d]
            c_path = os.path.join(self.root_dir, d)
            files = sorted([
                f for f in os.listdir(c_path)
                if f.lower().endswith((".jpg", ".png", ".jpeg"))
            ])
            if max_images_per_class is not None:
                files = files[:max_images_per_class]
            for f in files:
                self.samples.append((os.path.join(c_path, f), c_idx))
                
        self.labels = [s[1] for s in self.samples]
        self.num_classes = len(self.class_dirs)
        print(f"[+] Initialized WebFace [{split.upper()}]: {len(self.samples)} images across {self.num_classes} identities.")

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, int, str]:
        path, label = self.samples[idx]
        with Image.open(path) as img:
            img_rgb = img.convert("RGB")
            if img_rgb.size != (112, 112):
                img_rgb = img_rgb.resize((112, 112), Image.Resampling.BILINEAR)
            arr = (np.array(img_rgb, dtype=np.float32) - 127.5) / 128.0
            tensor = torch.from_numpy(arr.transpose(2, 0, 1))
            
        return tensor, label, path
