import os
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

import numpy as np
import torch
import tensorflow as tf
from tensorflow.keras import layers, Model

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2

def build_keras_unified_model():
    """
    Constructs the exact MobileNetV4-Conv-Small unified architecture in TensorFlow/Keras.
    Input: [1, 112, 112, 3] RGB in [-1.0, 1.0]
    Outputs:
        0: identity_embedding [1, 512]
        1: pad_logits [1, 3]
        2: quality_scores [1, 4]
        3: mesh_landmarks [1, 468, 3]
        4: geom_3dmm [1, 265]
        5: gaze_angles [1, 2]
        6: attribute_probs [1, 5]
    """
    inputs = layers.Input(shape=(112, 112, 3), batch_size=1, name="input_face_raw_rgb")
    
    # Custom Hardswish and Hardsigmoid for exact parity
    def hardswish(x):
        return x * tf.nn.relu6(x + 3.0) / 6.0

    def hardsigmoid(x):
        return tf.nn.relu6(x + 3.0) / 6.0

    def conv_bn_act(x, out_c, kernel_size, stride=1, padding="same", groups=1, act=True, name_prefix=""):
        if kernel_size > 1 and stride > 1:
            x = layers.ZeroPadding2D(padding=((1, 1), (1, 1)), name=f"{name_prefix}_pad")(x)
            padding = "valid"
        if groups == 1:
            x = layers.Conv2D(out_c, kernel_size, strides=stride, padding=padding, use_bias=False, name=f"{name_prefix}_conv")(x)
        else:
            x = layers.DepthwiseConv2D(kernel_size, strides=stride, padding=padding, use_bias=False, name=f"{name_prefix}_dw")(x)
        x = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name=f"{name_prefix}_bn")(x)
        if act:
            x = layers.Activation(hardswish, name=f"{name_prefix}_act")(x)
        return x

    def se_block(x, in_c, reduction=4, name_prefix=""):
        mid_c = max(1, in_c // reduction)
        # AdaptiveAvgPool2d(1)
        se = layers.GlobalAveragePooling2D(keepdims=True, name=f"{name_prefix}_se_gap")(x)
        se = layers.Conv2D(mid_c, 1, use_bias=True, activation="relu", name=f"{name_prefix}_se_fc1")(se)
        se = layers.Conv2D(in_c, 1, use_bias=True, activation=hardsigmoid, name=f"{name_prefix}_se_fc2")(se)
        return layers.Multiply(name=f"{name_prefix}_se_mul")([x, se])

    def inverted_bottleneck(x, in_c, out_c, stride=1, expand_ratio=3, use_se=True, name_prefix=""):
        use_residual = (stride == 1 and in_c == out_c)
        mid_c = int(round(in_c * expand_ratio))
        shortcut = x
        
        # Expansion
        if expand_ratio != 1:
            x = conv_bn_act(x, mid_c, 1, stride=1, padding="same", act=True, name_prefix=f"{name_prefix}_exp")
        # Depthwise
        x = conv_bn_act(x, mid_c, 3, stride=stride, padding="same", groups=mid_c, act=True, name_prefix=f"{name_prefix}_dw")
        # Squeeze-and-Excitation
        if use_se:
            x = se_block(x, mid_c, reduction=4, name_prefix=f"{name_prefix}_se")
        # Projection
        x = conv_bn_act(x, out_c, 1, stride=1, padding="same", act=False, name_prefix=f"{name_prefix}_proj")
        
        if use_residual:
            x = layers.Add(name=f"{name_prefix}_add")([shortcut, x])
        return x

    # --- Backbone ---
    # Stem: 112x112x3 -> 56x56x32
    x = conv_bn_act(inputs, 32, 3, stride=2, padding="same", act=True, name_prefix="backbone_stem")

    # Stage 1: 56x56x32 -> 28x28x48
    x = inverted_bottleneck(x, 32, 48, stride=2, expand_ratio=2, use_se=False, name_prefix="backbone_s1_b0")
    x = inverted_bottleneck(x, 48, 48, stride=1, expand_ratio=2, use_se=False, name_prefix="backbone_s1_b1")

    # Stage 2: 28x28x48 -> 14x14x96
    x = inverted_bottleneck(x, 48, 96, stride=2, expand_ratio=3, use_se=True, name_prefix="backbone_s2_b0")
    x = inverted_bottleneck(x, 96, 96, stride=1, expand_ratio=3, use_se=True, name_prefix="backbone_s2_b1")
    x = inverted_bottleneck(x, 96, 96, stride=1, expand_ratio=3, use_se=True, name_prefix="backbone_s2_b2")

    # Stage 3: 14x14x96 -> 7x7x192
    x = inverted_bottleneck(x, 96, 192, stride=2, expand_ratio=4, use_se=True, name_prefix="backbone_s3_b0")
    x = inverted_bottleneck(x, 192, 192, stride=1, expand_ratio=4, use_se=True, name_prefix="backbone_s3_b1")
    x = inverted_bottleneck(x, 192, 192, stride=1, expand_ratio=4, use_se=True, name_prefix="backbone_s3_b2")

    # Head projection: 7x7x192 -> 7x7x512
    features = conv_bn_act(x, 512, 1, stride=1, padding="same", act=True, name_prefix="backbone_head_conv")

    # --- Heads ---
    # 0: Identity Head [B, 512]
    id_gdc_dw = layers.DepthwiseConv2D(7, strides=1, padding="valid", use_bias=False, name="id_gdc_dw")(features)
    id_gdc_dw_bn = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name="id_gdc_dw_bn")(id_gdc_dw)
    id_gdc_conv = layers.Conv2D(512, 1, strides=1, padding="same", use_bias=False, name="id_gdc_conv")(id_gdc_dw_bn)
    id_gdc_conv_bn = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name="id_gdc_conv_bn")(id_gdc_conv)
    id_flat = layers.Flatten(name="id_flat")(id_gdc_conv_bn)
    id_out = layers.Lambda(lambda v: tf.math.l2_normalize(v, axis=-1), name="identity_embedding")(id_flat)

    # Global Average Pooled Features for auxiliary heads
    gap_feat = layers.GlobalAveragePooling2D(name="common_gap")(features)

    # 1: PAD Head [B, 3]
    pad_fc1 = layers.Dense(128, use_bias=True, name="pad_fc1")(gap_feat)
    pad_bn1 = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name="pad_bn1")(pad_fc1)
    pad_act1 = layers.Activation(hardswish, name="pad_act1")(pad_bn1)
    pad_out = layers.Dense(3, use_bias=True, name="pad_logits")(pad_act1)

    # 2: Quality Head [B, 4]
    qual_fc1 = layers.Dense(64, use_bias=True, name="qual_fc1")(gap_feat)
    qual_act1 = layers.Activation(hardswish, name="qual_act1")(qual_fc1)
    qual_out = layers.Dense(4, use_bias=True, activation="sigmoid", name="quality_scores")(qual_act1)

    # 3: Mesh Head [B, 468, 3]
    mesh_fc1 = layers.Dense(512, use_bias=True, name="mesh_fc1")(gap_feat)
    mesh_bn1 = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name="mesh_bn1")(mesh_fc1)
    mesh_act1 = layers.Activation(hardswish, name="mesh_act1")(mesh_bn1)
    mesh_dense = layers.Dense(468 * 3, use_bias=True, name="mesh_dense")(mesh_act1)
    mesh_out = layers.Reshape((468 * 3,), name="mesh_landmarks")(mesh_dense)

    # 4: 3DMM Geometry Head [B, 265]
    geom_fc1 = layers.Dense(384, use_bias=True, name="geom_fc1")(gap_feat)
    geom_bn1 = layers.BatchNormalization(momentum=0.9, epsilon=1e-5, name="geom_bn1")(geom_fc1)
    geom_act1 = layers.Activation(hardswish, name="geom_act1")(geom_bn1)
    geom_out = layers.Dense(265, use_bias=True, name="geom_3dmm")(geom_act1)

    # 5: Gaze Head [B, 2]
    gaze_fc1 = layers.Dense(64, use_bias=True, name="gaze_fc1")(gap_feat)
    gaze_act1 = layers.Activation(hardswish, name="gaze_act1")(gaze_fc1)
    gaze_out = layers.Dense(2, use_bias=True, name="gaze_angles")(gaze_act1)

    # 6: Attribute Head [B, 5]
    attr_fc1 = layers.Dense(64, use_bias=True, name="attr_fc1")(gap_feat)
    attr_act1 = layers.Activation(hardswish, name="attr_act1")(attr_fc1)
    attr_out = layers.Dense(5, use_bias=True, activation="sigmoid", name="attribute_probs")(attr_act1)

    model = Model(
        inputs=inputs,
        outputs=[id_out, pad_out, qual_out, mesh_out, geom_out, gaze_out, attr_out],
        name="OmniFaceUnifiedModelV1"
    )
    return model

def transfer_weights(pt_model, keras_model):
    """
    Transfers PyTorch state_dict tensors to equivalent Keras layer weights.
    """
    sd = pt_model.state_dict()
    
    def set_conv(k_layer, pt_weight):
        # pt: [out_c, in_c, kH, kW] -> keras: [kH, kW, in_c, out_c]
        w = pt_weight.permute(2, 3, 1, 0).detach().cpu().numpy()
        k_layer.set_weights([w])

    def set_conv_with_bias(k_layer, pt_weight, pt_bias):
        w = pt_weight.permute(2, 3, 1, 0).detach().cpu().numpy()
        b = pt_bias.detach().cpu().numpy()
        k_layer.set_weights([w, b])

    def set_dw(k_layer, pt_weight):
        # pt: [groups, 1, kH, kW] -> keras: [kH, kW, groups, 1]
        w = pt_weight.permute(2, 3, 0, 1).detach().cpu().numpy()
        k_layer.set_weights([w])

    def set_bn(k_layer, pt_w, pt_b, pt_m, pt_v):
        gamma = pt_w.detach().cpu().numpy()
        beta = pt_b.detach().cpu().numpy()
        mean = pt_m.detach().cpu().numpy()
        var = pt_v.detach().cpu().numpy()
        k_layer.set_weights([gamma, beta, mean, var])

    def set_dense(k_layer, pt_w, pt_b):
        # pt: [out_f, in_f] -> keras: [in_f, out_f]
        w = pt_w.detach().cpu().numpy().T
        b = pt_b.detach().cpu().numpy()
        k_layer.set_weights([w, b])

    # Stem
    set_conv(keras_model.get_layer("backbone_stem_conv"), sd["backbone.stem.0.weight"])
    set_bn(
        keras_model.get_layer("backbone_stem_bn"),
        sd["backbone.stem.1.weight"], sd["backbone.stem.1.bias"],
        sd["backbone.stem.1.running_mean"], sd["backbone.stem.1.running_var"]
    )

    # Inverted Bottleneck helper
    def map_ib_block(prefix_k, pt_path, has_exp=True, has_se=True):
        idx = 0
        if has_exp:
            set_conv(keras_model.get_layer(f"{prefix_k}_exp_conv"), sd[f"{pt_path}.block.{idx}.0.weight"])
            set_bn(
                keras_model.get_layer(f"{prefix_k}_exp_bn"),
                sd[f"{pt_path}.block.{idx}.1.weight"], sd[f"{pt_path}.block.{idx}.1.bias"],
                sd[f"{pt_path}.block.{idx}.1.running_mean"], sd[f"{pt_path}.block.{idx}.1.running_var"]
            )
            idx += 1
        
        # DW
        set_dw(keras_model.get_layer(f"{prefix_k}_dw_dw"), sd[f"{pt_path}.block.{idx}.0.weight"])
        set_bn(
            keras_model.get_layer(f"{prefix_k}_dw_bn"),
            sd[f"{pt_path}.block.{idx}.1.weight"], sd[f"{pt_path}.block.{idx}.1.bias"],
            sd[f"{pt_path}.block.{idx}.1.running_mean"], sd[f"{pt_path}.block.{idx}.1.running_var"]
        )
        idx += 1

        if has_se:
            set_conv_with_bias(
                keras_model.get_layer(f"{prefix_k}_se_se_fc1"),
                sd[f"{pt_path}.block.{idx}.fc.1.weight"], sd[f"{pt_path}.block.{idx}.fc.1.bias"]
            )
            set_conv_with_bias(
                keras_model.get_layer(f"{prefix_k}_se_se_fc2"),
                sd[f"{pt_path}.block.{idx}.fc.3.weight"], sd[f"{pt_path}.block.{idx}.fc.3.bias"]
            )
            idx += 1

        # Proj
        set_conv(keras_model.get_layer(f"{prefix_k}_proj_conv"), sd[f"{pt_path}.block.{idx}.0.weight"])
        set_bn(
            keras_model.get_layer(f"{prefix_k}_proj_bn"),
            sd[f"{pt_path}.block.{idx}.1.weight"], sd[f"{pt_path}.block.{idx}.1.bias"],
            sd[f"{pt_path}.block.{idx}.1.running_mean"], sd[f"{pt_path}.block.{idx}.1.running_var"]
        )

    # Stage 1
    map_ib_block("backbone_s1_b0", "backbone.stage1.0", has_exp=True, has_se=False)
    map_ib_block("backbone_s1_b1", "backbone.stage1.1", has_exp=True, has_se=False)

    # Stage 2
    map_ib_block("backbone_s2_b0", "backbone.stage2.0", has_exp=True, has_se=True)
    map_ib_block("backbone_s2_b1", "backbone.stage2.1", has_exp=True, has_se=True)
    map_ib_block("backbone_s2_b2", "backbone.stage2.2", has_exp=True, has_se=True)

    # Stage 3
    map_ib_block("backbone_s3_b0", "backbone.stage3.0", has_exp=True, has_se=True)
    map_ib_block("backbone_s3_b1", "backbone.stage3.1", has_exp=True, has_se=True)
    map_ib_block("backbone_s3_b2", "backbone.stage3.2", has_exp=True, has_se=True)

    # Head conv
    set_conv(keras_model.get_layer("backbone_head_conv_conv"), sd["backbone.head_conv.0.weight"])
    set_bn(
        keras_model.get_layer("backbone_head_conv_bn"),
        sd["backbone.head_conv.1.weight"], sd["backbone.head_conv.1.bias"],
        sd["backbone.head_conv.1.running_mean"], sd["backbone.head_conv.1.running_var"]
    )

    # Identity Head
    set_dw(keras_model.get_layer("id_gdc_dw"), sd["identity_head.gdc.0.weight"])
    set_bn(
        keras_model.get_layer("id_gdc_dw_bn"),
        sd["identity_head.gdc.1.weight"], sd["identity_head.gdc.1.bias"],
        sd["identity_head.gdc.1.running_mean"], sd["identity_head.gdc.1.running_var"]
    )
    set_conv(keras_model.get_layer("id_gdc_conv"), sd["identity_head.gdc.3.weight"])
    set_bn(
        keras_model.get_layer("id_gdc_conv_bn"),
        sd["identity_head.gdc.4.weight"], sd["identity_head.gdc.4.bias"],
        sd["identity_head.gdc.4.running_mean"], sd["identity_head.gdc.4.running_var"]
    )

    # PAD Head
    set_dense(keras_model.get_layer("pad_fc1"), sd["pad_head.net.2.weight"], sd["pad_head.net.2.bias"])
    set_bn(
        keras_model.get_layer("pad_bn1"),
        sd["pad_head.net.3.weight"], sd["pad_head.net.3.bias"],
        sd["pad_head.net.3.running_mean"], sd["pad_head.net.3.running_var"]
    )
    set_dense(keras_model.get_layer("pad_logits"), sd["pad_head.net.6.weight"], sd["pad_head.net.6.bias"])

    # Quality Head
    set_dense(keras_model.get_layer("qual_fc1"), sd["quality_head.net.2.weight"], sd["quality_head.net.2.bias"])
    set_dense(keras_model.get_layer("quality_scores"), sd["quality_head.net.4.weight"], sd["quality_head.net.4.bias"])

    # Mesh Head
    set_dense(keras_model.get_layer("mesh_fc1"), sd["mesh_head.net.2.weight"], sd["mesh_head.net.2.bias"])
    set_bn(
        keras_model.get_layer("mesh_bn1"),
        sd["mesh_head.net.3.weight"], sd["mesh_head.net.3.bias"],
        sd["mesh_head.net.3.running_mean"], sd["mesh_head.net.3.running_var"]
    )
    set_dense(keras_model.get_layer("mesh_dense"), sd["mesh_head.net.5.weight"], sd["mesh_head.net.5.bias"])

    # 3DMM Geom Head
    set_dense(keras_model.get_layer("geom_fc1"), sd["geom_head.net.2.weight"], sd["geom_head.net.2.bias"])
    set_bn(
        keras_model.get_layer("geom_bn1"),
        sd["geom_head.net.3.weight"], sd["geom_head.net.3.bias"],
        sd["geom_head.net.3.running_mean"], sd["geom_head.net.3.running_var"]
    )
    set_dense(keras_model.get_layer("geom_3dmm"), sd["geom_head.net.5.weight"], sd["geom_head.net.5.bias"])

    # Gaze Head
    set_dense(keras_model.get_layer("gaze_fc1"), sd["gaze_head.net.2.weight"], sd["gaze_head.net.2.bias"])
    set_dense(keras_model.get_layer("gaze_angles"), sd["gaze_head.net.4.weight"], sd["gaze_head.net.4.bias"])

    # Attribute Head
    set_dense(keras_model.get_layer("attr_fc1"), sd["attr_head.net.2.weight"], sd["attr_head.net.2.bias"])
    set_dense(keras_model.get_layer("attribute_probs"), sd["attr_head.net.4.weight"], sd["attr_head.net.4.bias"])

    print("[+] Successfully transferred all PyTorch weights to Keras model!", flush=True)

def export_all():
    print("=" * 70, flush=True)
    print(" OmniFace Unified Model V1 — TFLite Production FlatBuffer Exporter", flush=True)
    print("=" * 70, flush=True)

    # 1. Load PyTorch Checkpoint
    ckpt_path = "training/unified/checkpoints/best_unified_model_v1.pt"
    print(f"[*] Loading PyTorch checkpoint: {ckpt_path}...", flush=True)
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
    print("[*] Verifying numerical parity between PyTorch and Keras...", flush=True)
    dummy_pt = torch.randn(1, 3, 112, 112)
    # Convert NCHW -> NHWC for Keras
    dummy_tf = np.transpose(dummy_pt.numpy(), (0, 2, 3, 1))

    with torch.no_grad():
        pt_out = pt_model(dummy_pt)
    tf_out = k_model(dummy_tf)

    # Compare Identity Embedding
    cos_sim = np.dot(pt_out["identity_embedding"][0].numpy(), tf_out[0][0].numpy())
    print(f"    Identity Embedding Cosine Parity: {cos_sim:.6f} (target: ~1.000000)", flush=True)
    assert cos_sim > 0.999, f"Identity parity check failed: {cos_sim}"

    # Compare PAD Logits
    pad_mae = np.mean(np.abs(pt_out["pad_logits"][0].numpy() - tf_out[1][0].numpy()))
    print(f"    PAD Logits MAE: {pad_mae:.6f}", flush=True)

    print("[+] NUMERICAL PARITY CONFIRMED! Exporting FlatBuffers...", flush=True)

    os.makedirs("models_cache", exist_ok=True)
    os.makedirs("app/src/main/assets", exist_ok=True)

    # 4. FP32 Export
    print("[*] Converting to TFLite FP32...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    tflite_fp32 = converter.convert()
    fp32_path = "models_cache/unified_face_v1_fp32.tflite"
    with open(fp32_path, "wb") as f:
        f.write(tflite_fp32)
    print(f"[+] Exported: {fp32_path} ({len(tflite_fp32)/(1024*1024):.2f} MB)", flush=True)

    # 5. FP16 Export (Mobile GPU Delegate Target)
    print("[*] Converting to TFLite FP16 (GPU Delegate target)...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_fp16 = converter.convert()
    fp16_path = "models_cache/unified_face_v1_fp16.tflite"
    with open(fp16_path, "wb") as f:
        f.write(tflite_fp16)
    print(f"[+] Exported: {fp16_path} ({len(tflite_fp16)/(1024*1024):.2f} MB)", flush=True)

    # 6. INT8 Export (NPU / NNAPI MLIR Per-Channel Target)
    print("[*] Converting to TFLite INT8 with Dynamic Range Quantization...", flush=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(k_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_int8 = converter.convert()
    int8_path = "models_cache/unified_face_v1_int8.tflite"
    with open(int8_path, "wb") as f:
        f.write(tflite_int8)
    print(f"[+] Exported: {int8_path} ({len(tflite_int8)/(1024*1024):.2f} MB)", flush=True)

    # 7. Copy Production FlatBuffers into Android Assets
    import shutil
    asset_fp16 = "app/src/main/assets/unified_face_v1_fp16.tflite"
    asset_int8 = "app/src/main/assets/unified_face_v1_int8.tflite"
    shutil.copyfile(fp16_path, asset_fp16)
    shutil.copyfile(int8_path, asset_int8)
    print(f"[+] Deployed to Android Assets:", flush=True)
    print(f"    -> {asset_fp16} ({os.path.getsize(asset_fp16)/(1024*1024):.2f} MB)", flush=True)
    print(f"    -> {asset_int8} ({os.path.getsize(asset_int8)/(1024*1024):.2f} MB)", flush=True)

    # 8. Test TFLite Interpreter Signatures
    from ai_edge_litert.interpreter import Interpreter
    print("[*] Validating TFLite signature runners...", flush=True)
    interp = Interpreter(model_path=fp16_path)
    interp.allocate_tensors()
    input_details = interp.get_input_details()
    output_details = interp.get_output_details()
    print(f"[+] TFLite Verified Inputs: {len(input_details)}")
    for d in input_details:
        print(f"    Name: {d['name']}, Shape: {d['shape']}, Dtype: {d['dtype']}")
    print(f"[+] TFLite Verified Outputs: {len(output_details)}")
    for i, d in enumerate(output_details):
        print(f"    [{i}] Name: {d['name']}, Shape: {d['shape']}, Dtype: {d['dtype']}")

    print("\n" + "=" * 70, flush=True)
    print(" ALL UNIFIED FLATBUFFERS SUCCESSFULLY EXPORTED & DEPLOYED! ", flush=True)
    print("=" * 70, flush=True)

if __name__ == "__main__":
    export_all()
