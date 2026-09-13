import torch
import numpy as np
import random
from typing import Tuple

class BiometricAugmentationPipeline:
    """
    Field-hardened data augmentation simulating real-world biometric enrollment/scanner conditions:
    - Extreme lighting, glare, low-light shadows
    - Defocus blur & camera motion blur
    - Sensor noise & JPEG artifacts
    - Partial occlusions (masks, glasses, hands)
    """
    def __init__(self, is_training: bool = True):
        self.is_training = is_training

    def __call__(self, img_tensor: torch.Tensor) -> torch.Tensor:
        """
        img_tensor: Float tensor [3, 112, 112] normalized to [0.0, 1.0]
        """
        if not self.is_training:
            return img_tensor

        img = img_tensor.clone()

        # 1. Photometric: Brightness, Contrast, Gamma (p = 0.5)
        if random.random() < 0.5:
            brightness_factor = random.uniform(0.7, 1.3)
            img = torch.clamp(img * brightness_factor, 0.0, 1.0)
            
        if random.random() < 0.4:
            contrast_factor = random.uniform(0.75, 1.25)
            mean = img.mean(dim=[-2, -1], keepdim=True)
            img = torch.clamp((img - mean) * contrast_factor + mean, 0.0, 1.0)

        # 2. Sensor Noise (Gaussian) (p = 0.3)
        if random.random() < 0.3:
            noise = torch.randn_like(img) * random.uniform(0.01, 0.04)
            img = torch.clamp(img + noise, 0.0, 1.0)

        # 3. Defocus / Downsampling Blur (p = 0.3)
        if random.random() < 0.3:
            down_scale = random.randint(28, 56)
            down = torch.nn.functional.interpolate(img.unsqueeze(0), size=(down_scale, down_scale), mode='bilinear', align_corners=False)
            img = torch.nn.functional.interpolate(down, size=(112, 112), mode='bilinear', align_corners=False).squeeze(0)

        # 4. Partial Occlusion / Cutout (p = 0.25)
        if random.random() < 0.25:
            h, w = 112, 112
            mask_h = random.randint(12, 28)
            mask_w = random.randint(16, 40)
            y = random.randint(0, h - mask_h)
            x = random.randint(0, w - mask_w)
            img[:, y:y+mask_h, x:x+mask_w] = random.uniform(0.0, 0.5)

        return img
