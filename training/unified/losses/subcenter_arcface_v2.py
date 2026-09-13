import math
from typing import Dict, Tuple, Optional
import torch
import torch.nn as nn
import torch.nn.functional as F

class SubCenterArcFaceLoss(nn.Module):
    """
    Sub-Center ArcFace Loss (Deng et al., ECCV 2020).
    Employs K sub-centers per identity class to absorb intra-class variance
    (pose, illumination, age) and prevent hypersphere cluster fragmentation.
    """
    def __init__(
        self,
        embedding_dim: int = 512,
        num_classes: int = 1000,
        sub_centers: int = 3,
        scale: float = 64.0,
        margin: float = 0.40,
        easy_margin: bool = False
    ):
        super().__init__()
        self.embedding_dim = embedding_dim
        self.num_classes = num_classes
        self.sub_centers = sub_centers
        self.scale = scale
        self.margin = margin
        self.easy_margin = easy_margin
        
        # Total centers = num_classes * sub_centers
        self.weight = nn.Parameter(torch.FloatTensor(num_classes * sub_centers, embedding_dim))
        nn.init.xavier_uniform_(self.weight)
        
        self.cos_m = math.cos(margin)
        self.sin_m = math.sin(margin)
        self.th = math.cos(math.pi - margin)
        self.mm = math.sin(math.pi - margin) * margin

    def forward(self, embeddings: torch.Tensor, labels: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Args:
            embeddings: [B, D] L2-normalized feature embeddings
            labels: [B] Class labels in [0, num_classes - 1]
        Returns:
            loss: scalar Cross-Entropy ArcFace loss
            logits: [B, num_classes] similarity logits
        """
        # Normalize weights
        norm_w = F.normalize(self.weight, p=2, dim=1)
        # Cosine similarity: [B, num_classes * K]
        cosine_all = F.linear(embeddings, norm_w)
        
        # Reshape to [B, num_classes, K]
        cosine_reshaped = cosine_all.view(-1, self.num_classes, self.sub_centers)
        
        # Select highest-similarity sub-center per class: [B, num_classes]
        cosine, _ = torch.max(cosine_reshaped, dim=2)
        cosine = cosine.clamp(-1.0 + 1e-7, 1.0 - 1e-7)
        
        # Compute marginal cosine: cos(theta + m) = cos(theta)*cos(m) - sin(theta)*sin(m)
        sine = torch.sqrt(1.0 - torch.pow(cosine, 2)).clamp(0.0, 1.0)
        phi = cosine * self.cos_m - sine * self.sin_m
        
        if self.easy_margin:
            phi = torch.where(cosine > 0, phi, cosine)
        else:
            phi = torch.where(cosine > self.th, phi, cosine - self.mm)
            
        # One-hot mask for ground truth labels
        one_hot = torch.zeros(cosine.size(), device=embeddings.device)
        one_hot.scatter_(1, labels.view(-1, 1).long(), 1.0)
        
        # Apply margin to positive class only
        output = (one_hot * phi) + ((1.0 - one_hot) * cosine)
        output = output * self.scale
        
        loss = F.cross_entropy(output, labels)
        return loss, output

class BoundedHardNegativeLoss(nn.Module):
    """
    Identity-aware bounded in-batch hard negative loss.
    Operates on P x K mini-batches with stability guards against outliers.
    """
    def __init__(self, margin: float = 0.20, max_penalty: float = 1.0):
        super().__init__()
        self.margin = margin
        self.max_penalty = max_penalty

    def forward(self, embeddings: torch.Tensor, labels: torch.Tensor) -> torch.Tensor:
        """
        Args:
            embeddings: [B, D] L2-normalized embeddings
            labels: [B] Class labels
        """
        b = embeddings.size(0)
        if b < 4:
            return torch.tensor(0.0, device=embeddings.device, requires_grad=True)
            
        # Pairwise cosine matrix [B, B]
        sim_mat = torch.matmul(embeddings, embeddings.t())
        
        # Masks
        labels_eq = labels.unsqueeze(0) == labels.unsqueeze(1)
        eye_mask = torch.eye(b, dtype=torch.bool, device=embeddings.device)
        
        pos_mask = labels_eq & (~eye_mask)
        neg_mask = ~labels_eq
        
        losses = []
        for i in range(b):
            pos_indices = pos_mask[i].nonzero(as_tuple=True)[0]
            neg_indices = neg_mask[i].nonzero(as_tuple=True)[0]
            
            if len(pos_indices) == 0 or len(neg_indices) == 0:
                continue
                
            # Hardest positive (minimum similarity among positives)
            hardest_pos = torch.min(sim_mat[i, pos_indices])
            # Hardest negative (maximum similarity among negatives)
            hardest_neg = torch.max(sim_mat[i, neg_indices])
            
            diff = hardest_neg - hardest_pos + self.margin
            if diff > 0:
                losses.append(torch.clamp(diff, max=self.max_penalty))
                
        if len(losses) == 0:
            return torch.tensor(0.0, device=embeddings.device, requires_grad=True)
            
        return torch.stack(losses).mean()

class CavaFaceDistillationLoss(nn.Module):
    """
    Cosine distance distillation between student embedding and CavaFace teacher embedding.
    """
    def forward(self, student_emb: torch.Tensor, teacher_emb: torch.Tensor) -> torch.Tensor:
        cos = torch.sum(student_emb * teacher_emb, dim=-1)
        return torch.mean(1.0 - cos)

class UnifiedIdentityLossV2(nn.Module):
    """
    Master Composite Identity Loss for UnifiedFaceModel V2:
      L_id = L_arcface + lambda_distill * L_cava_distill + lambda_neg * L_hard_neg
    """
    def __init__(
        self,
        num_classes: int,
        embedding_dim: int = 512,
        sub_centers: int = 3,
        scale: float = 64.0,
        arc_margin: float = 0.40,
        triplet_margin: float = 0.20,
        lambda_distill: float = 0.50,
        lambda_neg: float = 0.20
    ):
        super().__init__()
        self.subcenter_arcface = SubCenterArcFaceLoss(
            embedding_dim=embedding_dim,
            num_classes=num_classes,
            sub_centers=sub_centers,
            scale=scale,
            margin=arc_margin
        )
        self.cava_distill = CavaFaceDistillationLoss()
        self.hard_negative = BoundedHardNegativeLoss(margin=triplet_margin)
        
        self.lambda_distill = lambda_distill
        self.lambda_neg = lambda_neg

    def forward(
        self,
        student_emb: torch.Tensor,
        labels: torch.Tensor,
        teacher_emb: Optional[torch.Tensor] = None
    ) -> Tuple[torch.Tensor, Dict[str, float]]:
        loss_arc, _ = self.subcenter_arcface(student_emb, labels)
        metrics = {"loss_arc": float(loss_arc.item())}
        total_loss = loss_arc
        
        if teacher_emb is not None:
            loss_dist = self.cava_distill(student_emb, teacher_emb)
            total_loss = total_loss + self.lambda_distill * loss_dist
            metrics["loss_distill"] = float(loss_dist.item())
        else:
            metrics["loss_distill"] = 0.0
            
        loss_neg = self.hard_negative(student_emb, labels)
        total_loss = total_loss + self.lambda_neg * loss_neg
        metrics["loss_hard_neg"] = float(loss_neg.item())
        metrics["loss_id_total"] = float(total_loss.item())
        
        return total_loss, metrics
