import os
import hashlib
from dataclasses import dataclass
from typing import Dict, Any, Optional
import numpy as np

@dataclass
class TeacherSampleTarget:
    sample_id: str
    image_hash: str
    identity_embedding: Optional[np.ndarray] = None
    pad_logits: Optional[np.ndarray] = None
    mesh_landmarks: Optional[np.ndarray] = None
    geom_3dmm: Optional[np.ndarray] = None
    gaze_angles: Optional[np.ndarray] = None
    quality_scores: Optional[np.ndarray] = None
    teacher_confidence: float = 1.0
    ground_truth_available: bool = False

class TeacherRegistry:
    """
    Registry of verified teacher models in archive/ml/models/ (with models_cache/ fallback).
    Provides reproducible offline pseudo-label targets with confidence weighting.
    """
    TEACHER_MANIFEST = {
        "cavaface": ["archive/ml/models/cavaface/cavaface.tflite", "models_cache/cavaface.tflite"],
        "silentface": ["archive/ml/models/silentface/silentface.tflite", "models_cache/silentface.tflite"],
        "facemap_3dmm": ["archive/ml/models/facemap_3dmm/facemap_3dmm.tflite", "models_cache/facemap_3dmm.tflite"],
        "face_landmark_detector": ["archive/ml/models/landmarks/face_landmark_detector.tflite", "models_cache/face_landmark_detector.tflite"],
        "eyegaze": ["archive/ml/models/eyegaze/eyegaze.tflite", "models_cache/eyegaze.tflite"],
        "hrnet_face": ["archive/ml/models/hrnet/hrnet_face.tflite", "models_cache/hrnet_face.tflite"]
    }
    
    def __init__(self, base_dir: str = "."):
        self.base_dir = base_dir
        self.verified_paths = {}
        for name, candidate_paths in self.TEACHER_MANIFEST.items():
            if isinstance(candidate_paths, str):
                candidate_paths = [candidate_paths]
            for rel_path in candidate_paths:
                full_path = os.path.join(base_dir, rel_path)
                if os.path.exists(full_path):
                    self.verified_paths[name] = full_path
                    break
                
    def get_status(self) -> Dict[str, bool]:
        return {name: (name in self.verified_paths) for name in self.TEACHER_MANIFEST}

    @staticmethod
    def compute_image_hash(image_bytes: bytes) -> str:
        return hashlib.sha256(image_bytes).hexdigest()
