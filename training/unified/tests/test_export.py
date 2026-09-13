import unittest
import os
import sys
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
import torch
from training.unified.models.student_backbones import build_student_backbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.export.exporter import ModelExporter

class TestModelExport(unittest.TestCase):
    def test_onnx_export_signature(self):
        backbone = build_student_backbone("ghostnet_v2", out_channels=512)
        model = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=106)
        
        exporter = ModelExporter(model)
        export_path = "training/unified/checkpoints/test_export_v2.onnx"
        ts_path = "training/unified/checkpoints/test_export_v2.pt"
        
        try:
            res_path = exporter.export_onnx(export_path)
            self.assertTrue(os.path.exists(res_path))
            file_size = os.path.getsize(res_path)
            self.assertGreater(file_size, 100_000)  # > 100KB
            
            res_ts = exporter.export_torchscript(ts_path)
            self.assertTrue(os.path.exists(res_ts))
            self.assertGreater(os.path.getsize(res_ts), 100_000)
        finally:
            for p in [export_path, export_path.replace(".onnx", ".pt"), ts_path]:
                if os.path.exists(p):
                    os.remove(p)

if __name__ == '__main__':
    unittest.main()
