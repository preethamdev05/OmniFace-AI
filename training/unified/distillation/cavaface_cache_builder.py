import os
import sys
from typing import List, Dict
import numpy as np
from PIL import Image
import torch
from ai_edge_litert.interpreter import Interpreter

class CavaFaceFeatureExtractor:
    """
    High-throughput feature extractor using the Qualcomm AI Hub CavaFace IR-SE-100 teacher model.
    Extracts L2-normalized 512-D facial embeddings from 112x112 RGB crops.
    """
    def __init__(self, model_path: str = "models_cache/cavaface.tflite", num_threads: int = 8):
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"CavaFace teacher model not found at: {model_path}")
        self.interp = Interpreter(model_path=model_path, num_threads=num_threads)
        self.interp.allocate_tensors()
        self.in_idx = self.interp.get_input_details()[0]["index"]
        self.out_idx = self.interp.get_output_details()[0]["index"]

    def extract_single(self, pil_img: Image.Image) -> np.ndarray:
        img_112 = pil_img.convert("RGB").resize((112, 112), Image.Resampling.BILINEAR)
        # CavaFace expects [1, 112, 112, 3] in [0.0, 1.0]
        arr = np.array(img_112, dtype=np.float32) / 255.0
        inp = np.expand_dims(arr, axis=0)
        self.interp.set_tensor(self.in_idx, inp)
        self.interp.invoke()
        emb = self.interp.get_tensor(self.out_idx)[0].copy()
        norm = np.linalg.norm(emb)
        if norm > 1e-6:
            emb /= norm
        return emb

    def extract_batch_paths(self, paths: List[str], max_workers: int = 1) -> Dict[str, np.ndarray]:
        cache = {}
        for p in paths:
            if os.path.exists(p):
                with Image.open(p) as img:
                    cache[p] = self.extract_single(img)
        return cache
