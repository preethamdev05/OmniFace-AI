import os
import sys
import time
import math
import json
from typing import Dict, List, Tuple, Any, Optional

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

import numpy as np
from PIL import Image
import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from training.unified.models.student_backbones import MobileNetV4ConvSmallBackbone
from training.unified.heads.multitask_heads import OmniFaceUnifiedModelV2
from training.unified.samplers.pk_sampler import IdentityPKSampler
from training.unified.losses.subcenter_arcface_v2 import UnifiedIdentityLossV2
from training.unified.datasets.webface_lfw_dataset import WebFaceDataset
from training.unified.datasets.nuaa_dataset import NUAAPadDataset
from training.unified.evaluation.evaluate_v2_comprehensive import ComprehensiveV2Evaluator
from training.unified.evaluation.lfw_evaluator import LFWEvaluator
from training.unified.distillation.cavaface_cache_builder import CavaFaceFeatureExtractor

def run_99pct_finetuning_pipeline(
    base_checkpoint: str = "training/unified/checkpoints/best_unified_model_v2.pt",
    finetune_epochs: int = 10,
    lr: float = 1e-4,
    arc_margin: float = 0.50,
    lambda_distill: float = 0.60,
    device_name: str = "cuda"
):
    print("=" * 80, flush=True)
    print(" OmniFace UnifiedFaceModel V2 — 99% Precision Multi-Metric Fine-Tuner", flush=True)
    print(" Utilizing skills: ml-engineer, mle-workflow, mlops-engineer, ml-best-practices", flush=True)
    print("=" * 80, flush=True)

    device = torch.device(device_name if torch.cuda.is_available() else "cpu")
    print(f"[*] Device: {device.type.upper()}", flush=True)
    if device.type == "cuda":
        print(f"    GPU: {torch.cuda.get_device_name(0)} (Capability {torch.cuda.get_device_capability(0)})", flush=True)

    if not os.path.exists(base_checkpoint):
        base_checkpoint = "training/unified/checkpoints/last_unified_model_v2.pt"
    if not os.path.exists(base_checkpoint):
        raise FileNotFoundError(f"[-] Base checkpoint not found at: {base_checkpoint}")

    # 1. Load Architecture & Foundation Checkpoint
    print(f"[*] Loading foundation checkpoint: {base_checkpoint}...", flush=True)
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468).to(device)
    
    ckpt = torch.load(base_checkpoint, map_location=device)
    model.load_state_dict(ckpt["model_state_dict"])
    print("[+] Foundation weights loaded successfully.", flush=True)

    # 2. Datasets
    print("[*] Initializing CASIA-WebFace fine-tuning sets...", flush=True)
    train_dataset = WebFaceDataset(split="train", train_class_count=8000)
    val_dataset = WebFaceDataset(split="open_set_val", train_class_count=8000)

    # Pre-select balanced unseen validation set (100 distinct identities x 4 images = 400 images)
    val_id_map = {}
    for idx, (p, lbl) in enumerate(val_dataset.samples):
        if lbl not in val_id_map:
            val_id_map[lbl] = []
        if len(val_id_map[lbl]) < 4:
            val_id_map[lbl].append(idx)
        if len(val_id_map) >= 100 and all(len(v) >= 4 for v in val_id_map.values()):
            break
    val_eval_indices = []
    for id_indices in val_id_map.values():
        val_eval_indices.extend(id_indices)

    pk_sampler = IdentityPKSampler(
        labels=train_dataset.labels,
        p_identities=16,
        k_images=4,
        max_batches=200
    )
    train_loader = DataLoader(
        train_dataset,
        batch_sampler=pk_sampler,
        num_workers=0,
        pin_memory=(device.type == "cuda")
    )

    nuaa_train_ds = NUAAPadDataset(split="train")
    nuaa_loader = DataLoader(
        nuaa_train_ds,
        batch_size=32,
        shuffle=True,
        num_workers=0,
        pin_memory=(device.type == "cuda")
    )
    nuaa_iter = iter(nuaa_loader)

    # 3. Precision Loss Functions (Increased Angular Margin + High-Density Teacher Distillation)
    print(f"[*] Configuring Precision Loss: ArcFace Margin = {arc_margin:.2f}, Lambda Distill = {lambda_distill:.2f}...", flush=True)
    id_loss_fn = UnifiedIdentityLossV2(
        num_classes=train_dataset.num_classes,
        embedding_dim=512,
        sub_centers=3,
        scale=64.0,
        arc_margin=arc_margin,
        triplet_margin=0.25,
        lambda_distill=lambda_distill,
        lambda_neg=0.25
    ).to(device)

    # Transfer classification weights from foundation if available
    if "id_loss_state_dict" in ckpt:
        try:
            id_loss_fn.load_state_dict(ckpt["id_loss_state_dict"], strict=False)
            print("[+] Classification head state restored.", flush=True)
        except Exception as e:
            print(f"[!] Head transfer warning: {e}", flush=True)

    pad_ce_loss = nn.CrossEntropyLoss()

    optimizer = torch.optim.AdamW(
        list(model.parameters()) + list(id_loss_fn.parameters()),
        lr=lr,
        weight_decay=5e-5
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=finetune_epochs, eta_min=1e-6)
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    cava_extractor = None
    if os.path.exists("models_cache/cavaface.tflite"):
        cava_extractor = CavaFaceFeatureExtractor("models_cache/cavaface.tflite", num_threads=8)
        print("[+] CavaFace Teacher Active for High-Density Geometric Distillation.", flush=True)

    evaluator = ComprehensiveV2Evaluator(device=device)

    best_tar_1pct = ckpt.get("tar_at_far_1pct", 0.0)
    best_epoch = 0
    save_path = "training/unified/checkpoints/experiment_v2_ft.pt"

    print("\n" + "=" * 80, flush=True)
    print(f" Starting Precision Fine-Tuning Loop ({finetune_epochs} Epochs)", flush=True)
    print("=" * 80, flush=True)

    for epoch in range(1, finetune_epochs + 1):
        model.train()
        id_loss_fn.train()
        t0 = time.time()
        train_loss_accum = 0.0
        steps = 0

        for batch_faces, batch_labels, batch_paths in train_loader:
            batch_faces = batch_faces.to(device)
            batch_labels = batch_labels.to(device)

            teacher_embs = None
            if cava_extractor is not None and steps % 2 == 0:
                try:
                    cava_list = []
                    for bp in batch_paths[:16]:
                        with Image.open(bp) as img:
                            cava_list.append(cava_extractor.extract_single(img))
                    t_embs = torch.from_numpy(np.array(cava_list)).to(device)
                    teacher_embs = torch.zeros((len(batch_faces), 512), device=device)
                    teacher_embs[:len(t_embs)] = t_embs
                except Exception:
                    teacher_embs = None

            try:
                nuaa_batch = next(nuaa_iter)
            except StopIteration:
                nuaa_iter = iter(nuaa_loader)
                nuaa_batch = next(nuaa_iter)

            nuaa_faces = nuaa_batch["face"].to(device)
            nuaa_pad_labels = nuaa_batch["pad_label"].to(device)

            optimizer.zero_grad()
            with torch.amp.autocast("cuda", enabled=(device.type == "cuda")):
                id_preds = model(batch_faces)
                loss_id, _ = id_loss_fn(id_preds["identity_embedding"], batch_labels, teacher_embs)
                pad_preds = model(nuaa_faces)
                loss_pad = pad_ce_loss(pad_preds["pad_logits"], nuaa_pad_labels)
                total_loss = loss_id + 0.30 * loss_pad

            scaler.scale(total_loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(list(model.parameters()) + list(id_loss_fn.parameters()), 3.0)
            scaler.step(optimizer)
            scaler.update()

            train_loss_accum += total_loss.item()
            steps += 1
            if steps % 50 == 0:
                print(f"    [Step {steps:03d}/{len(train_loader)}] Loss: {total_loss.item():.4f} (ID: {loss_id.item():.4f})", flush=True)

        scheduler.step()
        epoch_time = time.time() - t0
        avg_loss = train_loss_accum / max(1, steps)

        # Evaluation
        model.eval()
        val_samples = [val_dataset[i] for i in val_eval_indices]
        val_faces = [s[0] for s in val_samples]
        val_labels = np.array([s[1] for s in val_samples])
        val_embs = evaluator._extract_embs(model, val_faces)

        tier_b_res = evaluator.evaluate_identity_pairs(val_embs, val_labels, num_gen=600, num_imp=2000)
        pad_res = evaluator.evaluate_pad(model, max_samples=500)

        tar_1pct = tier_b_res["tar_at_far_1pct"]
        tar_01pct = tier_b_res["tar_at_far_01pct"]
        d_prime = tier_b_res["d_prime"]

        print(f"\n[Fine-Tune Epoch {epoch:02d}/{finetune_epochs:02d}] Loss: {avg_loss:.4f} (LR: {scheduler.get_last_lr()[0]:.2e}, Time: {epoch_time:.1f}s)", flush=True)
        print(f"  [+] Tier B (Unseen Identities): TAR@1%: {tar_1pct*100:.2f}% | TAR@0.1%: {tar_01pct*100:.2f}% | d': {d_prime:.3f}", flush=True)
        print(f"      Genuine: {tier_b_res['genuine_mean']:.3f} ± {tier_b_res['genuine_std']:.3f} | Impostor: {tier_b_res['impostor_mean']:.3f} ± {tier_b_res['impostor_std']:.3f}", flush=True)
        print(f"  [+] PAD Defense (NUAA Test)  : ACER: {pad_res['acer']*100:.2f}%", flush=True)

        if tar_1pct > best_tar_1pct:
            best_tar_1pct = tar_1pct
            best_epoch = epoch
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "id_loss_state_dict": id_loss_fn.state_dict(),
                "tar_at_far_1pct": tar_1pct,
                "tar_at_far_01pct": tar_01pct,
                "tier_b_metrics": tier_b_res,
                "pad_metrics": pad_res
            }, save_path)
            print(f"  ⭐ PRECISION CHECKPOINT SAVED: {save_path} (TAR@1%: {tar_1pct*100:.2f}%)", flush=True)

    print("\n" + "=" * 80, flush=True)
    print(f" Precision Fine-Tuning Complete! Best Epoch: {best_epoch} (TAR@1%: {best_tar_1pct*100:.2f}%)", flush=True)
    print("=" * 80, flush=True)

    return {
        "best_epoch": best_epoch,
        "best_tar_1pct": best_tar_1pct,
        "checkpoint_path": save_path
    }

if __name__ == "__main__":
    run_99pct_finetuning_pipeline()
