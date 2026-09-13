from typing import Dict, List, Tuple, Optional
import numpy as np
from dataclasses import dataclass
from typing import Dict, List, Tuple

@dataclass
class CalibratedThresholds:
    tau_standard: float
    tau_high: float
    tau_strict: float
    track_swap_threshold: float
    best_temporal_fusion_mode: str
    genuine_mean: float
    impostor_mean: float

class UnifiedModelCalibrator:
    """
    Calibrates independent decision gates and track-swap thresholds for OmniFaceUnifiedModelV2.
    Enforces the rule that legacy MobileFaceNet thresholds (0.120, 0.158, 0.220, 0.60) are not transferred.
    """
    def __init__(self):
        pass

    def calibrate_from_distributions(
        self,
        genuine_distances: np.ndarray,
        impostor_distances: np.ndarray,
        track_switch_distances: Optional[np.ndarray] = None
    ) -> CalibratedThresholds:
        assert len(genuine_distances) >= 10, "Need sufficient genuine pairs"
        assert len(impostor_distances) >= 50, "Need sufficient impostor pairs"
        
        sorted_imp = np.sort(impostor_distances)
        
        # STANDARD: FAR = 1 in 10 (10%)
        idx_std = max(0, int(0.10 * len(sorted_imp)))
        tau_std = float(sorted_imp[idx_std])
        
        # HIGH: FAR = 1 in 100 (1%)
        idx_high = max(0, int(0.01 * len(sorted_imp)))
        tau_high = float(sorted_imp[idx_high])
        
        # STRICT: FAR = 1 in 1,000 (0.1%)
        idx_strict = max(0, int(0.001 * len(sorted_imp)))
        tau_strict = float(sorted_imp[idx_strict])
        
        # Track-swap threshold: midpoint between genuine 99th percentile and impostor 1st percentile
        gen_p99 = float(np.percentile(genuine_distances, 99))
        imp_p01 = float(np.percentile(impostor_distances, 1))
        track_swap_thresh = (gen_p99 + imp_p01) / 2.0
        
        return CalibratedThresholds(
            tau_standard=tau_std,
            tau_high=tau_high,
            tau_strict=tau_strict,
            track_swap_threshold=track_swap_thresh,
            best_temporal_fusion_mode="quality_weighted",
            genuine_mean=float(np.mean(genuine_distances)),
            impostor_mean=float(np.mean(impostor_distances))
        )

    def compare_temporal_fusion(self, frames_embeddings: List[np.ndarray], quality_scores: List[float], gallery_emb: np.ndarray) -> Dict[str, float]:
        """
        Compares:
        1. best_frame
        2. mean_embedding
        3. quality_weighted
        """
        # 1. Best-frame
        best_idx = int(np.argmax(quality_scores))
        best_emb = frames_embeddings[best_idx]
        sim_best = float(np.dot(best_emb, gallery_emb))
        
        # 2. Mean-embedding
        mean_emb = np.mean(frames_embeddings, axis=0)
        mean_emb = mean_emb / np.linalg.norm(mean_emb)
        sim_mean = float(np.dot(mean_emb, gallery_emb))
        
        # 3. Quality-weighted
        weights = np.array(quality_scores)[:, None]
        weighted_emb = np.sum(np.array(frames_embeddings) * weights, axis=0)
        weighted_emb = weighted_emb / np.linalg.norm(weighted_emb)
        sim_weighted = float(np.dot(weighted_emb, gallery_emb))
        
        return {
            "best_frame_sim": sim_best,
            "mean_embedding_sim": sim_mean,
            "quality_weighted_sim": sim_weighted
        }
