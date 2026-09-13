import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Dict, Optional

class IdentityHead(nn.Module):
    """Identity Recognition Head: [B, C, 7, 7] -> [B, 512] L2-normalized embedding"""
    def __init__(self, in_channels=512, embedding_dim=512, dropout_rate=0.1):
        super().__init__()
        self.gdc = nn.Sequential(
            nn.Conv2d(in_channels, in_channels, kernel_size=7, stride=1, padding=0, groups=in_channels, bias=False),
            nn.BatchNorm2d(in_channels),
            nn.Dropout2d(p=dropout_rate),
            nn.Conv2d(in_channels, embedding_dim, kernel_size=1, stride=1, padding=0, bias=False),
            nn.BatchNorm2d(embedding_dim)
        )
        
    def forward(self, x):
        x = self.gdc(x)          # [B, 512, 1, 1]
        x = x.flatten(start_dim=1)  # [B, 512]
        return F.normalize(x, p=2, dim=-1)  # Strictly L2-normalized

class PassivePadHead(nn.Module):
    """Passive PAD Head: [B, C, 7, 7] -> [B, 3] logits [Bona Fide, Print, Screen]"""
    def __init__(self, in_channels=512, num_classes=3):
        super().__init__()
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 128),
            nn.BatchNorm1d(128),
            nn.Hardswish(inplace=True),
            nn.Dropout(p=0.1),
            nn.Linear(128, num_classes)
        )
    def forward(self, x):
        return self.net(x)

class DenseMeshHead(nn.Module):
    """Dense 3D Mesh / Landmark Regression: [B, C, 7, 7] -> [B, N*3]"""
    def __init__(self, in_channels=512, num_points=468):
        super().__init__()
        self.num_points = num_points
        self.out_dim = num_points * 3
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 512),
            nn.BatchNorm1d(512),
            nn.Hardswish(inplace=True),
            nn.Linear(512, self.out_dim)
        )
    def forward(self, x):
        out = self.net(x)
        return out.view(-1, self.num_points, 3)

class Geometry3DMMHead(nn.Module):
    """3D Morphable Model Parameter Regression: [B, C, 7, 7] -> [B, 265]"""
    def __init__(self, in_channels=512, num_params=265):
        super().__init__()
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 384),
            nn.BatchNorm1d(384),
            nn.Hardswish(inplace=True),
            nn.Linear(384, num_params)
        )
    def forward(self, x):
        return self.net(x)

class FaceQualityHead(nn.Module):
    """Face Quality Assessment: [B, C, 7, 7] -> [B, 4] in [0, 1]"""
    def __init__(self, in_channels=512):
        super().__init__()
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 64),
            nn.Hardswish(inplace=True),
            nn.Linear(64, 4),
            nn.Sigmoid()
        )
    def forward(self, x):
        return self.net(x)

class EyeGazeHead(nn.Module):
    """Gaze Regression: [B, C, 7, 7] -> [B, 2] [pitch, yaw]"""
    def __init__(self, in_channels=512):
        super().__init__()
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 64),
            nn.Hardswish(inplace=True),
            nn.Linear(64, 2)
        )
    def forward(self, x):
        return self.net(x)

class AttributeHead(nn.Module):
    """Facial Attributes: [B, C, 7, 7] -> [B, 5] probabilities"""
    def __init__(self, in_channels=512, num_attrs=5):
        super().__init__()
        self.net = nn.Sequential(
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Linear(in_channels, 64),
            nn.Hardswish(inplace=True),
            nn.Linear(64, num_attrs),
            nn.Sigmoid()
        )
    def forward(self, x):
        return self.net(x)

class OmniFaceUnifiedModelV2(nn.Module):
    """
    True Multi-Task Unified Biometric Model.
    Single shared learned backbone + 6 specialized task heads.
    Input: [B, 3, 112, 112] RGB
    """
    def __init__(self, backbone: nn.Module, feature_channels: int = 512, num_mesh_points: int = 468):
        super().__init__()
        self.backbone = backbone
        self.feature_channels = feature_channels
        self.num_mesh_points = num_mesh_points
        
        # Priority 0: Protected Heads
        self.identity_head = IdentityHead(in_channels=feature_channels, embedding_dim=512)
        self.pad_head = PassivePadHead(in_channels=feature_channels, num_classes=3)
        
        # Priority 1: Biometric Support Heads
        self.mesh_head = DenseMeshHead(in_channels=feature_channels, num_points=num_mesh_points)
        self.geom_head = Geometry3DMMHead(in_channels=feature_channels, num_params=265)
        self.quality_head = FaceQualityHead(in_channels=feature_channels)
        
        # Priority 2: Auxiliary Heads
        self.gaze_head = EyeGazeHead(in_channels=feature_channels)
        self.attr_head = AttributeHead(in_channels=feature_channels, num_attrs=5)
        
    def forward(self, x: torch.Tensor) -> Dict[str, torch.Tensor]:
        features = self.backbone(x)  # [B, 512, 7, 7]
        
        return {
            "identity_embedding": self.identity_head(features),      # [B, 512]
            "pad_logits": self.pad_head(features),                  # [B, 3]
            "mesh_landmarks": self.mesh_head(features),              # [B, N, 3]
            "geom_3dmm": self.geom_head(features),                  # [B, 265]
            "quality_scores": self.quality_head(features),          # [B, 4]
            "gaze_angles": self.gaze_head(features),                # [B, 2]
            "attribute_probs": self.attr_head(features)             # [B, 5]
        }
