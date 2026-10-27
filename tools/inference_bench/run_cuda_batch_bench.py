"""Compile and run the standalone CUDA batching feasibility benchmark.

Writes a compact receipt containing the exact command, Java output, exit code,
and elapsed time. It never selects a CPU provider: the Java harness fails before
creating sessions when CUDAExecutionProvider is unavailable.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("OnnxCudaBatchBenchmark.java")
DEFAULT_MODELS = ROOT / "build/streams-probe/streams-opt-baseline4-oct22/terrain-diffusion-models"
DEFAULT_JAR = next((ROOT / ".gradle").glob(
    "caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime_gpu/1.20.0/*/onnxruntime_gpu-1.20.0.jar"), None)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODELS)
    parser.add_argument("--models", default="coarse,decoder",
                        help="comma-separated subset of coarse,decoder,base (default: coarse,decoder)")
    parser.add_argument("--algorithm", choices=("HEURISTIC", "EXHAUSTIVE"), default="HEURISTIC",
                        help="CUDA cuDNN algorithm search; default matches production")
    parser.add_argument("--java-home", type=Path, default=Path(r"C:\Program Files\Java\jdk-21"))
    parser.add_argument("--runtime-jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--warmup", type=int, default=4)
    parser.add_argument("--runs", type=int, default=12)
    parser.add_argument("--out", type=Path, default=ROOT / "build/inference-bench")
    parser.add_argument("--decoder-lane-parity", action="store_true",
                        help="compare one decoder input across N=2/4/8 and all N=8 lanes")
    args = parser.parse_args()
    if args.runtime_jar is None or not args.runtime_jar.is_file():
        raise SystemExit("ONNX Runtime GPU 1.20.0 jar was not found; pass --runtime-jar")
    selected = tuple(part for part in args.models.split(",") if part)
    names = {"coarse": "coarse_model.onnx", "decoder": "decoder_model.onnx", "base": "base_model.onnx"}
    if not selected or any(part not in names for part in selected):
        raise SystemExit("--models must be a non-empty comma-separated subset of coarse,decoder,base")
    if not args.model_dir.is_dir():
        raise SystemExit(f"model directory does not exist: {args.model_dir}")
    for model in selected:
        if not (args.model_dir / names[model]).is_file():
            raise SystemExit(f"missing model: {args.model_dir / names[model]}")
    if args.warmup < 0 or args.runs < 1:
        raise SystemExit("--warmup must be nonnegative and --runs positive")

    javac = args.java_home / "bin/javac.exe"
    java = args.java_home / "bin/java.exe"
    if not javac.is_file() or not java.is_file():
        raise SystemExit(f"JDK 21 executables unavailable below {args.java_home}")
    classes = args.out / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    compile_command = [str(javac), "--release", "21", "-cp", str(args.runtime_jar), "-d", str(classes), str(SOURCE)]
    run_command = [str(java), "-cp", f"{classes};{args.runtime_jar}", "OnnxCudaBatchBenchmark",
                   str(args.model_dir), str(args.warmup), str(args.runs),
                   f"--models={args.models}", f"--algorithm={args.algorithm}"]
    if args.decoder_lane_parity:
        run_command.append("--decoder-lane-parity")
    subprocess.run(compile_command, cwd=ROOT, check=True)
    started = time.time()
    completed = subprocess.run(run_command, cwd=ROOT, text=True, capture_output=True)
    receipt = {
        "kind": "cuda_onnx_batch_feasibility",
        "model_dir": str(args.model_dir),
        "models": args.models,
        "algorithm": args.algorithm,
        "runtime_jar": str(args.runtime_jar),
        "warmup": args.warmup,
        "runs": args.runs,
        "decoder_lane_parity": args.decoder_lane_parity,
        "command": run_command,
        "exit_code": completed.returncode,
        "elapsed_seconds": round(time.time() - started, 3),
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    receipt_path = args.out / "receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    print(f"receipt={receipt_path}")
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
