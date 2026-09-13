#!/usr/bin/env python3
"""
OmniFace AI — Qualcomm Multi-Task Model Merger
Merges the individual Qualcomm Face Intelligence models into a single Unified Qualcomm NPU model.
Unified Model:
  Input: image [1, 112, 112, 3] or [1, 128, 128, 3] float32 [0.0, 1.0]
  Outputs:
    0: embeddings [1, 512] (ArcFace Identity Vector)
    1: parameters_3dmm [1, 265] (FaceMap 3DMM Shape Parameters)
    2: attributes [1, 5] (Smile, Eyeglasses, Mask, Eye Open, Liveness)
    3: gaze_pitchyaw [1, 2] (Optical Eye Gaze Vector)
    4: landmarks_mesh [1, 468, 3] (Dense 3D Keypoints)
"""

import os
import sys
import numpy as np
import tflite

MODEL_DIR = "/storage/emulated/0/AI-HUB/FR/models"
QUALCOMM_SUITE_DIR = os.path.join(MODEL_DIR, "qualcomm_suite")
QUALCOMM_CAVAFACE_DIR = os.path.join(MODEL_DIR, "qualcomm_cavaface")

CAVAFACE_PATH = os.path.join(QUALCOMM_CAVAFACE_DIR, "cavaface-tflite-float/cavaface.tflite")
FACEMAP_PATH = os.path.join(QUALCOMM_SUITE_DIR, "facemap_3dmm/facemap_3dmm-tflite-float/facemap_3dmm.tflite")
ATTRIB_PATH = os.path.join(QUALCOMM_SUITE_DIR, "face_attrib_net/face_attrib_net-tflite-float/face_attrib_net.tflite")
EYEGAZE_PATH = os.path.join(QUALCOMM_SUITE_DIR, "eyegaze/eyegaze-tflite-float/eyegaze.tflite")
LANDMARKS_PATH = os.path.join(QUALCOMM_SUITE_DIR, "mediapipe_face/mediapipe_face-tflite-float/face_landmark_detector.tflite")
HRNET_PATH = os.path.join(QUALCOMM_SUITE_DIR, "hrnet_face/hrnet_face-tflite-float/hrnet_face.tflite")

OUTPUT_UNIFIED_PATH = os.path.join(QUALCOMM_SUITE_DIR, "qualcomm_unified_face_npu.tflite")
OUTPUT_ASSETS_PATH = "/storage/emulated/0/AI-HUB/FR/app/src/main/assets/qualcomm_unified_face_npu.tflite"

def inspect_model(path, name):
    if not os.path.exists(path):
        print(f"[-] {name} not found at {path}")
        return None
    with open(path, "rb") as f:
        buf = f.read()
    model = tflite.Model.GetRootAsModel(buf, 0)
    subgraph = model.Subgraphs(0)
    print(f"[+] {name}: {len(buf)/(1024*1024):.2f} MB, {subgraph.OperatorsLength()} ops, {subgraph.TensorsLength()} tensors")
    return model

if __name__ == "__main__":
    print("=" * 60)
    print("🤖 OMNIFACE AI — QUALCOMM SUITE MODEL AUDIT")
    print("=" * 60)
    inspect_model(CAVAFACE_PATH, "CavaFace (ArcFace 512-D)")
    inspect_model(FACEMAP_PATH, "FaceMap 3DMM (265-D Shape)")
    inspect_model(ATTRIB_PATH, "FaceAttribNet (5-D Probabilities)")
    inspect_model(EYEGAZE_PATH, "EyeGaze (Gaze Ray & Pupils)")
    inspect_model(LANDMARKS_PATH, "MediaPipe Face Landmark Detector (468 Mesh)")
    inspect_model(HRNET_PATH, "HRNetFace (29 Heatmaps)")
