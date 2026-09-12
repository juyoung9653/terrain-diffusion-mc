# Streams Reflowing integration probe

Opt-in dedicated-server diagnostic for Minecraft 1.21.1 / NeoForge 21.1.249,
Streams Reflowing 2.13.1 and the current Terrain Diffusion source tree. Normal
builds do not include the diagnostic Java directory. Do not package a release
with `probe.init.gradle` enabled.

## Native Streams integration

The active river implementation is **Streams Reflowing 2.13.1**, with Terrain
Diffusion providing the base terrain. Keep Streams rivers, lakes, vegetation and
water-flow settings enabled. The separate finite TD preview must be disabled in
this world; existing preview worlds retain their own configuration and saves.

The NeoForge integration defaults `streams.lazy_prefetch=true` in
`terrain-diffusion-mc.properties`. Version-gated hooks skip only the native origin
warmup and speculative player plate/radius submissions on TD terrain. Native
engine creation, terrain sampling, network generation, carving and flow-tail
updates remain enabled. Background flow grids are admitted only for already
loaded TD chunks; foreground generation/urgent flow callers remain untouched.
Other biome sources retain Streams' normal behavior.
Demanded new watersheds still need computation; this is not instant generation.

Streams performs its own cold terrain calibration before origin warmup. Preserve
the same world's `streamsreflowing/minecraft_overworld/fastcal-v10.txt` and native
network store on restart. Do not transplant calibration between seeds or changed
TD model settings: its native header does not fingerprint the TD model/seed.

For the enabled integration audit, prepare from a Streams-enabled instance and
pass these quoted Gradle arguments through `probe.init.gradle`:

```text
-PtdProbe.playerPrefetchAudit=true
-PtdProbe.playerPrefetchMode=enabled
-PtdProbe.streamsTopologyAudit=true
-PtdProbe.topologyCenterX=-1425
-PtdProbe.topologyCenterZ=8
-PtdProbe.topologyRadius=512
```

Those coordinates refer to the Terralith fixture seed `6187795302574383407`.
The topology search is bounded and reports missing source/junction/outlet roles
as a failed audit. It identifies directed native segment endpoints and joins,
then verifies nearby native plans and actual water/bed blocks;
the native tick audit waits for ticking chunks before 100 server ticks. Run the
same probe world again for the immutable successful topology baseline check.
The older disabled-player audit proves only the Streams-disabled preview path.

`prepare-playtest.py --source-instance PRISM_INSTANCE --probe COMPLETED_PROBE
--jar PRODUCTION_JAR --output NEW_DIRECTORY --spawn X Y Z` stages **TD Streams
Integrated 1.21.1**, containing world **TD Streams Rivers**. It requires successful
enabled player, topology and tick receipts, preserves the native world data, and
refuses an existing output or an enabled standalone preview.

Use `--instance-name`, `--world-name`, and repeated `--client-mod PATH.jar` to
stage a separately named repaired instance with Sodium and Xaero's World Map.
Prepare its probe with `--c2me` to carry the same tested C2ME build into play.
For natural existing shores, `allWaterBanks=0` skips the extra bank pass around
non-Streams water; `customBanks=true` retains native Streams' own banks.

For surface regressions, `-PtdProbe.surfaceAudit=true` records actual ground
material, saved biome and direct biome-source output per column. The optional
surface-rule trace records the active resource and decoded runtime rule. In the
same-seed coastal fixture, use chunks `-75,7;-74,7;-75,8;-74,8`. Compare exposed
water (`surfaceY == topWaterY`) separately from water underneath solid ground.
Run causal controls in fresh worlds: a no-Streams control still reproduced the
old small ponds, while a no-carvers control removed them. Do not attribute all
water or sand in a Streams-enabled world to Streams.

Generated instances and screenshots stay under `build/`; installation preserves prior instances.

For natural-biome worlds, add `-PtdProbe.ecologyAudit=true` with `surfaceAudit`.
The per-column CSV appends continuous cover, temperature, moisture and slope,
plus actual log evidence above the original TD ground. Log columns are a trunk
proxy, not a canopy-area measurement. `-PtdProbe.ecologyOverview=true` writes an
8192-by-8192-block coarse-only overview at 64-block spacing to find candidate
ecotones. It uses the saved world scale and assumes zero slope/no coast; validate
selected locations in actual generated chunks before judging the scenery.
Use `ecologyOverviewRadius` and `ecologyOverviewStep` for a wider search; the
diagnostic limits the radius to 32768 blocks and the grid to 512 samples per side.
Tree cover measures woody vegetation, not bare ground. A dirt threshold of 0.58
made the tested temperate grassland mostly coarse dirt; 0.40 restored grass as
the dominant surface. Use biome IDs, climate fields and actual generated block
counts as the primary validation; biome IDs alone do not describe the resulting
soil or vegetation. Screenshots are an optional final appearance check, not a
substitute for reading the generation data or a reason to repeat scene tours.

The natural-biome fixture (`6187795302574383407`, scale 2) has an inland transect
near X=9472: forest around Z=0, woodland around Z=384, grassland around Z=896.
Final fresh-chunk checks covered 20 chunks and 100 ticks with native Streams and
C2ME; the existing native topology baseline matched. Four chunks per transect
site had mean cover 0.67/0.39/0.10 and observed log columns 25/17/7. Grassland
retained grass on 1020 of 1024 tested ground columns. These are bounded samples,
not a guarantee about all seeds or ecosystems.

The coastal repair was checked on 1,024 matching columns: base heights were
identical, exposed cave-water columns fell from 77 to zero, and underground
water remained. Native Streams + C2ME repeated the zero-pond result and passed
source/junction/terminal checks plus 100 ticks across 12 selected chunks. The
native topology snapshot matched after restart. Actual client screenshots also
confirmed gravel shore material, broader biome regions, native river banks,
Sodium rendering and Xaero's map. This is bounded same-seed coverage, not a
guarantee about every biome or seed.

### Verified native integration (2026-09-11)

The Terralith fixture above passed generation and a same-world restart with all
four native Streams generation/flow switches enabled and the standalone preview
disabled. Four selected FULL chunks became tickable and passed another 100 ticks.
Native graph roles and actual water/solid beds were verified at source
`(-1447,64)`, confluence `(-1432,96)` and terminal `(-1416,224)` (X,Z).
The flow sample `(-1425,8)` had two contiguous water blocks above clay at Y=76;
its native planned bed Y=77 was itself water, so the diagnostic records a bounded
physical-bed check separately from exact planned-bed checks at the three roles.
The immutable topology snapshot matched after restart.

The enabled-player audit observed 100 actual server ticks, including 41 checks
past the native cold-join age gate. On the final restart it recorded 33 skipped
player submissions and 486 skipped unloaded background grids, with queue/active
builds both zero and maximum server-task response of 15 ms. These are bounded
fixture measurements, not a guarantee of frame rate or instant new-area loading.
Native cold calibration took 173 seconds on the initial run; the same world's
persisted calibration reduced engine initialization to 17 ms on the final run.

The production JAR was also opened in the separate Prism instance and its native
source, confluence and terminal were inspected with Minecraft F2 screenshots:
`build/visual-review/native-{source,junction,outlet}.png`. Newly requested terrain
continued loading after teleporting. A tagged item placed in the audited flow
column moved from X/Z `-1425.50/7.95` to `-1429.11/6.88` over 25 seconds in the
native plan's flow direction; it was removed after the check. This verifies
physical movement in the sampled stream, not all streams or entity types.
Runtime receipts and logs are in
`build/streams-integration/`; generated evidence is intentionally unversioned.

## Isolation and scope

`prepare.py` copies only selected mod jars, configs and model assets from a Prism
instance. It creates a fresh world below `build/streams-probe/`; existing run
directories are refused. It never copies or edits the player's worlds. The
server listens only on `127.0.0.1:25585`, disables structures and mob spawning,
and sets spawn chunk radius to zero through the gated diagnostic subscriber.
Run one GPU test server at a time.

Preparation writes `eula=true` for the Minecraft development server. Use it only
when authorized to run Minecraft under its EULA.

```powershell
python tools/streams_probe/prepare.py --source '<Prism instance>/minecraft' --name minimal-12345
& tools/streams_probe/run.ps1 -Name minimal-12345 -GradleUserHome '<user>/.gradle'
python tools/streams_probe/summarize.py build/streams-probe/minimal-12345
```

Add `--terralith` to preparation to copy the installed Terralith jar and the
required Lithostitched jar (which embeds Apollib), plus the repository's existing
generated compatibility datapack. Add `--c2me` for the installed parallel chunk
generation mod. Use `--dry-floor 1`
in a **separate new run** to remove the dry-biome stream floor as a limiting
factor. That is an experimental control, not a recommended gameplay setting.

For a causal same-terrain control, prepare a separate run with
`--disable-waterways`, then pass the same `-Chunks '60,29;61,29;62,29;60,30'`
to both runs. This bypasses route discovery and compares identical coordinates.
Use `compare.py ENABLED CONTROL --output REPORT.json` to verify all sampled base
heights and natural biomes match before attributing surface-water differences.
`plot_comparison.py` exports a map from measured blocks, not a game screenshot.

`--fixed-biome minecraft:desert` is a **synthetic biome experiment**: it keeps TD's
terrain function but replaces the entire biome source with a fixed desert. It
does not reproduce a naturally selected desert/ocean biome boundary. Interpret
negative results cautiously because outlet biome conditions also change.

`input-manifest.json` records selected jar/model hashes and the seed.
`build.log` contains Gradle output. `logs/latest.log` contains game output.
The probe emits compact `[TD-SR-PROBE]` JSON records and writes receipts to
`world/td-streams-probe.jsonl` on completion.

## Interpretation

The probe searches for Streams-planned channel samples near the spawn, then
generates a bounded set of FULL chunks through normal server generation. It
records planned channels, actual water (including flowing water), surface
biomes, and the generator's base height versus TD's tile-aligned heightfield.
A run with no candidates is **incomplete**, not a compatibility failure. A
successful server exit alone is not proof that Streams carved correctly.
Read route and block-placement evidence together.

For fixed-chunk diagnosis, pass `-PtdProbe.planAudit=true` directly to Gradle
with the probe init script and an **absolute** `-PtdProbeDir` path. After each
requested FULL chunk, this queries the already initialized Streams engine and
writes `td-streams-probe-plan-audit-<x>-<z>.csv` in the world directory. Check
`planAuditStatus` before interpreting it: an absent engine or reflection error
is not a negative route result. `naturalPreCarveY` is the first-air boundary;
`naturalGroundMinusPlanWater` subtracts one before comparing to planned water.
`actualGroundY` is the separate post-generation ground measurement. These are
post-generation plans, not a trace captured during the original carve call.

Total water and water below the base-height boundary include underground water
from other generation stages. The savanna control demonstrated this directly:
the control contained *more total underground water* while having no surface
water. Compare matched **surface** columns, not total water volume, to attribute
a carved surface waterway.

The TD pre-carve value is its density-zero boundary; the expected highest solid
block is one lower. Runtime surface heightmaps and base-height queries have
different conventions. Generated decorations and carving can change actual
surface heights.

The test disables normal spawn pre-generation and structures to isolate terrain
and stream placement. It does not establish compatibility with all mods in the
player's pack, structures, client rendering, or visually natural waterways.

## Verified setup pitfalls

- Streams 2.13.1 requires NeoForge >=21.1.173. The repository default 21.1.80
  cannot load it; `run.ps1` overrides the version to the user's 21.1.249 without
  changing project defaults.
- Pass dotted Gradle properties as single quoted strings/argument-array entries
  in PowerShell. An unquoted `-Pneoforge_version=21.1.249` was received as `21`,
  producing a misleading Minecraft 1.21.11 classpath error.
- Offline compilation may succeed while offline server launch fails because
  server-only runtime dependencies have not been cached. The first run can
  download these; later runs may use `-Offline`.
- NeoForge can exit with a successful Gradle task after a mod-loading failure.
  Require the probe's own completion receipt and actual chunk measurements.
- With spawn radius zero, Streams may not yet have an engine at ServerStarted.
  Route discovery initializes its normal lazy engine factory before querying it.

## Verified result: natural savanna

Tested 2026-09-10 using this working tree, seed `12345`, scale `2`, CUDA, and the
installed Streams config (`wetnessDryFloor=0.02`). No production terrain code was
changed for this test.

- First route discovery selected chunks `(60,29)`, `(61,29)`, `(62,29)`, `(60,30)`.
  Actual generation completed in 166.2 seconds measured from probe start, mostly
  spent initializing/calibrating Streams terrain sampling.
- Reloaded those saved chunks for detailed attribution, then generated the same
  coordinates in a new world with Streams rivers/lakes disabled.
- All 1,024 base heights and natural biome identities matched. Every sampled
  natural biome was `minecraft:savanna`.
- Surface water: **502 columns enabled, 0 disabled**. All 502 enabled columns
  had water below the control's pre-carve terrain boundary. The control disables
  rivers and lakes together, so this attributes the change to Streams waterways
  collectively. It does not distinguish the river feature from the lake feature
  or directly inspect control-block solidity at each enabled water position.
- Representative route area: block X `960..1007`, Z `464..495`; only the four
  named chunks were measured. This proves surface waterway generation works in
  this combination; it does not establish global frequency or visual quality.

Raw local evidence is under `build/streams-probe/`: `savanna-comparison.json`,
`savanna-comparison.png`, each run's receipt/column CSV, and
`minimal-12345/first-generation-receipt.jsonl` for the original planned-route
samples. These generated artifacts are intentionally not versioned.

## Additional isolated runs

All runs use the current local TD source, not an independently verified copy of
the TD jar in the player's instance. Streams is the installed 2.13.1 jar.

| Run | Observed result |
| --- | --- |
| Terralith 2.6.2 + Lithostitched 1.8.0-beta.6 + existing surface compatibility pack | Four chunks completed; 477 surface-water columns; natural biome `terralith:mirage_isles` |
| Same + C2ME 0.4.0-alpha.0.120 | Four chunks completed; 482 surface-water columns; planned routes overlapped actual surface water |
| Synthetic fixed desert, original `wetnessDryFloor=0.02` | No route candidates within the bounded search; incomplete, not a crash |
| Synthetic fixed desert, `wetnessDryFloor=1` | Four chunks completed; 521 surface-water columns; all 1,024 natural-biome samples desert |

The desert pair changes only the copied dry-floor setting. It supports climate
dryness as a cause of missing routes in that synthetic scenario, not a guarantee
that every natural desert will gain a river. `1` was an experimental extreme.
Terralith/C2ME runs establish sampled generation, not identical block placement
between configurations or compatibility with the entire player modpack.

Discovery-based runs call Streams' normal lazy engine factory before generating
chunks. Fixed `-Chunks` runs skip both discovery and this explicit initialization;
use a **fresh enabled world** with fixed coordinates to test ordinary chunk-driven
initialization. Reopening an already generated world cannot establish that.

`natural-init-12345` performed that fresh-world check at the four savanna chunks
above: no route discovery or explicit engine initialization, completion in 144.0
seconds, all 1,024 base heights/biomes matching the disabled control, and again
**502 surface-water columns versus 0**. Evidence:
`build/streams-probe/natural-init-comparison.json` and `.png`. Underground water
counts differed from the discovery-based run; surface results matched. This
supports ordinary chunk-driven placement without claiming full block determinism.

Validation also passed Python compilation, PowerShell parsing, diagnostic Java
compilation, and normal Java compilation without the probe init script.

## Natural shore and World Preview color validation

`-PtdProbe.shoreAudit=true` reads exact tile ecology over a bounded 512x256
block coastal area by default, then generates representative low/high shore
chunks for `surfaceAudit`. It is not the coarse ecology overview. Only the
exact Terralith gravel-beach ID counts as gravel; other positive terrain IDs
include oceans and cliffs. Native Streams can replace saved biomes with river,
so compare biome/material pairs and report those overrides separately.

Seed 6187795302574383407 at scale 2: the shore revision checked 15 chunks /
3,840 columns across two runs. Dry sand-shore columns were 81/81 sand and dry
rock-shore columns 18/18 stone. All 68 matching former gravel-beach columns at
the old fixture became the surrounding forest; 66 had grass and 2 dirt.
2,304 matching original TD ground heights were unchanged. Native topology and
100 ticks passed, with the topology baseline matching on restart. This bounded
fixture reached sand and rock; gravel and snowy beach were covered by numeric
selection and surface-resource checks, not by this engine sample.

World Preview 1.3.1's actual `BiomeColorMapReloadListener.parseColorData` accepted
all 14 custom TD color mappings with nonzero RGB. Keep this resource separate
from terrain scheduling: adding map colors does not fix large-range inference
queues or preview seed/scale isolation.
