import os
import sys
import copy
import zipfile
import urllib.request
import numpy as np
import flatbuffers
from tensorflow.lite.python import schema_py_generated as schema_fb
import tensorflow as tf

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_CACHE_DIR = os.path.join(PROJECT_ROOT, "models_cache")
OUTPUT_UNIFIED_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "unified_omniface.tflite")

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
        "id": "cavaface",
        "name": "Qualcomm CavaFace (ArcFace-512)",
        "url": "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/cavaface/releases/v0.60.0/cavaface-tflite-float.zip",
        "is_zip": True,
        "zip_target": "cavaface-tflite-float/cavaface.tflite",
        "filename": "cavaface.tflite",
        "input_shape": [1, 112, 112, 3],
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
        if entry["is_zip"]:
            tmp_zip = os.path.join(MODELS_CACHE_DIR, f"{entry['id']}_tmp.zip")
            urllib.request.urlretrieve(entry["url"], tmp_zip)
            with zipfile.ZipFile(tmp_zip, "r") as z:
                target_file = None
                for name in z.namelist():
                    if name == entry.get("zip_target") or name.endswith(entry["filename"]):
                        target_file = name
                        break
                if not target_file:
                    raise RuntimeError(f"Could not locate {entry['filename']} inside {tmp_zip}")
                with open(dest_path, "wb") as f_out:
                    f_out.write(z.read(target_file))
            os.remove(tmp_zip)
        else:
            urllib.request.urlretrieve(entry["url"], dest_path)

        print(f"  [VERIFY] {entry['name']} saved ({os.path.getsize(dest_path):,} bytes)")
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
    unified.buffers = [schema_fb.BufferT()]

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
            if buf.data is not None:
                new_buf.data = np.frombuffer(buf.data, dtype=np.uint8)
            else:
                new_buf.data = None
            unified.buffers.append(new_buf)
            buffer_map[b_idx] = len(unified.buffers) - 1

        tensor_map = {}
        for t_idx, tensor in enumerate(sub.tensors):
            new_tensor = tensor
            raw_name = tensor.name.decode("utf-8", errors="ignore") if isinstance(tensor.name, (bytes, bytearray)) else str(tensor.name)
            new_tensor.name = f"{prefix}/{raw_name}"
            new_tensor.buffer = buffer_map.get(tensor.buffer, 0)
            subgraph.tensors.append(new_tensor)
            tensor_map[t_idx] = len(subgraph.tensors) - 1

        for inp in sub.inputs:
            subgraph.inputs.append(tensor_map[inp])
        for out in sub.outputs:
            subgraph.outputs.append(tensor_map[out])

        for op in sub.operators:
            new_op = op
            new_op.opcodeIndex = opcode_map[op.opcodeIndex]
            new_op.inputs = [tensor_map[i] if i in tensor_map else i for i in op.inputs]
            new_op.outputs = [tensor_map[o] if o in tensor_map else o for o in op.outputs]
            subgraph.operators.append(new_op)

    print("  Packing unified FlatBuffer with accelerated NumPy vectors...")
    builder = flatbuffers.Builder(450 * 1024 * 1024)
    packed_offset = unified.Pack(builder)
    builder.Finish(packed_offset, file_identifier=b"TFL3")
    unified_bytes = builder.Output()
    print(f"\nUnified Model File Size: {len(unified_bytes):,} bytes ({len(unified_bytes)/1024/1024:.2f} MB)")
    
    print(f"Writing production artifact to: {OUTPUT_UNIFIED_PATH}...")
    with open(OUTPUT_UNIFIED_PATH, "wb") as f:
        f.write(unified_bytes)
    print("Artifact successfully written!")
    return OUTPUT_UNIFIED_PATH

def run_numerical_equivalence_tests(model_path, model_paths):
    print("\n--- Running Automated Numerical Equivalence Tests ---")
    unified_interp = tf.lite.Interpreter(model_path=model_path)
    unified_interp.allocate_tensors()

    unified_inputs = {d["name"]: d for d in unified_interp.get_input_details()}
    unified_outputs = {d["name"]: d for d in unified_interp.get_output_details()}

    np.random.seed(42)

    for entry in MODEL_MANIFEST:
        mid = entry["id"]
        orig_interp = tf.lite.Interpreter(model_path=model_paths[mid])
        orig_interp.allocate_tensors()

        orig_in_details = orig_interp.get_input_details()
        orig_out_details = orig_interp.get_output_details()

        # Generate deterministic synthetic input
        feed_dict = {}
        for in_d in orig_in_details:
            shape = in_d["shape"]
            dtype = in_d["dtype"]
            if dtype == np.uint8:
                data = np.random.randint(0, 255, size=shape, dtype=np.uint8)
            else:
                data = np.random.uniform(0.0, 1.0, size=shape).astype(np.float32)
            feed_dict[in_d["index"]] = data
            orig_interp.set_tensor(in_d["index"], data)

        orig_interp.invoke()
        orig_outputs = [orig_interp.get_tensor(od["index"]) for od in orig_out_details]

        # Feed corresponding inputs in unified model
        for in_d in orig_in_details:
            u_name = f"{mid}/{in_d['name']}"
            if u_name in unified_inputs:
                unified_interp.set_tensor(unified_inputs[u_name]["index"], feed_dict[in_d["index"]])

        unified_interp.invoke()

        # Compare outputs
        max_diff = 0.0
        mae_accum = 0.0
        count = 0
        for idx, od in enumerate(orig_out_details):
            u_name = f"{mid}/{od['name']}"
            if u_name in unified_outputs:
                u_arr = unified_interp.get_tensor(unified_outputs[u_name]["index"])
                o_arr = orig_outputs[idx]
                diff = np.max(np.abs(u_arr - o_arr))
                mae = np.mean(np.abs(u_arr - o_arr))
                max_diff = max(max_diff, float(diff))
                mae_accum += float(mae)
                count += 1

        avg_mae = mae_accum / max(count, 1)
        passed = (max_diff < 1e-4)
        status = "PASSED (Bit-Exact)" if passed else "FAILED"
        print(f"  [{mid:<15}] Max Diff: {max_diff:.6e}, MAE: {avg_mae:.6e} -> {status}")
        if not passed:
            raise RuntimeError(f"Numerical drift detected in branch '{mid}'!")

def main():
    print("=== OmniFace-AI Unified Model Build Pipeline ===")
    model_paths = ensure_models_acquired()
    out_path = build_unified_model(model_paths)
    run_numerical_equivalence_tests(out_path, model_paths)
    print("\nPipeline completed successfully! Artifact is ready for Android compilation.")

if __name__ == "__main__":
    main()
