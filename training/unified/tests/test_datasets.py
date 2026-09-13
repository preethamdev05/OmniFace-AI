import unittest
import torch
from training.unified.preprocessing.transforms import BiometricAugmentationPipeline
from training.unified.datasets.multitask_dataset import OmniFaceMultiTaskDataset, OpenSetPairGenerator

class TestMultiTaskDatasets(unittest.TestCase):
    def test_augmentation_pipeline_shape_and_bounds(self):
        pipeline = BiometricAugmentationPipeline(is_training=True)
        img = torch.rand(3, 112, 112)
        augmented = pipeline(img)
        self.assertEqual(augmented.shape, (3, 112, 112))
        self.assertGreaterEqual(augmented.min().item(), 0.0)
        self.assertLessEqual(augmented.max().item(), 1.0)

    def test_dataset_item_schema(self):
        dataset = OmniFaceMultiTaskDataset(num_samples=20, num_identities=5, is_training=True)
        self.assertEqual(len(dataset), 20)
        
        face, targets, meta = dataset[0]
        self.assertEqual(face.shape, (3, 112, 112))
        self.assertIn("identity_label", targets)
        self.assertIn("pad_label", targets)
        self.assertIn("mesh_landmarks", targets)
        self.assertEqual(targets["mesh_landmarks"].shape, (468, 3))
        self.assertIn("sample_id", meta)
        self.assertIn("image_hash", meta)
        self.assertEqual(len(meta["image_hash"]), 64)  # SHA-256

    def test_open_set_pair_generator(self):
        dataset = OmniFaceMultiTaskDataset(num_samples=100, num_identities=10, is_training=False)
        generator = OpenSetPairGenerator(dataset)
        genuine, impostors = generator.generate_pairs(num_genuine=25, num_impostor=100, seed=123)
        
        self.assertEqual(len(genuine), 25)
        self.assertEqual(len(impostors), 100)
        
        # Verify genuine pairs share the exact same identity
        for idx_a, idx_b in genuine:
            self.assertEqual(dataset.identity_labels[idx_a], dataset.identity_labels[idx_b])
            self.assertNotEqual(idx_a, idx_b)
            
        # Verify impostor pairs belong to distinct identities
        for idx_a, idx_b in impostors:
            self.assertNotEqual(dataset.identity_labels[idx_a], dataset.identity_labels[idx_b])

if __name__ == '__main__':
    unittest.main()
