import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Dict, Tuple

class ArcFaceLoss(nn.Module):
    """ArcFace Cosine Margin Loss for Identity Head"""
    def __init__(self, in_features=512, num_classes=105, s=64.0, m=0.50):
        super().__init__()
        self.in_features = in_features
        self.num_classes = num_classes
        self.s = s
        self.m = m
        self.weight = nn.Parameter(torch.FloatTensor(num_classes, in_features))
        nn.init.xavier_uniform_(self.weight)
        
        self.cos_m = torch.cos(torch.tensor(m))
        self.sin_m = torch.sin(torch.tensor(m))
        self.th = torch.cos(torch.tensor(3.14159265 - m))
        self.mm = torch.sin(torch.tensor(3.14159265 - m)) * m
        
    def forward(self, embedding: torch.Tensor, labels: torch.Tensor) -> torch.Tensor:
        cosine = F.linear(embedding, F.normalize(self.weight, p=2, dim=-1))
        sine = torch.sqrt(torch.clamp(1.0 - torch.pow(cosine, 2), 1e-7, 1.0))
        phi = cosine * self.cos_m - sine * self.sin_m
        phi = torch.where(cosine > self.th, phi, cosine - self.mm)
        
        one_hot = torch.zeros_like(cosine)
        one_hot.scatter_(1, labels.view(-1, 1).long(), 1.0)
        output = (one_hot * phi) + ((1.0 - one_hot) * cosine)
        output *= self.s
        return F.cross_entropy(output, labels.long())

class PriorityGuardedMultiTaskLoss(nn.Module):
    """
    Priority-Guarded Multi-Task Loss with Homoscedastic Uncertainty Balancing.
    Priority 0 (Identity and PAD) are strictly guarded with floor weights >= 1.0.
    Priority 1 & 2 (Mesh, 3DMM, Gaze, Quality, Attributes) are dynamically balanced.
    """
    def __init__(self, num_identities=105, lambda_id=1.0, lambda_pad=1.0):
        super().__init__()
        self.lambda_id = max(1.0, lambda_id)
        self.lambda_pad = max(1.0, lambda_pad)
        
        self.arcface = ArcFaceLoss(in_features=512, num_classes=num_identities)
        self.ce_pad = nn.CrossEntropyLoss()
        self.l1_loss = nn.SmoothL1Loss()
        self.bce_loss = nn.BCELoss()
        
        # Learnable log variances for auxiliary tasks: log(sigma_k^2)
        self.log_var_mesh = nn.Parameter(torch.tensor(0.0))
        self.log_var_geom = nn.Parameter(torch.tensor(0.0))
        self.log_var_gaze = nn.Parameter(torch.tensor(0.0))
        self.log_var_quality = nn.Parameter(torch.tensor(0.0))
        self.log_var_attr = nn.Parameter(torch.tensor(0.0))
        
    def forward(self, preds: Dict[str, torch.Tensor], targets: Dict[str, torch.Tensor]) -> Tuple[torch.Tensor, Dict[str, float]]:
        # P0: Identity Loss (Protected)
        loss_id = self.arcface(preds["identity_embedding"], targets["identity_label"])
        
        # P0: PAD Loss (Protected)
        loss_pad = self.ce_pad(preds["pad_logits"], targets["pad_label"])
        
        # P1: Mesh Landmark Loss (Auxiliary)
        raw_loss_mesh = self.l1_loss(preds["mesh_landmarks"], targets["mesh_landmarks"])
        prec_mesh = torch.exp(-self.log_var_mesh)
        loss_mesh = 0.5 * prec_mesh * raw_loss_mesh + 0.5 * self.log_var_mesh
        
        # P1: 3DMM Geometry Loss (Auxiliary)
        raw_loss_geom = self.l1_loss(preds["geom_3dmm"], targets["geom_3dmm"])
        prec_geom = torch.exp(-self.log_var_geom)
        loss_geom = 0.5 * prec_geom * raw_loss_geom + 0.5 * self.log_var_geom
        
        # P1: Quality Loss (Auxiliary)
        raw_loss_quality = self.bce_loss(preds["quality_scores"], targets["quality_scores"])
        prec_quality = torch.exp(-self.log_var_quality)
        loss_quality = 0.5 * prec_quality * raw_loss_quality + 0.5 * self.log_var_quality
        
        # P2: Gaze Loss (Auxiliary)
        raw_loss_gaze = self.l1_loss(preds["gaze_angles"], targets["gaze_angles"])
        prec_gaze = torch.exp(-self.log_var_gaze)
        loss_gaze = 0.5 * prec_gaze * raw_loss_gaze + 0.5 * self.log_var_gaze
        
        # P2: Attribute Loss (Auxiliary)
        raw_loss_attr = self.bce_loss(preds["attribute_probs"], targets["attribute_probs"])
        prec_attr = torch.exp(-self.log_var_attr)
        loss_attr = 0.5 * prec_attr * raw_loss_attr + 0.5 * self.log_var_attr
        
        total_loss = (
            self.lambda_id * loss_id +
            self.lambda_pad * loss_pad +
            loss_mesh +
            loss_geom +
            loss_quality +
            loss_gaze +
            loss_attr
        )
        
        metrics = {
            "loss_total": total_loss.item(),
            "loss_id": loss_id.item(),
            "loss_pad": loss_pad.item(),
            "raw_loss_mesh": raw_loss_mesh.item(),
            "raw_loss_geom": raw_loss_geom.item(),
            "raw_loss_quality": raw_loss_quality.item(),
            "raw_loss_gaze": raw_loss_gaze.item(),
            "raw_loss_attr": raw_loss_attr.item()
        }
        return total_loss, metrics
