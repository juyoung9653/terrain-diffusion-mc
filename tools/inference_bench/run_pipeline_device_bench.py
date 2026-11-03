"""Compare CPU/CUDA or CUDA model offloading in frozen production elevation code.

Filesystem/config stubs isolate the game. Only the copied CPU session options
are instrumented for explicit intra-op threads; production files are untouched.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import statistics
import struct
import subprocess
import sys
import time

from capture_decoder_inputs import ROOT, sha
sys.path.insert(0, str(ROOT / "tools"))
from probe_suite import exclusive_lock, atomic_json, utc_now

PACKAGE = Path("com/github/xandergos/terraindiffusionmc")
HERE = Path(__file__).resolve().parent
FLAGS = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--capture", type=Path, default=ROOT / "build/inference-bench/fp16-application/capture-direct-fp32")
    parser.add_argument("--java-home", type=Path, default=Path("C:/Program Files/Java/jdk-21"))
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--comparison", choices=("cpu-cuda", "offload"), default="cpu-cuda",
                        help="preparation only: CPU thread comparison or CUDA offload false/true ABBA")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    output = args.output.resolve()
    if args.timeout < 1:
        raise ValueError("timeout must be positive")
    if not args.run:
        if output.exists():
            raise ValueError("refusing existing output")
        capture = json.loads((args.capture / "capture-preparation.json").read_text())
        if capture.get("preoptimizedDecoder"):
            raise ValueError("FP32 capture required")
        output.mkdir(parents=True)
        models = output / "models"
        models.mkdir()
        for name, expected in capture["models"].items():
            source = args.capture / "models" / name
            if sha(source) != expected:
                raise ValueError(f"capture model hash mismatch: {name}")
            shutil.copy2(source, models / name)
            if sha(models / name) != expected:
                raise ValueError(f"copy hash mismatch: {name}")
        # Copy pre-existing optimized graphs to equalize preparation in each JVM.
        cache = args.capture / "models/onnx-cache"
        if cache.is_dir():
            shutil.copytree(cache, models / "onnx-cache")
        src = output / "src"
        pipeline = src / PACKAGE / "pipeline"
        pipeline.mkdir(parents=True)
        production = ROOT / "common/src/main/java" / PACKAGE / "pipeline"
        source_hashes = {}
        for name in ("WorldPipeline.java", "OnnxModel.java", "WorldPipelineModelConfig.java"):
            source_hashes[name] = sha(production / name)
            text = (production / name).read_text(encoding="utf-8")
            if name == "OnnxModel.java":
                anchor = '        if ("cpu".equals(TerrainDiffusionConfig.inferenceDevice())) {\n            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();'
                if text.count(anchor) != 1:
                    raise ValueError("CPU session instrumentation anchor changed")
                text = text.replace(anchor, anchor + '\n            int benchmarkThreads = Integer.getInteger("td.bench.threads", 0);\n            if (benchmarkThreads > 0) sessionOptions.setIntraOpNumThreads(benchmarkThreads);')
            (pipeline / name).write_text(text, encoding="utf-8")
        shutil.copy2(HERE / "PipelineDeviceBenchmark.java", pipeline / "PipelineDeviceBenchmark.java")
        shutil.copy2(HERE / "capture_stubs" / PACKAGE / "pipeline/ModelAssetManager.java", pipeline / "ModelAssetManager.java")
        config = src / PACKAGE / "config/TerrainDiffusionConfig.java"
        config.parent.mkdir(parents=True)
        config.write_text('package com.github.xandergos.terraindiffusionmc.config;\n'
                          'public final class TerrainDiffusionConfig {\n'
                          'public static String inferenceDevice() { return System.getProperty("td.bench.device"); }\n'
                          'public static boolean offloadModels() { return Boolean.getBoolean("td.bench.offload"); }\n}\n', encoding="utf-8")
        dependencies = {"releaseJar": capture["releaseJar"],
                        **{k: capture["dependencies"][k] for k in ("onnxruntimeGpu", "gson", "slf4j")}}
        if sha(Path(dependencies["releaseJar"])) != capture["releaseJarSha256"]:
            raise ValueError("release dependency hash mismatch")
        receipt = {"kind": "pipeline_device_benchmark_preparation", "createdUtc": utc_now(),
                   "models": {str(p.relative_to(models)): sha(p) for p in models.rglob("*") if p.is_file()},
                   "dependencies": {k: {"path": v, "sha256": sha(Path(v))} for k, v in dependencies.items()},
                   "sources": {str(p.relative_to(src)): sha(p) for p in src.rglob("*.java")},
                   "productionSources": source_hashes, "javaHome": str(args.java_home),
                   "instrumentation": "Only CPU session options support td.bench.threads; file/config stubs; td.bench.offload selects production session lifecycle",
                   "comparison": args.comparison,
                   "order": (["cuda", "cuda-offload", "cuda-offload", "cuda"] if args.comparison == "offload"
                             else ["cuda", "cpu-default", "cpu-30", "cpu-30", "cpu-default", "cuda"])}
        atomic_json(output / "preparation.json", receipt)
        print(output / "preparation.json", flush=True)
        return 0

    receipt = json.loads((output / "preparation.json").read_text())
    if (output / "status.json").exists():
        raise ValueError("refusing to rerun started benchmark")
    for folder, key in (("models", "models"), ("src", "sources")):
        for name, expected in receipt[key].items():
            if sha(output / folder / name) != expected:
                raise ValueError(f"prepared input changed: {folder}/{name}")
    for item in receipt["dependencies"].values():
        if sha(Path(item["path"])) != item["sha256"]:
            raise ValueError("dependency changed")
    classes = output / "classes"
    classes.mkdir()
    deps = [item["path"] for item in receipt["dependencies"].values()]
    java = Path(receipt["javaHome"]) / "bin"
    runs = []
    with exclusive_lock(ROOT):
        status = {"startedUtc": utc_now(), "state": "running", "completed": 0}
        atomic_json(output / "status.json", status)
        try:
            cmd = [str(java / "javac.exe"), "--release", "21", "-encoding", "UTF-8", "-cp", os.pathsep.join(deps),
                   "-d", str(classes), *map(str, sorted((output / "src").rglob("*.java")))]
            compile_run = subprocess.run(cmd, cwd=ROOT, capture_output=True, creationflags=FLAGS)
            (output / "compile.log").write_bytes(compile_run.stdout + compile_run.stderr)
            compile_run.check_returncode()
            for index, variant in enumerate(receipt["order"]):
                run = output / f"run-{index:02d}-{variant}"
                run.mkdir()
                device, threads = ("gpu", 0) if variant.startswith("cuda") else ("cpu", 30 if variant == "cpu-30" else 0)
                offload = variant == "cuda-offload"
                cmd = [str(java / "java.exe"), "-Xmx10G", "-Dtd.inference.metrics=true",
                       f"-Dtd.bench.device={device}", f"-Dtd.bench.threads={threads}", f"-Dtd.capture.models={output / 'models'}",
                       f"-Dtd.bench.offload={str(offload).lower()}",
                       "-cp", os.pathsep.join([str(classes), *deps]),
                       "com.github.xandergos.terraindiffusionmc.pipeline.PipelineDeviceBenchmark", str(run)]
                atomic_json(run / "command.json", cmd)
                print(f"Starting {index + 1}/{len(receipt['order'])} {variant}", flush=True)
                started = time.monotonic()
                with (run / "stdout.log").open("wb") as log:
                    process = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                             timeout=args.timeout, creationflags=FLAGS)
                process.check_returncode()
                result = json.loads((run / "result.json").read_text())
                if result.get("success") is not True or result["provider"] != ("CUDA" if device == "gpu" else "CPU"):
                    raise ValueError("invalid successful result")
                if result["offloadModels"] != offload:
                    raise ValueError("wrong model offload setting")
                calls = [{key: val["calls"] for key, val in q["modelRuns"].items()} for q in result["queries"]]
                if runs and calls != runs[0]["calls"]:
                    raise ValueError("model call counts differ; cannot compare workloads")
                entry = {"variant": variant, "elapsedSeconds": time.monotonic() - started, "calls": calls,
                         "result": result, "output": str(run.relative_to(output)),
                         "logSha256": sha(run / "stdout.log"),
                         "outputSha256": [sha(run / f"{k}.elevation.bin") for k in range(2)]}
                runs.append(entry)
                print(json.dumps({"variant": variant, "queries": result["queries"]}), flush=True)
                status["completed"] = len(runs)
                atomic_json(output / "status.json", status)
            aggregates = {}
            for variant in dict.fromkeys(receipt["order"]):
                matching = [r for r in runs if r["variant"] == variant]
                aggregates[variant] = {"totalQuerySecondsMean": statistics.mean(sum(q["seconds"] for q in r["result"]["queries"]) for r in matching),
                                       "individualTotalQuerySeconds": [sum(q["seconds"] for q in r["result"]["queries"]) for r in matching]}
            for value in aggregates.values():
                value["timeRatioVsCuda"] = value["totalQuerySecondsMean"] / aggregates["cuda"]["totalQuerySecondsMean"]
            baseline = [struct.unpack("<65536f", (output / runs[0]["output"] / f"{k}.elevation.bin").read_bytes()) for k in range(2)]
            for run in runs:
                differences = []
                for k, reference in enumerate(baseline):
                    actual = struct.unpack("<65536f", (output / run["output"] / f"{k}.elevation.bin").read_bytes())
                    diffs = [abs(a - b) for a, b in zip(reference, actual)]
                    differences.append({"maxAbs": max(diffs), "meanAbs": statistics.mean(diffs)})
                run["elevationDifferenceVsFirstCuda"] = differences
            # Verify no input model/cache/source changed during the benchmark.
            for folder, key in (("models", "models"), ("src", "sources")):
                for name, expected in receipt[key].items():
                    if sha(output / folder / name) != expected:
                        raise ValueError(f"input mutated during benchmark: {folder}/{name}")
            atomic_json(output / "summary.json", {"success": True, "comparison": receipt.get("comparison", "cpu-cuda"), "aggregates": aggregates, "runs": runs,
                        "limitations": "FP32 after warmup, two elevation crops, two JVMs per variant; excludes Minecraft generation and initial startup; session reloads caused by offloading during queries ARE timed; output differences are reported, not equivalence accepted."})
            status.update(state="success", completedUtc=utc_now())
            atomic_json(output / "status.json", status)
            print(json.dumps(aggregates, indent=2), flush=True)
        except BaseException as failure:
            status.update(state="failed", error=str(failure), completedUtc=utc_now())
            atomic_json(output / "status.json", status)
            raise
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
