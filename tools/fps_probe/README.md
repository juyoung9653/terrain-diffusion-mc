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
