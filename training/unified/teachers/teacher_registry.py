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
    Registry of verified teacher models in models_cache/
    Provides reproducible offline pseudo-label targets with confidence weighting.
    """
    TEACHER_MANIFEST = {
        "cavaface": "models_cache/cavaface.tflite",
        "silentface": "models_cache/silentface.tflite",
        "facemap_3dmm": "models_cache/facemap_3dmm.tflite",
        "face_landmark_detector": "models_cache/face_landmark_detector.tflite",
        "eyegaze": "models_cache/eyegaze.tflite",
        "hrnet_face": "models_cache/hrnet_face.tflite"
    }
    
    def __init__(self, base_dir: str = "."):
        self.base_dir = base_dir
        self.verified_paths = {}
        for name, rel_path in self.TEACHER_MANIFEST.items():
            full_path = os.path.join(base_dir, rel_path)
            if os.path.exists(full_path):
                self.verified_paths[name] = full_path
                
    def get_status(self) -> Dict[str, bool]:
        return {name: (name in self.verified_paths) for name in self.TEACHER_MANIFEST}

    @staticmethod
    def compute_image_hash(image_bytes: bytes) -> str:
        return hashlib.sha256(image_bytes).hexdigest()
