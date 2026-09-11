# Finite river preview

The active integration direction is [native Streams with TD terrain](../streams_probe/README.md).
This standalone experiment remains available to preserve existing preview worlds.

Opt-in experimental river generation for a **new Terrain Diffusion Overworld**.
This is a bounded basin preview, not yet a river system for the entire infinite
world. Normal worlds keep the existing terrain and Streams behavior.

## Generation contract

- Sample the original TD terrain in aligned tiles. Use natural biome climate for
  relative local runoff; carry upstream runoff through dry biomes.
- Priority-flood routes D8 drainage to explicit ocean-biome roots or EXTERNAL
  window boundaries. External basins and unsupported filled depressions do not
  become artificial rivers, mouths, or lakes.
- Accumulated supported catchment controls width. Water level, bed and bank are
  separate; beds have a bowl profile and banks blend with smoothstep.
- Reject paths exceeding the cut budget, missing a downstream connection, or
  lacking lateral water containment. Recompute supported width after rejection.
- Apply only the current chunk at `applyCarvers` TAIL, before vegetation.
  Restore a shallow solid bed/bank lining and update generation heightmaps.
- Persist enabled state, bounds, algorithm version, seed and scale. Existing
  worlds cannot enable preview through the launch flag. Later launch flags do
  not rewrite an existing preview world's settings.
- Before the first carved chunk, persist settings and atomically publish exact
  DEM, runoff, ocean mask and full-resolution ground in
  `td-river-preview-cache-v2.bin.gz` (V1 worlds retain their V1 cache). Rebuild geometry from that immutable input
  on restart. Missing/corrupt/mismatched caches in existing preview worlds fail
  closed; a cache without matching enabled settings is also rejected.
- Restore active banks and bed to cached heights and clear through the actual
  solid surface if it is higher. Preserve natural ocean water outside wet beds.
  This handles one-block raw CUDA height drift without a lid above the river.

## Isolated testing

Prepare with `tools/streams_probe/prepare.py --disable-waterways`; its server
properties already disable structures. The preview requires structures disabled
and, if Streams is loaded, both `generateStreams=false` and `generateLakes=false`.
It does not claim structure support or simultaneous independent river carving.

Use the probe init script with an **absolute** `-PtdProbeDir`, and these Gradle
properties:

```text
-PtdRiver.preview=true
-PtdProbe.riverPreview=true
-PtdProbe.riverTickAudit=true
-PtdProbe.maxChunks=12
```

JVM equivalents of `tdRiver.*` are `td.rivers.*`. Defaults: origin X/Z -2048,
size 4096, spacing 32, minCatchment 65536, maxFillDepth 1, maxCut 6. Bounds are
fixed per world. The sample grid is capped at 513 by 513.

The probe records the planned network and DEM as `td-river-preview-plan.csv`
and `td-river-preview-dem.csv`. Per-chunk audit CSVs compare expected wet cells
and solid beds with actual blocks. The tick audit forces selected chunks,
verifies they are ticking, schedules water updates, waits 100 server ticks,
checks water/beds/bank leaks again, and releases only its own force tickets.
It also checks for motion-blocking lids immediately above expected water;
flowing water is excluded. Vegetation in that short interval is reported too.
Read the `complete` and tick-audit receipts, not just Gradle's exit status.

`run-tests.ps1` runs the standalone JDK 21 planner and geometry tests.
`plot-preview.py WORLD --output PATH.png` plots the plan and measured water.

For geometry tuning, add `-PtdRiver.exportTerrain=true` to one diagnostic run.
It exports `td-river-preview-ground.bin.gz`: gzip, big-endian magic `0x54445231`,
origin X/Z and inclusive side as ints, followed by row-major signed-short ground
heights. Compile `ReplayPreview.java` with `DrainagePlanner.java` and
`RiverGeometry.java`, then run:

```text
java -cp BUILD_DIR ReplayPreview WORLD/td-river-preview-dem.csv WORLD/td-river-preview-ground.bin.gz 65536 replay-segments.csv
```

Append `natural` after the output path to replay V2 geometry. Replay uses sea
level 62, fill budget 1 and cut budget 6. It supports the default
4096-block snapshot, counts retained connections to actual ocean roots, and
separates inland lateral leaks from sea-level water contacts. This avoids another
GPU terrain run for every geometry change. It does not replace actual Minecraft
generation and fluid-tick validation.

## Final cache validation (2026-09-11)

- `RiverPreviewCacheTest` passes exact roundtrip, defensive-copy, key mismatch,
  truncated/corrupted/trailing input, invalid payload and existing-file
  preservation checks.
- Fresh cached preview: 158 reaches, 8 joins, 118 above-sea reaches, maximum full
  width 11.05. Twelve chunks and 1,303 wet columns pass the 100-tick water, bed,
  roof and checked bank audits.
- Restart that world **without the enable flag**, add C2ME, reverse the old
  requested chunks and add 12 far-away chunks: 24 ticking chunks and 1,580 wet
  columns pass all four checks. Plan CSV, DEM CSV and cache hashes are identical;
  all 12 old per-chunk audit CSVs are byte-identical too. This establishes
  same-world preview persistence, not identical raw CUDA terrain across newly
  created worlds.
- Final Terralith 2.6.2 + Lithostitched 1.8.0 beta6 + local surface compatibility
  pack: 154 reaches, 12 ticking chunks and 1,291 wet columns pass water, bed,
  roof and checked bank audits after 100 ticks. All selected chunks in this
  test were `terralith:gravel_beach`; other Terralith biomes are not covered.
- An existing enabled development world without the new cache stops with an
  error before sampling; no replacement cache is created. This intentionally
  rejects older uncached preview worlds.
- Normal `:neoforge:build -PuseCuda=true -Pneoforge_version=21.1.249 --offline`
  passes. The packaged preview contains all eight required river/state/mixin
  classes and excludes the diagnostic package. Artifact receipt:
  `build/river-shape-analysis/preview-runtime/artifact.json`.

Receipts: `persisted-first.json`, `persisted-restart-c2me.json` and
`persisted-network.json` and `persisted-terralith.json` under
`build/river-shape-analysis/preview-runtime/`.

## Why the world cache is required

Before caching, reversing fresh chunk generation with C2ME changed 33 of 16,641
raw DEM heights by one block and changed three river widths. A restart also
changed the retained network from 158 to 161 reaches. Seed and scale matched;
CUDA inference batching/order is a likely mechanism, not a proven cause. Never
re-sample raw terrain to reconstruct this preview world's rivers. The immutable
cache fixes the planning inputs while active-bank restoration handles small
live terrain height differences during later chunk creation.

Raw receipts are under `build/river-shape-analysis/preview-runtime/`, exact
replay under `build/river-shape-analysis/preview-replay/`, and server logs under
`build/river-preview-*.log`. Read the latest receipt per chunk: the probe emits
both before- and after-tick observations.

## Known limits and integration constraints

### Opening the prepared playtest

In Prism Launcher select **TD River Preview 1.21.1**, then open the singleplayer
world **TD Natural Rivers** for V2 geometry; **TD River Preview** retains V1.
The copied, cached Terralith test world starts in
creative mode near audited river water at `-540 64 -96`, with commands enabled.
Use `/tp @s -540 80 -96` to return to this area. The instance uses Java 21,
NeoForge 21.1.249, the CUDA build and a 12 GiB heap limit. Initial model loading
can take time. Streams waterways, vanilla-water current processing and structures
are disabled in this test.

If chunks remain blank after joining and the log shows
`streamsreflowing-prefetch` or `streamsreflowing-sampler` requesting TD terrain,
check `[water] flowVanillaRivers=false` and `vanillaWaterFlow=false` in
`config/streamsreflowing-common.toml`. Streams 2.13.1 still initializes its flow
engine for nearby players when only `generateStreams` and `generateLakes` are
disabled. The playtest preparation tool disables all four options. Restart the
game after changing them to discard already queued terrain work.

The headless probe needs `-PtdProbe.playerPrefetchAudit=true` to cover this
player-triggered path: it invokes Streams' actual tick handler with a temporary
player entry across 40 advancing server ticks and requires the engine to stay
uninitialized. Follow it with chunk and water-tick audits; a no-player probe
alone does not establish interactive loading performance.

The instance has no global preview-enable flag: open the supplied world to
inspect the new rivers. Creating an ordinary world does not enable the preview.
Keep the complete world, including its river cache and data packs, together.

`prepare-playtest.py --source-instance PRISM_INSTANCE --probe COMPLETED_PROBE
--jar PRODUCTION_JAR --output NEW_DIRECTORY` stages a separate instance without
editing its sources. It currently targets the audited Terralith seed
`6187795302574383407`; its spawn coordinates are specific to that fixture.
Copy the resulting directory into Prism's `instances` directory while Prism is
closed. Existing output directories are refused. Generated instances, models
and saves belong under `build/` and are not committed.

### V2 geometry and comparison

New enabled preview worlds use shared junction tangents, cubic curves and
continuous width variation. A bounded one-block world-space edge field breaks
parallel banks; spatial indexing and containment checks include that margin.
Terrain and cardinal-bank checks constrain displacement and
fall back to straight reaches when necessary. Wet beds use spatially coherent
sand/gravel/clay patches; shallow banks retain soil rather than exposed stone.
Existing V1 worlds retain their original geometry and materials, including
new chunks, so installing the updated JAR does not change their river seams.

`prepare-comparison-world.py --source PROBE_WORLD --output NEW_WORLD --mode natural`
creates a separate chunk-empty V2 comparison world from the cached V1 fixture.
It preserves terrain input bytes and refuses existing output or player worlds.
Use `--mode streams` with a separate Streams-enabled instance for the reference.
The fixed spawn is specific to seed `6187795302574383407`.

V2 runtime validation on this fixture covered 12 chunks before and after 100
ticks: no missing water/bed, blocked channels or lateral bank leaks. The player
prefetch check passed across 40 ticks. These checks establish containment and
loading behavior, not aesthetic quality. Compare actual F2 screenshots in game;
desktop screen capture may show a stale OpenGL frame on this Windows setup.
V2 exports `td-river-preview-curves.csv`; the plot tool draws these actual paths.
The final fixture replay retained 154 reaches and 6 joins, with zero inland
bank leaks and maximum cut 6. The client restart reproduced the cache, plan and
curve CSV bytes exactly. Actual before/after and close-range F2 captures plus
build/test/runtime receipts are under `build/visual-review/`.

### Generation constraints

- Curves still follow a D8 drainage network; large-scale meanders and broad
  floodplains need further refinement. Relative runoff is not a physical model
  of discharge, erosion, sediment, or seasonal water balance.
- Rejection can reduce network coverage. It is preferable to terminating rivers
  in dry land or creating deep cuts to force a connection.
- Ocean roots are identified by biome and sea-level terrain within this finite
  window; this does not establish global ocean connectivity outside the window.
- CARVERS-stage `WorldGenRegion.getBiome` may access unavailable neighbours.
  Sample the biome source directly for placement materials instead.
- Water and bank checks do not establish compatibility with all downstream mod
  features or every possible cave contact. Keep model assets and terrain
  settings unchanged when comparing generation order or restarting a preview.
- Atomic rename is not a guarantee against power-loss disk damage. An enabled
  preview world interrupted before first cache publication is rejected on
  restart; use a fresh preview world or restore its complete backup.
- Do not replace an existing player's world or enable this in a full modpack
  on the strength of pure tests alone. Use completed runtime audit evidence.
