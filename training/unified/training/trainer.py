import os
import torch
import torch.optim as optim
from torch.utils.data import DataLoader
from typing import Dict, Any, Optional
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
    metas = [b[2] for b in batch]
    return faces, targets, metas

class MultiTaskTrainer:
    """
    End-to-End Trainer for OmniFaceUnifiedModelV2 with Priority-Guarded Objectives.
    """
    def __init__(
        self,
        model: torch.nn.Module,
        train_dataset: OmniFaceMultiTaskDataset,
        val_dataset: OmniFaceMultiTaskDataset,
        config: TrainingConfig = TrainingConfig(),
        loss_weights: LossWeightsConfig = LossWeightsConfig(),
        device: Optional[torch.device] = None
    ):
        self.device = device or (torch.device("cuda") if torch.cuda.is_available() else torch.device("cpu"))
        self.model = model.to(self.device)
        self.train_dataset = train_dataset
        self.val_dataset = val_dataset
        self.config = config
        
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
        self.evaluator = BiometricEvaluator(self.model, self.device)
        self.best_checkpoint_score = -float("inf")

    def train_epoch(self, dataloader: DataLoader) -> Dict[str, float]:
        self.model.train()
        total_loss_accum = 0.0
        steps = 0
        
        for faces, targets, _ in dataloader:
            faces = faces.to(self.device)
            targets = {k: v.to(self.device) for k, v in targets.items()}
            
            self.optimizer.zero_grad()
            preds = self.model(faces)
            loss, metrics = self.loss_fn(preds, targets)
            
            loss.backward()
            torch.nn.utils.clip_grad_norm_(self.model.parameters(), self.config.gradient_clip_val)
            self.optimizer.step()
            
            total_loss_accum += loss.item()
            steps += 1
            
        return {"train_loss": total_loss_accum / max(1, steps)}

    def run_training_cycle(self, epochs: int = 1) -> Dict[str, Any]:
        loader = DataLoader(self.train_dataset, batch_size=min(len(self.train_dataset), 8), shuffle=True, collate_fn=collate_fn)
        history = []
        
        for ep in range(epochs):
            train_metrics = self.train_epoch(loader)
            verif_metrics = self.evaluator.evaluate_verification(self.val_dataset, num_genuine=10, num_impostor=50)
            pad_metrics = self.evaluator.evaluate_pad(self.val_dataset)
            
            # Security-gated selection score: high TAR, low ACER
            score = verif_metrics["tar_at_far_1pct"] - pad_metrics["acer"]
            is_best = score > self.best_checkpoint_score
            if is_best:
                self.best_checkpoint_score = score
                
            record = {
                "epoch": ep + 1,
                "train_loss": train_metrics["train_loss"],
                "tar_at_far_1pct": verif_metrics["tar_at_far_1pct"],
                "pad_acer": pad_metrics["acer"],
                "is_best": is_best
            }
            history.append(record)
            
        return {"history": history, "best_score": self.best_checkpoint_score}
