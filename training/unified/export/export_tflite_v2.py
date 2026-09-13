import os
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

import shutil
import numpy as np
import torch
import tensorflow as tf

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.export.export_tflite import build_keras_unified_model, transfer_weights

def export_v2_all():
    print("=" * 75, flush=True)
    print(" OmniFace UnifiedFaceModel V2 — Dedicated LiteRT FlatBuffer Exporter", flush=True)
    print("=" * 75, flush=True)

    # 1. Load V2 PyTorch Checkpoint
    ckpt_path = "training/unified/checkpoints/production_winner_unified_v2.pt"
    if not os.path.exists(ckpt_path):
        ckpt_path = "training/unified/checkpoints/best_unified_model_v2.pt"
    if not os.path.exists(ckpt_path):
        raise FileNotFoundError(f"V2 Checkpoint not found: {ckpt_path}")
        
    print(f"[*] Loading PyTorch V2 Checkpoint: {ckpt_path}...", flush=True)
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    pt_model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468)
    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
    pt_model.load_state_dict(ckpt["model_state_dict"])
    pt_model.eval()

    # 2. Build Keras Model & Transfer Weights
    print("[*] Constructing Keras equivalent architecture...", flush=True)
    k_model = build_keras_unified_model()
    transfer_weights(pt_model, k_model)

    # 3. Numerical Parity Verification
    print("[*] Verifying numerical parity between PyTorch V2 and Keras V2...", flush=True)
    dummy_pt = torch.randn(5, 3, 112, 112)
    dummy_tf = np.transpose(dummy_pt.numpy(), (0, 2, 3, 1))

    with torch.no_grad():
        pt_out = pt_model(dummy_pt)
    tf_out = k_model(dummy_tf)

    cos_sims = []
    for i in range(5):
        sim = np.dot(pt_out["identity_embedding"][i].numpy(), tf_out[0][i].numpy())
        cos_sims.append(sim)
    mean_cos = float(np.mean(cos_sims))
    print(f"    Identity Embedding Cosine Parity: {mean_cos:.6f} (Requirement: >= 0.999)", flush=True)
    assert mean_cos >= 0.999, f"Identity parity check failed: {mean_cos}"

    os.makedirs("models_cache", exist_ok=True)
    os.makedirs("app/src/main/assets", exist_ok=True)

    # 4. FP32 Export
    print("[*] Converting to TFLite V2 FP32...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    tflite_fp32 = converter.convert()
    fp32_path = "models_cache/unified_face_v2_fp32.tflite"
    with open(fp32_path, "wb") as f:
        f.write(tflite_fp32)
    print(f"[+] Exported: {fp32_path} ({len(tflite_fp32)/(1024*1024):.2f} MB)", flush=True)

    # 5. FP16 Export (GPU Delegate Target)
    print("[*] Converting to TFLite V2 FP16 (GPU Delegate target)...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_fp16 = converter.convert()
    fp16_path = "models_cache/unified_face_v2_fp16.tflite"
    with open(fp16_path, "wb") as f:
        f.write(tflite_fp16)
    print(f"[+] Exported: {fp16_path} ({len(tflite_fp16)/(1024*1024):.2f} MB)", flush=True)

    # 6. INT8 Export (NPU / NNAPI Dynamic Range Quantization Target)
    print("[*] Converting to TFLite V2 INT8...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_int8 = converter.convert()
    int8_path = "models_cache/unified_face_v2_int8.tflite"
    with open(int8_path, "wb") as f:
        f.write(tflite_int8)
    print(f"[+] Exported: {int8_path} ({len(tflite_int8)/(1024*1024):.2f} MB)", flush=True)

    # 7. Copy V2 FlatBuffers into Android Assets (Distinct V2 filenames)
    asset_fp16 = "app/src/main/assets/unified_face_v2_fp16.tflite"
    asset_int8 = "app/src/main/assets/unified_face_v2_int8.tflite"
    shutil.copyfile(fp16_path, asset_fp16)
    shutil.copyfile(int8_path, asset_int8)
    print(f"[+] Deployed V2 to Android Assets:", flush=True)
    print(f"    -> {asset_fp16} ({os.path.getsize(asset_fp16)/(1024*1024):.2f} MB)", flush=True)
    print(f"    -> {asset_int8} ({os.path.getsize(asset_int8)/(1024*1024):.2f} MB)", flush=True)

    # 8. Test TFLite Interpreter Signatures & 7-Head Verification
    from ai_edge_litert.interpreter import Interpreter
    print("[*] Validating TFLite V2 Signatures & All 7 Heads...", flush=True)
    interp_fp16 = Interpreter(model_path=fp16_path)
    interp_fp16.allocate_tensors()
    
    in_idx = interp_fp16.get_input_details()[0]["index"]
    out_details = interp_fp16.get_output_details()
    size_map = {int(np.prod(od["shape"][1:])): od["index"] for od in out_details}
    
    expected_heads = {
        "Identity Embedding": 512,
        "PAD Spoof Logits": 3,
        "Quality Scores": 4,
        "Mesh Landmark Points": 1404,
        "3DMM Shape Parameters": 265,
        "Gaze Angles": 2,
        "Facial Attributes": 5
    }
    
    print("    Verifying 7 Output Heads:")
    for head_name, expected_dim in expected_heads.items():
        assert expected_dim in size_map, f"Missing output head: {head_name} (dim: {expected_dim})"
        print(f"      [OK] Head: {head_name:<24} -> Output Tensor Dim: [{expected_dim}]")
    
    fp16_cosines = []
    for i in range(5):
        img_nhwc = dummy_tf[i:i+1]
        interp_fp16.set_tensor(in_idx, img_nhwc)
        interp_fp16.invoke()
        tf_id = interp_fp16.get_tensor(size_map[512])[0]
        cos = np.dot(pt_out["identity_embedding"][i].numpy(), tf_id)
        fp16_cosines.append(cos)
    mean_fp16_parity = float(np.mean(fp16_cosines))
    print(f"    TFLite V2 FP16 Identity Parity: {mean_fp16_parity:.6f} (>= 0.999)")
    assert mean_fp16_parity >= 0.999, f"FP16 parity below 0.999: {mean_fp16_parity}"

    interp_int8 = Interpreter(model_path=int8_path)
    interp_int8.allocate_tensors()
    int8_cosines = []
    in_idx_8 = interp_int8.get_input_details()[0]["index"]
    size_map_8 = {int(np.prod(od["shape"][1:])): od["index"] for od in interp_int8.get_output_details()}
    for i in range(5):
        img_nhwc = dummy_tf[i:i+1]
        interp_int8.set_tensor(in_idx_8, img_nhwc)
        interp_int8.invoke()
        tf_id_8 = interp_int8.get_tensor(size_map_8[512])[0]
        cos = np.dot(pt_out["identity_embedding"][i].numpy(), tf_id_8)
        int8_cosines.append(cos)
    mean_int8_parity = float(np.mean(int8_cosines))
    print(f"    TFLite V2 INT8 Identity Parity: {mean_int8_parity:.6f} (>= 0.997)")
    assert mean_int8_parity >= 0.997, f"INT8 parity below 0.997: {mean_int8_parity}"

    print("\n" + "=" * 75, flush=True)
    print(" ALL UNIFIED V2 FLATBUFFERS SUCCESSFULLY EXPORTED & VERIFIED! ", flush=True)
    print("=" * 75, flush=True)

if __name__ == "__main__":
    export_v2_all()
