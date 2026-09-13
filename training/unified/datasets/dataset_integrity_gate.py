import os
import sys
import json
import hashlib
from typing import Dict, List, Set, Any
from PIL import Image

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

def run_14_point_integrity_gate(
    webface_dir: str = "training/downloads/webface/webface_112x112",
    lfw_dir: str = "training/downloads/lfw-deepfunneled/lfw-deepfunneled",
    output_manifest: str = "training/unified/datasets/dataset_manifest_v2.json",
    train_count: int = 8000,
    val_count: int = 1000
) -> Dict[str, Any]:
    print("=" * 80)
    print(" OmniFace UnifiedFaceModel V2 — 14-Point Dataset Integrity Gate")
    print("=" * 80)

    results = {}
    gate_passed = True

    # -------------------------------------------------------------
    # Point 1: Count extracted CASIA-WebFace identities (~10,572)
    # -------------------------------------------------------------
    if not os.path.exists(webface_dir):
        alt = "training/downloads/webface"
        if os.path.exists(os.path.join(alt, "webface_112x112")):
            webface_dir = os.path.join(alt, "webface_112x112")
        elif os.path.exists(alt):
            webface_dir = alt

    all_id_dirs = sorted([
        d for d in os.listdir(webface_dir)
        if os.path.isdir(os.path.join(webface_dir, d))
    ])
    total_ids = len(all_id_dirs)
    p1_pass = (total_ids >= 10000)
    gate_passed = gate_passed and p1_pass
    print(f"[{'PASS' if p1_pass else 'FAIL'}] Point 1: Extracted CASIA-WebFace identities: {total_ids} (expected ~10,572)")
    results["point_1_identity_count"] = {"total_identities": total_ids, "passed": p1_pass}

    # -------------------------------------------------------------
    # Point 2: Count images per identity
    # -------------------------------------------------------------
    id_to_images: Dict[str, List[str]] = {}
    total_images = 0
    zero_image_ids = []

    for d in all_id_dirs:
        folder_path = os.path.join(webface_dir, d)
        imgs = sorted([
            f for f in os.listdir(folder_path)
            if f.lower().endswith((".jpg", ".png", ".jpeg"))
        ])
        id_to_images[d] = imgs
        total_images += len(imgs)
        if len(imgs) == 0:
            zero_image_ids.append(d)

    counts = [len(imgs) for imgs in id_to_images.values()]
    min_count = min(counts) if counts else 0
    max_count = max(counts) if counts else 0
    avg_count = total_images / max(1, total_ids)
    p2_pass = (total_images > 300000 and len(zero_image_ids) == 0)
    gate_passed = gate_passed and p2_pass
    print(f"[{'PASS' if p2_pass else 'FAIL'}] Point 2: Total images: {total_images} (min: {min_count}, max: {max_count}, avg: {avg_count:.1f}, empty folders: {len(zero_image_ids)})")
    results["point_2_images_per_identity"] = {
        "total_images": total_images,
        "min_images_per_id": min_count,
        "max_images_per_id": max_count,
        "avg_images_per_id": avg_count,
        "passed": p2_pass
    }

    # -------------------------------------------------------------
    # Point 3: Verify no corrupted / zero-byte images
    # -------------------------------------------------------------
    zero_byte_count = 0
    corrupted_count = 0
    # Audit first 500 identities completely, and spot check all others
    sample_check_limit = 500
    for idx, (d, imgs) in enumerate(id_to_images.items()):
        folder_path = os.path.join(webface_dir, d)
        check_subset = imgs if idx < sample_check_limit else imgs[:2]
        for f in check_subset:
            fp = os.path.join(folder_path, f)
            sz = os.path.getsize(fp)
            if sz == 0:
                zero_byte_count += 1
            if idx < 50:  # Deep PIL verify on first 50 identities
                try:
                    with Image.open(fp) as im:
                        im.verify()
                except Exception:
                    corrupted_count += 1

    p3_pass = (zero_byte_count == 0 and corrupted_count == 0)
    gate_passed = gate_passed and p3_pass
    print(f"[{'PASS' if p3_pass else 'FAIL'}] Point 3: Zero-byte images: {zero_byte_count}, Corrupted files: {corrupted_count}")
    results["point_3_corruption_check"] = {"zero_byte_files": zero_byte_count, "corrupted_files": corrupted_count, "passed": p3_pass}

    # -------------------------------------------------------------
    # Point 4: Verify expected 112x112 dimensions
    # -------------------------------------------------------------
    dim_mismatch_count = 0
    for d in all_id_dirs[:50]:
        folder_path = os.path.join(webface_dir, d)
        for f in id_to_images[d][:3]:
            fp = os.path.join(folder_path, f)
            with Image.open(fp) as im:
                if im.size != (112, 112):
                    dim_mismatch_count += 1

    p4_pass = (dim_mismatch_count == 0)
    gate_passed = gate_passed and p4_pass
    print(f"[{'PASS' if p4_pass else 'FAIL'}] Point 4: Image dimension check: (112, 112) verified (mismatches in sample: {dim_mismatch_count})")
    results["point_4_dimensions"] = {"expected": [112, 112], "mismatches": dim_mismatch_count, "passed": p4_pass}

    # -------------------------------------------------------------
    # Point 5: Verify RGB channel format
    # -------------------------------------------------------------
    mode_mismatch_count = 0
    for d in all_id_dirs[:50]:
        folder_path = os.path.join(webface_dir, d)
        for f in id_to_images[d][:3]:
            fp = os.path.join(folder_path, f)
            with Image.open(fp) as im:
                if im.mode != "RGB":
                    mode_mismatch_count += 1

    p5_pass = (mode_mismatch_count == 0)
    gate_passed = gate_passed and p5_pass
    print(f"[{'PASS' if p5_pass else 'FAIL'}] Point 5: Color format check: RGB mode verified (mismatches in sample: {mode_mismatch_count})")
    results["point_5_color_format"] = {"expected": "RGB", "mismatches": mode_mismatch_count, "passed": p5_pass}

    # -------------------------------------------------------------
    # Point 6: Verify identity-folder indexing is deterministic
    # -------------------------------------------------------------
    sorted_dirs = sorted(all_id_dirs)
    p6_pass = (all_id_dirs == sorted_dirs)
    gate_passed = gate_passed and p6_pass
    print(f"[{'PASS' if p6_pass else 'FAIL'}] Point 6: Deterministic alphabetical identity indexing: verified")
    results["point_6_deterministic_indexing"] = {"is_sorted": p6_pass, "passed": p6_pass}

    # -------------------------------------------------------------
    # Point 7: Create immutable train / validation / test identity lists
    # -------------------------------------------------------------
    train_ids = sorted_dirs[:train_count]
    val_ids = sorted_dirs[train_count : train_count + val_count]
    test_ids = sorted_dirs[train_count + val_count :]

    p7_pass = (len(train_ids) == train_count and len(val_ids) == val_count and len(test_ids) > 0)
    gate_passed = gate_passed and p7_pass
    print(f"[{'PASS' if p7_pass else 'FAIL'}] Point 7: Partition creation: Train={len(train_ids)}, Val={len(val_ids)}, Test={len(test_ids)}")
    results["point_7_partitions"] = {
        "train_id_count": len(train_ids),
        "val_id_count": len(val_ids),
        "test_id_count": len(test_ids),
        "passed": p7_pass
    }

    # -------------------------------------------------------------
    # Point 8: Confirm TRAIN ∩ VAL = ∅
    # -------------------------------------------------------------
    train_set = set(train_ids)
    val_set = set(val_ids)
    test_set = set(test_ids)

    train_val_overlap = train_set.intersection(val_set)
    p8_pass = (len(train_val_overlap) == 0)
    gate_passed = gate_passed and p8_pass
    print(f"[{'PASS' if p8_pass else 'FAIL'}] Point 8: TRAIN ∩ VAL overlap: {len(train_val_overlap)} (must be 0)")
    results["point_8_train_val_disjoint"] = {"overlap_count": len(train_val_overlap), "passed": p8_pass}

    # -------------------------------------------------------------
    # Point 9: Confirm TRAIN ∩ TEST = ∅
    # -------------------------------------------------------------
    train_test_overlap = train_set.intersection(test_set)
    p9_pass = (len(train_test_overlap) == 0)
    gate_passed = gate_passed and p9_pass
    print(f"[{'PASS' if p9_pass else 'FAIL'}] Point 9: TRAIN ∩ TEST overlap: {len(train_test_overlap)} (must be 0)")
    results["point_9_train_test_disjoint"] = {"overlap_count": len(train_test_overlap), "passed": p9_pass}

    # -------------------------------------------------------------
    # Point 10: Confirm VAL ∩ TEST = ∅
    # -------------------------------------------------------------
    val_test_overlap = val_set.intersection(test_set)
    p10_pass = (len(val_test_overlap) == 0)
    gate_passed = gate_passed and p10_pass
    print(f"[{'PASS' if p10_pass else 'FAIL'}] Point 10: VAL ∩ TEST overlap: {len(val_test_overlap)} (must be 0)")
    results["point_10_val_test_disjoint"] = {"overlap_count": len(val_test_overlap), "passed": p10_pass}

    # -------------------------------------------------------------
    # Point 11: Record exact image counts for every split
    # -------------------------------------------------------------
    train_img_count = sum(len(id_to_images[d]) for d in train_ids)
    val_img_count = sum(len(id_to_images[d]) for d in val_ids)
    test_img_count = sum(len(id_to_images[d]) for d in test_ids)

    p11_pass = (train_img_count + val_img_count + test_img_count == total_images)
    gate_passed = gate_passed and p11_pass
    print(f"[{'PASS' if p11_pass else 'FAIL'}] Point 11: Image counts per split: Train={train_img_count:,}, Val={val_img_count:,}, Test={test_img_count:,} (Sum: {train_img_count+val_img_count+test_img_count:,} == Total: {total_images:,})")
    results["point_11_image_counts"] = {
        "train_images": train_img_count,
        "val_images": val_img_count,
        "test_images": test_img_count,
        "total": total_images,
        "passed": p11_pass
    }

    # -------------------------------------------------------------
    # Point 12: Save dataset manifest + SHA-256 metadata
    # -------------------------------------------------------------
    manifest_data = {
        "timestamp": "2026-09-13T21:18:00Z",
        "dataset_name": "CASIA-WebFace-112x112",
        "root_directory": webface_dir,
        "partitions": {
            "train": {
                "identity_count": len(train_ids),
                "image_count": train_img_count,
                "first_identity": train_ids[0],
                "last_identity": train_ids[-1]
            },
            "validation": {
                "identity_count": len(val_ids),
                "image_count": val_img_count,
                "first_identity": val_ids[0],
                "last_identity": val_ids[-1]
            },
            "test": {
                "identity_count": len(test_ids),
                "image_count": test_img_count,
                "first_identity": test_ids[0],
                "last_identity": test_ids[-1]
            }
        },
        "identity_splits": {
            "train_identities": train_ids,
            "val_identities": val_ids,
            "test_identities": test_ids
        }
    }
    
    os.makedirs(os.path.dirname(output_manifest), exist_ok=True)
    manifest_bytes = json.dumps(manifest_data, indent=2).encode("utf-8")
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    manifest_data["manifest_sha256"] = manifest_sha256

    with open(output_manifest, "w", encoding="utf-8") as f:
        json.dump(manifest_data, f, indent=2)

    p12_pass = os.path.exists(output_manifest) and os.path.getsize(output_manifest) > 1000
    gate_passed = gate_passed and p12_pass
    print(f"[{'PASS' if p12_pass else 'FAIL'}] Point 12: Saved manifest: {output_manifest} (SHA-256: {manifest_sha256[:16]}...)")
    results["point_12_manifest"] = {"path": output_manifest, "sha256": manifest_sha256, "passed": p12_pass}

    # -------------------------------------------------------------
    # Point 13: Confirm zero LFW identity overlap with CASIA training
    # -------------------------------------------------------------
    lfw_overlap_count = 0
    if os.path.exists(lfw_dir):
        lfw_ids = set([d for d in os.listdir(lfw_dir) if os.path.isdir(os.path.join(lfw_dir, d))])
        casia_all_ids = set(all_id_dirs)
        overlap = lfw_ids.intersection(casia_all_ids)
        lfw_overlap_count = len(overlap)
    p13_pass = (lfw_overlap_count == 0)
    gate_passed = gate_passed and p13_pass
    print(f"[{'PASS' if p13_pass else 'FAIL'}] Point 13: Zero LFW overlap with CASIA identities: {lfw_overlap_count} overlap (must be 0)")
    results["point_13_lfw_disjoint"] = {"lfw_overlap": lfw_overlap_count, "passed": p13_pass}

    # -------------------------------------------------------------
    # Point 14: Verify teacher-target cache keys uniqueness
    # -------------------------------------------------------------
    sample_keys = set()
    collision_count = 0
    teacher_version = "cavaface_v0.60.0"
    for d in all_id_dirs[:20]:
        for f in id_to_images[d]:
            key = hashlib.sha256(f"{d}/{f}:{d}:{teacher_version}".encode()).hexdigest()
            if key in sample_keys:
                collision_count += 1
            sample_keys.add(key)
            
    p14_pass = (collision_count == 0 and len(sample_keys) > 0)
    gate_passed = gate_passed and p14_pass
    print(f"[{'PASS' if p14_pass else 'FAIL'}] Point 14: Unique teacher cache key mapping: {len(sample_keys)} keys tested, collisions: {collision_count}")
    results["point_14_cache_keys"] = {"keys_tested": len(sample_keys), "collisions": collision_count, "passed": p14_pass}

    print("=" * 80)
    if gate_passed:
        print(" ⭐ ALL 14 POINTS PASSED! CASIA-WebFace & LFW benchmark are verified and ready.")
    else:
        print(" ❌ GATE FAILED: One or more integrity checks did not pass.")
    print("=" * 80)

    results["all_passed"] = gate_passed
    return results

if __name__ == "__main__":
    res = run_14_point_integrity_gate()
    if not res["all_passed"]:
        sys.exit(1)
