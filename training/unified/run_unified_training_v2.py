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
from training.unified.distillation.cavaface_cache_builder import CavaFaceFeatureExtractor

def train_v2_pipeline(
    train_identities: int = 8000,
    val_identities: int = 1000,
    p_identities: int = 16,
    k_images: int = 4,
    max_epochs: int = 25,
    patience: int = 7,
    lr: float = 3e-4,
    device_name: str = "cuda"
):
    device = torch.device(device_name if torch.cuda.is_available() else "cpu")
    print("=" * 80, flush=True)
    print(f" OmniFace UnifiedFaceModel V2 — Production Training on {device.type.upper()}", flush=True)
    if device.type == "cuda":
        print(f" Device: {torch.cuda.get_device_name(0)} (Capability {torch.cuda.get_device_capability(0)})", flush=True)
    print("=" * 80, flush=True)

    # 1. Check Datasets
    webface_dir = "training/downloads/webface/webface_112x112"
    if not os.path.exists(webface_dir):
        if os.path.exists("training/downloads/webface"):
            webface_dir = "training/downloads/webface"
            
    print(f"[*] Loading CASIA-WebFace from {webface_dir}...", flush=True)
    train_dataset = WebFaceDataset(root_dir=webface_dir, split="train", train_class_count=train_identities)
    val_dataset = WebFaceDataset(root_dir=webface_dir, split="open_set_val", train_class_count=train_identities)
    
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
    print(f"[+] Balanced Tier B validation set ready: {len(val_eval_indices)} images across {len(val_id_map)} unseen identities.", flush=True)

    # 2. Sampler & DataLoader with P x K batching
    pk_sampler = IdentityPKSampler(
        labels=train_dataset.labels,
        p_identities=p_identities,
        k_images=k_images,
        max_batches=250  # 250 batches x 64 = 16,000 faces per epoch
    )
    train_loader = DataLoader(
        train_dataset,
        batch_sampler=pk_sampler,
        num_workers=0,
        pin_memory=(device.type == "cuda")
    )
    print(f"[+] P x K DataLoader initialized: Batch Size = {p_identities * k_images} ({p_identities} IDs x {k_images} images).", flush=True)

    # 3. Dedicated PAD Dataset (NUAA)
    nuaa_train_ds = NUAAPadDataset(split="train")
    nuaa_loader = DataLoader(
        nuaa_train_ds,
        batch_size=min(32, len(nuaa_train_ds)),
        shuffle=True,
        num_workers=0,
        pin_memory=(device.type == "cuda")
    )
    nuaa_iter = iter(nuaa_loader)

    # 4. Initialize Architecture
    print("[*] Instantiating MobileNetV4-Conv-Small Backbone & 7-Head Graph...", flush=True)
    backbone = MobileNetV4ConvSmallBackbone(out_channels=512)
    model = OmniFaceUnifiedModelV2(backbone, feature_channels=512, num_mesh_points=468).to(device)
    total_params = sum(p.numel() for p in model.parameters())
    print(f"[+] Model instantiated: {total_params:,} parameters.", flush=True)

    # 5. Initialize Loss Functions
    id_loss_fn = UnifiedIdentityLossV2(
        num_classes=train_dataset.num_classes,
        embedding_dim=512,
        sub_centers=3,
        scale=64.0,
        arc_margin=0.40,
        triplet_margin=0.20,
        lambda_distill=0.40,
        lambda_neg=0.20
    ).to(device)

    pad_ce_loss = nn.CrossEntropyLoss()

    optimizer = torch.optim.AdamW(
        list(model.parameters()) + list(id_loss_fn.parameters()),
        lr=lr,
        weight_decay=1e-4
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max_epochs, eta_min=1e-6)
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    # 6. Initialize CavaFace Teacher
    cava_extractor = None
    if os.path.exists("models_cache/cavaface.tflite"):
        try:
            print("[*] Initializing CavaFace Teacher Extractor...", flush=True)
            cava_extractor = CavaFaceFeatureExtractor("models_cache/cavaface.tflite")
            print("[+] CavaFace Teacher ready for online geometric distillation.", flush=True)
        except Exception as e:
            print(f"[!] Warning: CavaFace initialization deferred: {e}", flush=True)

    # 7. Evaluator
    evaluator = ComprehensiveV2Evaluator(device=device)

    # 8. Training Loop with Metric-Driven Early Stopping
    os.makedirs("training/unified/checkpoints", exist_ok=True)
    best_tar_1pct = -1.0
    best_epoch = 0
    patience_counter = 0

    print("\n" + "=" * 80, flush=True)
    print(" Starting UnifiedFaceModel V2 Training Loop", flush=True)
    print("=" * 80, flush=True)

    for epoch in range(1, max_epochs + 1):
        model.train()
        id_loss_fn.train()
        t0 = time.time()
        
        train_loss_accum = 0.0
        steps = 0

        for batch_faces, batch_labels, batch_paths in train_loader:
            batch_faces = batch_faces.to(device)
            batch_labels = batch_labels.to(device)

            # Online CavaFace teacher embeddings for distillation
            teacher_embs = None
            if cava_extractor is not None and steps % 2 == 0:
                try:
                    cava_list = []
                    for bp in batch_paths[:16]:  # Distill top slice to preserve throughput
                        with Image.open(bp) as img:
                            cava_list.append(cava_extractor.extract_single(img))
                    t_embs = torch.from_numpy(np.array(cava_list)).to(device)
                    teacher_embs = torch.zeros((len(batch_faces), 512), device=device)
                    teacher_embs[:len(t_embs)] = t_embs
                except Exception:
                    teacher_embs = None

            # NUAA PAD batch
            try:
                nuaa_batch = next(nuaa_iter)
            except StopIteration:
                nuaa_iter = iter(nuaa_loader)
                nuaa_batch = next(nuaa_iter)
                
            nuaa_faces = nuaa_batch["face"].to(device)
            nuaa_pad_labels = nuaa_batch["pad_label"].to(device)

            optimizer.zero_grad()
            with torch.amp.autocast("cuda", enabled=(device.type == "cuda")):
                # Forward Identity on WebFace batch
                id_preds = model(batch_faces)
                loss_id, id_metrics = id_loss_fn(id_preds["identity_embedding"], batch_labels, teacher_embs)

                # Forward PAD on NUAA batch
                pad_preds = model(nuaa_faces)
                loss_pad = pad_ce_loss(pad_preds["pad_logits"], nuaa_pad_labels)

                total_loss = loss_id + 0.30 * loss_pad

            if device.type == "cuda":
                scaler.scale(total_loss).backward()
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(list(model.parameters()) + list(id_loss_fn.parameters()), 5.0)
                scaler.step(optimizer)
                scaler.update()
            else:
                total_loss.backward()
                torch.nn.utils.clip_grad_norm_(list(model.parameters()) + list(id_loss_fn.parameters()), 5.0)
                optimizer.step()

            train_loss_accum += total_loss.item()
            steps += 1
            if steps % 50 == 0:
                print(f"    [Step {steps:03d}/{len(train_loader)}] Loss: {total_loss.item():.4f} (ID: {loss_id.item():.4f}, PAD: {loss_pad.item():.4f})", flush=True)

        scheduler.step()
        epoch_time = time.time() - t0
        avg_loss = train_loss_accum / max(1, steps)

        # 9. Metric-Driven Evaluation on Disjoint Unseen Identities (Tier B)
        print(f"\n[Epoch {epoch:02d}/{max_epochs:02d}] Loss: {avg_loss:.4f} (LR: {scheduler.get_last_lr()[0]:.2e}, Time: {epoch_time:.1f}s)", flush=True)
        print("  [*] Running Open-Set Identity & PAD Verification...", flush=True)

        # Extract Tier B (Unseen identities from balanced evaluation subset)
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
        pad_acer = pad_res["acer"]

        print(f"  [+] Tier B (Unseen Identities): TAR@1%: {tar_1pct*100:.2f}% | TAR@0.1%: {tar_01pct*100:.2f}% | d': {d_prime:.3f}", flush=True)
        print(f"      Genuine: {tier_b_res['genuine_mean']:.3f} ± {tier_b_res['genuine_std']:.3f} | Impostor: {tier_b_res['impostor_mean']:.3f} ± {tier_b_res['impostor_std']:.3f}", flush=True)
        print(f"  [+] PAD Defense (NUAA Test)  : APCER: {pad_res['apcer']*100:.1f}% | BPCER: {pad_res['bpcer']*100:.1f}% | ACER: {pad_acer*100:.1f}%", flush=True)

        is_best = tar_1pct > best_tar_1pct
        if is_best:
            best_tar_1pct = tar_1pct
            best_epoch = epoch
            patience_counter = 0
            best_path = "training/unified/checkpoints/best_unified_model_v2.pt"
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "id_loss_state_dict": id_loss_fn.state_dict(),
                "optimizer_state_dict": optimizer.state_dict(),
                "tar_at_far_1pct": tar_1pct,
                "tar_at_far_01pct": tar_01pct,
                "tier_b_metrics": tier_b_res,
                "pad_metrics": pad_res
            }, best_path)
            print(f"  ⭐ NEW BEST CHECKPOINT SAVED: {best_path} (TAR@1%: {tar_1pct*100:.2f}%)", flush=True)
        else:
            patience_counter += 1
            if patience_counter >= patience:
                print(f"[!] Early stopping triggered at epoch {epoch} (no improvement over {patience} epochs).", flush=True)
                break

    # Save last checkpoint
    last_path = "training/unified/checkpoints/last_unified_model_v2.pt"
    torch.save({
        "epoch": epoch,
        "model_state_dict": model.state_dict(),
        "id_loss_state_dict": id_loss_fn.state_dict()
    }, last_path)
    print(f"[+] Saved Last Checkpoint: {last_path}", flush=True)

    print("\n" + "=" * 80, flush=True)
    print(f" UnifiedFaceModel V2 Training Complete! Best Epoch: {best_epoch} (TAR@1%: {best_tar_1pct*100:.2f}%)", flush=True)
    print("=" * 80, flush=True)

    return {
        "best_epoch": best_epoch,
        "best_tar_1pct": best_tar_1pct
    }

if __name__ == "__main__":
    train_v2_pipeline()
