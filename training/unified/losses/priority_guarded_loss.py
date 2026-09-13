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
        
        self.register_buffer("cos_m", torch.cos(torch.tensor(m)))
        self.register_buffer("sin_m", torch.sin(torch.tensor(m)))
        self.register_buffer("th", torch.cos(torch.tensor(3.14159265 - m)))
        self.register_buffer("mm", torch.sin(torch.tensor(3.14159265 - m)) * m)
        
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
        # P0: Identity Loss (ArcFace + CavaFace Distillation)
        loss_id = self.arcface(preds["identity_embedding"], targets["identity_label"])
        loss_id_distill = torch.tensor(0.0, device=preds["identity_embedding"].device)
        if "teacher_emb" in targets and targets["teacher_emb"] is not None:
            t_emb = F.normalize(targets["teacher_emb"].to(preds["identity_embedding"].device), p=2, dim=-1)
            cos_sim = F.cosine_similarity(preds["identity_embedding"], t_emb)
            loss_id_distill = (1.0 - cos_sim).mean()
            loss_id = loss_id + 2.0 * loss_id_distill

        # P0: PAD Loss (CrossEntropy + SilentFace Distillation)
        loss_pad = self.ce_pad(preds["pad_logits"], targets["pad_label"])
        loss_pad_distill = torch.tensor(0.0, device=preds["pad_logits"].device)
        if "teacher_pad_logits" in targets and targets["teacher_pad_logits"] is not None:
            t_pad = targets["teacher_pad_logits"].to(preds["pad_logits"].device)
            loss_pad_distill = F.kl_div(
                F.log_softmax(preds["pad_logits"], dim=-1),
                F.softmax(t_pad, dim=-1),
                reduction="batchmean"
            )
            loss_pad = loss_pad + 0.5 * loss_pad_distill
        
        # P1: Mesh Landmark Loss (Auxiliary)
        raw_loss_mesh = self.l1_loss(preds["mesh_landmarks"], targets["mesh_landmarks"])
        prec_mesh = torch.exp(-self.log_var_mesh)
        loss_mesh = 0.5 * prec_mesh * raw_loss_mesh + 0.5 * self.log_var_mesh
        
        # P1: 3DMM Geometry Loss (Auxiliary)
        raw_loss_geom = self.l1_loss(preds["geom_3dmm"], targets["geom_3dmm"])
        prec_geom = torch.exp(-self.log_var_geom)
        loss_geom = 0.5 * prec_geom * raw_loss_geom + 0.5 * self.log_var_geom
        
        # P1: Quality Loss (Auxiliary)
        clamped_quality = torch.clamp(preds["quality_scores"].float(), 1e-6, 1.0 - 1e-6)
        with torch.amp.autocast("cuda", enabled=False):
            raw_loss_quality = F.binary_cross_entropy(clamped_quality, targets["quality_scores"].float())
        prec_quality = torch.exp(-self.log_var_quality)
        loss_quality = 0.5 * prec_quality * raw_loss_quality + 0.5 * self.log_var_quality
        
        # P2: Gaze Loss (Auxiliary)
        raw_loss_gaze = self.l1_loss(preds["gaze_angles"], targets["gaze_angles"])
        prec_gaze = torch.exp(-self.log_var_gaze)
        loss_gaze = 0.5 * prec_gaze * raw_loss_gaze + 0.5 * self.log_var_gaze
        
        # P2: Attribute Loss (Auxiliary)
        clamped_attr = torch.clamp(preds["attribute_probs"].float(), 1e-6, 1.0 - 1e-6)
        with torch.amp.autocast("cuda", enabled=False):
            raw_loss_attr = F.binary_cross_entropy(clamped_attr, targets["attribute_probs"].float())
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
            "loss_id_distill": loss_id_distill.item(),
            "loss_pad": loss_pad.item(),
            "loss_pad_distill": loss_pad_distill.item(),
            "raw_loss_mesh": raw_loss_mesh.item(),
            "raw_loss_geom": raw_loss_geom.item(),
            "raw_loss_quality": raw_loss_quality.item(),
            "raw_loss_gaze": raw_loss_gaze.item(),
            "raw_loss_attr": raw_loss_attr.item()
        }
        return total_loss, metrics
