import os
import time
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader
from typing import Dict, Any, Optional, List
from training.unified.configs.base_config import TrainingConfig, LossWeightsConfig
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset
from training.unified.losses.priority_guarded_loss import PriorityGuardedMultiTaskLoss
from training.unified.evaluation.evaluator import BiometricEvaluator

def collate_fn(batch):
    faces = torch.stack([b[0] for b in batch])
    targets = {
        "identity_label": torch.stack([b[1]["identity_label"] for b in batch]),
        "pad_label": torch.stack([b[1]["pad_label"] for b in batch]),
        "mesh_landmarks": torch.stack([b[1]["mesh_landmarks"] for b in batch]),
        "geom_3dmm": torch.stack([b[1]["geom_3dmm"] for b in batch]),
        "quality_scores": torch.stack([b[1]["quality_scores"] for b in batch]),
        "gaze_angles": torch.stack([b[1]["gaze_angles"] for b in batch]),
        "attribute_probs": torch.stack([b[1]["attribute_probs"] for b in batch])
    }
    if "teacher_emb" in batch[0][1] and batch[0][1]["teacher_emb"] is not None:
        targets["teacher_emb"] = torch.stack([b[1]["teacher_emb"] for b in batch])
    if "teacher_pad_logits" in batch[0][1] and batch[0][1]["teacher_pad_logits"] is not None:
        targets["teacher_pad_logits"] = torch.stack([b[1]["teacher_pad_logits"] for b in batch])
    metas = [b[2] for b in batch]
    return faces, targets, metas

class MultiTaskTrainer:
    """
    End-to-End Trainer for OmniFaceUnifiedModelV2 with Priority-Guarded Objectives.
    Optimized for NVIDIA GeForce RTX 5060 Laptop GPU with Mixed Precision (AMP).
    """
    def __init__(
        self,
        model: torch.nn.Module,
        train_dataset: OmniFaceMultiTaskDataset,
        val_dataset: OmniFaceMultiTaskDataset,
        config: TrainingConfig = TrainingConfig(),
        loss_weights: LossWeightsConfig = LossWeightsConfig(),
        device: Optional[torch.device] = None,
        checkpoint_dir: str = "training/unified/checkpoints"
    ):
        self.device = device or (torch.device("cuda") if torch.cuda.is_available() else torch.device("cpu"))
        self.model = model.to(self.device)
        self.train_dataset = train_dataset
        self.val_dataset = val_dataset
        self.config = config
        self.checkpoint_dir = checkpoint_dir
        os.makedirs(self.checkpoint_dir, exist_ok=True)
        
        self.loss_fn = PriorityGuardedMultiTaskLoss(
            num_identities=train_dataset.num_identities,
            lambda_id=loss_weights.lambda_id,
            lambda_pad=loss_weights.lambda_pad
        ).to(self.device)
        
        self.optimizer = optim.AdamW(
            list(self.model.parameters()) + list(self.loss_fn.parameters()),
            lr=config.learning_rate,
            weight_decay=config.weight_decay
        )
        self.scaler = torch.amp.GradScaler("cuda", enabled=(self.device.type == "cuda"))
        self.evaluator = BiometricEvaluator(self.model, self.device)
        self.best_checkpoint_score = -float("inf")

    def train_epoch(self, dataloader: DataLoader) -> Dict[str, float]:
        self.model.train()
        total_loss_accum = 0.0
        steps = 0
        use_cuda = (self.device.type == "cuda")
        
        for faces, targets, _ in dataloader:
            faces = faces.to(self.device, non_blocking=use_cuda)
            targets = {k: v.to(self.device, non_blocking=use_cuda) if isinstance(v, torch.Tensor) else v for k, v in targets.items()}
            
            self.optimizer.zero_grad()
            with torch.amp.autocast("cuda", enabled=use_cuda):
                preds = self.model(faces)
                loss, metrics = self.loss_fn(preds, targets)
            
            all_params = list(self.model.parameters()) + list(self.loss_fn.parameters())
            if use_cuda:
                self.scaler.scale(loss).backward()
                self.scaler.unscale_(self.optimizer)
                torch.nn.utils.clip_grad_norm_(all_params, self.config.gradient_clip_val)
                self.scaler.step(self.optimizer)
                self.scaler.update()
            else:
                loss.backward()
                torch.nn.utils.clip_grad_norm_(all_params, self.config.gradient_clip_val)
                self.optimizer.step()
            
            total_loss_accum += loss.item()
            steps += 1
            
        return {"train_loss": total_loss_accum / max(1, steps)}

    def run_training_cycle(self, epochs: int = 10, batch_size: int = 32) -> Dict[str, Any]:
        bs = min(len(self.train_dataset), batch_size)
        loader = DataLoader(
            self.train_dataset,
            batch_size=bs,
            shuffle=True,
            collate_fn=collate_fn,
            num_workers=0,
            pin_memory=(self.device.type == "cuda")
        )
        history = []
        
        print(f"[*] Starting Multi-Task Training on {self.device.type.upper()} ({self.device}) for {epochs} epochs (Batch size: {bs})...", flush=True)
        
        for ep in range(epochs):
            t0 = time.time()
            train_metrics = self.train_epoch(loader)
            
            # Security-gated evaluation on validation set
            verif_metrics = self.evaluator.evaluate_verification(self.val_dataset, num_genuine=50, num_impostor=200)
            pad_metrics = self.evaluator.evaluate_pad(self.val_dataset)
            
            score = verif_metrics["tar_at_far_1pct"] - pad_metrics["acer"]
            is_best = score > self.best_checkpoint_score
            if is_best:
                self.best_checkpoint_score = score
                ckpt_path = os.path.join(self.checkpoint_dir, "best_unified_model_v1.pt")
                torch.save({
                    "epoch": ep + 1,
                    "model_state_dict": self.model.state_dict(),
                    "loss_fn_state_dict": self.loss_fn.state_dict(),
                    "optimizer_state_dict": self.optimizer.state_dict(),
                    "score": score,
                    "tar_at_far_1pct": verif_metrics["tar_at_far_1pct"],
                    "pad_acer": pad_metrics["acer"],
                    "verif_metrics": verif_metrics,
                    "pad_metrics": pad_metrics
                }, ckpt_path)
                
            elapsed = time.time() - t0
            record = {
                "epoch": ep + 1,
                "train_loss": train_metrics["train_loss"],
                "tar_at_far_1pct": verif_metrics["tar_at_far_1pct"],
                "pad_acer": pad_metrics["acer"],
                "is_best": is_best,
                "epoch_time_sec": elapsed
            }
            history.append(record)
            
            star = " * [BEST]" if is_best else ""
            print(
                f"[Epoch {ep+1:02d}/{epochs:02d}] "
                f"Loss: {train_metrics['train_loss']:.4f} | "
                f"TAR@1%: {verif_metrics['tar_at_far_1pct']*100:.1f}% | "
                f"PAD ACER: {pad_metrics['acer']*100:.1f}% | "
                f"Gen/Imp: {verif_metrics['mean_genuine_dist']:.3f}/{verif_metrics['mean_impostor_dist']:.3f} | "
                f"Time: {elapsed:.2f}s{star}",
                flush=True
            )
            
        print(f"[+] Training complete. Best Score: {self.best_checkpoint_score:.4f}", flush=True)
        return {"history": history, "best_score": self.best_checkpoint_score}
