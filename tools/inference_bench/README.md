# Isolated inference measurements

Use these tools to screen decoder changes before a full modpack benchmark.
They require the cached Java 21 / ONNX Runtime GPU 1.20.0 dependencies and hold
the shared `build/probe-suite.lock` for native work. Do not run another GPU probe,
Gradle job, or game workload during measurements. No player instance is modified.

## Capture actual decoder inputs

`capture_decoder_inputs.py` copies the five model/config assets into a fresh
workspace directory. It compiles a copy of `WorldPipeline` with one hook directly
before the decoder call, plus filesystem/config stubs. The release JAR supplies
the remaining production classes. Preparation records source, model and JAR
hashes; execution validates the frozen sources and copied assets.

```powershell
python -B tools/inference_bench/capture_decoder_inputs.py --output build/inference-bench/my-capture
python -B tools/inference_bench/capture_decoder_inputs.py --output build/inference-bench/my-capture --capture
```

Use `--models` and `--release-jar` to select other validated inputs; defaults refer
to the local decoder-cache-256 experiment. Supply `--decoder-model` during
preparation to copy a candidate decoder. Completed captures are not reusable as
fresh runs. Capture saves 12 real decoder inputs (four per region), plus three
256x256 elevation crops at seed `1018612869571620377`. The crop query retains its
218-pixel halo. This executes pipeline math, not Minecraft block/biome/river or
structure placement. Capture wall time includes initialization and is not a
chunk-throughput measurement.

## Measure execution policy or precision

```powershell
$decoderOrt = (Resolve-Path '.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime_gpu/1.20.0/*/onnxruntime_gpu-1.20.0.jar').Path
python -B tools/inference_bench/run_decoder_bench.py `
  --model BASELINE.onnx --candidate CANDIDATE.onnx `
  --candidate-algorithm HEURISTIC `
  --inputs build/inference-bench/my-capture `
  --output build/inference-bench/my-abba --ort-jar $decoderOrt
```

Each candidate gets four fresh JVMs in A/B/B/A order, four warmups per input and
12 timed repetitions per input per JVM. Baseline uses `HEURISTIC`; use the same
model on both sides and explicitly select `EXHAUSTIVE` to test cuDNN search.
Precision comparisons should keep both policies `HEURISTIC`.

Timing covers tensor construction, `session.run` and output retrieval; disk reads,
warmup and session construction are outside the timed interval. CUDA provider
initialization must succeed, but individual node placement remains ORT's choice.
Receipts retain raw timings, input/model/source hashes, logs, per-run numeric
differences, and within-session repeat stability. `measured` and finite outputs
are not terrain compatibility acceptance. Compare repeated candidate output hashes
across JVMs as well. The synthetic fallback is explicitly labelled and must not
be described as real terrain evidence.

## FP16 conversion and elevation comparison

Conversion uses a separate Python environment with `onnx`,
`onnxconverter-common`, and NumPy. The tested versions were ONNX 1.23.2 and
onnxconverter-common 1.16.0. Install dependencies before measuring.

Convert the **production FP32 optimized cache**, created by `OnnxModel` using
CPU `EXTENDED_OPT`, rather than the original downloaded graph:

```powershell
.local/decoder-fp16-venv/Scripts/python.exe -B tools/inference_bench/prepare_decoder_fp16.py `
  --source FP32_OPTIMIZED_CACHE.onnx --output-dir build/inference-bench/my-fp16 `
  --rewrite-original-float-casts-to-fp16 --block-einsum
```

The original graph contains weight-normalization computations which FP32
optimization folds first (3,213 nodes become 1,171). Direct raw-graph FP16
conversion produced a finite but nonreproducible result in one tested JVM, so it
is not an accepted replacement. Optimized-first conversion removed that observed
failure in the tested runs. This is evidence for this graph/runtime/device, not a
general determinism guarantee.

The converter needs the explicit source `Cast(to=FLOAT)` retargeting option:
otherwise converted Conv consumers retain incompatible FLOAT weights. Only
original source casts are retargeted; inserted I/O/blocked-op bridges are retained.
The Fourier `Einsum` remains FP32. Full ONNX checking, I/O/opset invariants,
initializer statistics and conversion settings are saved beside the candidate.
Tiny-value clipping and FP16 rounding remain accuracy changes.

Capture a candidate in a new directory, then compare its elevation with baseline:

```powershell
.local/decoder-fp16-venv/Scripts/python.exe -B tools/inference_bench/compare_elevation_captures.py `
  --baseline build/inference-bench/my-capture `
  --candidate build/inference-bench/my-candidate-capture `
  --output build/inference-bench/my-elevation-comparison.json
```

The comparison requires identical input tensors, source/seed/query contracts and
regions. It reports continuous elevation differences and a floor/clamp height
proxy matching `LocalTerrainProvider.toHeights`. These are not final modpack
ground heights, biome equivalence, structure acceptance or existing-world seam
validation.

## October 27–28 local results

RTX 5060 Laptop GPU, driver 610.62, ORT GPU 1.20.0, real captured inputs, N=1.
Numbers are mean timed milliseconds per decoder call, combining two JVMs per
variant; they exclude model loading and are not modpack chunk throughput.

| Experiment | Baseline | Candidate | Time reduction | Output evidence |
| --- | ---: | ---: | ---: | --- |
| Same raw FP32 graph, HEURISTIC vs EXHAUSTIVE | 55.22 | 55.02 | 0.4% | Bit-identical; no useful speedup established |
| Raw graph converted to FP16 | 55.29 | 43.31 | 21.7% | Rejected: candidate JVMs disagreed substantially |
| FP32 optimized graph, then FP16 | 55.78 | 34.16 | 38.8% | Within-session and between-candidate-JVM outputs bit-identical |

The last candidate differs from FP32 numerically (raw decoder maximum absolute
difference 0.047624). Its three elevation crops contain 196,608 samples: mean
absolute differences by region are 0.044 / 0.067 / 0.183, with maximum 0.645.
Floor/clamp heights differ at 4.45% / 6.71% / 18.52% of positions; every observed
integer difference is one block. This candidate therefore does not preserve
existing terrain exactly. A second fresh pipeline run reproduced all three FP16
elevation crops byte for byte. Production model assets/defaults were not changed.

Local raw evidence is under `build/inference-bench/decoder-precision/`:
`settings-abba/receipt.json`, `fp16-abba/receipt.json`,
`fp16-optimized-abba/receipt.json`, `fp16-optimized-source/receipt.json`, and
`fp16-optimized-elevation-parity.json`, and
`fp16-optimized-repeat-parity.json`. The optimized candidate SHA-256 is
`af32fdd6955f15611c5fe4696f5a6c96dd008657b361f391db1c948739d125ee`.
It is 56,077,790 bytes. Those standalone experiments did not establish full
modpack throughput or visual/biome/structure effects; integration evidence follows.

## Selecting the verified decoder in the mod

The CUDA build now supports `inference.decoder_precision=fp16` with
`inference.device=gpu`. Install the verified candidate as
`terrain-diffusion-models/decoder_model_fp16.onnx` and restart. Default precision
remains `fp32` for existing installations until explicitly selected. Both shared
`PipelineModels` and standalone `WorldPipeline` use the same asset selector.

The original downloaded `decoder_model.onnx` remains separate. FP16 selection
always checks the exact candidate SHA and size, including when
`validate_model=false`; a missing or mismatched candidate stops loading instead
of silently changing precision. Optimized runtime cache names already include the
source hash, so changing precision cannot reuse a different decoder's cache.
The precision changes newly generated terrain; keep the chosen setting stable.

The selected FP16 graph **bypasses CPU runtime optimization** and is passed
directly to GPU `ALL_OPT`. Do not send this already-converted graph back through
`OnnxModel`'s FP32 CPU cache preparation: ORT 1.20.0 inserted 503 casts (including
352 casts to FLOAT), promoted the 93 Conv inputs to FP32 and expanded QuickGelu.
A matched A/B/B/A test measured direct FP16 at 33.35 / 33.19 ms versus the
CPU-rewritten graph at 55.44 / 55.42 ms. This regression was caught in the first
fullpack integration and the slow artifact was not installed. FP32/base/coarse
retain their existing CPU optimization/cache path. Offloaded FP16 sessions keep
the validated original GPU graph bytes for session recreation.

After fixing that production path, fresh pipeline captures were compared again
against FP32 (196,608 positions, identical decoder inputs). Region mean absolute
elevation differences were 0.090 / 0.064 / 0.258, maximum 1.129. Floor/clamp
heights differed at 9.10% / 6.39% / 25.96%; all changed positions differed by one
block except one position differing by two. These supersede the earlier
CPU-reoptimized candidate's elevation figures when assessing the final fast path.
The repeated direct-path captures were byte-identical in all three regions.
See `build/inference-bench/fp16-application/direct-elevation-parity.json` and
`direct-repeat-parity.json`.

The local Prism modpack uses an **external ONNX library companion**. Build its
replacement with `:neoforge:build -PuseCuda=true -PneoforgeDiskLibraries=true`
and the populated workspace `.gradle` user home (also set `GRADLE_USER_HOME` for
wrapper bootstrap). A normal embedded-runtime JAR conflicts with the installed
companion at module resolution before world loading. Preserve the matching
companion and update the TD JAR's `.onnx-receipt.properties` sidecar together with
the mod. Inspect the release JAR for stale diagnostic classes/resources after
probe builds.

`test_decoder_selection.py` compiles the real config/asset manager and checks
FP32 default selection, FP16 selection, missing/incorrect model rejection and
invalid precision/device settings in separate JVMs. Full modpack fixtures can
use `tools/chunk_profile/run.py prepare ... --decoder-fp16 PATH`; the manifest
records the separate model and clone-only config override.

## Applied local integration

The final `td-fp16-apply-fp16-c` fullpack fixture completed successfully with 288
mods, scale 1, shaders disabled and 128 requested FULL chunks. Logs confirm the
pinned FP16 SHA, CUDA, and bypass of CPU graph optimization. Decoder means were
52.88 / 53.76 ms for the two regions, versus 83.34 / 82.75 ms in the FP32
integration baseline. Sample biome/start counts matched. Aggregate chunk-time
differences are **not a controlled throughput improvement claim**: decoder calls
and warmed terrain coverage differed (woodland 99 vs 181, ocean 531 vs 423).

The verified disk-library JAR, separate FP16 model, matching runtime receipt and
FP16 config were installed into the local Prism instance after successful
preflight. Original files were backed up; post-install hashes were rechecked.
The original FP32 model and source-world metadata remained unchanged.

The final JAR SHA is
`7225eca7f7fe09c6a6d3977177ced96ca0927ac802cb413e823310752de305eb`.
Evidence: `build/inference-bench/fp16-application/application-summary.json` and
`installed-backup/install-receipt.json`. `install_decoder_fp16.py` accepts only a
successful, fresh FP16 fixture, verifies the external runtime companion and
existing target hashes, backs up the four targets, then publishes configuration
last. Its default invocation is read-only; `--apply` requires prior user
authorization for the selected instance. Keep backups outside `mods/`.
