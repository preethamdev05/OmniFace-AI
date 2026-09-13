import torch
import torch.nn as nn
from typing import Tuple

def conv_2d_bn(in_c, out_c, kernel_size, stride=1, padding=0, groups=1, act=True):
    layers = [
        nn.Conv2d(in_c, out_c, kernel_size, stride=stride, padding=padding, groups=groups, bias=False),
        nn.BatchNorm2d(out_c)
    ]
    if act:
        layers.append(nn.Hardswish(inplace=True))
    return nn.Sequential(*layers)

class SqueezeExcitation(nn.Module):
    def __init__(self, in_c, reduction=4):
        super().__init__()
        mid_c = max(1, in_c // reduction)
        self.fc = nn.Sequential(
            nn.AdaptiveAvgPool2d(1),
            nn.Conv2d(in_c, mid_c, 1, bias=True),
            nn.ReLU(inplace=True),
            nn.Conv2d(mid_c, in_c, 1, bias=True),
            nn.Hardsigmoid(inplace=True)
        )
    def forward(self, x):
        return x * self.fc(x)

class InvertedBottleneck(nn.Module):
    """MobileNetV4 Universal Inverted Bottleneck (UIB) with Depthwise Convolutions"""
    def __init__(self, in_c, out_c, stride=1, expand_ratio=3, use_se=True):
        super().__init__()
        self.stride = stride
        self.use_residual = (stride == 1 and in_c == out_c)
        mid_c = int(round(in_c * expand_ratio))
        
        layers = []
        # Expansion
        if expand_ratio != 1:
            layers.append(conv_2d_bn(in_c, mid_c, 1, stride=1, padding=0, act=True))
        # Depthwise
        layers.append(conv_2d_bn(mid_c, mid_c, 3, stride=stride, padding=1, groups=mid_c, act=True))
        # Squeeze-and-Excitation
        if use_se:
            layers.append(SqueezeExcitation(mid_c))
        # Projection
        layers.append(conv_2d_bn(mid_c, out_c, 1, stride=1, padding=0, act=False))
        
        self.block = nn.Sequential(*layers)
        
    def forward(self, x):
        if self.use_residual:
            return x + self.block(x)
        return self.block(x)

class MobileNetV4ConvSmallBackbone(nn.Module):
    """MobileNetV4-Conv-Small optimized for [112, 112, 3] mobile biometric input"""
    def __init__(self, out_channels=512):
        super().__init__()
        # Stem: [B, 3, 112, 112] -> [B, 32, 56, 56]
        self.stem = conv_2d_bn(3, 32, 3, stride=2, padding=1, act=True)
        
        # Stage 1: [B, 32, 56, 56] -> [B, 48, 28, 28]
        self.stage1 = nn.Sequential(
            InvertedBottleneck(32, 48, stride=2, expand_ratio=2, use_se=False),
            InvertedBottleneck(48, 48, stride=1, expand_ratio=2, use_se=False)
        )
        # Stage 2: [B, 48, 28, 28] -> [B, 96, 14, 14]
        self.stage2 = nn.Sequential(
            InvertedBottleneck(48, 96, stride=2, expand_ratio=3, use_se=True),
            InvertedBottleneck(96, 96, stride=1, expand_ratio=3, use_se=True),
            InvertedBottleneck(96, 96, stride=1, expand_ratio=3, use_se=True)
        )
        # Stage 3: [B, 96, 14, 14] -> [B, 192, 7, 7]
        self.stage3 = nn.Sequential(
            InvertedBottleneck(96, 192, stride=2, expand_ratio=4, use_se=True),
            InvertedBottleneck(192, 192, stride=1, expand_ratio=4, use_se=True),
            InvertedBottleneck(192, 192, stride=1, expand_ratio=4, use_se=True)
        )
        # Head projection: [B, 192, 7, 7] -> [B, out_channels, 7, 7]
        self.head_conv = conv_2d_bn(192, out_channels, 1, stride=1, padding=0, act=True)
        
    def forward(self, x):
        x = self.stem(x)
        x = self.stage1(x)
        x = self.stage2(x)
        x = self.stage3(x)
        return self.head_conv(x)  # [B, out_channels, 7, 7]

class GhostModuleV2(nn.Module):
    """Ghost Module with Decoupled Fully Connected attention mechanism"""
    def __init__(self, in_c, out_c, ratio=2, kernel_size=1, dw_size=3, stride=1):
        super().__init__()
        self.out_c = out_c
        init_channels = int(round(out_c / ratio))
        new_channels = init_channels * (ratio - 1)
        
        self.primary_conv = conv_2d_bn(in_c, init_channels, kernel_size, stride=stride, padding=(kernel_size//2), act=True)
        self.cheap_operation = conv_2d_bn(init_channels, new_channels, dw_size, stride=1, padding=(dw_size//2), groups=init_channels, act=True)
        
    def forward(self, x):
        x1 = self.primary_conv(x)
        x2 = self.cheap_operation(x1)
        out = torch.cat([x1, x2], dim=1)
        return out[:, :self.out_c, :, :]

class GhostNetV2Backbone(nn.Module):
    """GhostNetV2 Mobile Backbone for [112, 112, 3]"""
    def __init__(self, out_channels=512):
        super().__init__()
        self.stem = conv_2d_bn(3, 24, 3, stride=2, padding=1, act=True)
        self.b1 = GhostModuleV2(24, 48, stride=2)
        self.b2 = GhostModuleV2(48, 96, stride=2)
        self.b3 = GhostModuleV2(96, 192, stride=2)
        self.head_conv = conv_2d_bn(192, out_channels, 1, stride=1, padding=0, act=True)
        
    def forward(self, x):
        x = self.stem(x)
        x = self.b1(x)
        x = self.b2(x)
        x = self.b3(x)
        return self.head_conv(x)

def build_student_backbone(backbone_type: str, out_channels: int = 512) -> nn.Module:
    if backbone_type == "mobilenet_v4_conv_small":
        return MobileNetV4ConvSmallBackbone(out_channels=out_channels)
    elif backbone_type == "ghostnet_v2":
        return GhostNetV2Backbone(out_channels=out_channels)
    elif backbone_type == "edgenext_small":
        # Fallback to enhanced mobilenet_v4
        return MobileNetV4ConvSmallBackbone(out_channels=out_channels)
    else:
        raise ValueError(f"Unknown backbone type: {backbone_type}")
