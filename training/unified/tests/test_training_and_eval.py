import unittest
import torch
from training.unified.models.student_backbones import build_student_backbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset
from training.unified.training.trainer import MultiTaskTrainer
from training.unified.evaluation.evaluator import BiometricEvaluator

class TestTrainingAndEvaluationPipeline(unittest.TestCase):
    def test_end_to_end_training_and_evaluation_cycle(self):
        backbone = build_student_backbone("mobilenet_v4_conv_small", out_channels=512)
        model = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=468)
        
        train_ds = OmniFaceMultiTaskDataset(num_samples=16, num_identities=4, is_training=True)
        val_ds = OmniFaceMultiTaskDataset(num_samples=16, num_identities=4, is_training=False)
        
        trainer = MultiTaskTrainer(model, train_ds, val_ds)
        result = trainer.run_training_cycle(epochs=2)
        
        self.assertEqual(len(result["history"]), 2)
        self.assertIn("train_loss", result["history"][0])
        self.assertIn("tar_at_far_1pct", result["history"][0])
        self.assertIn("pad_acer", result["history"][0])
        self.assertTrue(result["history"][0]["is_best"])

if __name__ == '__main__':
    unittest.main()
