from dataclasses import dataclass, field
from enum import Enum
from typing import Tuple

class BackboneType(str, Enum):
    MOBILENET_V4_CONV_SMALL = "mobilenet_v4_conv_small"
    GHOSTNET_V2 = "ghostnet_v2"
    EDGENEXT_SMALL = "edgenext_small"

class LandmarkMode(str, Enum):
    FULL_468 = "full_468"
    COMPACT_106 = "compact_106"

@dataclass
class StudentArchitectureConfig:
    backbone_type: BackboneType = BackboneType.MOBILENET_V4_CONV_SMALL
    input_shape: Tuple[int, int, int] = (3, 112, 112)
    feature_channels: int = 512
    embedding_dim: int = 512
    pad_classes: int = 3
    landmark_mode: LandmarkMode = LandmarkMode.FULL_468
    landmark_dim: int = 468 * 3  # 1404 floats for 468-pt 3D mesh
    geom_3dmm_dim: int = 265
    gaze_dim: int = 2
    quality_dim: int = 4
    attribute_dim: int = 5
    dropout_rate: float = 0.1

@dataclass
class LossWeightsConfig:
    # Priority 0: Security Critical (Hard Lower Bound >= 1.0)
    lambda_id: float = 1.0
    lambda_pad: float = 1.0
    
    # Priority 1 & 2: Auxiliary (Dynamically Balanced or Uncertainty Weighted)
    use_uncertainty_weighting: bool = True
    initial_log_var_mesh: float = 0.0
    initial_log_var_geom: float = 0.0
    initial_log_var_gaze: float = 0.0
    initial_log_var_quality: float = 0.0
    initial_log_var_attr: float = 0.0
    
    # Static fallback weights if uncertainty weighting is disabled
    lambda_mesh: float = 0.10
    lambda_geom: float = 0.15
    lambda_gaze: float = 0.05
    lambda_quality: float = 0.05
    lambda_attr: float = 0.02
    
    # ArcFace Hyperparameters
    arcface_scale: float = 64.0
    arcface_margin: float = 0.50

@dataclass
class TrainingConfig:
    experiment_id: str = "exp_unified_v2_baseline"
    model_version: str = "2.0.0-alpha1"
    seed: int = 42
    batch_size: int = 128
    val_batch_size: int = 64
    learning_rate: float = 1e-3
    min_learning_rate: float = 1e-6
    weight_decay: float = 1e-4
    warmup_epochs: int = 5
    total_epochs: int = 60
    gradient_clip_val: float = 5.0
    mixed_precision: bool = True
    num_workers: int = 4

@dataclass
class AcceptanceGateConfig:
    min_tar_far_1pct: float = 0.9942
    min_tar_far_01pct: float = 0.9865
    min_tar_far_001pct: float = 0.9680
    max_apcer: float = 0.0125
    max_bpcer: float = 0.0085
    max_model_size_mb_int8: float = 5.0
    max_model_size_mb_fp16: float = 10.0
    max_npu_latency_ms: float = 15.0
    max_peak_ram_mb: float = 60.0
