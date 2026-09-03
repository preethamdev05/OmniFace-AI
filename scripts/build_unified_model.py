"""
OmniFace-AI: Automated Unified LiteRT/TFLite Model Build Pipeline.
Discovers/downloads authoritative models, stitches them into a single unified
TFLite FlatBuffer graph, verifies numerical equivalence, and exports to Android assets.
"""
import os
import sys
import copy
import time
import zipfile
import urllib.request
import flatbuffers
import numpy as np

# Ensure TensorFlow is imported
import tensorflow as tf
from tensorflow.lite.python import schema_py_generated as schema_fb

MODELS_CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models_cache")
ASSETS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets")
OUTPUT_MODEL_FILENAME = "unified_omniface.tflite"

MODEL_MANIFEST = [
    {
        "id": "anti_spoof",
        "name": "MiniFASNetV2 LiteRT",
        "url": "https://huggingface.co/litert-community/Silent-Face-Anti-Spoofing-LiteRT/resolve/main/silentface.tflite",
        "is_zip": False,
        "filename": "silentface.tflite",
        "input_shape": [1, 3, 80, 80],
        "input_dtype": np.float32,
        "input_type": "float"
    },
    {
        "id": "face_embedding",
        "name": "FaceNet-512",
        "url": "https://huggingface.co/nxp/facenet512-imx/resolve/main/original_model/facenet512_uint8_float32.tflite",
        "is_zip": False,
        "filename": "facenet512.tflite",
        "input_shape": [1, 160, 160, 3],
        "input_dtype": np.uint8,
        "input_type": "uint8"
    },
    {
        "id": "facemap_3dmm",
        "name": "Qualcomm FaceMap 3DMM",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/facemap_3dmm/releases/v0.61.0/facemap_3dmm-tflite-float.zip",
        "is_zip": True,
        "zip_target": "facemap_3dmm-tflite-float/facemap_3dmm.tflite",
        "filename": "facemap_3dmm.tflite",
        "input_shape": [1, 128, 128, 3],
        "input_dtype": np.float32,
        "input_type": "float"
    },
    {
        "id": "face_attrib",
        "name": "Qualcomm FaceAttribNet",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/face_attrib_net/releases/v0.61.0/face_attrib_net-tflite-float.zip",
        "is_zip": True,
        "zip_target": "face_attrib_net-tflite-float/face_attrib_net.tflite",
        "filename": "face_attrib_net.tflite",
        "input_shape": [1, 128, 128, 3],
        "input_dtype": np.float32,
        "input_type": "float"
    },
    {
        "id": "eyegaze",
        "name": "Qualcomm EyeGaze",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/eyegaze/releases/v0.61.0/eyegaze-tflite-float.zip",
        "is_zip": True,
        "zip_target": "eyegaze-tflite-float/eyegaze.tflite",
        "filename": "eyegaze.tflite",
        "input_shape": [1, 96, 160],
        "input_dtype": np.float32,
        "input_type": "float"
    },
    {
        "id": "mediapipe_mesh",
        "name": "Qualcomm MediaPipe Face Mesh",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mediapipe_face/releases/v0.61.0/mediapipe_face-tflite-float.zip",
        "is_zip": True,
        "zip_target": "mediapipe_face-tflite-float/face_landmark_detector.tflite",
        "filename": "face_landmark_detector.tflite",
        "input_shape": [1, 192, 192, 3],
        "input_dtype": np.float32,
        "input_type": "float"
    },
    {
        "id": "hrnet_face",
        "name": "Qualcomm HRNetFace",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/hrnet_face/releases/v0.61.0/hrnet_face-tflite-float.zip",
        "is_zip": True,
        "zip_target": "hrnet_face-tflite-float/hrnet_face.tflite",
        "filename": "hrnet_face.tflite",
        "input_shape": [1, 256, 256, 3],
        "input_dtype": np.float32,
        "input_type": "float"
    }
]

def ensure_models_acquired():
    os.makedirs(MODELS_CACHE_DIR, exist_ok=True)
    acquired_paths = {}
    for entry in MODEL_MANIFEST:
        dest_path = os.path.join(MODELS_CACHE_DIR, entry["filename"])
        if os.path.exists(dest_path) and os.path.getsize(dest_path) > 10000:
            print(f"  [CACHE] {entry['name']}: {dest_path} ({os.path.getsize(dest_path):,} bytes)")
            acquired_paths[entry["id"]] = dest_path
            continue

        print(f"  [DOWNLOAD] Fetching {entry['name']} from {entry['url']}...")
        req = urllib.request.Request(entry["url"], headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as resp:
            data = resp.read()

        if entry.get("is_zip", False):
            zip_dest = os.path.join(MODELS_CACHE_DIR, entry["filename"] + ".zip")
            with open(zip_dest, "wb") as f:
                f.write(data)
            with zipfile.ZipFile(zip_dest, "r") as z:
                extracted_data = z.read(entry["zip_target"])
                with open(dest_path, "wb") as out_f:
                    out_f.write(extracted_data)
        else:
            with open(dest_path, "wb") as f:
                f.write(data)

        print(f"  [SUCCESS] Acquired {entry['name']} -> {dest_path} ({os.path.getsize(dest_path):,} bytes)")
        acquired_paths[entry["id"]] = dest_path
    return acquired_paths

def load_tflite_model_obj(path):
    with open(path, "rb") as f:
        buf = f.read()
    return schema_fb.ModelT.InitFromObj(schema_fb.Model.GetRootAsModel(buf, 0))

def find_or_add_opcode(unified_codes, code):
    for i, c in enumerate(unified_codes):
        if (c.builtinCode == code.builtinCode and 
            c.customCode == code.customCode and 
            c.version == code.version):
            return i
    new_code = schema_fb.OperatorCodeT()
    new_code.builtinCode = code.builtinCode
    new_code.customCode = code.customCode
    new_code.version = code.version
    unified_codes.append(new_code)
    return len(unified_codes) - 1

def build_unified_model(model_paths):
    print("\n--- Constructing Unified LiteRT Computational Graph ---")
    unified = schema_fb.ModelT()
    unified.version = 3
    unified.description = "OmniFace-AI Unified Biometric Neural Network"
    unified.operatorCodes = []
    unified.buffers = [schema_fb.BufferT()] # Buffer 0 reserved

    subgraph = schema_fb.SubGraphT()
    subgraph.name = "main"
    subgraph.inputs = []
    subgraph.outputs = []
    subgraph.tensors = []
    subgraph.operators = []
    unified.subgraphs = [subgraph]

    for entry in MODEL_MANIFEST:
        prefix = entry["id"]
        path = model_paths[prefix]
        print(f"  Integrating branch '{prefix}' ({entry['name']})...")
        m = load_tflite_model_obj(path)
        sub = m.subgraphs[0]

        opcode_map = {}
        for idx, code in enumerate(m.operatorCodes):
            opcode_map[idx] = find_or_add_opcode(unified.operatorCodes, code)

        buffer_map = {0: 0}
        for b_idx, buf in enumerate(m.buffers):
            if b_idx == 0:
                continue
            new_buf = schema_fb.BufferT()
            new_buf.data = buf.data
            unified.buffers.append(new_buf)
            buffer_map[b_idx] = len(unified.buffers) - 1

        tensor_map = {}
        for t_idx, tensor in enumerate(sub.tensors):
            new_tensor = copy.deepcopy(tensor)
            new_tensor.name = f"{prefix}/{tensor.name}"
            new_tensor.buffer = buffer_map.get(tensor.buffer, 0)
            subgraph.tensors.append(new_tensor)
            tensor_map[t_idx] = len(subgraph.tensors) - 1

        for inp in sub.inputs:
            subgraph.inputs.append(tensor_map[inp])
        for out in sub.outputs:
            subgraph.outputs.append(tensor_map[out])

        for op in sub.operators:
            new_op = copy.deepcopy(op)
            new_op.opcodeIndex = opcode_map[op.opcodeIndex]
            new_op.inputs = [tensor_map[i] if i != -1 else -1 for i in op.inputs]
            new_op.outputs = [tensor_map[o] if o != -1 else -1 for o in op.outputs]
            subgraph.operators.append(new_op)

    builder = flatbuffers.Builder(150 * 1024 * 1024)
    packed = unified.Pack(builder)
    builder.Finish(packed, b"TFL3")
    return builder.Output()

def run_equivalence_tests(unified_bytes, model_paths):
    print("\n--- Running Automated Numerical Equivalence Tests ---")
    interp_u = tf.lite.Interpreter(model_content=bytes(unified_bytes))
    interp_u.allocate_tensors()
    u_inputs = {inp["name"]: inp for inp in interp_u.get_input_details()}
    u_outputs = {out["name"]: out for out in interp_u.get_output_details()}

    np.random.seed(42)
    test_inputs = {}
    for entry in MODEL_MANIFEST:
        shape = entry["input_shape"]
        if entry["input_type"] == "uint8":
            test_inputs[entry["id"]] = (np.random.rand(*shape) * 255).astype(np.uint8)
        else:
            test_inputs[entry["id"]] = np.random.rand(*shape).astype(np.float32)

    # Load unified inputs
    for entry in MODEL_MANIFEST:
        p = entry["id"]
        for u_name, u_detail in u_inputs.items():
            if p in u_name:
                interp_u.set_tensor(u_detail["index"], test_inputs[p])

    interp_u.invoke()

    all_passed = True
    for entry in MODEL_MANIFEST:
        p = entry["id"]
        path = model_paths[p]
        interp_s = tf.lite.Interpreter(model_path=path)
        interp_s.allocate_tensors()
        for idx, inp in enumerate(interp_s.get_input_details()):
            interp_s.set_tensor(inp["index"], test_inputs[p])
        interp_s.invoke()

        s_outs = [interp_s.get_tensor(out["index"]) for out in interp_s.get_output_details()]
        u_outs = []
        for u_name, u_detail in u_outputs.items():
            if p in u_name:
                u_outs.append(interp_u.get_tensor(u_detail["index"]))

        diffs = [np.max(np.abs(s - u)) for s, u in zip(s_outs, u_outs)]
        maes = [np.mean(np.abs(s - u)) for s, u in zip(s_outs, u_outs)]
        max_diff = max(diffs)
        max_mae = max(maes)
        passed = max_diff < 1e-5
        if not passed: all_passed = False
        status = "PASSED (Bit-Exact)" if max_diff == 0.0 else ("PASSED" if passed else "FAILED")
        print(f"  [{p:<15}] Max Diff: {max_diff:.6e}, MAE: {max_mae:.6e} -> {status}")

    return all_passed

def benchmark_unified_model(unified_bytes):
    print("\n--- Benchmarking Unified Model Inference Latency ---")
    interp = tf.lite.Interpreter(model_content=bytes(unified_bytes))
    interp.allocate_tensors()
    # Warmup
    for _ in range(3):
        interp.invoke()
    # Timed run
    times = []
    for _ in range(10):
        t0 = time.perf_counter()
        interp.invoke()
        times.append((time.perf_counter() - t0) * 1000.0)
    avg_lat = np.mean(times)
    min_lat = np.min(times)
    p95_lat = np.percentile(times, 95)
    print(f"  Average Latency: {avg_lat:.2f} ms | Min: {min_lat:.2f} ms | P95: {p95_lat:.2f} ms")

def main():
    print("=== OmniFace-AI Unified Model Build Pipeline ===")
    model_paths = ensure_models_acquired()
    unified_bytes = build_unified_model(model_paths)
    print(f"\nUnified Model File Size: {len(unified_bytes):,} bytes ({len(unified_bytes)/(1024*1024):.2f} MB)")
    
    passed = run_equivalence_tests(unified_bytes, model_paths)
    if not passed:
        print("\n[ERROR] Numerical equivalence verification failed!")
        sys.exit(1)

    benchmark_unified_model(unified_bytes)

    os.makedirs(ASSETS_DIR, exist_ok=True)
    out_asset_path = os.path.join(ASSETS_DIR, OUTPUT_MODEL_FILENAME)
    print(f"\nWriting production artifact to: {out_asset_path}...")
    with open(out_asset_path, "wb") as f:
        f.write(unified_bytes)
    print("Pipeline completed successfully! Artifact is ready for Android compilation.")

if __name__ == "__main__":
    main()
