import unittest
import numpy as np
from training.unified.distillation.teacher_extractor import MultiTeacherExtractor

class TestTeacherDistillationPipeline(unittest.TestCase):
    def test_extractor_deterministic_targets(self):
        extractor = MultiTeacherExtractor(models_dir="models_cache")
        sample_face = np.ones((112, 112, 3), dtype=np.float32) * 0.5
        
        targets1 = extractor.extract_targets(sample_face)
        targets2 = extractor.extract_targets(sample_face)
        
        self.assertEqual(targets1["image_hash"], targets2["image_hash"])
        self.assertTrue(np.allclose(targets1["identity_embedding"], targets2["identity_embedding"]))
        
        # Verify identity embedding shape & unit norm
        emb = targets1["identity_embedding"]
        self.assertEqual(emb.shape, (512,))
        norm = np.linalg.norm(emb)
        self.assertAlmostEqual(norm, 1.0, places=5)
        
        # Verify PAD logits
        self.assertEqual(targets1["pad_logits"].shape, (3,))
        self.assertEqual(targets1["geom_3dmm"].shape, (265,))
        self.assertEqual(targets1["mesh_landmarks"].shape, (468, 3))
        self.assertEqual(targets1["gaze_angles"].shape, (2,))

if __name__ == '__main__':
    unittest.main()
