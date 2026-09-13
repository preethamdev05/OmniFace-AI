import unittest
import torch
from training.unified.configs.base_config import StudentArchitectureConfig, LossWeightsConfig
from training.unified.models.student_backbones import build_student_backbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.losses.priority_guarded_loss import PriorityGuardedMultiTaskLoss

class TestUnifiedModelScaffold(unittest.TestCase):
    def test_student_backbone_construction(self):
        backbone = build_student_backbone('mobilenet_v4_conv_small', out_channels=512)
        self.assertIsNotNone(backbone)
        dummy_input = torch.randn(2, 3, 112, 112)
        feature_map = backbone(dummy_input)
        self.assertEqual(feature_map.shape, (2, 512, 7, 7))

    def test_unified_model_parameter_count(self):
        backbone = build_student_backbone('mobilenet_v4_conv_small', out_channels=512)
        model = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=468)
        total_params = sum(p.numel() for p in model.parameters())
        trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
        self.assertLess(total_params, 5_500_000, f'Model params {total_params} exceeds 5.5M budget')
        self.assertEqual(trainable_params, total_params)

    def test_unified_model_forward_pass(self):
        backbone = build_student_backbone('mobilenet_v4_conv_small', out_channels=512)
        model = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=468)
        model.eval()
        batch_size = 2
        dummy_input = torch.randn(batch_size, 3, 112, 112)
        with torch.no_grad():
            outputs = model(dummy_input)
        self.assertIn('identity_embedding', outputs)
        self.assertEqual(outputs['identity_embedding'].shape, (batch_size, 512))
        norms = torch.norm(outputs['identity_embedding'], p=2, dim=-1)
        self.assertTrue(torch.allclose(norms, torch.ones_like(norms), atol=1e-5))
        self.assertEqual(outputs['pad_logits'].shape, (batch_size, 3))
        self.assertEqual(outputs['mesh_landmarks'].shape, (batch_size, 468, 3))
        self.assertEqual(outputs['geom_3dmm'].shape, (batch_size, 265))
        self.assertEqual(outputs['quality_scores'].shape, (batch_size, 4))
        self.assertEqual(outputs['gaze_angles'].shape, (batch_size, 2))
        self.assertEqual(outputs['attribute_probs'].shape, (batch_size, 5))

    def test_priority_guarded_loss_and_gradient_flow(self):
        backbone = build_student_backbone('mobilenet_v4_conv_small', out_channels=512)
        model = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=468)
        loss_fn = PriorityGuardedMultiTaskLoss(num_identities=10, lambda_id=1.0, lambda_pad=1.0)
        batch_size = 2
        dummy_input = torch.randn(batch_size, 3, 112, 112)
        preds = model(dummy_input)
        targets = {
            'identity_label': torch.tensor([0, 1]),
            'pad_label': torch.tensor([0, 2]),
            'mesh_landmarks': torch.randn(batch_size, 468, 3),
            'geom_3dmm': torch.randn(batch_size, 265),
            'quality_scores': torch.rand(batch_size, 4),
            'gaze_angles': torch.randn(batch_size, 2),
            'attribute_probs': torch.rand(batch_size, 5)
        }
        total_loss, metrics = loss_fn(preds, targets)
        self.assertGreater(total_loss.item(), 0.0)
        self.assertIn('loss_id', metrics)
        self.assertIn('loss_pad', metrics)
        total_loss.backward()
        stem_conv = list(model.backbone.stem.children())[0]
        self.assertIsNotNone(stem_conv.weight.grad)
        self.assertGreater(torch.norm(stem_conv.weight.grad).item(), 0.0)

if __name__ == '__main__':
    unittest.main()
