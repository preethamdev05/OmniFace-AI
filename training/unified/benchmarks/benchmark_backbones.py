import os
import time
import torch
import torch.nn as nn
from training.unified.models.student_backbones import (
    MobileNetV4ConvSmallBackbone,
    GhostNetV2Backbone,
    build_student_backbone
)
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2

def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())

def estimate_flops(model: nn.Module, input_size=(1, 3, 112, 112)) -> int:
    # Rough analytical estimation for conv2d layers
    total_flops = 0
    def conv_hook(module, input, output):
        nonlocal total_flops
        if isinstance(module, nn.Conv2d):
            batch_size, out_channels, out_h, out_w = output.shape
            in_channels = module.in_channels
            kernel_ops = module.kernel_size[0] * module.kernel_size[1] * (in_channels // module.groups)
            flops = batch_size * out_channels * out_h * out_w * kernel_ops * 2
            total_flops += flops
            
    hooks = []
    for m in model.modules():
        if isinstance(m, nn.Conv2d):
            hooks.append(m.register_forward_hook(conv_hook))
            
    dummy_input = torch.randn(*input_size)
    with torch.no_grad():
        model(dummy_input)
        
    for h in hooks:
        h.remove()
        
    return total_flops

def benchmark_latency(model: nn.Module, input_size=(1, 3, 112, 112), num_runs=50) -> float:
    model.eval()
    dummy = torch.randn(*input_size)
    # Warmup
    for _ in range(10):
        with torch.no_grad():
            model(dummy)
            
    start = time.perf_counter()
    for _ in range(num_runs):
        with torch.no_grad():
            model(dummy)
    end = time.perf_counter()
    return ((end - start) / num_runs) * 1000.0  # ms

if __name__ == "__main__":
    print("=" * 80)
    print("OmniFaceUnifiedModelV2 — Candidate Backbone & Multi-Task Head Benchmark")
    print("=" * 80)
    
    candidates = [
        ("Candidate A (MobileNetV4-Conv-Small)", MobileNetV4ConvSmallBackbone(out_channels=512)),
        ("Candidate B (GhostNetV2)", GhostNetV2Backbone(out_channels=512))
    ]
    
    for name, backbone in candidates:
        backbone_params = count_parameters(backbone)
        backbone_flops = estimate_flops(backbone)
        
        # 1. With Full 468-point Mesh Head
        model_468 = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=468)
        total_params_468 = count_parameters(model_468)
        lat_468 = benchmark_latency(model_468)
        
        # 2. With Compact 106-point Mesh Head
        model_106 = OmniFaceUnifiedModelV2(backbone=backbone, feature_channels=512, num_mesh_points=106)
        total_params_106 = count_parameters(model_106)
        lat_106 = benchmark_latency(model_106)
        
        print(f"\nBackbone: {name}")
        print(f"  Backbone Params: {backbone_params:,} ({backbone_params/1e6:.2f}M)")
        print(f"  Backbone FLOPs:  {backbone_flops:,} ({backbone_flops/1e6:.1f} MFLOPs)")
        print(f"  Full Model (468-pt Mesh):")
        print(f"    Total Params:  {total_params_468:,} ({total_params_468/1e6:.2f}M)")
        print(f"    CPU Latency:   {lat_468:.2f} ms")
        print(f"  Compact Model (106-pt Mesh):")
        print(f"    Total Params:  {total_params_106:,} ({total_params_106/1e6:.2f}M)")
        print(f"    CPU Latency:   {lat_106:.2f} ms")
        
    print("\nBenchmark completed successfully!")
