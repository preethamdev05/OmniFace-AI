import os
import sys
import time
import csv
from typing import Dict, List, Tuple, Any, Optional
import numpy as np
from PIL import Image
import torch

class LFWEvaluator:
    """
    Official LFW 6,000-Pair Biometric Verification Benchmark Evaluator.
    Implements 10-fold cross validation adhering to ISO/IEC 19794-5 standards.
    """
    def __init__(
        self,
        lfw_dir: str = "training/downloads/lfw-deepfunneled/lfw-deepfunneled",
        pairs_csv: str = "training/downloads/pairs.csv",
        img_size: Tuple[int, int] = (112, 112)
    ):
        self.lfw_dir = lfw_dir
        self.pairs_csv = pairs_csv
        self.img_size = img_size
        self.pairs = self._load_pairs()

    def _load_pairs(self) -> List[Dict[str, Any]]:
        if not os.path.exists(self.pairs_csv):
            raise FileNotFoundError(f"Pairs file not found: {self.pairs_csv}")
            
        pairs = []
        with open(self.pairs_csv, mode="r", encoding="utf-8") as f:
            reader = csv.reader(f)
            header = next(reader)
            
            for row in reader:
                if not row or len(row) < 3:
                    continue
                # Clean empty trailing cells
                row = [c.strip() for c in row if c.strip()]
                if len(row) == 3:
                    # Genuine pair: name, num1, num2
                    name, n1, n2 = row[0], int(row[1]), int(row[2])
                    p1 = os.path.join(self.lfw_dir, name, f"{name}_{n1:04d}.jpg")
                    p2 = os.path.join(self.lfw_dir, name, f"{name}_{n2:04d}.jpg")
                    pairs.append({"path1": p1, "path2": p2, "is_same": True})
                elif len(row) >= 4:
                    # Impostor pair: name1, num1, name2, num2
                    name1, n1, name2, n2 = row[0], int(row[1]), row[2], int(row[3])
                    p1 = os.path.join(self.lfw_dir, name1, f"{name1}_{n1:04d}.jpg")
                    p2 = os.path.join(self.lfw_dir, name2, f"{name2}_{n2:04d}.jpg")
                    pairs.append({"path1": p1, "path2": p2, "is_same": False})
        return pairs

    def _load_and_preprocess(self, path: str) -> torch.Tensor:
        with Image.open(path) as img:
            img = img.convert("RGB").resize(self.img_size, Image.Resampling.BILINEAR)
            arr = (np.array(img, dtype=np.float32) - 127.5) / 128.0
            tensor = torch.from_numpy(arr.transpose(2, 0, 1)).unsqueeze(0)
            return tensor

    @torch.no_grad()
    def evaluate(self, model: torch.nn.Module, device: torch.device, batch_size: int = 64, use_flip_tta: bool = False) -> Dict[str, Any]:
        """
        Runs full 10-fold cross validation on LFW 6,000 pairs.
        Optionally enables Test-Time Augmentation (horizontal flip fusion) for maximum accuracy.
        """
        model.eval()
        
        # 1. Collect all unique images to minimize inference calls
        all_paths = set()
        for p in self.pairs:
            all_paths.add(p["path1"])
            all_paths.add(p["path2"])
        unique_paths = sorted(list(all_paths))
        
        # 2. Extract embeddings
        path_to_emb: Dict[str, np.ndarray] = {}
        for i in range(0, len(unique_paths), batch_size):
            chunk = unique_paths[i:min(i + batch_size, len(unique_paths))]
            tensors = []
            valid_chunk = []
            for cp in chunk:
                if os.path.exists(cp):
                    tensors.append(self._load_and_preprocess(cp))
                    valid_chunk.append(cp)
            if not tensors:
                continue
            batch_t = torch.cat(tensors, dim=0).to(device)
            out = model(batch_t)
            emb = out["identity_embedding"].cpu().numpy()
            
            if use_flip_tta:
                batch_t_flip = torch.flip(batch_t, dims=[3])
                out_flip = model(batch_t_flip)
                emb_flip = out_flip["identity_embedding"].cpu().numpy()
                emb = emb + emb_flip
                
            for cp, e in zip(valid_chunk, emb):
                norm = np.linalg.norm(e)
                path_to_emb[cp] = e / max(1e-7, norm)
                
        # 3. Compute cosine similarities for pairs
        sims = []
        is_same = []
        for p in self.pairs:
            if p["path1"] in path_to_emb and p["path2"] in path_to_emb:
                e1 = path_to_emb[p["path1"]]
                e2 = path_to_emb[p["path2"]]
                cos_sim = float(np.dot(e1, e2))
                sims.append(cos_sim)
                is_same.append(p["is_same"])
                
        sims = np.array(sims)
        is_same = np.array(is_same)
        
        # Separate genuine and impostor
        gen_sims = sims[is_same == True]
        imp_sims = sims[is_same == False]
        
        # 4. Standard 10-Fold Cross-Validation
        num_pairs = len(sims)
        fold_size = num_pairs // 10
        fold_accuracies = []
        fold_thresholds = []
        
        for fold in range(10):
            val_mask = np.zeros(num_pairs, dtype=bool)
            val_mask[fold * fold_size : (fold + 1) * fold_size] = True
            train_mask = ~val_mask
            
            # Find optimal threshold on 9 training folds
            train_sims = sims[train_mask]
            train_same = is_same[train_mask]
            
            best_thresh = 0.0
            best_acc = 0.0
            # Test 200 threshold candidates between min and max
            for thresh in np.linspace(np.percentile(train_sims, 1), np.percentile(train_sims, 99), 200):
                preds = train_sims >= thresh
                acc = np.mean(preds == train_same)
                if acc > best_acc:
                    best_acc = acc
                    best_thresh = thresh
                    
            # Evaluate on the held-out test fold
            test_preds = sims[val_mask] >= best_thresh
            test_acc = float(np.mean(test_preds == is_same[val_mask]))
            fold_accuracies.append(test_acc)
            fold_thresholds.append(best_thresh)
            
        mean_acc = float(np.mean(fold_accuracies))
        std_acc = float(np.std(fold_accuracies))
        optimal_thresh = float(np.mean(fold_thresholds))
        
        # 5. Operational Verification Metrics (TAR @ FAR)
        sorted_imp = np.sort(imp_sims)
        idx_far_1pct = max(0, int(len(sorted_imp) * 0.99))
        tau_far_1pct = float(sorted_imp[idx_far_1pct])
        tar_at_far_1pct = float(np.mean(gen_sims >= tau_far_1pct))
        
        idx_far_01pct = max(0, int(len(sorted_imp) * 0.999))
        tau_far_01pct = float(sorted_imp[idx_far_01pct])
        tar_at_far_01pct = float(np.mean(gen_sims >= tau_far_01pct))
        
        # Separation d-prime
        mu_gen, std_gen = float(np.mean(gen_sims)), float(np.std(gen_sims))
        mu_imp, std_imp = float(np.mean(imp_sims)), float(np.std(imp_sims))
        d_prime = (mu_gen - mu_imp) / max(1e-6, np.sqrt(0.5 * (std_gen**2 + std_imp**2)))
        
        return {
            "lfw_accuracy_mean": mean_acc,
            "lfw_accuracy_std": std_acc,
            "optimal_threshold": optimal_thresh,
            "genuine_mean": mu_gen,
            "genuine_std": std_gen,
            "impostor_mean": mu_imp,
            "impostor_std": std_imp,
            "d_prime": float(d_prime),
            "tar_at_far_1pct": tar_at_far_1pct,
            "tar_at_far_01pct": tar_at_far_01pct,
            "total_evaluated_pairs": len(sims)
        }

    def evaluate_tflite(self, tflite_path: str, is_cavaface: bool = False, num_threads: int = 8, cache_path: Optional[str] = None) -> Dict[str, Any]:
        """
        Runs full 10-fold cross validation using a TFLite FlatBuffer interpreter.
        """
        all_paths = set()
        for p in self.pairs:
            all_paths.add(p["path1"])
            all_paths.add(p["path2"])
        unique_paths = sorted(list(all_paths))

        path_to_emb: Dict[str, np.ndarray] = {}
        if cache_path is not None and os.path.exists(cache_path):
            print(f"[*] Loading pre-computed embeddings from cache: {cache_path}", flush=True)
            cached_data = np.load(cache_path)
            for k in cached_data.files:
                path_to_emb[k] = cached_data[k]
            print(f"[+] Loaded {len(path_to_emb)} embeddings from cache.", flush=True)
        else:
            from ai_edge_litert.interpreter import Interpreter
            interp = Interpreter(model_path=tflite_path, num_threads=num_threads)
            interp.allocate_tensors()
            in_idx = interp.get_input_details()[0]["index"]
            
            # Find identity embedding output tensor
            out_details = interp.get_output_details()
            id_out_idx = None
            for od in out_details:
                if od["shape"][-1] == 512:
                    id_out_idx = od["index"]
                    break
            if id_out_idx is None:
                id_out_idx = out_details[0]["index"]
                
            print(f"[*] Extracting embeddings for {len(unique_paths)} unique LFW images (threads={num_threads})...", flush=True)
            t_start = time.time()
            for idx, cp in enumerate(unique_paths):
                if not os.path.exists(cp):
                    continue
                with Image.open(cp) as img:
                    img = img.convert("RGB").resize(self.img_size, Image.Resampling.BILINEAR)
                    arr = np.array(img, dtype=np.float32)
                    if is_cavaface:
                        arr = arr / 255.0  # CavaFace range [0.0, 1.0]
                    else:
                        arr = (arr - 127.5) / 128.0  # Unified range [-1.0, 1.0]
                    inp = np.expand_dims(arr, axis=0)
                    interp.set_tensor(in_idx, inp)
                    interp.invoke()
                    emb = interp.get_tensor(id_out_idx)[0].flatten().copy()
                    norm = np.linalg.norm(emb)
                    if norm > 1e-6:
                        emb = emb / norm
                    path_to_emb[cp] = emb
                if (idx + 1) % 1000 == 0 or (idx + 1) == len(unique_paths):
                    elapsed = time.time() - t_start
                    fps = (idx + 1) / max(1e-3, elapsed)
                    print(f"    [{idx+1}/{len(unique_paths)}] Extracted ({fps:.1f} images/s, elapsed: {elapsed:.1f}s)", flush=True)
            
            if cache_path is not None:
                os.makedirs(os.path.dirname(cache_path), exist_ok=True)
                np.savez_compressed(cache_path, **path_to_emb)
                print(f"[+] Saved {len(path_to_emb)} embeddings to cache: {cache_path}", flush=True)

        sims = []
        is_same = []
        for p in self.pairs:
            if p["path1"] in path_to_emb and p["path2"] in path_to_emb:
                e1 = path_to_emb[p["path1"]]
                e2 = path_to_emb[p["path2"]]
                cos_sim = float(np.dot(e1, e2))
                sims.append(cos_sim)
                is_same.append(p["is_same"])
                
        sims = np.array(sims)
        is_same = np.array(is_same)
        gen_sims = sims[is_same == True]
        imp_sims = sims[is_same == False]
        
        num_pairs = len(sims)
        fold_size = num_pairs // 10
        fold_accuracies = []
        fold_thresholds = []
        
        for fold in range(10):
            val_mask = np.zeros(num_pairs, dtype=bool)
            val_mask[fold * fold_size : (fold + 1) * fold_size] = True
            train_mask = ~val_mask
            
            train_sims = sims[train_mask]
            train_same = is_same[train_mask]
            best_thresh = 0.0
            best_acc = 0.0
            for thresh in np.linspace(np.percentile(train_sims, 1), np.percentile(train_sims, 99), 200):
                preds = train_sims >= thresh
                acc = np.mean(preds == train_same)
                if acc > best_acc:
                    best_acc = acc
                    best_thresh = thresh
            test_preds = sims[val_mask] >= best_thresh
            test_acc = float(np.mean(test_preds == is_same[val_mask]))
            fold_accuracies.append(test_acc)
            fold_thresholds.append(best_thresh)
            
        mean_acc = float(np.mean(fold_accuracies))
        std_acc = float(np.std(fold_accuracies))
        optimal_thresh = float(np.mean(fold_thresholds))
        
        sorted_imp = np.sort(imp_sims)
        idx_far_1pct = max(0, int(len(sorted_imp) * 0.99))
        tau_far_1pct = float(sorted_imp[idx_far_1pct])
        tar_at_far_1pct = float(np.mean(gen_sims >= tau_far_1pct))
        
        idx_far_01pct = max(0, int(len(sorted_imp) * 0.999))
        tau_far_01pct = float(sorted_imp[idx_far_01pct])
        tar_at_far_01pct = float(np.mean(gen_sims >= tau_far_01pct))
        
        mu_gen, std_gen = float(np.mean(gen_sims)), float(np.std(gen_sims))
        mu_imp, std_imp = float(np.mean(imp_sims)), float(np.std(imp_sims))
        d_prime = (mu_gen - mu_imp) / max(1e-6, np.sqrt(0.5 * (std_gen**2 + std_imp**2)))
        
        return {
            "lfw_accuracy_mean": mean_acc,
            "lfw_accuracy_std": std_acc,
            "optimal_threshold": optimal_thresh,
            "genuine_mean": mu_gen,
            "genuine_std": std_gen,
            "impostor_mean": mu_imp,
            "impostor_std": std_imp,
            "d_prime": float(d_prime),
            "tar_at_far_1pct": tar_at_far_1pct,
            "tar_at_far_01pct": tar_at_far_01pct,
            "total_evaluated_pairs": len(sims)
        }

