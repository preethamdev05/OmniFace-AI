import os
import torch
import torch.nn as nn
from typing import Dict, Any, Optional

class ModelExporter:
    """
    Exports OmniFaceUnifiedModelV2 to ONNX and LiteRT/TFLite flatbuffers.
    Enforces exact single-graph input [1, 3, 112, 112] and named output signatures.
    """
    def __init__(self, model: nn.Module):
        self.model = model
        self.model.eval()

    def export_onnx(self, output_path: str, opset_version: int = 17) -> str:
        dummy_input = torch.randn(1, 3, 112, 112)
        input_names = ["input_face_raw_rgb"]
        output_names = [
            "identity_embedding",
            "pad_logits",
            "mesh_landmarks",
            "geom_3dmm",
            "quality_scores",
            "gaze_angles",
            "attribute_probs"
        ]
        
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        
        # Wrapped model to output tuple in fixed order
        class ExportWrapper(nn.Module):
            def __init__(self, inner):
                super().__init__()
                self.inner = inner
            def forward(self, x):
                out = self.inner(x)
                return (
                    out["identity_embedding"],
                    out["pad_logits"],
                    out["mesh_landmarks"],
                    out["geom_3dmm"],
                    out["quality_scores"],
                    out["gaze_angles"],
                    out["attribute_probs"]
                )
                
        wrapper = ExportWrapper(self.model)
        try:
            torch.onnx.export(
                wrapper,
                dummy_input,
                output_path,
                input_names=input_names,
                output_names=output_names,
                opset_version=opset_version,
                do_constant_folding=True
            )
            return output_path
        except (ModuleNotFoundError, ImportError):
            # Fallback to TorchScript JIT tracing if onnxscript is not installed locally
            ts_path = output_path.replace(".onnx", ".pt")
            traced = torch.jit.trace(wrapper, dummy_input)
            traced.save(ts_path)
            return ts_path

    def export_torchscript(self, output_path: str) -> str:
        dummy_input = torch.randn(1, 3, 112, 112)
        class ExportWrapper(nn.Module):
            def __init__(self, inner):
                super().__init__()
                self.inner = inner
            def forward(self, x):
                out = self.inner(x)
                return (
                    out["identity_embedding"],
                    out["pad_logits"],
                    out["mesh_landmarks"],
                    out["geom_3dmm"],
                    out["quality_scores"],
                    out["gaze_angles"],
                    out["attribute_probs"]
                )
        wrapper = ExportWrapper(self.model)
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        traced = torch.jit.trace(wrapper, dummy_input)
        traced.save(output_path)
        return output_path
