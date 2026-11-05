# Controlled client FPS measurements

This probe opens a **copy of an existing save**. It preserves the installed
Photon shader, resource packs and JVM heap/collector, and does not write to the
Prism instance. Snapshot and run directories are immutable inputs or fresh
outputs; failed runs are retained for inspection. Do not launch concurrent Java
or GPU workloads during a comparison.

```powershell
python tools/fps_probe/run.py snapshot --world 'SAVE NAME'
python tools/fps_probe/run.py run baseline
python tools/fps_probe/run.py run spark-off --no-spark-background
python tools/fps_probe/analyze.py build/fps-probe/runs/baseline build/fps-probe/runs/spark-off --output build/fps-probe/comparison.json
```

The snapshot includes the saved chunks/entities and existing terrain caches on
purpose: this is an existing-scene rendering comparison, not a fresh worldgen
test. Every trial starts from the same snapshot. The installed terrain mod hash
must match the accepted deployment receipt; do not replace it with a new build
just to run the FPS probe. A selected pack missing from the source is recorded as
already missing, not silently replaced with a different asset.

The fixed coastal view is `(39.587, 99.88247, -572.153)`, yaw `-129.3`, pitch
`36.6`, day time `6000`, clear weather. The integrated server is frozen after
setting the view and world conditions. Warmup is 45 seconds and sampling is 90
seconds. This isolates stationary rendering; it does **not** validate travelling,
live entity animation or server tick performance. Entity hiding cancels living
entity rendering through `RenderLivingEvent.Pre` and is a diagnostic condition,
not a recommended gameplay setting or an animation-only measurement. Custom
Gecko renderers may not emit that event; it does not hide every entity renderer.

Separate trial controls are `--no-vsync`, `--cap 70|260`,
`--gecko ENABLED|DISABLED`, `--no-spark-background`, `--no-pom`, and
`--hide-entities`. Vary one condition at a time and repeat promising results.
All changed settings are backed up inside each trial before editing. Do not
interpret the full set of alternatives as a recommendation to enable them.

The probe records render-event wall time, render-thread CPU counter deltas,
OpenGL timestamp elapsed time, and direct `glfwSwapBuffers` call wall/CPU time.
GPU timestamp elapsed includes idle gaps and is **not GPU busy time**. On the
observed Windows JVM, thread CPU counters advance in 15.625ms steps. Their
aggregate average is useful, but per-frame percentiles and per-call wall minus
CPU are not precise wait-time estimates. Swap wall time is measured directly;
remaining time must not automatically be attributed to VSync or a GPU stall.

A successful Java exit is insufficient. Require a fresh successful
`receipt.json`, zero inactive/invalid frames, matched camera/time/weather/entity
count/framebuffer, matching input hashes except declared controls, and complete
GPU timestamps (allow only bounded final pending queries, no errors/drops).
Inspect the screenshot in the trial's `screenshots` directory. Screenshot writing
happens after timing. Report mean FPS, frame-time p99, slowest-1%-mean FPS and
long-frame counts; a 60Hz VSync trace can naturally straddle 16.667ms.

Optional `--jfr` is a separate diagnostic run. Both repository and output use
`--jfr-root C:/Users/Public/td-fps-profile` (ASCII only). JFR attaches after the
sample-start marker and records approximately 85 of the 90 seconds. Use its own
event interval, not the nominal frame window, for allocation rates. Existing
`tools/memory_probe/summarize_jfr.py` computes `ThreadAllocationStatistics`
thread-ID counter differences; never substitute sampled-allocation weights.
Do not compare an instrumented JFR run directly with an uninstrumented FPS run.
Late JFR attach can itself introduce class redefinition/safepoint stalls. Treat
the attach interval separately; those stalls do not establish normal gameplay
stutter. Root Xaero map/replay caches are not copied into the template, so a
clone's cold client caches are not equivalent to a warmed live instance.

The earlier `render-mods-check` JFR has 9,003 `ThreadDump` VM operations over
roughly 90 seconds, while its JFR ThreadDump setting is 60 seconds. Spark's Java
sampler appears in `ThreadImpl.getThreadInfo` native samples and the installed
Spark configuration enables background profiling. This is evidence for a
controlled Spark-background on/off experiment, not proof of an FPS improvement.

The local NVIDIA global profile was explicitly capped at 60 FPS. A zero WGL
swap interval and high game cap did not bypass that driver limiter. Record
driver settings before/after every new trial with `nvapi_readonly.py`; do not
infer GPU saturation from the resulting 60 FPS plateau. The separate
`nvapi_trial_override.py` supports a temporary exact-Java-path FRL exception,
backs up its identity, and restores only its own validated one-setting profile.
Keep its state receipt and restore it after experiments; do not change the
global profile. NVIDIA normalizes stored application paths to lowercase and
forward slashes, so compare normalized Windows paths when validating identity.

For loaded-jungle work, locate saved candidates with `find_saved_scene.py`, then
set `--x --y --z --yaw --pitch --warmup` on the launcher. Coordinates specify
player feet, not eye position. Confirm the screenshot and loading has settled
before accepting a scene; a biome/leaf-count scan alone cannot prove visibility.

### Current installed pack and live-world measurements

`current_run.py snapshot --world '<save folder>' --work <fresh directory>`
discovers the selected Iris ZIP instead of using the historical shader filename.
It copies active mods, PureScale natives, selected resources, model files and
bounded client caches, records hashes, and leaves the source instance untouched.
`run <name> --work <directory>` preserves the source FPS limit and VSync by
default. Explicit controls include `--cap`, `--spark-off`, and `--collector`.

`--live-world --yaw-sweep 360` keeps entity/random ticks running and rotates the
camera during the timed sample. It still fixes position, noon and clear weather;
it does not test travel or new chunk generation. Review the actual final yaw
alongside the requested sweep. Entity hashes are expected to change in live
mode, so those runs are not deterministic entity-matched A/B comparisons.

Old saves can contain Voxy storage incompatible with the currently installed
Voxy. `Unknown type: block_state` in `Mapper.StateEntry.deserialize` can cause
configuration-payload errors and a later Sable null-camera crash. Inspect the
earliest join exception. `--rebuild-voxy-cache` quarantines only the new trial's
Voxy data under `settings-before/voxy-cache`, retains its `config.json`, and
rebuilds the cache without changing rendering distances. Record the cold-cache
condition; never reset the player's original cache to make a benchmark run.

Reject samples overlapped by another Java/CUDA workload, including independent
structure-density tests. A successful probe receipt and a plausible FPS value
are insufficient when `launch-result.json` reports competing Java processes.

`--render-stages` enables diagnostic CPU wall/OpenGL timestamp spans for Iris
passes and PureScale SR/FG calls, plus once-per-second read-only pipeline state.
Read `render-stages-summary.json`, `render-stages.csv` and
`render-pipeline-state.json`. Spans are inclusive and nested: do not sum parents
and children as independent GPU costs. OpenGL timeline intervals include waits
and are not a cross-API GPU-busy measurement. Check invalid/dropped/pending counts.
Instrumented runs are not eligible to establish target FPS; validate a candidate
again without this flag. Mixin helper classes must be outside the configured
`.mixin` package or runtime class loading fails despite successful compilation.

`--pipeline-state` records only the once-per-second SR/FG state, without render
stage timestamp queries. Use the same flag for both comparison runs. Check
effective `native.fgOn`, Photon success/failure counts and input/output sizes;
the legacy `frameGen` text alone does not describe the installed runtime's
frame-generation behavior. Keep rendered FPS separate from generated presents.

The DLSSG state counter reports presents since the previous native state query.
Capture the existing `PurescaleDLSSG.getState()` return value; an additional
diagnostic query consumes that counter and can disturb the HUD estimate. In
matched-settings launches, native state `[0,1,5,100,1]` and `[0,2,5,100,1]` have
both occurred. A true `fgOn` flag alone does not establish identical generated
presentation behavior. Retain the raw state with timing evidence.

`--shader-override candidate.zip` replaces only the selected shader ZIP inside a
new disposable trial, after verifying the template. It keeps the selected
filename and its option sidecar, backs up the original ZIP and records both
hashes. Shader settings, resource packs, PureScale config and the source instance
remain unchanged. Inspect actual changed ZIP entries and runtime compilation.

Iris 1.8.14's `ProgramBuilder` directly constructs its uniform builder; hooking
only `ProgramUniforms.builder()` misses shader-pack program names. Record the
`ProgramBuilder(String,int,ImmutableSet)` constructor's actual GLuint. The
installed Photon composite3 pass measured only about 0.09 ms/frame in the TD
forest scene; its redundant direct-DLSS work does not justify a complicated
deferred-pass replay mod on its own.
