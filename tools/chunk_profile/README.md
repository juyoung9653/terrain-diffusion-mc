# Full-modpack chunk generation diagnosis

This runner copies the currently installed Prism client into a new workspace
fixture. It uses the installed TD JAR unchanged, all active mod JARs, the selected
resource/shader packs, and the source JVM heap/collector. It never launches the
source instance or writes to its saves. The direct launcher must match the
installed Minecraft and NeoForge versions; preparation checks that contract.

```powershell
python tools/chunk_profile/run.py prepare NAME --no-shaders
python tools/chunk_profile/run.py run NAME '--regions=-15,-64;1024,1024' --timeout 1800
python tools/chunk_profile/analyze.py build/chunk-profile/NAME
```

Add `--request-telemetry` only when investigating TD request shape. It passes
`-Dtd.inference.requestTelemetry=true` to the clone and records that opt-in in
`process.json`. The resulting phase analysis reports full/ground work totals,
origin totals, bounded examples and history completeness when the selected TD
JAR supports them. Older JARs remain compatible and report no telemetry section.

The diagnostic also snapshots outer TD cache key membership under the provider's
lock and traces actual Streams `NetworkTracer.build` calls (the audited 2.15.2
signature). Cache recency is advisory because concurrent hits update it outside
that lock; internal inference-window maps are deliberately not read unsafely.
Cache and trace snapshot overhead is recorded outside the phase timer. Trace
network summaries precede TD's return-time refinement; no native build in flight
does **not** prove that all Streams work is finished. The analyzer retains trace
availability, in-flight keys, and error/drop counters.

For the installed pack's Streams ablation, add `--without-streams` to `prepare`.
Preparation verifies the Streams JAR's declared mod ID and removes that single
artifact from the fresh clone. The manifest retains its source hash and verifies
the remaining mod count. The copied world's natural-river `enabled` byte becomes
zero while its version, seed and scale are preserved; the exact-Streams marker
is omitted. Both edits are required by TD's persisted-world adapter checks and
are recorded explicitly. The source instance and source world remain unchanged.
This control measures generation without Streams rivers; compare FP32/FP16
within the same ablation, with the same source inputs and region order.

For a controlled TD source comparison, prepare two different fixture names from
the same closed source instance. The baseline omits `--td-jar`; the candidate
copies only the supplied TD JAR over the cloned TD JAR, leaving the player
instance unchanged.

```powershell
python tools/chunk_profile/run.py prepare td-baseline --no-shaders
python tools/chunk_profile/run.py prepare td-candidate --no-shaders --td-jar 'C:\path\to\NeoForge-terrain-diffusion-mc-candidate.jar'
python tools/chunk_profile/run.py run td-baseline '--regions=-15,-64;1024,1024' --timeout 1800
python tools/chunk_profile/run.py run td-candidate '--regions=-15,-64;1024,1024' --timeout 1800
```

To compare the reviewed FP16 decoder against the installed FP32 default with the
same TD JAR, pass the candidate only while preparing the FP16 clone:

```powershell
python tools/chunk_profile/run.py prepare decoder-fp32 --no-shaders --decoder-precision fp32
python tools/chunk_profile/run.py prepare decoder-fp16 --no-shaders `
  --decoder-fp16 'C:\path\to\decoder_model_fp16.onnx'
```

`--decoder-fp16` accepts only the reviewed 56,077,790-byte model with SHA-256
`af32fdd6955f15611c5fe4696f5a6c96dd008657b361f391db1c948739d125ee`. It stages it
as `terrain-diffusion-models/decoder_model_fp16.onnx`, adds
`inference.decoder_precision=fp16` only to the clone configuration, and records
the source path, staged path, hash, size, and configuration selection in the
input manifest. `--decoder-precision fp32` forces the clone-only FP32 baseline.
With neither precision option, preparation preserves and records the installed
effective setting: an installed FP16 setting must have the same validated candidate
already present in the copied model directory. Before launch, the runner hashes every
staged input and again verifies the fixed FP16 model identity; a changed candidate or
configuration invalidates the fixture.

`--td-jar` accepts only a readable NeoForge JAR declaring
`terrain_diffusion_mc`. The fixture manifest retains both the installed source
JAR hash and selected replacement provenance/hash. A source JAR passed back as
an override, multiple active TD JARs, a changed fixture, or a reused fixture is
rejected. Compare manifests, diagnostic JAR hash, regions, shader setting,
seed, and terrain/structure fingerprints before comparing timing.

`prepare` selects the most recently saved world unless `--world NAME` is given.
It copies world generation metadata, world configuration and datapacks, removes
the copied player tag and clears `initialized` in the **copy**. It copies no
region, entity, POI, player, saved structure index or Streams graph data. TD
profile markers and structure placement settings are retained. Model/config
files are real, hash-verified copies. An existing destination is refused.
`--no-shaders` changes only the clone's Iris setting and records the override.
The example coordinates describe the October 27 seed, not universally suitable
land locations. Inspect the receipt's biomes and structures for each world.

The probe opens the copied world, waits for initial settling and then requires
the TD inference lane to be empty for three seconds. Each region requests 8x8
FULL chunks by default, with eight outstanding requests per batch. Tickets are
admitted/removed on the server thread; future requests originate off-thread.
Each later phase also waits for an idle inference boundary. The fixture must be
unrun, and the process holds the repository probe-suite lock. Keep other Java,
CUDA and game workloads out of the run.

Both process exit zero and a successful fresh receipt are required. Retain
`input-manifest.json`, `process.json`, `receipt.json`, `analysis.json`, logs,
GPU/process samples and the source-preservation result. A failure does not make
the generated fixture reusable: prepare a new destination. An interrupted parent
can leave its recorded Java PID running; inspect that task-owned process first.

## Interpret the measurements

- ONNX run counters measure host-observed calls, including synchronization, not
  GPU kernel time alone. Model counters separate base, coarse and decoder work.
- Executor and provider execution overlap ONNX calls. Queue waits overlap each
  other. Do not add these counters to elapsed time or call their sum a percentage.
- `ThreadMXBean` sampling attributes CPU deltas to sampled stacks, including
  native entry points. It is approximate and has overhead. Waiting-stack counts
  are separate thread samples, not elapsed seconds or CPU work. GPU telemetry
  includes rendering and any other applications using that GPU.
- The first FULL request can require a wide river/terrain region and generate
  neighboring chunks. Later requested chunks can then finish in a burst. Dividing
  requested chunks by elapsed time is not sustained world-generation throughput.
- Initial generation, earlier phases and background prefetch warm terrain and
  river caches even though every fixture begins chunk-empty. Inspect before/after
  active/queued counters and readiness evidence. The player stays near spawn;
  ordinary integrated-server and client rendering work continue during the probe.
- The TD-only three-second idle gate does not establish Streams quiescence.
  Instrumented FP32/FP16 woodland runs began with identical outer cache keys but
  an existing Streams native build still in progress. Both then started the same
  next plate during the phase, while FP16 recorded more completed tile work. Model-call
  increases alone therefore cannot establish extra required work or explain a
  latency regression. Inspect native build traces and the last required chunk
  dependency before making either claim.
- The October 27 shaders-on exploratory run had no idle gate. Its timings must
  not be treated as a matched comparison against the later shaders-off run.
- Each successful phase includes `terrainStructureFingerprint`, a SHA-256 digest
  of the requested chunks' center surface height, biome key, and valid structure
  start IDs. It detects a changed sampled terrain/structure outcome, but is not
  full block equivalence or a guarantee that adjacent generated chunks match.
- A `groundToFullUpgrade` or `bothCompletedOverlap` telemetry result does not by
  itself show duplicated neural inference: height-only work can validly precede
  climate/biome work. Likewise, `postEvictionRecompute` with zero newly computed
  windows is not evidence of neural recomputation. Treat history drops or an
  incomplete history as limits on example and origin attribution. The analyzer
  does not interpret post-eviction causal flags or examples, even when a receipt
  contains them.
- Telemetry overlap, same-kind-repeat and post-eviction categories are not
  disjoint. Do not sum them. The analyzer treats receipts without
  `schemaVersion` as v1 and suppresses v1 overlap, overlap examples and
  queued-ground-reuse values because those classifications were corrected in v2.
- `stage_window_telemetry` contains exact per-stage window-key counters when the
  TD artifact supplies them. `recomputedWindows` identifies successful exact-key
  diffusion-window recomputation. Do not sum stage counts or treat them as ONNX
  call counts: a model call can batch several windows, and some stages perform
  no ONNX call. Per-stage history drops make its retained history incomplete. A
  changed `historyGeneration` invalidates that stage's delta; the analyzer marks
  it incomplete rather than claiming repeated work.
- Surface heights include tree canopies and vegetation: independent unmodified
  full-pack runs can differ here while underlying ground elevation is identical.
  Investigate mismatches in saved block columns; do not silently call them terrain
  changes or dismiss them as harmless. Structure support validation separately
  compares unbound and sampling-bound native terrain plans exactly.

The diagnostic mod is compiled separately with the existing production-library
classpath; it is never added to the release TD JAR or the player's mods folder.

## Verified October 27 findings

### October 28 Streams-only ablation

`build/chunk-profile/no-streams-abba/summary.json` records fresh
FP32/FP16/FP16/FP32 runs with 287 mod JARs, shaders off, scale 1, and the same
installed TD and diagnostic JARs. Only Streams and its required copied-world
markers were excluded as described above. Source inputs, fresh world metadata,
initial outer cache key sets and region order matched. Both phase boundaries had
TD active/queued zero; Streams trace counts remained zero. All four processes and
receipts succeeded and preserved source-world metadata.

| 64 requested FULL chunks | FP32 seconds (two runs) | FP16 seconds (two runs) | Mean elapsed reduction |
| --- | --- | --- | --- |
| Woodland | 22.925, 18.241 | 17.843, 17.854 | 13.3% |
| Distant ocean | 22.402, 24.692 | 18.747, 17.988 | 22.0% |

For the combined 128 requests, mean elapsed fell 44.130 to 36.216 seconds
(17.9% shorter), or 2.901 to 3.534 requested chunks/second (21.9% more). These
are two trials per precision in these regions. Woodland FP32 variation is large;
do not generalize its mean uplift. Model-call counts matched (11 decoder calls
in woodland, 103 in ocean), while decoder means dropped roughly 86–89 to
49–52 ms. The ocean decoder-time saving closely tracks its elapsed reduction;
the larger woodland difference cannot be attributed entirely to eleven calls.

The earlier same-TD-artifact Streams-enabled FP16 ocean phase took 62.520 seconds
and executed 423 decoder calls, versus 17.988–18.747 seconds and 103 calls without
Streams. It is a separate integration run with different warmed river state,
not an interleaved Streams-on/off speedup guarantee. Its decoder-window telemetry
shows only 12 of 423 publications were exact-key recomputations: most extra work
was new coverage, so increasing cache capacity alone cannot remove that cost.
Native trace domains span about 4097 blocks per side. Reducing this wide-area
planning workload requires examining required versus speculative work and the
sampling design; preserve river correctness when changing either.

### Earlier full-pack profile

The shaders-off full-pack run, 288 active JARs and scale 1, completed both phases
with empty initial inference queues. See
[`analysis.json`](../../build/chunk-profile/fullpack-oct27-shaders-off/analysis.json)
and the adjacent raw receipt. The 64 woodland requests took 26.12 seconds; the
64 distant ocean requests took 81.56 seconds. Both include neighboring generation
dependencies and are not throughput benchmarks.

The distant phase spent 68.17 host-observed seconds in ONNX calls (decoder
42.07, base 26.10), while Streams sampler stacks repeatedly awaited TD ground
and complete biome tiles. The woodland phase also exposed substantial TD
structure-preflight CPU work: `StructureTerrain.plan` via village reservation
assembly repeatedly invokes native height/density sampling. C2ME thread names
identify the executor, not the responsible mod. Inspect the task stack before
attributing this cost to C2ME or Streams.

The distant phase had 411 tile evictions before shutdown. This is real planning
cache pressure, but neither that count nor the miss count proves pathological
recomputation. Establish matched cache-policy evidence before changing capacity.

## Structure sampling scope comparison

After checkpoint `36bd42e`, `StructurePlacementRuntime.assemble` scopes only
`StructureTerrain.plan` with the existing provider sampling cursor. Native height,
water and material checks still run. The fast path applies to legacy sampling,
including the tested scale-1 fast-ground profile; nonlegacy sampling falls back.

Four fresh full-pack clones ran in baseline/changed/changed/baseline order with
shaders off, scale 1, the same seed, 288 mod JARs, region order, prefetch 8 and
diagnostic JAR. Input manifests differ only in the TD JAR; both phases began with
idle inference queues. The installed JAR matched the checkpoint build exactly.

| Requested region (64 FULL chunks) | Baseline seconds | Changed seconds | Mean elapsed change |
| --- | --- | --- | --- |
| Woodland | 26.51, 25.76 | 23.92, 24.34 | 26.14 to 24.13 s; 7.7% shorter |
| Distant ocean | 78.49, 78.52 | 77.47, 81.32 | 78.51 to 79.39 s; 1.1% longer |

Woodland provider cache-hit lookups fell from a mean 355.62 million to 65.59
million (81.6% fewer), confirming the intended reduction in repeated lookups.
These are two trials per build, not a general throughput guarantee. Startup and
phase boundaries can warm caches differently even with empty queues; differing
ONNX call counts do not establish an inference optimization. The ocean phase
shows no consistent benefit.

See [`comparison.json`](../../build/chunk-profile/optimization/comparison.json)
and its four linked run names for raw receipts and counters. The saved woodland
center columns have identical natural-ground elevations in both pairs. Canopy,
vegetation and some material placements vary even between unmodified runs, so
these results do not establish block-for-block world equivalence; see
[`saved-column-parity.json`](../../build/chunk-profile/optimization/saved-column-parity.json).

The separate full native density/support run
[`structure-binding-verified`](../../build/structure-density/structure-binding-verified/world/density-audit.json)
exited successfully with a fresh successful receipt: four structure starts,
exact bound/unbound plan parity for an 82-piece adapted desert village, 3,343
CARVERS columns, 109 FEATURES support columns, 12 policy round trips and three
native locate checks. This server fixture uses 29 companion mods and is a
correctness check, not another full-pack timing comparison. Its historical
source needed missing mod files restored before it could load; use its effective
input manifest rather than assuming an old fixture directory is still complete.

## Shared structure heights and tensor-window evidence

The next implementation shares policy-independent native height pairs within one
village candidate-cell choice. The native `height-sharing-audit` support probe
preserved exact plans and rejection details across five policy cases. Its small
same-JVM comparison used 16,168 native height calls versus 49,704 without sharing,
with no concurrent inference; this synthetic reduction is not chunk throughput.
Actual preflight counters avoided 342,556 height calls across 39 choices.

The fresh full-pack `td-height-sharing-a` and `td-height-sharing-b` runs took
23.44/24.22 seconds in woodland and 80.43/79.69 seconds in the distant ocean.
Woodland mean 23.83 seconds is 8.8% below the original 26.14-second baseline but
only 1.3% below the sampling-binding version. That small incremental change is
within the observed run spread; ocean shows no improvement. See
[`comparison.json`](../../build/chunk-profile/optimization-next/comparison.json).
Both runs enable optional request telemetry; A uses v1, B uses corrected v2 and
adds internal tensor-window counters. These are additional measurement costs.

Only B can identify internal tensor-window repeats. Its decoder stage computed
111 windows in woodland, including 49 previously computed keys; in the ocean it
computed 470, including 63 repeats. All stage histories remained complete with
zero dropped keys. Ocean ONNX execution was 65.63 seconds of 79.69 seconds elapsed,
including 39.74 seconds in decoder calls. The remaining model calls were base
inference; coarse calls were zero in that phase. Outer tile `sameKindRepeat`
showing zero new windows does **not** exclude this internal tensor LRU churn.
Likewise, ground-to-full new windows cannot automatically be called climate work.
Use the stage counters and actual model-run deltas together.

Increasing only the decoder output cache from 100 to 256 MiB then reduced ocean
decoder repeats from 63 in the instrumented 100-MiB run to 12 in both fresh
`td-decoder-cache-256-a/b` runs. Decoder calls fell from 470 to 423/424. Ocean
elapsed times were 76.93/76.56 seconds (mean 76.75), versus the immediately prior
height-sharing mean 80.06 seconds; 4.1% shorter. Woodland was 23.73/23.85 seconds
with unchanged 49 repeats and no additional consistent benefit.

Across all changes, the two-run means versus the original baseline are woodland
26.14 to 23.79 seconds (9.0% shorter), ocean 78.51 to 76.75 seconds (2.2% shorter).
These are descriptive observations on the sampled regions, not a general
modpack throughput guarantee. The decoder cache's retained-output cap increases
by 156 MiB per pipeline; other stage caps stay at 100 MiB, model execution and
generated-value formulas are unchanged. The cap is not a bound on transient
tensor working memory. See
[`decoder-comparison.json`](../../build/chunk-profile/optimization-next/decoder-comparison.json)
and the linked per-run receipts. Saved center ground elevations and biomes match;
some grass/rooted-dirt and vegetation placements vary even between repeated runs.

Final native integration `decoder-cache-final` passed with process exit 0 and a
fresh successful receipt: the same four structure starts and native locate
results, exact planning parity for the 82-piece adapted village, 3,343 CARVERS
columns and 109 FEATURES support columns. Release JAR inspection excluded probe
classes/resources. Artifact hashes and validation links are retained in
[`validation.json`](../../build/chunk-profile/optimization-next/validation.json).

## Streams cache saturation regression

Full terrain tiles and elevation-only tiles share one completed-tile budget.
Eviction must choose the oldest entry across both maps. Previously, ground
publication counted both maps but evicted only ground entries: a full cache at
512 tiles immediately discarded each newly computed ground tile. Density-column
sampling then recomputed the same tile repeatedly, even with decoder windows
already cached. This is a TD cache defect present before the Streams batch/marine
changes, not a native Streams inference implementation issue.

The failed `streams-integration-audit` receipt demonstrates this condition:
900 seconds without finishing the first requested batch, roughly 997,000 new
misses/evictions, zero retained ground tiles, and only 63 decoder calls taking
3.27 seconds. Treat that run as failure evidence, never as a throughput sample.
`GroundCacheAdmissionTest` reproduces the original failure at saturated 64/512
targets and verifies admission, bounded combined residency, invalidation and full
upgrades after the fix. It runs with `python -B tools/test_terrain_sampling.py`.

The corrected `streams-cache-fix-audit` completed both regions: woodland 64 FULL
chunks in 24.60 s (2.60 requested chunks/s), ocean 64 in 58.22 s (1.10/s).
Woodland had 21 misses and eight evictions, retaining its new ground tile. The
native marine audit compared seven eligible samples with zero mismatches.
Fresh world metadata, all other input files and the diagnostic JAR matched the
failed reproduction; the only changed release entries were `LocalTerrainProvider`
and its new eviction-candidate record. This demonstrates recovery from the
saturation defect, not a general percentage throughput gain. See
[`validation-summary.json`](../../build/chunk-profile/streams-cache-fix-artifact/validation-summary.json).

For a stronger measurement boundary, use `--request-telemetry` together with
`--streams-settle-seconds 15`. This requires an empty TD lane and an unchanged,
error-free native build trace with no inflight builds for 15 seconds. Native
RETURN still precedes TD refinement: this is observed quiescence, not a guarantee
against subsequent work. `--phase-timeout 300` bounds a stalled measurement.
Creating or touching `dump-threads.request` inside an active fixture asks its
launcher to collect a thread dump; the request and any attach error are retained.
A dump perturbs the measurement, so exclude such runs from speed comparisons.

`--streams-ground-marine true|false` and `--streams-batch-sampling true|false`
select the two integration paths on the same candidate JAR. The marine shortcut
applies only to scale 1 legacy sampling; larger scales add height noise after
biome classification and must retain the original predicate. Use
`--streams-ground-marine-audit` separately from timing: it performs the original
full predicate too and requires positive sample count with zero mismatches.
Batch capability is restricted to the audited Streams 2.15.2 caching sampler;
native host height calculation remains in place. It is opt-in, disabled by
default: the matched `streams-batch-abba` comparison found only a 0.33% mean
elapsed-time difference (off 59.40 s, on 59.21 s for 64 fresh ocean FULL chunks),
inside the run spread. All four runs used identical prepared input bytes,
initial outer cache keys and fresh target graphs. Only the nine startup graphs
were copied identically to avoid repeatedly rebuilding them. Initial base-model
batch groupings varied, so their call counts are not claimed identical. See
[`summary.json`](../../build/chunk-profile/streams-batch-abba/summary.json).

Completed fixtures may have duplicate `mods`, `terrain-diffusion-models`,
`natives` and `resourcepacks` directories pruned. Their `cleanup-receipt.json`
records the removal and content-addressed TD/Streams/probe JAR archives under
`retained-artifacts`. Receipts, manifests, logs and generated worlds remain.
Prepare a new fixture for another run; a cleaned historical fixture is evidence,
not a reusable launch directory. Keep only the current experiment's runtime
copies and archive its results before pruning them.
