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
- The October 27 shaders-on exploratory run had no idle gate. Its timings must
  not be treated as a matched comparison against the later shaders-off run.

The diagnostic mod is compiled separately with the existing production-library
classpath; it is never added to the release TD JAR or the player's mods folder.

## Verified October 27 findings

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
