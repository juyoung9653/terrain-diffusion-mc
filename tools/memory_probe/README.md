# Client memory probe

Use this only after starting the Prism Minecraft client. Find its Java PID in
Task Manager, then run PowerShell from the repository root:

```powershell
& .\tools\memory_probe\collect_client_memory.ps1 -TargetPid 12345 -Samples 6 -IntervalSeconds 60
```

The tool writes a timestamped directory under `tools/memory_probe/results/`.
It records the selected Java process's working set, private bytes, virtual
bytes, handle/thread counts, CPU time, `jcmd GC.heap_info`, and `jcmd VM.flags`.
It does not enumerate JVMs, collect the game command line, change game settings,
force a garbage collection, create a heap dump, or stop the process. It records
the selected process's start time and rejects a reused PID between samples.

Each `jcmd` call has a 15-second timeout (`-JcmdTimeoutSeconds` changes it). On
timeout, the collector terminates only its own `jcmd` child and retains a result
file with exit code 124; it never terminates or changes the selected JVM.

`GC.heap_info` is a low-impact JVM diagnostic command, but attaches to the JVM
briefly. Do not collect during a timing benchmark. The optional histogram uses
`GC.class_histogram -all`, which includes unreachable objects and therefore does
not request a collection for a live-only view. It is still more intrusive because
it walks the heap and may pause the game briefly; request it only after a
reproducible high-memory state:

```powershell
& .\tools\memory_probe\collect_client_memory.ps1 -TargetPid 12345 -Samples 1 -ClassHistogram
```

Compare `measurements.json` with the corresponding `sample-*-heap-info.txt`.
The process metrics are taken immediately before the `jcmd` request and the
heap-info file has its own UTC command-start time, so a sample is not an atomic
snapshot.
Rising Java heap after the same gameplay has settled, together with rising
private bytes, warrants a histogram comparison. A stable heap with high private
bytes points to native, GPU-driver, or other non-heap memory; this tool cannot
assign those bytes to a mod.

Use `-JavaHome` when JDK 21 is installed elsewhere. A JRE is insufficient
because it does not provide `jcmd`.

## Memory implementation and validation

Persistent ONNX sessions release their Java model bytes after successful session
construction; offloaded sessions retain bytes for recreation. The inspected YUNG
API 5.1.9 constructor allocates its empty aquifer BitSet lazily. Loaded template
coordinates share bounded immutable BlockPos instances. Large river planning
caches retain losslessly packed ecology planes with 16 decoded tiles by default;
`-Dtd.inference.coldEcologyCache=false` is the comparison switch and
`-Dtd.inference.coldEcologyHotTiles=N` controls decoded retention. Public height
arrays and generated terrain values are unchanged.

Voxy 0.2.12-alpha and 1.1.0 are supported by the optional cache mixins. Both inspected
builds use the same `WorldEngine(SectionStorage, VoxyInstance)` constructor constants
(1024 below the heap threshold, otherwise 2048) and `_releaseArray()` pool limit (400).
Unknown versions are skipped. Default settings preserve these upstream limits.
The optional `config/terrain-diffusion-mc-memory.properties` budgets are experimental:
`voxy.secondary_cache_sections` and `voxy.array_reuse_sections`. Lower limits can
increase disk reads or allocations; a static scene does not validate travel and
revisit performance. Default settings do not apply Voxy cache mixins.

To audit a new installed JAR explicitly, run
`python tools/memory_probe/test_compatibility_budgets.py --voxy-jar <path-to-voxy-1.1.0.jar>`.
The version gate retains 0.2.12-alpha support; this does not rewrite either Voxy JAR.

For NeoForge 1.21.1, build the separate CUDA FML LIBRARY distribution with:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle'
./gradlew.bat :neoforge:build --offline '-PuseCuda=true' '-PneoforgeDiskLibraries=true' '-Pneoforge_version=21.1.249'
```

The slim mod requires its companion in `neoforge/build/disk-libraries`; verify
both against `onnx-runtime-receipt.properties`. Normal builds still use portable
Jar-in-Jar. `externalize_nested.py` stages the inspected Voxy RocksDB/SQLite and
Burnt Basic dependencies without altering source files. The installer performs
hash/metadata preflight, backs up replaced files outside `mods`, and has rollback
on failure. Never install both a nested ONNX mod and the external companion.
The externalizer defaults to Voxy 0.2.12-alpha; pass `--voxy-version 1.1.0` for the
inspected 1.1.0 build. Use a source directory containing original Voxy and Burnt JARs,
without previously externalized companions. The selected Voxy filename/version is
validated and retained in the plan; old plans remain supported. Only RocksDB/SQLite
are split from Voxy: 1.1.0's other four nested libraries remain byte-identical.
When upgrading an already externalized instance, pair the stripped Voxy JAR with its
two companions; do not mix the original bundled JAR with those same companions.

Focused checks compile production Java where possible:

```text
python tools/memory_probe/model_retention_java_test.py
python tools/memory_probe/template_position_interner_test.py
python tools/memory_probe/test_compatibility_budgets.py
python tools/test_terrain_sampling.py
python tools/memory_probe/test_disk_libraries.py
python tools/memory_probe/test_externalize_nested.py
python tools/memory_probe/test_install_optimized_instance.py
python -m unittest tools.optimization_bench.test_staging
```

The isolated client harness records staged input hashes before launch and checks
optional mixin targets before generating a world. It measures a fixed fresh TD
chunk workload followed by a static rendering scene; post-GC heap is measured
outside the frame timing window. It does not establish long-distance Voxy
performance or every model/resource-pack combination. Use the native marine and
Lush audits in one prepared fixture with `run_compat_probe.py` for worldgen
compatibility. Exclude diagnostic classes/resources from release JARs after that
probe build, and preserve a separately hashed release for client comparisons.

### Verified compatibility limits

The installed-pack comparison found 18 additional missing inventory/item models
with ModernFix `mixin.perf.dynamic_resources=true`, plus its model-registry
`replaceAll` compatibility warnings for Burnt Additions and Display Delight.
The Create/vanilla world-model smoke passed, but does not cover those inventory
models. Keep this option disabled for the production instance until those models
have a targeted compatibility fix. Its lower heap measurement is an experimental
result, not the installed configuration's memory claim.

The client launcher must apply replacements and config edits before adding
companion libraries; verify the pre-launch inventory. A companion alongside the
original nested ONNX mod creates a module collision. RSS analysis uses the logged
sample-start time and one-Hz process samples, excluding post-sample explicit GC
and optional model smoke. Compare post-GC Java heap separately from whole-process
RSS; changing collectors can change committed/native memory independently.

### Historical memory configuration comparison

The fixed-seed, 81-requested-chunk client comparison selected G1 with 4096 MiB
minimum / 8192 MiB maximum heap and dynamic resources disabled. The original
12000 MiB fixed ZGC baseline measured 8142 MiB post-explicit-GC heap and
16558.98 MiB median process RSS; the selected run measured 3897.88 MiB and
12097.46 MiB respectively. These are different metrics, not additive savings.
The code-only ZGC comparison measured 5258 MiB post-GC heap, establishing a
2884 MiB reduction without attributing collector differences to code changes.

This is a memory-first setting: baseline versus selected sample was 48.47 versus
46.07 FPS, with p99 frame time 35.11 versus 48.68 ms. It is not a demonstrated FPS
or stutter improvement and does not establish long-distance travel headroom.
The client receipts, hashes and timing windows are under
`build/memory-bench/final-comparison.json`; installation verification is in
`build/memory-bench/installed-result.json`. Native marine/Lush audits and model
smoke passed. Backups are outside the instance's `mods` directory. Re-measure
before tightening the heap further or enabling experimental resource/cache knobs.

### Repeated allocation diagnosis

The later live-instance investigation used the user's restored 12000 MiB ZGC
configuration; do not treat the historical G1 comparison as the current setting
or change heap/collector settings as part of allocation fixes.

Simple Block Physics `1.21.1-neoforge-1.2.0` reads each global player's position
in every ticking dimension before testing dimension equality. The optional
compatibility mixin filters the player list to the event dimension before that
read, preserving same-dimension physics and mixed-dimension multiplayer iteration.
Run `python tools/memory_probe/test_simple_block_physics.py` and validate the
installed client: repeated idle requests from `TickHandler.onLevelTick` in empty
dimensions must disappear. A C2ME `invokeAsyncLoad` hook counts load-stage attempts,
not necessarily physical disk reads.

The 90-second stationary coastal client validation kept F3, shader, heap and view
matched (world time/entities varied). C2ME workers fell from 624.38 MB/s to
0.0327 MB/s in allocation-counter deltas. Load-stage attempts stopped increasing
after initial loading; the shutdown counter showed 3.26 million Overworld
requests and no requests to the three formerly affected empty dimensions. The
temporary probe was removed after normal shutdown. Receipts are in
`build/runtime-profile/physics-fix-comparison.json` and
`build/runtime-profile/installed-physics-dimension.json`.

Use JFR `ThreadAllocationStatistics` counter deltas for per-thread allocation
rates. `ObjectAllocationSample.weight` can give implausibly large attribution for
rare samples in these ZGC recordings; inspect raw sample counts and stacks too,
and never report weighted stack sums as measured bytes/second. On this Windows
host JFR needs an ASCII repository path as well as an ASCII output path; setting
only the output path previously produced empty recordings.
