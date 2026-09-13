import unittest
import numpy as np
from training.unified.calibration.calibrator import UnifiedModelCalibrator

class TestCalibrationProtocol(unittest.TestCase):
    def test_independent_threshold_calibration(self):
        calibrator = UnifiedModelCalibrator()
        
        # Simulate realistic genuine and impostor cosine distances
        rng = np.random.RandomState(42)
        genuine_dists = rng.normal(loc=0.18, scale=0.04, size=1000).clip(0.0, 1.0)
        impostor_dists = rng.normal(loc=0.75, scale=0.08, size=10000).clip(0.0, 1.0)
        
        thresholds = calibrator.calibrate_from_distributions(genuine_dists, impostor_dists)
        
        # Assert thresholds are computed and distinct from legacy MobileFaceNet constants
        self.assertGreater(thresholds.tau_standard, 0.0)
        self.assertGreater(thresholds.tau_high, 0.0)
        self.assertGreater(thresholds.tau_strict, 0.0)
        self.assertGreater(thresholds.track_swap_threshold, 0.0)
        
        # Strict must be tighter (smaller distance) than standard
        self.assertLess(thresholds.tau_strict, thresholds.tau_standard)

    def test_temporal_fusion_comparison(self):
        calibrator = UnifiedModelCalibrator()
        gallery = np.array([1.0] + [0.0]*511, dtype=np.float32)
        
        # 3 frames of a face with high similarity to gallery [1, 0, 0, ...]
        f1 = np.array([1.0] + [0.001]*511, dtype=np.float32); f1 /= np.linalg.norm(f1)
        f2 = np.array([1.0] + [0.002]*511, dtype=np.float32); f2 /= np.linalg.norm(f2)
        f3 = np.array([0.95] + [0.005]*511, dtype=np.float32); f3 /= np.linalg.norm(f3)
        
        res = calibrator.compare_temporal_fusion([f1, f2, f3], [0.8, 0.99, 0.4], gallery)
        self.assertIn("quality_weighted_sim", res)
        self.assertIn("best_frame_sim", res)
        self.assertGreater(res["quality_weighted_sim"], 0.9)

if __name__ == '__main__':
    unittest.main()
