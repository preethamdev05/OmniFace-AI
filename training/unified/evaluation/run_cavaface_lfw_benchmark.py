import os
import sys
import json
import time

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.abspath("."))

from training.unified.evaluation.lfw_evaluator import LFWEvaluator

def main():
    print("=" * 80)
    print(" CavaFace Teacher — Official 6,000-Pair LFW Benchmark (10-Fold CV)")
    print("=" * 80)
    
    tflite_path = "models_cache/cavaface.tflite"
    cache_path = "training/unified/evaluation/cavaface_lfw_cache.npz"
    out_json = "training/unified/evaluation/cavaface_lfw_baseline_results.json"
    
    if not os.path.exists(tflite_path):
        print(f"[-] Error: CavaFace model not found at {tflite_path}")
        sys.exit(1)
        
    evaluator = LFWEvaluator()
    print(f"[*] Total pairs to evaluate: {len(evaluator.pairs)}")
    
    t0 = time.time()
    results = evaluator.evaluate_tflite(
        tflite_path=tflite_path,
        is_cavaface=True,
        num_threads=8,
        cache_path=cache_path
    )
    elapsed = time.time() - t0
    results["total_evaluation_time_seconds"] = elapsed
    results["model_name"] = "CavaFace (Qualcomm AI Hub IR-SE-100)"
    results["model_path"] = tflite_path
    
    print("\n" + "=" * 80)
    print(" Official CavaFace LFW 6,000-Pair Results:")
    print("=" * 80)
    print(f"  Accuracy (10-Fold CV) : {results['lfw_accuracy_mean']*100:.2f}% ± {results['lfw_accuracy_std']*100:.2f}%")
    print(f"  Optimal Threshold     : {results['optimal_threshold']:.4f}")
    print(f"  Separation Index d'   : {results['d_prime']:.3f}")
    print(f"  Genuine Mean          : {results['genuine_mean']:.4f} ± {results['genuine_std']:.4f}")
    print(f"  Impostor Mean         : {results['impostor_mean']:.4f} ± {results['impostor_std']:.4f}")
    print(f"  TAR @ 1.0% FAR        : {results['tar_at_far_1pct']*100:.2f}%")
    print(f"  TAR @ 0.1% FAR        : {results['tar_at_far_01pct']*100:.2f}%")
    print(f"  Total Pairs Evaluated : {results['total_evaluated_pairs']}")
    print(f"  Time Elapsed          : {elapsed:.1f}s")
    print("=" * 80)
    
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"[+] Baseline benchmark saved to: {out_json}")

if __name__ == "__main__":
    main()
