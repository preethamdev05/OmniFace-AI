# OmniFace Unified Biometric Neural Network (V2) — Architecture Specification

**Status**: Architecture Design Document  
**Target Model**: `OmniFaceUnifiedModelV2`  
**Classification Standards**: **[MEASURED]**, **[SYNTHETIC]**, **[TEACHER-DERIVED]**, **[EXPERIMENTALLY-INFERRED]**, **[NOT YET VALIDATED]**.

---

## 1. Multi-Candidate Student Backbone Search

The model must be a genuine multi-task neural network with a **single shared backbone** and specialized task heads. Three candidate mobile backbones will be prototyped and benchmarked during Phase D:

### Candidate A: Depthwise-Separable MobileNetV4-Conv-Small (Preferred)
- **Design**: Inverted residual blocks with universal inverted bottlenecks (UIB), squeeze-and-excitation (SE), hardswish activations.
- **Parameters**: ~4.2M parameters. **[EXPERIMENTALLY-INFERRED]**
- **Target FLOPs**: ~450 MFLOPs. **[EXPERIMENTALLY-INFERRED]**
- **INT8 Compatibility**: 100% supported per-channel quantization operators in LiteRT/NNAPI. **[EXPERIMENTALLY-INFERRED]**

### Candidate B: GhostNetV2 Mobile Backbone
- **Design**: Cheap linear operations to generate ghost feature maps combined with DFC (Decoupled Fully Connected) attention.
- **Parameters**: ~4.8M parameters. **[EXPERIMENTALLY-INFERRED]**
- **Target FLOPs**: ~420 MFLOPs. **[EXPERIMENTALLY-INFERRED]**

### Candidate C: EdgeNeXt Hybrid Attention Backbone
- **Design**: Split depthwise convolutions with lightweight cross-covariance self-attention in later stages.
- **Parameters**: ~5.6M parameters. **[EXPERIMENTALLY-INFERRED]**
- **Target FLOPs**: ~560 MFLOPs. **[EXPERIMENTALLY-INFERRED]**

---

## 2. Shared Feature Representation & Multi-Task Heads

```text
               Input Face RGB [1, 112, 112, 3]
                              │
                    ┌─────────▼─────────┐
                    │  Shared Backbone  │ (MobileNetV4 / GhostNetV2)
                    └─────────┬─────────┘
                              │
                    Shared Feature Tensor
                   F_shared: [1, 512, 7, 7]
                              │
      ┌───────────┬───────────┼───────────┬───────────┬───────────┐
      │           │           │           │           │           │
┌─────▼─────┐┌────▼────┐┌─────▼─────┐┌────▼────┐┌─────▼─────┐┌────▼────┐
│ Identity  ││Passive  ││   Dense   ││3D Geomet││Face Quality││Face Gaze│
│   Head    ││PAD Head ││ Mesh Head ││  Head   ││   Head    ││  Head   │
└─────┬─────┘└────┬────┘└─────┬─────┘└────┬────┘└─────┬─────┘└────┬────┘
      │           │           │           │           │           │
   512-D       3 Logits    468/106 3D  265 3DMM    4 Quality   Pitch/Yaw
  Vector     (Bona/P/S)   Landmarks     Params      Scores     [1, 2]
```

### Head 1: Identity Recognition Head (Priority 0)
- **Architecture**: Global Depthwise Conv (7x7) -> BatchNorm -> Linear(512) -> L2 Normalization.
- **Output**: 512-D unit vector ($||z||_2 = 1.0$).
- **Supervision**: ArcFace / Sub-Center ArcFace loss ($s = 64.0, m = 0.50$). **[NOT YET VALIDATED]**

### Head 2: Passive PAD / Anti-Spoofing Head (Priority 0)
- **Architecture**: Conv2d(1x1, 128) -> AdaptiveAvgPool2d -> Linear(128, 64) -> Linear(64, 3).
- **Output**: 3 logits [Bona Fide Live, Printed Photo, Screen Replay].
- **Supervision**: Softmax Cross-Entropy / Focal Loss with hard mining. **[NOT YET VALIDATED]**

### Head 3: Dense Mesh & Landmark Head (Priority 1)
- **Training Target**: 468-point 3D landmark regression ($[1, 468, 3]$) supervised by MediaPipe teacher, augmented by HRNet heatmaps. **[NOT YET VALIDATED]**
- **Runtime Export Options**:
  - Full 468 3D points ($[1, 468, 3]$)
  - Compact 106 3D canonical landmarks ($[1, 106, 3]$)
  - Evaluated empirically based on accuracy vs latency/size trade-off. **[NOT YET VALIDATED]**

### Head 4: 3D Geometry & Depth Head (Priority 1)
- **Architecture**: Conv2d(1x1) -> Linear -> Linear(265).
- **Output**: 265-D 3DMM parameter vector (shape, expression, camera pose, albedo, illumination). **[NOT YET VALIDATED]**

### Head 5: Face Quality Assessment Head (Priority 1)
- **Architecture**: Linear(4) with Sigmoid activation.
- **Output**: `[blur_quality, illumination_quality, pose_quality, overall_usability]`. **[NOT YET VALIDATED]**

### Head 6: Eye Gaze Head (Priority 2)
- **Architecture**: Linear(64) -> Linear(2).
- **Output**: `[pitch, yaw]` in degrees. **[NOT YET VALIDATED]**

### Head 7: Optional Facial Attribute Head (Priority 2)
- **Architecture**: Linear(5) with Sigmoid activation.
- **Output**: Glasses, mask, beard, young/adult, frontality. **[NOT YET VALIDATED]**\n