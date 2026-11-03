# Photon vegetation: nine chunks

`build.py` patches the hash-pinned local `photon-burnt-compat.zip` into a new
ZIP. Only `shaders/include/vertex/displacement.glsl` changes; all other members
are byte-checked. Keep the original ZIP and copy its `.zip.txt` settings to the
new pack's `.zip.txt` when installing.

The player eye position selects a 3x3 square of chunk columns. The owning block
centre (`world_pos + at_midBlock / 64`) selects each plant/leaf's chunk, including
negative coordinates and vertices extending beyond a chunk edge. Terrain and
shadow passes share this rule. All vegetation animation outside the square is
skipped; water displacement and static 3D crop yaw are preserved. Crossing a
chunk boundary changes the square immediately, with no fade.

```powershell
python tools/photon_waving/build.py --source <original.zip> --output <new.zip>
python tools/photon_waving/validate.py <new.zip> --settings <original.zip.txt> --output <validation-directory>
```

Validation uses the existing hidden OpenGL compiler with installed settings,
Iris/Voxy macros, three dimensions, terrain/shadow/water/entity/hand/glint
programs, and plants/leaves toggle combinations. It also checks the nine-chunk
boundary contract on the CPU. This is not an Iris runtime or FPS test. The
include resolver handles relative includes (e.g. `fast_math.glsl`), and the
standalone compiler needs the Iris-supplied `MC_SHADOW_QUALITY` macro for water.

In-game acceptance: reload the new pack, inspect plants, leaves and 3D crops
inside/outside the 3x3 area and their shadows, then cross positive and negative
chunk boundaries. Use third-person to check the player remains the centre.

## Measure GPU motion

```powershell
python tools/photon_waving/probe_gpu.py <nine-chunk.zip> --baseline <original.zip> --settings <nine-chunk.zip.txt> --output <gpu-report-directory>
```

This runs the actual Overworld terrain vertex program and three shadow vertex
programs with transform feedback. The only shader instrumentation exports the
position immediately after animation; animation code is unchanged. It compares
three animation times, nine vegetation material IDs, boundary-crossing vertices,
and player locations spanning positive and negative chunk boundaries. The
original pack is a positive control: distant plants must move there, while the
limited pack must preserve nearby motion and have zero distant motion. A separate
light-matrix rotation control demonstrates that a stationary outside leaf still
changes shadow projection as the light moves.

The test uses controlled uniforms/attributes/textures in a standalone GL context.
It validates GPU execution of pack code, not the live Iris attribute bindings,
framebuffers, filtering, or interactions with other client mods. A passing report
cannot establish the cause of a particular in-game shimmer.

## Capture an isolated game scene

```powershell
python tools/photon_waving/client_probe.py prepare <fresh-name>
python tools/photon_waving/client_probe.py run <fresh-name> --timeout 480
python tools/photon_waving/client_probe.py prepare <fresh-control-name> --no-wind
python tools/photon_waving/client_probe.py run <fresh-control-name> --timeout 480
```

The launcher copies the current Prism mods/config and selected resource/shader
packs, plus the existing FPS fixture save, into a fresh workspace directory.
It builds a synthetic platform above the saved terrain. The lime cluster is
inside the player's 3x3 area and the orange cluster is outside. It fixes time,
weather, ticks and player pose, waits for rendering to settle, and captures three
images at least six seconds apart. Current captures require at least 45 seconds
and 256 consecutive frames with loaded render sections; receipts record elapsed
scene time and frame counts. The older five-second warmup included a large
startup drift: compare all pairs, not only later frames against the first.
Require process success, successful receipt,
measured camera/time agreement, three image files, and visual inspection.
`compare_captures.py` measures explicit screenshot regions; pixel changes alone
do not identify wind versus filtering/lighting.

The initial 2026-10-30 nine-chunk and all-waving-off runs both completed with three
captures at game time 6000 and the same measured camera. Their prepared inputs
differed only in the shader settings sidecar. Small outside-shadow pixel changes
persisted in the all-waving-off control, as did the pre-existing depth-format
errors. Together with zero outside vertex motion in the GPU probe, this separates
the residual image variation from vegetation wind, but does not identify a
specific filter, moving cloud-lighting effect, or framebuffer fault. Those initial
runs omitted `purescale_natives` and used XAIS fallback rather than the installed
DLSS bridge: their image findings are limited to that fallback environment.
Evidence is
in `build/photon-waving-9chunks-20261029/diagnostic-summary.json`.

Current staging copies and hashes `purescale_natives` too. For live-equivalent
upscaler tests, match the source resolution (`--width 2560 --height 1440` on the
tested setup) and require the `OpenGL D3D12 SR/FG bridge initialized` log rather
than trusting `mode=DLSS` in config. Missing bridge DLLs silently fall back to a
different upscaler. `--shader-option NAME=VALUE` changes one shader control;
`--purescale-off` disables only the installed Modern upscaler in the clone.

`--freeze-jitter` is diagnostic instrumentation, not a shipping quality preset.
It changes only two expressions in the cloned ZIP's `shaders/shaders.properties`:
the `jitter_x`/`jitter_y` frame counters become zero. All ZIP members are preserved,
and the receipt distinguishes source and instrumented shader hashes. TAA/TAAU,
PCF, DOF and AO settings remain intact. Fixing jitter removes the sampling
diversity temporal reconstruction needs; do not install this as a general fix.

Use `analyze_runs.py <run-directory> ... --output <report.json>` for the
2560x1440 fixed scene. It validates successful launches, measured player pose,
time, capture gaps, warmup/frame counts and required DLSS initialization, then
reports all three frame pairs, signed drift and absolute pixel differences.
Prepared-file differences identify which inputs changed between controls.
Pixel deltas do not measure physical motion or establish the cause of an
unrecorded scene in the user's world.

In the fixed-time probe, cloud advection is frozen too: Photon defines
`world_age` from `worldDay` and `worldTime` in `shaders.properties`. Do not infer
cloud movement from the advancing `frameTimeCounter` declaration. PCF and AO
sample rotation, however, explicitly use `frameCounter` even when TAA is off.

The settled jitter controls reproduced the effect with the installed native
DLSS bridge: fixing just the two projection-jitter expressions reduced mean
outside-shadow RGB pixel delta from 0.691 to 0.177 (74%). With PureScale disabled,
the matched reduction was 1.294 to 0.303 (77%). Both prepared manifests differed
only in the shader ZIP; archive comparison found only those two expression edits.
This establishes a substantial projection-jitter contribution in the probe,
not a sole cause or a universal percentage. Each condition has one run and three
screenshots; the three pair comparisons are not independent repetitions.
PCF off under disabled TAA/TAAU reduced the same metric by only 21%, and changed
the filter itself. Depth-format errors vanished with PureScale off while shimmer
remained; with DLSS active those errors persisted despite the jitter improvement.
Detailed receipts and limitations are linked by
`build/photon-waving-9chunks-20261029/cause-summary.json`. The installed nine-chunk
pack was not replaced by any diagnostic ZIP.

The old FPS fixture's derived `voxy` cache must be excluded when used with the
current mod registry. Copying it produced Voxy's `Mapper.StateEntry.deserialize`
`Unknown type: block_state` failure for removed Excavated Variants IDs during
login, followed by misleading null-camera/null-player crashes. Keep saved
Minecraft chunks, rebuild the renderer cache only in the clone, and inspect the
first login exception rather than only the final recoverable-crash screen.
The runner terminates only its own child on a crash report or timeout. It does
not alter the installed instance or player saves.

## Residual temporal variation and reproducible viewport

Do not resize or maximize the diagnostic window. Compare only runs whose measured
viewport equals the requested size and each other. The untouched reruns used
2556x1440. Window-decoration/size manipulation at runtime caused a native D3D11
present crash with PureScale; use the normal launch size instead. The client mixin
package must be a dedicated `.mixin` subpackage: placing ordinary probe classes in
the declared mixin package caused `IllegalClassLoadError` during mod loading.

After projection jitter was fixed, fixing stochastic sampling seeds reduced the
unshadowed-ground RGB delta from 0.1951 to 0.0115, while the outside-shadow delta
did not improve (0.1506 versus 0.1943). Freezing `frameTimeCounter` too did not
eliminate the shadow variation. Disabling PureScale alone did not eliminate it
either. In the matched no-PureScale, all-seeds/time-fixed comparison, disabling
Photon TAA/TAAU reduced mean outside-shadow delta from 0.4006 to 0.0169 (95.8%).
This identifies a substantial temporal-history contribution in the static probe;
it is not evidence of outside vegetation displacement, nor proof every remaining
pixel change has one cause. Each condition is one run with three screenshots;
pairwise comparisons are not independent repetitions. These diagnostic controls
must not be installed as an image-quality fix. Receipts and raw image metrics are
in `residual-untouched-metrics.json`, `residual-dlss-control.json`, and
`residual-taa-control.json` under the existing Photon build directory.

`--probe-from <prepared-run>` reuses the exact hash-verified diagnostic JAR for
matched visual controls. `refresh-probe` is only for unrun fixtures. Completed
fixtures must never be rerun; archival deduplication may hardlink their immutable
mod/model copies after hash verification. Active/fresh fixtures retain independent
copies, and writable settings/saves are never deduplicated.

## Shadow-caster comparison scope

The initial dense synthetic caster comparison used render distance 8 chunks and
shader `shadowDistance=64.0`; it is not a 12-chunk real-world benchmark. Three
successful, focused-window 60-second runs are recorded in
`caster-focused-first-comparison.json`. Each mode has one independent run only.
The standalone checks passed 39 compile/link variants and 48 GPU clip cases per
mode. The live mapping verifier checked 619,749 block states with zero mismatches.
Full-cube-only and cube-plus-stairs/slabs are shader vertex rejection prototypes;
they retain mesh submission/vertex fetch. Entity shadows retain their source
behavior; `shadow_block` shares the filtered non-entity path. The synthetic scene
does not validate block-entity rendering.

Existing-world runs use `--scene-config` to pin the saved pose and source-world
fingerprint and skip both synthetic placement paths. Set `--render-distance 12`
and `--shader-option shadowDistance=192.0` explicitly for the requested comparison.
Iris `maxShadowRenderDistance=32` is a chunk cap and already permits 192 blocks.
Never substitute the old synthetic scene's measurements for this profile.

Iris rewrites the settings sidecar's date comment on startup. The benchmark
analyzer's `--settings-reference` must hash-match the prepared original bytes;
only comment/blank-line changes are accepted, never changed option values.
All measurement frames require direct GLFW focus evidence, matching dimensions,
the same probe/world/configuration, successful registry verification for variants,
and balanced nonblocking GPU queries. GL timeline intervals are not GPU-busy
utilization and may omit PureScale's D3D12 work.

`cleanup_copies.py --apply` archives completed copies while retaining logs,
screenshots, timings, input/configuration evidence, shader ZIPs and the diagnostic
JAR. It refuses active probes and paths outside the task's fixed runs directory.
`--include-unrun` is only for abandoned prepared fixtures after planned runs end.
Archived fixtures cannot be launched again. Source Prism worlds are never cleanup
targets.

For further pack-only comparisons, the user prefers switching shaders in the
same loaded world instead of restarting the large modpack for every condition.
The current probe is single-shot and exits after its captures. Implement a
settled Iris-reload sequence with fresh per-condition timers before scheduling
additional repetitions; do not present the existing launcher as supporting that
sequence already.

The completed original-modpack World (8) comparison uses its saved overworld pose
(-748.6602, 87.6519, -406.5742), yaw/pitch (131.85065, 20.88002), render distance
12 and shadow distance 192, at 2556x1440 with the original native DLSS setup.
All 625 chunks in that radius already existed. `world8-final-comparison.json`
records one 60-second run per mode: baseline/cubes/cubes+stairs+slabs shadow
medians 0.620512/0.589312/0.580560 ms, whole-GL interval medians
9.012976/9.010176/8.953440 ms, and approximately 60 FPS throughout. This does not
demonstrate a substantial overall improvement, nor statistical significance or
total GPU-busy savings. It must not be generalized to all viewpoints. Source
world's 129 copied file hashes and the installed three-Photon-pack set were
verified unchanged; final summary and retained captures document the comparison.
