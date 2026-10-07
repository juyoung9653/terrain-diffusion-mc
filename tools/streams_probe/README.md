# Streams Reflowing integration probe

Opt-in dedicated-server diagnostic for Minecraft 1.21.1 / NeoForge 21.1.249,
Streams Reflowing 2.13.1 and the current Terrain Diffusion source tree. Normal
builds do not include the diagnostic Java directory. Do not package a release
with `probe.init.gradle` enabled.

## Native Streams integration

### Reusing the tested setup in a modpack

Use the tested Terralith surface pack with `terralith=true` and
`natural_biomes=true`, and keep `generateStreams`, `generateLakes`,
`vanillaWaterFlow` and `flowVanillaRivers` enabled. Matching the mod JARs alone
does not match the saved world's biome source, scale or selected datapacks.

For a new scale-5 world, enable the pack first, then choose Terrain Diffusion,
Customize, World Scale 5, Done, and Create. Customize sets both the dimension
type and the pending runtime scale. A datapack dimension type alone does not
set the runtime scale: first-load initialization otherwise defaults to 2.
Make Customize the last world-type action before creation.

If generation is disabled while vanilla-river flow remains enabled,
`StreamFlowNet.onChunkSent` can first initialize Streams' calibration on the
server thread and wait for distant TD heightmap tiles. Check this call path,
actual settings and selected world datapacks when diagnosing missing chunks;
keep the requested river features enabled during the comparison.

### TD natural river profile (new worlds)

New real NeoForge TD Overworlds with audited Streams 2.13.1/2.13.5/2.13.8 pin natural
profile v7 in `data/terrain_diffusion_natural_rivers.dat`. Legacy worlds, Fabric,
worlds without an audited adapter and finite-preview worlds remain disabled.
`-Dtd.rivers.natural=false` opts out **only before the first world creation**;
the saved choice wins on restart. Missing/corrupt profile data beside an existing
natural cache, or loading an enabled world without its audited adapter, stops
generation. Keep the complete world together when copying it.
Do not recursively exclude `td-*` while copying a playtest world: that also
removes `streamsreflowing/minecraft_overworld/td-natural-vN`, forcing native
graph regeneration and delaying client flow-grid delivery. Preserve profile
data, SRN graphs and raw flow tiles; compare their hashes after staging.
The first marker is written and read back atomically before publishing it to
generation threads: C2ME defers `DimensionDataStorage.save()`, so calling that
method and immediately reading the file is not a synchronous persistence gate.

The implementation is a TD module plus versioned Mixin adapters; it does not
patch, redistribute or require a fork of Streams. Native routing, lake/sea
connections, indexing, water flow and bank decoration remain in Streams.
`NaturalRiverProfile`, `NaturalRiverWatersheds` and `NaturalRiverMorphology` own the independent policy;
`StreamsNaturalConfig`, `StreamsNaturalNetwork` and `StreamsRecordAccess` isolate
the native types. The shipped JARs strip the Java Record attribute: use audited
accessor/constructor layouts, never reflection field order or `isRecord()` alone.

New worlds use **profile v7**. Profiles 1–5 retain their saved configuration and
cache namespaces. V4 adds a deterministic directed-graph selection after the V3
watershed policy: optional tributaries need 0.15 plate scales of remaining path
and 0.05 plate scales of source clearance from retained channels (768 and 256
blocks at scale 5). These are admission criteria, not minimum walking distances:
confluences, meanders, independent mainstems, and protected lake/border/cyclic
connections can remain closer. Each component first retains its longest native
source-to-terminal shortest route; a shorter protected path must not replace
that mainstem. Other mandatory upstream paths are completed before
optional admission so their geometry participates in spacing. A monotone width
response narrows intermediate channels while retaining the source taper and
full widths of 24 blocks or more. Actual wetted width still needs engine checks.

V5 preserves the v4 routing, widths, water grades, lake blending and connectivity
repairs. It adds a bounded dry-bank finishing pass before corner/containment
repairs: only narrow inland corridors may lose up to three soil blocks of bank
height. A one-block freeboard above nearby planned channel water is retained;
gentle reaches get a softer bank ramp than steep reaches. Lakes, sources,
outlets, protected structures/biomes, fluids and non-soil obstructions are
excluded. Exposed material varies in coherent patches rather than per-block
random speckles. The pass runs only while generating new v5 chunks; it does not
reshape a player's saved v4 terrain. V5 uses its own `td-natural-v5` namespace.
For a bounded regression run, `-PtdProbe.naturalBankShapeAudit=true` reports
edited columns and removed blocks; `-PtdRiver.bankTrace=true` emits exact
before/after bank heights for checking cut limits against saved blocks. These
counters alone do not prove water containment or aesthetic quality.

V5's dry-bank trimming did not resolve deeply incised narrow rivers: saved
samples included water 8–11 blocks below physical ground only four blocks to
either side, while its inherited containment pass could build soil crests.
V6 instead raises eligible graph-node grades toward the uncarved TD surface.
It queries the generator's base height directly, bypassing native grade
filtering/calibration. The target water is one block below the original top
solid block, bounded by nearby terrain, a 12-block maximum lift, and a width
fade to zero at half-width four. A graph-wide difference-constraint solver
limits additional descent to half a block per horizontal block and prevents
new downstream ascents; existing sharp falls may flatten without that limit. Final grades are floored to 1/64-block policy precision.
Wide nodes, terminals and sampled lake/border-intersecting reaches pin the
correction. These constraints mean not every stream can achieve the shallow
target. Native indexing is rebuilt before writing the separate v6 cache.

V6 disables TD soil-crest construction beside narrow tributaries and V5 dry-bank trimming, retains
corner connectivity repairs, and suppresses only an audited native dry-bank
AIR write where existing soil should remain. This adds no soil or water. The
hook is ordinal six in `RiverCarverFeature.place` (bytecode 6982 in 2.13.1,
7198 in 2.13.5); the smoke test pins both layouts. Its retained-write counter
does not prove bank containment: later native cleanup and water ticks still
require physical validation. Compare actual saved water and uncarved base
heights as well as images; a passed no-flood audit alone is not naturalness.

V7 inherits the shallow-grade and retained-soil behavior. Before shallow-grade
refinement it changes only native segment widths: slow background growth,
area-proxy contributions at joins, and a carried gradual widening target.
Comparable native tributaries can recover the native main-river scale; tiny
contributions have a weaker effect. Half-width growth limits are 0.001/block
between joins and 0.02/block toward an outstanding join target. These are visual
heuristics using squared native width, not measured discharge. Protected
lake/border edges and outlets keep native widths, with a 128-block approach;
native endpoint differences can remain there. Cyclic components stay native.
Display clamps have small segment-subdivision sensitivity.

V7 also suppresses native MUD skin/depth painting when no actual water exists
within horizontal radius two and vertical distance two of the scanned surface.
The original substrate remains, including any independently generated mud.
The audited helper takes x, z, scanTop: v7 reads its positioned MutableBlockPos;
legacy profiles 1?6 intentionally keep the old argument capture and behavior.
All water-tag fluids count; this test is independent of planned river distance.

For actual current checks, `-PtdProbe.itemFlowAudit=true` warms fixed loaded wet
cells for 100 ticks, then measures ordinary dropped items for 100 server ticks.
`-PtdProbe.itemFlowCells=x,y,z;x,y,z` selects exactly two predetermined samples.
Zero flow or unavailable water is not a pass. The JSON report includes both
initial/warmed vectors, downstream displacement and final water contact.
This tests server physics, not the visible client water texture.
Use the separate `client-flow.init.gradle` with `-PtdClientFlowProbe=true`
for client grid/flow logging every 100 ticks near the player. It includes only
`client-java`, never the automatic server probe. As with the server probe,
do not package a production JAR with this init script enabled.

Fresh v7 validation at scale 5 / seed `6187795302574383407` passed seven
bank boxes and 200 settled ticks with no newly inundated grass. Both freshly
derived source-to-marine routes have face-connected nonascending saved water
(1,155/1,155 required chunks FULL). All 22 fixed narrow samples remain wet;
median surrounding ground above water at offsets 4/8/12 remains 2/2/4 blocks.
In the same seven boxes, exposed mud beyond the water-distance bound changed
from 16 in v6 to zero in v7; 22 water-adjacent exposed and 89 submerged mud
blocks remain. Saved blocks cannot distinguish native skin from terrain mud.
Two predetermined wet cells passed 100-tick item drift after 100 warmup ticks.
Restarting with the opt-out flag retained profile 7 and all four SRN files
byte-for-byte. Evidence is under `build/river-v7-validation` and
`build/river-v7-analysis`.
The Sodium client also received 1,536-byte native flow grids and nonzero
`FluidState.getFlow` at the fixed creek and junction after native initialization.
Initial no-grid/zero-flow readings can persist during that warmup; enabled
config alone does not prove immediate client readiness.

### Narrow-river overflow and active physics

Streams 2.13.5's `spillOverWater = true` overrides `FlowingFluid.isWaterHole`
above source-water pools once its physics grid is available. On narrow TD
cascades this can spread an upper sheet of flowing water onto dry banks.
Use `spillOverWater = false` in `config/streamsreflowing-common.toml` while
retaining `vanillaWaterFlow = true` and `flowVanillaRivers = true`: entity
currents are controlled separately. This is a runtime setting, not a new
terrain profile or a reason to deepen saved rivers.

The earlier 200-tick bank checks did not explicitly activate native physics
grids and cannot establish containment under player-loaded conditions.
Use `-PtdProbe.warmFlowGrids=true` with a predetermined wet channel column
(`flowGridFixedX` / `flowGridFixedZ`) and `-PtdProbe.containmentTicks=1200`.
The probe requires an actual native physics angle there before ticking water.
At the reported v7 location, identical pre-overflow copies produced 245 newly
wet grass cells with spill enabled and zero with it disabled over 1,200 ticks.
Three pre-existing flowing cells outside the native corridor remain separately
reported; the strict all-water containment check does not call these a pass.

Changing the setting alone does not retract an already-supported saved fan.
On an isolated world copy only, `-PtdProbe.resetFlowingWater=true` clears ordinary
non-source water inside the explicit containment bounds, asserts source count
is unchanged, and lets ordinary scheduled fluid ticks refill it. It does not
remove waterlogged blocks or alter soil. Preserve the original save and verify
the repaired copy's source positions, terrain, settled water and item currents
before using it. This flag is diagnostic-only and is absent from release JARs.

The saved-location repair retained all 266 ordinary source-water coordinates;
the bounded 97×97×81 block comparison found 559 changes, all air/water only.
Flowing cells beyond the native half-width plus one block fell from 236 to zero.
Two wet-grass fringe cells remain inside that tolerance, so the stricter
all-wet-grass diagnostic still reports failure. All 262 assessed local native
segments retained a nonascending face-connected water path; this is not a
global outlet proof. Both fixed item samples moved downstream (3.51 / 3.36
blocks) but ended on banks, failing the item audit's final-water-contact gate.
Client play at the original position received a native 1,536-byte flow grid
and nonzero current; after saving, all 310 settled water cells matched the
server result and none of the original 236 escaped cells were wet again.

That repair covered only its explicit bounds, not the whole saved world. A later
report at `(-4504,171,23)` was outside those bounds. Both manually removed source
cells match the native planned pool height (Y171); stale flowing water at Y172
was the spreading sheet. Recomputing only flowing water preserved both sources
and removed the distant spread. Fresh generation of the same 36-chunk cohort
with spill disabled passed 1,200 active-physics ticks with no new wet grass.
Do not infer incorrect source placement solely because replacing a source with
a block changes the resulting flow.

For a complete saved-world pass, `inventory_saved_water.py --world <copy>
--output-dir <inventory>` inventories ordinary non-source water above sea level
in every saved FULL chunk, using packed section palettes. It writes exact target
positions, a one-chunk load halo, hashes and an explicit missing-FULL halo count.
Require that count to be zero before loading the cohort. The default sea level
is 63; pass `--sea-level` if the world uses another value. Empty region files are
allowed; malformed chunk storage fails rather than being treated as dry ground.

Run the dedicated probe on the copy with `-PtdProbe.expectedNaturalProfile=7`,
`-PtdProbe.savedWaterPositions=<inventory>/flow-candidate-positions.txt` and
`-PtdProbe.savedWaterChunks=<inventory>/flow-candidate-halo1-chunks.txt`.
`SavedWaterSettlement` validates the complete target list before mutation,
freezes fluid ticks while clearing it, uses normal server block updates (including
lighting and heightmaps), and schedules ordinary reflow from preserved sources.
It then runs 1,200 ticks and checks that no source positions disappeared.
Its `settled` receipt means the operation completed, not that every water cell
in the world passed a containment test. Validate reported locations and fresh
generation separately. Preserve the user's player/entity data when installing
a tested terrain copy; do not copy diagnostic server level settings over it.

The complete v7 saved-world pass inventoried 7,578 FULL chunks and reset 4,036
ordinary flowing-water cells in 307 chunks, with all 1,137 halo chunks ticking.
After 1,200 ticks, none of the 26,756 captured source positions disappeared;
normal fluid physics added seven sources. This is source-preservation evidence,
not a global containment guarantee. Client inspection at the latest saved
position and the earlier reported bend showed the spreading sheets cleared;
the latest position received a 1,536-byte native grid and nonzero current.
The two reported client regions retained exactly the settled water states after
saving (85 intersecting chunks). The whole-world block diff is not air/water-only:
normal loaded-chunk updates also change leaf distance properties, trigger loose
gravel and plants, and turn 64 ordinary source blocks into bubble columns.
Do not describe this broader operation as preserving every solid block state.
The delivered copy restricts replacement to the 1,137 verified halo chunks,
copying complete settled chunk records (including lighting and heightmaps) into
original region files. Other chunk records and original entity/player data are
preserved. This excludes unrelated falling-gravel updates outside the cohort.

Streams 2.13.8 uses a separate `StreamsBankMaterial2138Mixin`: its bank helper
adds engine and geometry arguments. The plugin selects exactly one bank-material
hook for 2.13.1/2.13.5 or 2.13.8 and rejects other adapter versions. V7 reads the
mutable surface; legacy profiles retain their original x/scan-top mapping.
The extra 2.13.8 pool-grade call is `berm lake level-out`, with a proposal of
`max(current, lakeLevel)`. `StreamsRiverRaster.poolGrade` passes that native raise
through its first guard; it must not be treated as a lowering junction-pool call.
The dry-bank AIR write remains ordinal 6 (bytecode 7843); water stays ordinal 7.
`test_natural_adapter.py` runs `audit_streams_compat.py` for both 2.13.5 and 2.13.8,
pinning descriptors, local positions, write ordinals and the extra call's operands.
Supporting another native version requires another audit, not a blanket gate bypass.

The paired v7 36-chunk carve test uses identical saved native graphs for 2.13.5
and 2.13.8. After 1,200 active physics ticks, all 221 water positions and levels
match exactly and neither exceeds the native width plus one block. The 2.13.8
strict wet-grass gate still reports one cell at `(-4518,177,12)`: its ground is
grass instead of 2.13.5's mud, while the water above it is unchanged. Record that
failure rather than presenting this as a universal containment pass. The settled
saved-v7 fixture on 2.13.8 has zero added/removed water and zero newly wet grass
after a further 1,200 ticks.
An additional cache-free 2.13.8 run exercised native network construction and
v7 confluence refinement, then generated the same 36-chunk cohort. It also had
zero flow beyond width plus one and one strict wet-grass fringe (this time
`(-4492,165,43)`); do not claim the stricter gate passed. Actual 2.13.8 client
play received a 1,536-byte grid and nonzero current. After visiting and saving
both reported locations, the 85-chunk bounded comparison found no water-state
changes. The production NeoForge build excludes the diagnostic package; the
tested copied save is installed separately from the v7 baseline and RPG pack.

The fresh v6 scale-5 validation (seed `6187795302574383407`) generated
1,354 FULL chunks and settled 100 ticks; all seven corridor bank boxes and a
200-tick containment audit passed without newly inundated grass. Both freshly
selected source-to-marine routes have strictly nonascending, face-connected
saved water (1,155/1,155 required chunks FULL). At the fixed 22 v5 narrow
samples, all 22 still contain water. Median ground height above water at
4/8/12 blocks to either side changed from 3/4/6 to 2/2/4 blocks. At
`(-3932,-1433)`, actual water rose from Y216 to Y225 against an original top
solid of Y227. These are sampled results, not a universal shallow-depth bound.
Restarting with `tdRiver.natural=false` retained profile 6 and all four SRN
files byte-for-byte, with topology and settled-water checks passing again.
The retention hook recorded zero writes in this cohort, so the cohort does not
prove its active path; its placement is separately bytecode-audited.

Do not infer grade-only invariants from two separately generated native caches:
C1 and C2 already received different segment counts before shallow refinement.
The upstream reproducibility cause is unresolved. A same-input replay over
6,836 real edges verified preserved XY/width/speed, no lowering or new uphill
edges, a maximum lift of 12, and 6,070 unchanged wide endpoints. Native
reassembly retains the supplied lake list; cross-world lake identity is not a
passing claim. Validate saved routes against each candidate's own graph.

Read current bank tops from `MOTION_BLOCKING_NO_LEAVES`, not `OCEAN_FLOOR_WG`:
after CARVERS, `ProtoChunk.setBlockState` maintains the final heightmaps only.
The stale worldgen map can point at already-carved air and silently skip most
bank edits; it may also be unprimed at the native cut site. V6 retention
checks the existing dry block directly without a heightmap query. Validate on fresh chunks and require actual edit traces. Serialize
ordinary builds and live probe runs: an ordinary compile can remove diagnostic
classes from the shared output while a running probe still needs them.
For a new-world test, `-PtdProbe.expectedNaturalProfile=7` rejects a disabled or
wrong saved profile before exporting plans or requesting chunks. A chunk-empty
copy still needs `Data.initialized=0`; removing caches alone does not make an
initialized legacy world eligible for first-world enablement. Use
`prepare-natural-comparison.py` rather than copying `level.dat` unchanged.

V5 scale-5 validation (seed 6187795302574383407, Streams 2.13.5): a genuinely
new enabled world passed the 1,354-FULL-chunk/100-tick native topology audit,
a 200-tick containment check with zero newly inundated grass, and seven inland
bank boxes with zero outside-channel wet grass. The finishing trace recorded
1,626 lowered columns (1,510 one-block and 116 two-block cuts). Saved FULL
chunks verified 1,508 of those tops; 118 unfinished halo columns were excluded.
Fresh exported graph routes in bounds `-4300,-1700,-2450,750` yielded actual
nonascending, face-connected water paths of 3,511 and 2,032 positions to
sea-level endpoints; all 1,155 requested route chunks were FULL. This is
sampled route/containment evidence, not an all-world guarantee.
Restarting that world with `-PtdRiver.natural=false` retained its enabled v5
marker and all four native graph files byte-for-byte; native topology matched,
and loading saved terrain produced no further bank edits.

At voxel resolution, v4 raises positive sub-voxel plan half-widths to 0.71
(just over half a block diagonal), including the native source taper. Otherwise
an otherwise continuous spring can rasterize as disconnected puddles. This changes
column plans, not saved native graph geometry. The common plan factory covers
`plan`, `planLean`, alternate plans and their caches. Keep the explicit 22-field
`ColumnPlan` constructor audit; 2.13.1 has a four-argument factory and 2.13.5 adds
the ready-plate array.

Native water-surface inset depends on width. A narrowing reach can therefore
rise one block even along a flat/downhill graph grade. V4 uses the full configured
inset consistently inside the planner's **lake-aware inset method**, keeping its
native lake-level blending. Do not replace global `RiverEngine.insetFor`: the
native graph tracer uses it too. Do not subtract an inferred inset from an already
lake-adjusted column. Regression checks must include both width-transition shelves,
a lake-fed source, an inland lake terminal and a marine mouth.

Near a lake, the incised stream reference can sit below lake backwater. V4 raises
only nonchannel bank references to the nearby lake level when the difference is
within the configured inset; channel water retains the native lake blending.
Audit gross added wet-grass positions against the native lake bitmap, smooth
shore coverage and saved water. A net decrease can conceal new shore leaks.
The native `lakeNear` radius is measured in grid cells, not blocks.

Native lateral junction pooling can lower the upstream side of a tight meander
toward a nearby downstream bend across a dry neck, leaving an uphill obstruction.
V4 accepts that lowering only across a face-connected channel crossing; rounded
diagonal samples need an orthogonal wet-plan bridge. Sources, outlets and nearby
lakes retain native pooling. For diagnosis use `-PtdProbe.carveTrace=x,z,radius`
on fresh chunks and inspect `[SR-STACK]`; saved water alone cannot identify which
native grade adjustment caused a pocket.

V4 bank finishing handles two distinct cases. A narrow raster corner lacking a
local downhill channel connection gets a one-block soil excavation; native water
fills it without adding a source. Other shallow lateral openings receive a soil
crest with one block of freeboard for settling at steps. Dry cliff-side gaps may
need up to six blocks of supported fill; never overwrite existing fluid or solid
non-soil material. Check each highest incoming arm separately: a connected lower/equal arm,
or an uphill-only detour, must not hide a disconnected upstream arm. Dry tall
grass can occupy both blocks of a bank opening, so checking for air alone misses
it. Both operations preserve native structure/biome protection and skip lakes,
sources, outlets and existing fluid blocks. They run only during v4 generation.
Equal-height disconnected arms also need the corner; a sole arm is not a gap.

The bank regression oracle must check **both** newly inundated grass and actual
downhill connectivity. Some native diagonal gaps were previously joined only by
water spilling over grass; blocking that spill alone dams the river. Compare the
footprint reachable from the same mainstem entry separately from disconnected
aquifer water elsewhere in a scanned vertical volume. The full-volume grass
audit remains a separate check; lowering its count alone cannot prove containment.

V4 validation (seed 6187795302574383407, scale 5, Streams 2.13.5): the fixed
50-transect comparison recorded 266 to 116 river encounters, with median gaps
95 to 219 blocks. These are sample results, not a universal spacing guarantee.
The two retained coastal routes (1,559 and 3,104 blocks of planned length) passed
strict face-connected, nonascending saved-water traversal from their first wet
headwater sample to sea. All 18 inland bank boxes had zero outside-channel wet
grass after fluid ticks and restart. Saved graphs/profile remained byte-identical
with the current enable flag set false. Lake regressions separately passed the
gross-footprint oracle above. Actual client views of narrow headwaters, a major
confluence and the marine mouth were inspected; this remains block-based native
Streams flow, not a sediment/erosion or discharge simulation.

V2's high accumulation threshold removed the fine
headwater network along with excess coastal channels. V3 restores the native
minimum of 6 (preserving larger native overrides), uses growth multiplier 1,
and expands the routing plate to 5,120 blocks at scale 5. Width remains tied to
accumulation with 45% of the native width coefficient. Distances below are
implementation targets, not universal real-river dimensions.

The selector operates on the **plate-local** native directed graph before its
indices and persistent cache are rebuilt. It can reject a short complete
single-terminal coastal component (mainstem below 0.375 plate scales), while
preserving components touching lakes or the plate boundary. On valid coastal
trees it also prunes short source leaves below 0.0625 plate scales in one pass,
keeping at least one incoming branch. Each candidate leaf, including its
collapsed self geometry, must avoid protected lake/boundary reaches. Thus a
lake elsewhere downstream does not preserve every tiny twig. Unknown,
cyclic and multiple-terminal components remain untouched. This is conservative
local selection, not a globally routed drainage-area model.

Native node identity is `Math.round(coordinate * 16)`. Rounded parallel geometry
must not create extra tributaries; collapsed self-edges can be native connection
placeholders. After selection, retain original sink objects/order wherever
retained endpoint geometry still supports them; do not reconstruct native sink
semantics from a simplified graph. Lake metadata and relief are preserved.

Large plates caused repeated TD tile reconstruction with the 64-entry cache.
V3 uses a bounded 512-entry heightmap cache; server cleanup restores 64. This is
an entry limit, not a bound on all caller-held or pending tile memory. Geometry
and biome calculations are unchanged.

The design follows the importance of small headwaters in real networks
([EPA](https://www.epa.gov/cwa-404/learn-about-streams)) and upstream catchment
connectivity ([USGS](https://water.usgs.gov/themes/hydrofabric/)). Hydraulic
geometry relates width to discharge, with site-dependent coefficients
([USGS](https://pubs.usgs.gov/pp/0252/report.pdf)); neither a universal minimum
width nor fixed mouth spacing follows from those sources. CSV widths are plan
widths; prove actual wetted widths from saved blocks separately.

V3 scale-5 validation (seed 6187795302574383407, Streams 2.13.5): fresh candidate
`build/watershed-v3-validation/worlds-c3/natural` completed two directed coastal
routes of 1,559 and 3,104 planned blocks. Saved FULL chunks contained downhill
water paths of 1,802 and 3,242 positions respectively, allowing face neighbours
and one-block descending edge cascades. All 24-block-spaced samples with plan
width at least 2 found nearby actual water. The first 25–250 blocks had sampled
wetted-width medians of 2.5 and 3.5 blocks; the second route also crossed a
25-block-wide water section. These are raster water transects, not bankfull
measurements or a claim that every headwater stays narrow. The successful paths
use known FULL chunks; the search also encountered three missing/unfinished
neighbour chunks, which were not treated as water.

All 1,157 selected FULL chunks became tickable and advanced 100 server ticks;
native source, confluence, terminal and flow checks passed. Restart with the
opposite opt-out flag retained profile 3, matched the topology snapshot and
preserved all four native network files plus the marker byte-for-byte.
Evidence: `physical-water.json`, `v3-physical.log`, `v3-restart-verified.json` in
that validation directory. A source-to-terminal water path alone does not prove
that every terminal in the world reaches an ocean.

In the same 3,072-block-square plan window, length-weighted width below 8 blocks
increased from 3.0% (v2) to 68.4% (v3). Marine-adjacent terminal counts were 4
and 6, compared with 19 in the original v1 fixture. Boundary protection leaves
one 138-block coastal stream; this is not a universal minimum length or mouth
spacing guarantee. `river_routes.py` reports the largest source-to-terminal
**shortest directed path** per marine terminal, including cyclic/multiple-outlet
graphs; `river_hierarchy.py` restricts its basin-length metrics to valid DAGs.
Do not equate their outlet subsets or label planned widths as physical water.

The production CUDA JAR (without diagnostic classes) also passed the NeoForge
build and Fabric Java compilation. A separate Prism instance/world named
`TD Watershed Rivers v3` was installed from the verified saved chunks; actual F2
screenshots were inspected at the thin headwater, confluence and estuary
(`in-game-headwater.png`, `in-game-confluence.png`, `in-game-estuary.png`).
The estuary camera is approximately (-2580,115,260), yaw 165, pitch 30.
The native endpoint at (-2529,62,217) extends seaward beyond the visible shore
crossing: framing only that endpoint misses the river mouth upstream to its west.
F1 hides the HUD **and prevents this client from opening chat**; restore it
before issuing observation commands. Use Save and Quit for integrated-server
persistence; its command dispatcher does not expose `save-all flush`.

Frozen **profile v2** raises minimum accumulation to 128 and the
growth multiplier to at least 2 (effective threshold 256 with native
`growFromWater=true`). These are summed upstream wetness/grid-cell units, not
square kilometres or a promised mouth spacing. The native drainage selection
removes insufficiently supplied channels before geometry, retaining native
downstream routing rather than cutting connected graph segments after carving.
The nominal ocean source-chain minimum is 640 blocks at scales 1/2 and 1,600
at scale 5. Native dry/lake/junction exemptions remain. Width still follows
the square root of accumulated drainage area, but its coefficient is halved;
the base width and maximum remain intact. Slope-driven widening is reduced
from .85 to .25 and delta-mouth strength from .10 to .02. This targets excessive
minor-channel/estuary width without assigning every river the same width.
Neither global basin rerouting across Streams plates nor a universal fixed
distance between mouths is introduced.

Existing **profile v1/v2** worlds keep their previous configuration and
`td-natural-v1`/`td-natural-v2` caches; v3 uses `td-natural-v3`, and v4 uses
`td-natural-v4`. Never move native graphs between these namespaces in a player
world. Synthetic bank-only controls may copy a graph solely to isolate carving;
they are not generation or playable-world acceptance evidence.

For lateral-spread regression checks, `-PtdProbe.containmentAudit=true` freezes
game ticks at server start, generates a fixed FULL chunk cohort, snapshots dry
grass and water, schedules ordinary fluid ticks, then verifies at least 200
actual game ticks. Existing submerged channel substrate is reported separately
from newly inundated dry grass and water over outside-channel grass. Successful
Gradle exit alone is not success: inspect `river_containment_audit.status` and
`td-containment-comparison.json`. `containmentDetails=true` adds bounded native
plan and block-state neighborhoods for diagnosis. Use fresh terrain to test
carving; a restart cannot exercise generation-only bank corrections.

`test_natural_adapter.py --frozen-jar PATH` additionally compares saved profiles
1–3 against a preserved production binary at scales 2, 5 and 6 for every supplied
native JAR. Without this explicit artifact, the tool reports that comparison as
skipped; its native layout and adapter checks still run.
The persisted version and namespace provide isolation: native graph loading
warns about a fingerprint mismatch but still accepts that graph. A fingerprint
is not a fail-closed migration barrier. Never switch an already generated
world's marker to a newer version.
Create a fresh world to inspect the new profile.

Profile v1 raises the native accumulation minimum from 6 to at least 24 and
raises the growth threshold multiplier to at least 1. Coastal minimum chain
length is 256 blocks at scales 1/2, scaled to 640 at scale 5, converted to native
grid nodes with ceiling division. This is a nominal grid-chain threshold, not a
measured shoreline setback. Scope 1 targets ocean source chains and preserves
tributary/lake joins; native dry-channel exceptions still apply. Confinement
follows low terrain after native meandering. Native terrain/climate hydraulic
shaping provides asymmetric beds and variable widths; reduced bar/delta strength
avoids making every lowland river a braid. `streamDepth` is capped at 0.12 of
full width (native bed shaping still varies it); existing native maximum width
and world-scale policy remain the bounds. Global Streams TOML is not edited.

The grade module preserves x/z, widths, speeds, topology and exact grades at
sources, outlets and junctions. It only lowers gentle unbranched chain kinks,
by at most one additional block, and skips lake approaches, steep or invalid
chains. It never flattens TD mountains to force a level river. This is bounded
grade smoothing, **not a continuous sub-block water renderer** or a hydraulic
simulation: native integer water levels and natural terrain drops remain.
No periodic eight-block drop pattern or fluid-tick settling is introduced.
Major floodplain creation, erosion history and full-size 100+ block rivers are
outside the currently audited native geometry envelope.

Native graph files for v1 use `streamsreflowing/minecraft_overworld/td-natural-v1/`.
The native seed/config fingerprint is diagnostic, not a load rejection. Do not change the profile's
algorithm/constants in place for released worlds: introduce a new saved version,
cache namespace and matching flow-cache fingerprint, retaining the old policy.
Streams config/model/scale changes likewise need fresh comparison worlds.

Before adding an upgraded Streams version to the audited allowlist, run
`python tools/streams_probe/test_natural_adapter.py` against the actual JAR,
then run a fresh-world and restart topology/tick probe. Public config and assembly
checks reduce porting work; they do not promise automatic future compatibility.
Use `--jar VERSION=PATH` (repeatable) and `--java-home PATH` to check other local
artifacts without editing the test. Review the actual constructor injection sites
and retained network metadata before changing the version allowlist.
Do not use `DeadEndResolver` for coastal thinning: in 2.13.5 it admits otherwise
discarded no-water dead ends, rather than filtering normal marine outlets.

For a same-seed comparison, `prepare-natural-comparison.py --template-world WORLD
--output NEW_DIRECTORY` stages two chunk-empty dedicated worlds, retaining TD
scale and datapacks and copying no old river caches. Run the baseline with
`-PtdRiver.natural=false` and natural with `-PtdRiver.natural=true` through the
probe init script on their first load. Add `-PtdProbe.naturalRiverAudit=true`
and the same `topologyCenterX/Z` plus `naturalRadius` (default 768) to export
`td-natural-river-network.csv` and the effective profile. `river_metrics.py CSV
--bounds MIN_X MIN_Z MAX_X MAX_Z --baseline BASELINE_CSV` compares clipped plan
length density and widths; repeated `--transect X1 Z1 X2 Z2` counts centerline
crossings. Plan crossings are not a physical walking/water audit. Keep the
existing topology, marine-connectivity and 100-tick checks for actual blocks.
`naturalCenterX/Z` can place the export separately from the physical topology
probe. With `naturalCoastAudit=true`, `td-natural-coast-terminals.csv` lists
downstream graph terminals in those bounds. `marineWithin32=true` additionally
requires a grade at most sea level + 2 and a sampled original TD marine-mask
column within 32 blocks. These are marine-adjacent **planned** terminals, not
confirmed physical mouths; lake/sea connectivity still needs the block audit.
`naturalPlanOnly=true` stops after the export and explicitly reports zero physical
chunks checked. `river_hierarchy.py` measures width-length distributions and
source-to-first-confluence paths using full exported topology before clipping.
`saved_water.py` reads actual FULL Anvil chunks and samples water transects;
missing or unfinished chunks cannot establish a dry-channel defect.
For long physical routes, `chunksFile` accepts a UTF-8 file with `X,Z` chunk
coordinates separated by semicolons or newlines, avoiding command-line length
limits. It is mutually exclusive with `chunks`.
`river_routes.py` reconstructs directed source-to-terminal shortest paths even
where braids/cycles prevent simple tree statistics. Its reported maximum is
the largest of those shortest paths, not a longest simple path or basin size.
In Windows PowerShell, quote complete Gradle properties such as
`'-Pneoforge_version=21.1.249'`; an unquoted dotted value can become `21` and
incorrectly trigger the plugin's newer-Minecraft fallback.

Validated v1 fixture: Streams 2.13.5 + C2ME, seed `6187795302574383407`, scale 5,
bounds X `-4341..-2805`, Z `-676..860`. Same-seed plan length fell from 20,073 to
10,098 blocks (49.7%); in-bounds source nodes from 143 to 29. Two fixed horizontal
centerline transects at Z 600/700 crossed 16/10 channels before and 9/6 after.
These are plan measurements, not a walking or all-seeds aesthetic claim.
Four actual source/junction/outlet/flow chunks passed topology and 100 ticks on
first generation and restart. Restart with the opposite JVM opt-out retained
enabled v1; the profile, network CSV and four native graph files kept identical
SHA-256 values. Marine validation found 32/32 sampled planned centers wet and
physical terminal water on TD's ocean mask at `-3575,62,701`; the direct biome
was beach, so this is the `td_marine_mask_connection` result, not the stricter
direct-ocean-biome result or a full continuous source-to-sea route proof.
Evidence is under `build/natural-river-validation/`; it is not packaged.

V2 coastal acceptance (same seed, scale 5, Streams 2.13.5 + C2ME): expanded
bounds X `-5036..-1964`, Z `-936..2136` contain 19 marine-adjacent planned
terminals in v1 and 4 in v2. Two fixed plan transects at Z 600/700 change
13/12 crossings to 2/1. Plan length changes 19,393 to 5,691 blocks. The same
central mouth at `-3571,704` changes full planned width 59.5 to 19.4 blocks;
do not compare terminal median widths as though the removed minor streams
were still in the sample. One pair of retained terminals is about 51 blocks
apart; there is no hard universal minimum spacing.

The v2 physical run generated 331 selected FULL chunks, passed topology and
100 ticks, and retained identical profile/native graph hashes after restart
with the opposite JVM opt-out. A saved-block search found an 871-position
downhill water path from `-3620,91,243` to `-3571,62,704`, through 55 FULL
chunks, without missing chunks or a search-budget cutoff. Descending corner
steps are allowed for native one-block cascades. Marine sampling was 25/25
wet and separately reached TD's original marine mask at `-3575,62,701`.
The path search bounds must cover the actual planned bend (X reaches -3425);
the earlier narrower X bound cut off that bend and was not a connectivity
failure. v1 marker/four pre-existing network files also retained exact hashes
when loaded by the new code. Evidence, plan plots and measured-block maps are
under `build/coastal-v2-validation/`; plots are not in-game screenshots.
The production CUDA JAR was also launched in the separate Prism
`TD-Streams-Coastal-v2` instance with Sodium and Xaero's World Map. Actual
in-game inspection covered the central estuary, an upstream bend and the
remaining nearby mouth pair; F2 captures are `in-game-estuary.png` and
`in-game-mainstem.png` in that evidence directory. Existing player instances
and worlds were not overwritten.

### Legacy integration and previous validation

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

For **new TD Overworlds with Streams 2.13.8**, TD now chooses Streams' native
exact host-terrain sampler before either generic calibration pass. The former
form-sampler check visits two 15-by-15 lattices at spacing 211/512 blocks and can
expand to 1,800 candidates; even its ocean classifier requests TD inference.
The second fast-estimator calibration also samples remote terrain. Neither is
needed when the exact host sampler is selected. Native river routing, actual
height/biome queries, carving and flow remain active; required river-domain
generation still has a cold cost. This does not defer calibration to player entry.

`data/terrain_diffusion_streams_exact_v1.txt` pins this choice with the world's
seed and scale before spawn generation. The marker is written only for a genuinely
new world with no prior native Streams state. Existing unmarked worlds retain
their sampler and caches even if they have never initialized Streams. Marked
worlds keep the exact choice across restarts and `streams.lazy_prefetch` changes;
invalid markers or an unsupported Streams version fail before new generation.
The marker must stay with its own world. New exact-mode river layouts need not
match older approximate-calibration layouts, whose accepted height error was
bounded but not zero. No existing river network is migrated.

`python tools/streams_probe/test_startup.py` verifies the installed 2.13.8
bytecode targets and exact-mode branch, and runs JDK-only fresh/legacy/restart,
world-isolation and marker-failure checks. Runtime verification must additionally
measure a fresh engine initialization and first FULL chunks, then check rivers
and flow; JDK checks do not prove mixin application or a wall-clock improvement.

The 2026-10-06 startup repair's scale-1 four-chunk probe improved from 598.7s
to 72.4s (442 to 77 terrain tile requests). Its C2ME river probe passed seven
FULL chunks, source/junction/terminal water and bed checks, and 100 ticks.
A restart matched the persisted topology and passed the enabled-player audit,
but then crashed in `onnxruntime.dll` during JVM exit while the daemon inference
thread was still running. Treat the functional receipts and clean process exit
as separate gates: a `complete=success` receipt does not certify safe shutdown.
The follow-up shutdown repair closes each world's inference admission after
server saving completes, cancels queued work without interrupting native calls,
and waits for the actual worker to return. A cancelled Future alone is not a
drain barrier. Streams callbacks retain their originating provider, so an old
world cannot silently submit into a reopened world's pipeline. Preview sessions
have separate scopes; client teardown drains all scopes and model loading before
`Minecraft.destroy()` reaches `System.exit` (a TAIL injection is too late).
NeoForge scope initialization belongs in `ServerAboutToStartEvent`, because
`ServerStartingEvent` follows level loading.

The CUDA/C2ME runtime audits in `build/shutdown-repair/active-stop.log` and
`build/shutdown-repair/final-active-stop.log` deliberately
submitted active and queued inference after world save. It verified zero active
and queued work after gameplay stop, rejected stale-provider requests, reopened
a different-seed provider in the same JVM, then drained an active preview on
global shutdown. This is a server/process and provider-lifecycle test, not an
interactive client world-switch test. Use the opt-in probe properties
`tdProbe.inferenceShutdownAudit=true` and `tdProbe.inferenceReopenAudit=true`;
require the final `[TD-SHUTDOWN-AUDIT] passed` receipt and a successful JVM exit.
Do not interrupt an active ONNX call or conceal failures with forced exit.
The final run exited with code 0 and no native crash report; NeoForge release
build and installed-JAR hash verification passed. Fabric compilation was not
validated because its offline dependency cache was incomplete. The dedicated
model-loading barrier has a deterministic blocked-constructor harness; a real
startup-abort event and interactive client quit still require runtime coverage.

### Chunk performance checks

Streams 2.13.8 demanded builds can execute on `streamsreflowing-prefetch`:
the thread name alone does not identify speculative work. Preserve the captured
`LowResGrid$PrioritizedTask` priority (0 urgent, 1 background) across its worker
boundary and scope `BuildGate.asDemanded` likewise. Run
`python tools/streams_probe/test_sampler_priority.py` to audit these version-gated
hooks against the installed binary.

Bound Streams density scans reuse exact ground heights through a scoped weak
tile cursor. The cursor retains full query bounds and bypasses repeated provider
lookups for each Y value; provider hit counters therefore no longer count those
cursor reads. Do not interpret their reduction as an equal wall-time speedup.
`python tools/test_terrain_sampling.py` checks parity, invalidation and priority
scope cleanup. Validate mixin application, login, chunk progress and shutdown
separately in a copied full client; a successful build does not establish that an
intermittent exit hang is fixed.

`tdProbe.performanceAudit=true` runs cold and repeated density/biome queries in
isolated preview scopes. `tdProbe.performanceParity=true` additionally compares
every cell of sampled 16x16 regions against the original configured full-tile
result, including raw short heights and all ecology fields. Cover scales 1–6,
negative coordinates, tile edges, land and water. The first small-native-read
implementation changed a height by one block: `WorldPipeline.get` reconstruction
is sensitive to request shape. Preserve the original native bounds and halo;
crop outputs afterward. Coordinate-invariant test fixtures cannot establish this
property, so retain the native-model differential check.

`tdInference.legacySampling=true` restores full-tile sampling for comparison.
`tdInference.metrics=true` enables model-session creation, native execution,
input preparation and output-copy wall times. Provider shutdown summaries report
cache reuse, shared requests, evictions and queue durations. The shared native
lane remains serial; priorities cannot cross seed-mutation barriers, and a bounded
urgent streak lets background work progress. Native reference arrays have both
32 MiB and eight-entry limits, separate from complete and partial output caches.

For Incendium, `tdProbe.incendiumAudit=true` waits for exactly one helper, the
permanent origin ticket, and release of the temporary ticket. Compare with
`tdIncendium.deferOrigin=false`. `ServerChunkCache.getChunkFuture` itself blocks
on the server thread; use a region ticket with nonblocking chunk/entity-readiness
checks instead. Only exact audited function bodies are intercepted. Helper-based
Incendium actions may be unavailable until origin readiness; total background
generation is not removed. Unknown function bodies retain their original behavior.

Always validate diagnostic receipts as well as JVM exit:

```text
python tools/streams_probe/check_receipts.py path/to/run.log --require terrain_performance_audit
```

Gradle can report success after a diagnostic emits a fatal/error receipt. GPU
residency comparisons also need VRAM sampling during active inference; an idle
sample is not a peak measurement. Dedicated-server headroom does not certify a
full client with shaders and resource packs.

For existing unmarked worlds and older audited Streams versions, Streams still
performs its own cold terrain calibration before origin warmup. Preserve
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

## Ecology showcase and scale constraints

Showcases fix the **land ecology**, not oceans or shores. Returning the selected
land holder before reading terrain hides `IS_OCEAN` from Streams' native outlet
queries. Preserve both Terralith marine alternatives and vanilla ocean fallbacks;
ecological land decoration must likewise skip these marine columns.

The native topology audit proves local source/junction/terminal water and tick
behavior only. Its `outlet` role is a bounded graph endpoint, not proof of a route
to the sea. A marine claim additionally needs direct source-ocean classification
or the original TD marine mask, plus physical water connectivity. The marine receipt records
sparse planned-center checks separately as `sampledCorridorStatus`: dry or unaligned
samples are inconclusive corridor observations, never proof that the whole route is
blocked. The 3D mouth search is capped at 8,192 water nodes and 32,768 checked
positions within 64 blocks of the start horizontally/vertically; it records the exact direct-ocean
water coordinate on success. Separately, `td_marine_mask_connection` records physical sea-level water
whose aligned TD model elevation is negative and whose underlying vanilla biome ID is an ocean. This
keeps the strict direct `IS_OCEAN` result false for legitimate estuary/river source tags. Missing chunks
or a bounded search ending are also inconclusive.

For fixed FULL chunks, pass semicolon-separated chunk coordinates through Gradle,
for example `-PtdProbe.chunks="-90,0;-89,14;-223,42;-223,43"`; the init script forwards
that as `td.streamsProbe.chunks` and bypasses route discovery.

Regenerate scale changes in fresh worlds. The scale codec omits its legacy
default value of 2, so changing that decoding default would reinterpret existing
saves. `tools/ecology/prepare_showcases.py --scale 5` instead writes explicit
scale data and the matching `_scale_5` dimension type into fresh staged copies.
Do not copy old Streams networks or calibration files across scale/biome-source
changes. The template and existing player worlds remain untouched by staging.

In natural ecology, broad climate chooses land labels; local lapse and slope
control continuous vegetation. Per-column cliff labels or local lapse thresholds
can create small biome islands. The broad climate radius is 512 model pixels,
and soil variation uses 64 model-pixel cells with moisture amplitude 0.12.
Run `tools/test_terralith.py` for the pure continuity and resource checks, then
validate new terrain in the engine at the intended scale.

Streams 2.13.1, 2.13.5 and 2.13.8 have audited hook layouts. Native `plateScale` is
already in blocks: scale 5 adapts the installed 768-block setting to 1,920,
width factor 1.0 to 1.3, and maximum half-width 16 to 32. Keep native
headwater width and drainage/merging logic. The compatibility policy applies
only to TD worlds; do not alter the global Streams TOML.

Scale-5 fixture, seed 6187795302574383407, Streams 2.13.5: all eleven land
showcases and the mixed-ecology world passed local topology and 100 ticking
server ticks. In the temperate-forest fixture, saved FULL chunks contained a
1,068-position downhill water path from the confluence (-3699,102,96) to
(-3573,62,699), including 40 one-block cascades across 68 FULL chunks.
The endpoint's original TD model elevation was -1 with vanilla ocean ID 41.
This proves that sampled mainstem reaches TD marine water; it does not prove
every headwater or every native terminal reaches the sea. Sparse center samples
can hit dry bars even when a continuous adjacent water lane exists.


## Chunk performance checks

The opt-in init script forwards `-PtdInference.*` and `-PtdIncendium.*` to
`td.inference.*` and `td.incendium.*` system properties. Use
`-PtdProbe.performanceAudit=true` with `-PtdProbe.performanceParity=true` and
`-PtdProbe.performanceScales=1,2,3,4,5,6` for native sampling parity; check the
TerrainPerformanceAudit for fixture coordinates and emitted receipts.
Full-tile derivation is the production default. Explicitly pass
`-PtdInference.legacySampling=false` to exercise experimental partial sampling.
The latter passed sparse parity but regressed dense Streams startup: repeated
large reference reconstruction dominated watchdog stacks. The cache regression
test must revisit interleaved regions, not only eight nearby sample points. `-PtdInference.metrics=true` enables ONNX session/run counters.

Native inference must retain the original full reference query bounds: shrinking
those bounds can change Laplacian floating-point results and round a height to a
different block. Crop derived outputs only, with the original interpolation order.
The strict audit compares heights, vanilla/Terralith IDs and all ecology samples,
including land fixtures and negative tile boundaries.

Gradle can return success after a diagnostic failure. Always additionally run
`python -B tools/streams_probe/check_receipts.py <log> --require terrain_performance_audit`.
The Incendium audit uses `-PtdProbe.incendiumAudit=true`; compare with
`-PtdIncendium.deferOrigin=false` in a separate fixture. Check one helper,
persistent origin ticket, removed temporary ticket and server callback latency.
Helper-dependent Incendium features wait until the origin is ready.

Keep user worlds out of these fixtures. Measure VRAM with the real shader-enabled
client before generalizing isolated server figures. On restricted Windows runners,
Gradle dependency JAR access denial can also surface as BootstrapLauncher
NoSuchElementException; use the approved local execution context rather than
changing mod dependencies to work around the sandbox.


Repeated full-tile reads use one weak recent-entry hint per worker. Reuse requires
matching provider, bounds, scale and invalidation generation; evicted entries are
marked invalid. Previews retain their own cache. `td.inference.recentTileCache=false`
disables the hint for comparison. Hit counts still count every hit (LongAdder is
an approximate concurrent snapshot); recency is refreshed every 64 consecutive
hint hits. Run `python -B tools/test_terrain_sampling.py --benchmark` for warmed
lookup cost only, not chunks/second or full client loading performance.

Use the `Terrain cache before river cleanup` receipt for gameplay cache capacity
and evictions. The later shutdown snapshot follows the 512-to-64 capacity shrink
and can otherwise be mistaken for gameplay cache thrashing.

Greeneries sand guards do not suppress ordinary grass/fern on dirt, grass blocks
or podzol. Test against the installed mod with `test_greeneries_compat.py`. TD's
EcoGroundcoverFeature directly places vanilla species, while Greeneries additional
species depend on its biome tags; fewer visible variants alone do not establish
lower placement density. Non-sand placements skip the guard's biome lookup.

Ecotone regression: `-PtdProbe.locateBiomeAudit=true` compares bounded native
biome searches with and without request-local reuse, including full position/Y.
`-PtdProbe.aquaticPlacementFixture=true` temporarily prepares and restores sky
columns in an isolated loaded test chunk to verify vanilla and Streams short/tall
seagrass writes. Pair with `-PtdProbe.aquaticPlacementAudit=true` and require both
passed receipts. This fixture proves hook behavior, not landscape appearance.

Beach modifiers must use `eco_beach_surface` with constant count 1. Merely giving
an empty-placement alias another registry key does not separate it: Minecraft's
FeatureSorter keys PlacedFeature record values by equality. With Streams water
skin this creates opposite land/beach ordering and a runtime feature cycle.
`test_beach_order.py` covers the reduced cycle; generate fresh mixed beach/land
chunks with installed modifiers for runtime confirmation. Constant count 1 keeps
the origin and consumes no RNG. Saved FULL chunks can hide this failure.
# Fast locate integration regression

Use the probe init script with `-PtdProbe.fastLocateAudit=true`
and `-PtdProbe.fastLocateOnly=true` against an isolated natural-world fixture with
structure generation enabled. `FastLocateAudit` dispatches the actual command,
rechecks returned biome coordinates through the normal biome source, verifies an
actual shipwreck start, and checks unsupported placements, busy state and cancel.
Require the `fast_locate_audit` receipt with `check_receipts.py`; a Gradle success
alone does not mean the audit passed. Candidate exhaustion is valid command behavior
but fails this particular positive-result fixture. Keep cold and warm observations
separate when reporting speed.

Remote asynchronous `STRUCTURE_STARTS` requests need a dedicated ticket until
verification/drain completes: vanilla `UNKNOWN` tickets expire after one tick.
The native C2ME fixture reproduced an unloaded `ChunkResult` without that ticket.
Release the ticket and job ownership together on the server thread, or an old
cleanup can remove a new job's same-chunk ticket. Retest the native command after
changing this lifecycle; planner-only Java tests do not exercise it.

For ordinary Async Locator searches, use `-PtdProbe.asyncLocateAudit=true`
with `-PtdProbe.asyncLocateOnly=true` and
`'-PtdProbe.asyncLocateStructure=#minecraft:village'`. Install the real Async
Locator JAR in the isolated fixture. Require the `async_locate_audit` receipt:
locked speculative probes must do no terrain work, unlocked probes must still
run, and the returned structure must have a valid start. The audit closes locate
admission at the end, so do not combine it with subsequent locate tests. Server
callback latency demonstrates responsiveness in this fixture, not full-pack TPS.

Async Locator synchronizes both `StructureCheck.checkStart` and structure-load
updates. Native terrain inference inside the speculative check can therefore
stall the server even though locate itself runs on a worker. Test this lock path
as well as the chunk-future bridge after changing either adapter.

`-PtdProbe.bankWidthAudit=true` exports loaded bank columns; its receipt confirms
execution only. Compare fresh-region CSVs for relief of raised dry soil, retained
channel water and unchanged narrow-bank geometry. Pair with `containmentAudit`
at the target bounds to exercise actual fluid ticks. Cached FULL chunks do not
test generation changes. Reuse model assets and Streams plans in a new fixture,
but do not copy region, entity or POI files.

`-PtdProbe.vegetationSnowAudit=true` counts actual Greeneries grass/fern blocks
and runs the registered Snow Under Trees feature on restored test columns.
Require `vegetation_snow_fixture` to pass dry-floor, planted-floor, warm-biome,
water and restoration controls. A warm natural cohort is inconclusive for
natural snow coverage; the controlled fixture separately verifies the canopy
hook with the installed Snow! Real Magic! adapter.

`-PtdProbe.greeneriesDensityAudit=true` requires Greeneries 1.0.3, Terrain Slabs
3.1.2 with vegetation enabled, and Immersive Weathering 1.0.5 in the test fixture.
Require `greeneries_density_fixture` to pass: 2,000 native-provider candidates per
substrate, all 15 ecology biomes with each native feature present exactly once,
base/non-TD calls unthinned, and real short/tall plant survival on full soils and
bottom/top/double slabs. The proxy BlockGetter exercises Terrain Slabs' non-server
render-offset branch; this verifies the offset math, not an in-game screenshot.
Neighbor updates, removal of support, waterlogging and fixture restoration are
checked. Also generate a fresh chunk to detect feature-order cycles hidden by
saved FULL chunks. Counts measure accepted attempts, not final landscape coverage.
