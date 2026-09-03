# OmniFace-AI Complete Machine Learning Model Inventory & Fusion Architecture

This document establishes the exhaustive technical inventory of every ML model used across the OmniFace-AI application prior to consolidation into a single unified LiteRT/TFLite model.

---

## 1. Machine Learning Model Inventory Table

| Attribute | Model 1: MiniFASNetV2 | Model 2: Qualcomm CavaFace | Model 3: FaceNet-512 | Model 4: FaceMap 3DMM | Model 5: FaceAttribNet | Model 6: EyeGaze | Model 7: MediaPipe Mesh | Model 8: HRNetFace |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Model Name** | Silent-Face-Anti-Spoofing (MiniFASNetV2) | Qualcomm CavaFace (ArcFace-512) | FaceNet-512 Identity Embedding | FaceMap 3DMM | FaceAttribNet | EyeGaze | MediaPipe Face Landmark Detector | HRNetFace |
| **Original Format** | PyTorch / TFLite (LiteRT) | PyTorch / ONNX / TFLite | TensorFlow / TFLite | PyTorch / ONNX / TFLite | PyTorch / ONNX / TFLite | PyTorch / ONNX / TFLite | TFLite | PyTorch / ONNX / TFLite |
| **Authoritative Source URL** | `https://huggingface.co/litert-community/Silent-Face-Anti-Spoofing-LiteRT/resolve/main/silentface.tflite` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/cavaface/releases/v0.60.0/cavaface-tflite-float.zip` | `https://huggingface.co/nxp/facenet512-imx/resolve/main/original_model/facenet512_uint8_float32.tflite` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/facemap_3dmm/releases/v0.61.0/facemap_3dmm-tflite-float.zip` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/face_attrib_net/releases/v0.61.0/face_attrib_net-tflite-float.zip` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/eyegaze/releases/v0.61.0/eyegaze-tflite-float.zip` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mediapipe_face/releases/v0.61.0/mediapipe_face-tflite-float.zip` | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/hrnet_face/releases/v0.61.0/hrnet_face-tflite-float.zip` |
| **License** | Apache-2.0 | Qualcomm AI Hub Community | MIT / Apache-2.0 | Qualcomm AI Hub Community | Qualcomm AI Hub Community | Qualcomm AI Hub Community | Apache-2.0 | Qualcomm AI Hub Community |
| **Input Shape** | `[1, 3, 80, 80]` | `[1, 112, 112, 3]` | `[1, 160, 160, 3]` | `[1, 128, 128, 3]` | `[1, 128, 128, 3]` | `[1, 96, 160]` (Grayscale) | `[1, 192, 192, 3]` | `[1, 256, 256, 3]` |
| **Input Datatype** | `float32` [0.0, 1.0] | `float32` [0.0, 1.0] | `uint8` | `float32` [0.0, 1.0] | `float32` [0.0, 1.0] | `float32` [0.0, 1.0] | `float32` [0.0, 1.0] | `float32` [0.0, 1.0] |
| **Output Tensors** | `[1, 3]` (`output_0`: class logits) | `[1, 512]` (`embeddings`) | `[1, 512]` (`embeddings`) | `[1, 265]` (`parameters_3dmm`) | `[1, 5]` (`probability`) | 1. `[1, 3, 34, 48, 80]`<br>2. `[1, 34, 2]`<br>3. `[1, 2]` | 1. `[1]` (`scores`)<br>2. `[1, 468, 3]` (`landmarks`) | `[1, 29, 64, 64]` (`heatmaps`) |
| **Output Datatype** | `float32` | `float32` | `float32` | `float32` | `float32` | `float32` | `float32` | `float32` |
| **Preprocessing** | Square face crop resized to 80x80, normalized [0.0, 1.0] | 112x112 ArcFace crop, normalized [0.0, 1.0] | Square crop resized to 160x160 | Face crop resized to 128x128, normalized [0.0, 1.0] | Face crop resized to 128x128, normalized [0.0, 1.0] | Eye region cropped to 160x96, grayscale normalized [0.0, 1.0] | Square face crop resized to 192x192, normalized [0.0, 1.0] | Face crop resized to 256x256, normalized [0.0, 1.0] |
| **Postprocessing** | 3-class softmax: Live = class 1; Spoof = classes 0 & 2; threshold >= 0.65 | L2-normalization, Cosine distance matching against database | L2-normalization, Cosine distance matching against database | Compute eigenvalue variance across first 40 parameters (`variance > 0.005`) | Extract smile (index 0), glasses (index 1), yaw (index 2) | Gaze vector fusion (`headPose + ocularVector`), attentive cone check | Project 468 3D points to screen, compute Z-depth variance | 2D Argmax across 29 heatmaps to compute keypoint coordinates & confidences |
| **Quantization State** | Float32 | Float32 | uint8 input, Float32 output | Float32 | Float32 | Float32 | Float32 | Float32 |
| **Required Operators** | CONV_2D, DEPTHWISE_CONV_2D, ADD, RELU6, AVERAGE_POOL_2D, RESHAPE, FULLY_CONNECTED | CONV_2D, PRELU, ADD, FULLY_CONNECTED, BATCH_MATMUL | CONV_2D, DEPTHWISE_CONV_2D, MAX_POOL_2D, ADD, RELU, MEAN, FULLY_CONNECTED, L2_NORMALIZATION | CONV_2D, BATCH_MATMUL, RESHAPE, FULLY_CONNECTED, RELU | CONV_2D, DEPTHWISE_CONV_2D, ADD, RELU, FULLY_CONNECTED, SIGMOID | CONV_2D, RESHAPE, TRANSPOSE, FULLY_CONNECTED, TANH, RELU | CONV_2D, DEPTHWISE_CONV_2D, ADD, PRELU, RESHAPE, FULLY_CONNECTED | CONV_2D, DEPTHWISE_CONV_2D, ADD, RELU, RESIZE_BILINEAR |
| **Current Runtime** | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter | TFLite Interpreter |
| **Current Android Integration** | `PassivePadEngine.kt` | `FaceRecognitionEngine.kt` | `FaceRecognitionEngine.kt` | `QualcommFaceIntelligenceEngine.kt` | `QualcommFaceIntelligenceEngine.kt` | `QualcommFaceIntelligenceEngine.kt` | `QualcommFaceIntelligenceEngine.kt` | `QualcommFaceIntelligenceEngine.kt` |
| **Directly Convertible?** | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **Embeddable in Unified Graph?** | Yes (Branch 1) | Yes (Branch 2) | Yes (Branch 3) | Yes (Branch 4) | Yes (Branch 5) | Yes (Branch 6) | Yes (Branch 7) | Yes (Branch 8) |
| **GPU / Delegate Compatibility** | Full Adreno GPU & Hexagon NPU | Full Adreno GPU & Hexagon NPU | Full Adreno GPU & Hexagon NPU | Full Adreno GPU & Hexagon NPU | Full Adreno GPU & Hexagon NPU | Supported on GPU/CPU | Full Adreno GPU & Hexagon NPU | Full Adreno GPU & Hexagon NPU |
| **Conversion Blockers** | None | None | None | None | None | None | None | None |

---

## 2. Non-Neural Framework Components (Preserved Outside Model)

1. **Google ML Kit Face Detector (`com.google.mlkit:face-detection:16.1.7`)**:
   - Real-time video frame bounding box tracker running at 60 FPS directly on the CameraX surface. It identifies face bounding box and 5 canonical fiducials (left eye, right eye, nose base, left mouth, right mouth) to feed the stabilized face crop to the unified neural network.
2. **Remote Photoplethysmography (rPPG Pulse Vitality Engine)**:
   - Implemented in `RemotePpgPulseEngine.kt`: Non-neural mathematical chrominance decomposition (`GREEN - RED` color channel signal extraction over time) for pulse extraction.
3. **Temporal Liveness Gate**:
   - Implemented in `TemporalLivenessEngine.kt`: Finite state machine tracking micro-motion, eye blink dynamics, and 3D head rotation consistency across sequential camera frames.
4. **Umeyama Similarity Alignment**:
   - Implemented in `UmeyamaSimilarityTransform.kt`: Closed-form analytical least-squares rigid 2D similarity transform mapping facial fiducials to canonical ArcFace coordinates.

---

## 3. Unified Architecture Specification

```
                         Input: face_input [1, 256, 256, 3] Float32 [0.0, 1.0]
                                eye_input  [1, 96, 160]    Float32 [0.0, 1.0]
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       │       Shared Bilinear Preprocessing       │
                       └─────────────────────┬─────────────────────┘
                                             │
      ┌──────────────┬──────────────┬────────┼──────────────┬──────────────┬──────────────┐
      │              │              │        │              │              │              │
      ▼              ▼              ▼        ▼              ▼              ▼              ▼
[Resize 80x80] [Resize 160x160] [Resize 128x128] [Resize 128x128] [Resize 192x192] [256x256 Direct] [Eye Input]
      │              │              │        │              │              │              │
  MiniFASNet     FaceNet-512     FaceMap   FaceAttribNet    MediaPipe      HRNetFace       EyeGaze
  Anti-Spoof     Embedding       3DMM      Expression       Face Mesh      Landmarks      Pupil Track
      │              │              │        │              │              │              │
      ▼              ▼              ▼        ▼              ▼              ▼              ▼
liveness_scores face_embedding parameters_3dmm face_attrib   mesh_scores    hrnet_heatmaps  gaze_angles
   [1, 3]         [1, 512]       [1, 265]      [1, 5]        [1], [1, 468, 3] [1, 29, 64, 64] [1, 2], [1, 34, 2]
```

### Key Technical Advantages of the Unified Graph:
1. **Single TFLite File**: Packaged as `app/src/main/assets/unified_omniface.tflite`.
2. **Single Inference Interface**: The Android app initializes one `Interpreter` and runs inference with `runForMultipleInputsOutputs`.
3. **Zero Redundant Passes**: A single face crop from CameraX feeds all neural branches simultaneously.
4. **Hardware Acceleration**: Builtin TFLite operators ensure full acceleration via Qualcomm Adreno GPU Delegate and Hexagon NPU.
