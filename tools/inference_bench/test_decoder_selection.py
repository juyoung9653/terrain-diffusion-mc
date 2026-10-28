"""Pure-JVM selection and integrity checks for the optional FP16 decoder.

Compiles the production config and asset-manager classes with a tiny platform
path stub.  It never creates an ONNX session, starts Minecraft, or downloads an
asset: the manifest contains only already-created local test files and every
case sets ``validate_model=false`` where asset validation would otherwise try a
network repair.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/config/TerrainDiffusionConfig.java"
ASSETS = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/ModelAssetManager.java"
CANDIDATE = ROOT / "build/inference-bench/decoder-precision/fp16-optimized-source/decoder_model.onnx"
EXPECTED_SHA = "af32fdd6955f15611c5fe4696f5a6c96dd008657b361f391db1c948739d125ee"
EXPECTED_BYTES = 56_077_790


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            value.update(block)
    return value.hexdigest()


def cached_jar(group: str, name: str) -> Path:
    matches = list((ROOT / ".gradle/caches/modules-2/files-2.1" / group / name).glob("*/*/*.jar"))
    if len(matches) != 1:
        raise RuntimeError(f"expected one cached {group}:{name} JAR, found {matches}")
    return matches[0]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def sources(root: Path) -> list[Path]:
    platform = root / "src/com/github/xandergos/terraindiffusionmc/platform/PlatformPaths.java"
    harness = root / "src/DecoderSelectionHarness.java"
    write(platform, """package com.github.xandergos.terraindiffusionmc.platform;
import java.nio.file.Path;
public final class PlatformPaths {
  private static Path config, game;
  public static void configure(Path c, Path g) { config=c; game=g; }
  public static Path configDir() { return config; }
  public static Path gameDir() { return game; }
}
""")
    write(harness, """import java.nio.file.Path;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
public final class DecoderSelectionHarness {
  public static void main(String[] a) {
    PlatformPaths.configure(Path.of(a[0]), Path.of(a[1]));
    try {
      Path selected=ModelAssetManager.resolveDecoderAssetPath();
      if (!a[2].equals(selected.getFileName().toString()))
        throw new AssertionError("selected="+selected+" expected="+a[2]);
      System.out.println("PASS selected="+selected.getFileName());
    } catch (Throwable failure) {
      if (!a[2].equals("ERROR") || !String.valueOf(failure.getMessage()).contains(a[3])) {
        failure.printStackTrace(System.out); System.exit(2);
      }
      System.out.println("PASS rejected="+failure.getClass().getSimpleName()+":"+failure.getMessage());
    }
  }
}
""")
    return [platform, harness, CONFIG, ASSETS]


def make_case(root: Path, name: str, properties: str, asset_kind: str) -> tuple[Path, Path]:
    case = root / "cases" / name
    config = case / "config"
    game = case / "game"
    models = game / "terrain-diffusion-models"
    models.mkdir(parents=True)
    write(config / "terrain-diffusion-mc.properties", properties)
    # Manifest is intentionally local and tiny. validate_model=false prevents
    # its fake FP32 digest from invoking the downloader.
    resources = case / "resources"
    write(resources / "model-assets-manifest.json", json.dumps({
        "repositorySlug": "local/test", "revision": "none",
        "assets": {"decoder_model.onnx": {"sha256": "00", "sizeBytes": 1, "url": "https://invalid.test/never"}},
    }))
    write(resources / "build-variant.properties", "build.variant=cuda\n")
    write(resources / "terrain-diffusion-mc.properties", "inference.device=gpu\nvalidate_model=false\ntile_size=256\n")
    original = models / "decoder_model.onnx"
    original.write_bytes(b"original-fp32-must-remain")
    fp16 = models / "decoder_model_fp16.onnx"
    if asset_kind == "correct":
        shutil.copyfile(CANDIDATE, fp16)
    elif asset_kind == "wrong-size":
        fp16.write_bytes(b"wrong-size")
    elif asset_kind == "wrong-sha-same-size":
        shutil.copyfile(CANDIDATE, fp16)
        with fp16.open("r+b") as output:
            output.seek(0)
            output.write(bytes([output.read(1)[0] ^ 0x01]))
    elif asset_kind != "missing":
        raise ValueError(asset_kind)
    return config, game


def run_case(java: Path, classes: Path, gson: Path, slf4j: Path, resource: Path,
             config: Path, game: Path, expected: str, message: str) -> subprocess.CompletedProcess[str]:
    cp = ";".join(map(str, (resource, classes, gson, slf4j)))
    return subprocess.run([str(java), "-cp", cp, "DecoderSelectionHarness", str(config), str(game), expected, message],
                          cwd=ROOT, text=True, capture_output=True, check=False,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def main() -> int:
    java_home = Path(r"C:\Program Files\Java\jdk-21")
    javac, java = java_home / "bin/javac.exe", java_home / "bin/java.exe"
    if not CANDIDATE.is_file() or CANDIDATE.stat().st_size != EXPECTED_BYTES or digest(CANDIDATE) != EXPECTED_SHA:
        raise SystemExit("pinned FP16 candidate is absent or does not match its expected SHA/size")
    gson, slf4j = cached_jar("com.google.code.gson", "gson"), cached_jar("org.slf4j", "slf4j-api")
    output = ROOT / "build/inference-bench" / f"decoder-selection-test-{time.strftime('%Y%m%d-%H%M%S')}"
    src, classes = output / "harness", output / "classes"
    classes.mkdir(parents=True)
    source_files = sources(src)
    compiled = subprocess.run([str(javac), "--release", "21", "-cp", f"{gson};{slf4j}", "-d", str(classes), *map(str, source_files)],
                               cwd=ROOT, text=True, capture_output=True, check=False,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    (output / "compile.stdout.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode:
        raise SystemExit(f"compile failed; see {output / 'compile.stdout.log'}")
    cases = [
        # Omit decoder_precision: this specifically exercises the production
        # default rather than merely the explicit fp32 branch.
        ("fp32-default", "validate_model=false\n", "missing", "decoder_model.onnx", "", "fp32"),
        ("fp16-correct", "inference.decoder_precision=fp16\ninference.device=gpu\nvalidate_model=false\n", "correct", "decoder_model_fp16.onnx", "", "fp16"),
        ("fp16-missing", "inference.decoder_precision=fp16\ninference.device=gpu\nvalidate_model=false\n", "missing", "ERROR", "FP16 decoder missing or invalid", "rejected"),
        ("fp16-wrong-size", "inference.decoder_precision=fp16\ninference.device=gpu\nvalidate_model=false\n", "wrong-size", "ERROR", "FP16 decoder missing or invalid", "rejected"),
        ("fp16-wrong-sha", "inference.decoder_precision=fp16\ninference.device=gpu\nvalidate_model=false\n", "wrong-sha-same-size", "ERROR", "FP16 decoder missing or invalid", "rejected"),
        ("unknown-precision", "inference.decoder_precision=bf16\ninference.device=gpu\nvalidate_model=false\n", "missing", "ERROR", "must be fp32 or fp16", "rejected"),
        ("fp16-cpu", "inference.decoder_precision=fp16\ninference.device=cpu\nvalidate_model=false\n", "missing", "ERROR", "requires the CUDA build and inference.device=gpu", "rejected"),
    ]
    records = []
    for name, props, kind, expected, message, category in cases:
        config, game = make_case(output, name, props, kind)
        resource = output / "cases" / name / "resources"
        completed = run_case(java, classes, gson, slf4j, resource, config, game, expected, message)
        log = output / "cases" / name / "stdout.log"
        log.write_text(completed.stdout + completed.stderr, encoding="utf-8")
        original = game / "terrain-diffusion-models/decoder_model.onnx"
        unchanged = original.read_bytes() == b"original-fp32-must-remain"
        if completed.returncode or not unchanged:
            raise SystemExit(f"{name} failed; see {log}")
        records.append({"name": name, "category": category, "returnCode": completed.returncode,
                        "stdout": str(log.relative_to(output)), "fp32Unchanged": unchanged})
    receipt = {"status": "success", "kind": "decoder_selection_pure_jvm", "candidate": {"path": str(CANDIDATE), "sha256": digest(CANDIDATE), "bytes": CANDIDATE.stat().st_size},
               "productionSources": {"configSha256": digest(CONFIG), "assetManagerSha256": digest(ASSETS)},
               "cases": records, "noNetwork": True, "noNativeInference": True}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(output / "receipt.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
