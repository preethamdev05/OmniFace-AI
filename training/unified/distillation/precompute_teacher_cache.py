import os
import sys
import time
import math
import hashlib
import numpy as np
from PIL import Image
import torch
from typing import Dict, Any, List

def compute_quality_scores(pil_img: Image.Image, mesh_landmarks: np.ndarray, gaze_angles: np.ndarray) -> np.ndarray:
    gray = np.array(pil_img.convert("L"), dtype=np.float32)
    lap = np.abs(gray[1:-1, 2:] + gray[1:-1, :-2] + gray[2:, 1:-1] + gray[:-2, 1:-1] - 4 * gray[1:-1, 1:-1])
    sharpness = min(1.0, float(np.var(lap)) / 500.0)
    
    mean_b = float(np.mean(gray))
    brightness = max(0.0, 1.0 - abs(mean_b - 128.0) / 128.0)
    
    if mesh_landmarks is not None and len(mesh_landmarks) >= 468:
        nose = mesh_landmarks[1]
        left_eye = mesh_landmarks[33]
        right_eye = mesh_landmarks[263]
        dist_l = np.linalg.norm(nose[:2] - left_eye[:2])
        dist_r = np.linalg.norm(nose[:2] - right_eye[:2])
        ratio = min(dist_l, dist_r) / (max(dist_l, dist_r) + 1e-6)
        symmetry = float(np.clip(ratio, 0.0, 1.0))
    else:
        symmetry = 0.85
        
    if gaze_angles is not None and len(gaze_angles) >= 2:
        pitch, yaw = gaze_angles[0], gaze_angles[1]
        angle_dev = math.sqrt(pitch**2 + yaw**2)
        frontalness = max(0.0, 1.0 - angle_dev / 0.5)
    else:
        frontalness = 0.90
        
    return np.array([sharpness, brightness, symmetry, frontalness], dtype=np.float32)

class MultiTeacherDatasetAnnotator:
    def __init__(self, models_dir: str = "models_cache"):
        self.models_dir = models_dir
        import ai_edge_litert.interpreter as tflite
        
        print("[*] Initializing 6 Specialist Teacher TFLite Interpreters...")
        self.cava = tflite.Interpreter(os.path.join(models_dir, "cavaface.tflite"))
        self.cava.allocate_tensors()
        
        self.silentface = tflite.Interpreter(os.path.join(models_dir, "silentface.tflite"))
        self.silentface.allocate_tensors()
        
        self.facemap = tflite.Interpreter(os.path.join(models_dir, "facemap_3dmm.tflite"))
        self.facemap.allocate_tensors()
        
        self.mesh = tflite.Interpreter(os.path.join(models_dir, "face_landmark_detector.tflite"))
        self.mesh.allocate_tensors()
        
        self.gaze = tflite.Interpreter(os.path.join(models_dir, "eyegaze.tflite"))
        self.gaze.allocate_tensors()
        
        self.attrib = tflite.Interpreter(os.path.join(models_dir, "face_attrib_net.tflite"))
        self.attrib.allocate_tensors()
        print("[+] All 6 Teacher Interpreters allocated successfully.")

    def extract(self, pil_img: Image.Image) -> Dict[str, np.ndarray]:
        img_112 = pil_img.resize((112, 112), Image.Resampling.BILINEAR)
        arr_112 = np.array(img_112, dtype=np.float32) / 255.0
        
        # CavaFace
        self.cava.set_tensor(self.cava.get_input_details()[0]["index"], np.expand_dims(arr_112, 0))
        self.cava.invoke()
        cava_emb = self.cava.get_tensor(self.cava.get_output_details()[0]["index"])[0].copy()
        norm = np.linalg.norm(cava_emb)
        if norm > 1e-6:
            cava_emb /= norm
            
        # SilentFace (80x80 NCHW)
        img_80 = pil_img.resize((80, 80), Image.Resampling.BILINEAR)
        arr_80 = np.transpose(np.array(img_80, dtype=np.float32) / 255.0, (2, 0, 1))
        self.silentface.set_tensor(self.silentface.get_input_details()[0]["index"], np.expand_dims(arr_80, 0))
        self.silentface.invoke()
        pad_logits = self.silentface.get_tensor(self.silentface.get_output_details()[0]["index"])[0].copy()
        
        # FaceMap 3DMM (128x128 RGB)
        img_128 = pil_img.resize((128, 128), Image.Resampling.BILINEAR)
        arr_128 = np.array(img_128, dtype=np.float32) / 255.0
        self.facemap.set_tensor(self.facemap.get_input_details()[0]["index"], np.expand_dims(arr_128, 0))
        self.facemap.invoke()
        geom_3dmm = self.facemap.get_tensor(self.facemap.get_output_details()[0]["index"])[0].copy()
        
        # MediaPipe Mesh (192x192 RGB)
        img_192 = pil_img.resize((192, 192), Image.Resampling.BILINEAR)
        arr_192 = np.array(img_192, dtype=np.float32) / 255.0
        self.mesh.set_tensor(self.mesh.get_input_details()[0]["index"], np.expand_dims(arr_192, 0))
        self.mesh.invoke()
        mesh_landmarks = self.mesh.get_tensor(self.mesh.get_output_details()[1]["index"])[0].copy()
        
        # EyeGaze (160x96 Grayscale)
        img_gaze = pil_img.convert("L").resize((160, 96), Image.Resampling.BILINEAR)
        arr_gaze = np.array(img_gaze, dtype=np.float32) / 255.0
        self.gaze.set_tensor(self.gaze.get_input_details()[0]["index"], np.expand_dims(arr_gaze, 0))
        self.gaze.invoke()
        gaze_angles = self.gaze.get_tensor(self.gaze.get_output_details()[2]["index"])[0].copy()
        
        # Face Attribute Net (128x128 RGB)
        self.attrib.set_tensor(self.attrib.get_input_details()[0]["index"], np.expand_dims(arr_128, 0))
        self.attrib.invoke()
        attribs = self.attrib.get_tensor(self.attrib.get_output_details()[0]["index"])[0].copy()
        
        # Face Quality Scores
        quality = compute_quality_scores(pil_img, mesh_landmarks, gaze_angles)
        
        # Normalized input tensor: [3, 112, 112]
        face_tensor = np.transpose(arr_112, (2, 0, 1)).astype(np.float32)
        
        return {
            "face": face_tensor,
            "cava_emb": cava_emb,
            "pad_logits": pad_logits,
            "geom_3dmm": geom_3dmm,
            "mesh_landmarks": mesh_landmarks,
            "gaze_angles": gaze_angles,
            "attribs": attribs,
            "quality": quality
        }

def build_annotated_cache(
    dataset_dir: str = "training/105_classes_pins_dataset",
    output_cache_path: str = "training/unified/datasets/teacher_dataset_cache.pt",
    max_images_per_class: int = 20,
    val_images_per_class: int = 4
):
    print(f"[*] Scanning dataset in {dataset_dir}...")
    classes = sorted([d for d in os.listdir(dataset_dir) if os.path.isdir(os.path.join(dataset_dir, d))])
    num_classes = len(classes)
    print(f"[+] Found {num_classes} identity classes.")
    
    annotator = MultiTeacherDatasetAnnotator()
    
    train_data = []
    val_data = []
    
    t0 = time.time()
    total_processed = 0
    
    for class_idx, class_name in enumerate(classes):
        class_folder = os.path.join(dataset_dir, class_name)
        img_files = sorted([f for f in os.listdir(class_folder) if f.lower().endswith(('.jpg', '.jpeg', '.png'))])
        
        selected = img_files[:max_images_per_class]
        val_cut = len(selected) - val_images_per_class
        
        for i, fname in enumerate(selected):
            fpath = os.path.join(class_folder, fname)
            try:
                with Image.open(fpath) as img:
                    img_rgb = img.convert("RGB")
                    annotated = annotator.extract(img_rgb)
                    
                    record = {
                        "face": torch.from_numpy(annotated["face"]),
                        "identity_label": torch.tensor(class_idx, dtype=torch.long),
                        "teacher_emb": torch.from_numpy(annotated["cava_emb"]),
                        "pad_logits": torch.from_numpy(annotated["pad_logits"]),
                        "pad_label": torch.tensor(0, dtype=torch.long),
                        "geom_3dmm": torch.from_numpy(annotated["geom_3dmm"]),
                        "mesh_landmarks": torch.from_numpy(annotated["mesh_landmarks"]),
                        "gaze_angles": torch.from_numpy(annotated["gaze_angles"]),
                        "attribute_probs": torch.from_numpy(annotated["attribs"]),
                        "quality_scores": torch.from_numpy(annotated["quality"]),
                        "sample_id": f"{class_name}_{fname}"
                    }
                    
                    if i < val_cut:
                        train_data.append(record)
                    else:
                        val_data.append(record)
                    total_processed += 1
            except Exception as e:
                print(f"[!] Skipping corrupt image {fpath}: {e}")
                
        if (class_idx + 1) % 15 == 0 or (class_idx + 1) == num_classes:
            elapsed = time.time() - t0
            rate = total_processed / max(1e-3, elapsed)
            print(f"[{class_idx + 1}/{num_classes}] Processed {total_processed} images ({rate:.1f} img/s) - Train: {len(train_data)}, Val: {len(val_data)}")
            
    print(f"[*] Packaging into PyTorch dataset cache: {output_cache_path}...")
    os.makedirs(os.path.dirname(os.path.abspath(output_cache_path)), exist_ok=True)
    
    payload = {
        "classes": classes,
        "num_classes": num_classes,
        "train_data": train_data,
        "val_data": val_data,
        "total_images": total_processed,
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    torch.save(payload, output_cache_path)
    file_size_mb = os.path.getsize(output_cache_path) / (1024 * 1024)
    print(f"[+] Multi-Teacher Dataset Cache Saved: {output_cache_path} ({file_size_mb:.2f} MB)")
    print(f"[+] Train count: {len(train_data)}, Val count: {len(val_data)}")

if __name__ == "__main__":
    build_annotated_cache()
