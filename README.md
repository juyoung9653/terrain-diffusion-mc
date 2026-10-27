# Terrain Diffusion Mod [[Modrinth]](https://modrinth.com/mod/terrain-diffusion)

#### UPDATE: The research behind this mod has been accepted to SIGGRAPH 2026, the world's premier graphics conference! That means the research was officially peer reviewed and recognized as a significant contribution to the field. Enjoy the mod!

This is a Minecraft multiplateform mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion).

## Which version should I use?

Three builds are available on the [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) page:

**The CPU build is slow unless you are on MacOS.**

| Build                     | Supports                    | Setup required                          |
|---------------------------| --------------------------- | --------------------------------------- |
| **Windows** (recommended) | Windows with any modern GPU | None                                    |
| **CUDA**                  | NVIDIA GPUs                 | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **CPU**                   | Everything else             | None                                    |

> **Mac users:** the CPU build automatically uses CoreML for hardware acceleration on Apple Silicon. No extra setup is needed.

Use the `-cuda` build only if you are on Linux, or have an NVIDIA GPU and prefer CUDA (may improve performance).

## Requirements

- Windows with a GPU OR Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM (GPU RAM) needed: 1.5GB
- RAM needed: 2.5GB (May need to increase Minecraft's RAM allocation)

One of the following:
- Minecraft with [Fabric](https://fabricmc.net/) and the [Fabric API Mod](https://modrinth.com/mod/fabric-api) installed
- Minecraft with [NeoForge](https://neoforged.net/) installed

## Usage

**If using CUDA build:** First see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Download the mod jar from [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) for your Minecraft version and place it in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft, at least once online to download the models (~2.5GB).
3. Create a world, and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale` (see [Per-world settings](#per-world-settings) below).
4. The mod will search for a land spawn point near the world origin automatically. If the area around (0, 0) is entirely ocean, it may take a moment to find land. Use `/td-explore` (see below) to scout the world further.

## Exploring the World

The mod includes a built-in terrain explorer web UI. Run the `/td-explore` command in-game; it will print a clickable link (e.g. `http://localhost:19801`) that opens an interactive map in your browser. Click the map on the left to open a "detailed view". Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

Use the explorer to scout continents, mountains, islands, and other interesting terrain before venturing out in Minecraft.

## Configuration

Edit `config/terrain-diffusion-mc.properties` (created automatically on first launch):

```
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128
```

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale` (integer `1..6`)

This value is saved with the world save and affects:

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.)
- world max height for newly created worlds (assumes tallest point is 10000 real-world meters)
- 2 is recommended for a good balance of scale and playability. Use 1 for smaller, more compressed worlds.
- Lower values put more stress on the GPU (Terrain Diffusion runs more often), while higher values put more stress on the CPU (larger world height). Most modern GPUs will be bottlenecked by the CPU around scale 2 or 3.

## Common Issues

**A dynamic link library (DLL) initialization routine failed**

This can happen for some older Java versions. Please update to the most recent version of Java 21 or higher. The [latest Microsoft OpenJDK 21](https://learn.microsoft.com/en-us/java/openjdk/download) version is known to work.

**LoadLibrary failed with error 126** *(CUDA build only)*

This is typically due to an improper CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

This typically indicates an "out of memory" error (the logs should show this as well).
Terrain Diffusion's models take up about 2.5GB of RAM, so make sure to allocate enough RAM to account for this.

**If your issue is still not resolved, please [raise it here](https://github.com/xandergos/terrain-diffusion-mc/issues/new).**

## Building from Source

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

The `-windows` build requires `libs/onnxruntime-dml.jar`, which is provided as part of the repo. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build from source.

### Build task layout

Use `Windows` in commands when you want the DirectML build. The old `Dml` task names still work as aliases, but the readable names are preferred.

| What you want | Command |
|---------------|---------|
| Fabric + NeoForge, Windows/DirectML | `./gradlew buildWindows` |
| Fabric + NeoForge, CUDA | `./gradlew buildCuda` |
| Fabric + NeoForge, CPU/CoreML | `./gradlew buildCpu` |
| Every loader and every variant | `./gradlew buildRelease` |
| Every loader/variant, copied into `build/release/` | `./gradlew collectReleaseJars` |
| Fabric only, Windows/DirectML | `./gradlew buildFabricWindows` |
| Fabric only, CUDA | `./gradlew buildFabricCuda` |
| Fabric only, CPU/CoreML | `./gradlew buildFabricCpu` |
| Every Fabric variant | `./gradlew buildFabricAll` |
| NeoForge only, Windows/DirectML | `./gradlew buildNeoForgeWindows` |
| NeoForge only, CUDA | `./gradlew buildNeoForgeCuda` |
| NeoForge only, CPU/CoreML | `./gradlew buildNeoForgeCpu` |
| Every NeoForge variant | `./gradlew buildNeoForgeAll` |

Equivalent direct Gradle property calls still work:

```
./gradlew build -PuseDml=true
./gradlew build -PuseCuda=true
./gradlew build -PuseCpu=true
./gradlew :fabric:build -PuseDml=true
./gradlew :neoforge:build -PuseDml=true
```

Compatibility aliases kept for existing scripts:

```
./gradlew buildDml
./gradlew buildFabricDml
./gradlew buildNeoForgeDml
./gradlew buildAll
```

Final jars are written under each loader module:

```
fabric/build/libs/
neoforge/build/libs/
```

For release packaging, use `./gradlew collectReleaseJars`. It copies the distributable jars into:

```
build/release/fabric/
build/release/neoforge/
```

### Building onnxruntime with DirectML

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain — install *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both VS toolchains up to date. Full details at the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and place it in `libs/` in this repository.

### Native runtime startup failures

If world creation reports `Terrain-diffusion models did not load`, inspect the
first `Failed to load terrain-diffusion models` entry in `logs/latest.log`.
An `UnsatisfiedLinkError` for `onnxruntime.dll` happens before model inference;
changing biome packs or `inference.device` cannot fix that native library load.
Failed model loading is retained for the process lifetime to preserve the original
cause and prevent repeated background retries. Restart Minecraft after fixing it.

The Windows build uses the local DirectML runtime, while the CUDA build uses
ONNX Runtime GPU 1.20.0. On NVIDIA systems with CUDA 12.x and cuDNN 9.x on `PATH`,
build `./gradlew :neoforge:build -PuseCuda=true` and replace the Windows mod JAR
with the CUDA JAR. Keep only one Terrain Diffusion mod JAR installed. See the
[CUDA runtime requirements](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html#requirements).
The CPU build (`-PuseCpu=true`) is also available for diagnosis.

Before launching Minecraft, test the exact runtime JAR with Java 21:

```text
java -cp "path/to/onnxruntime_gpu-1.20.0.jar" tools/onnx/RuntimeSmokeTest.java CUDA
```

Use `CPU` or `DML` with the corresponding runtime JAR. This checks native loading,
session creation and a small addition inference; it does not validate full terrain
models, world generation, or in-game performance.

### Structure search during world creation

Before spawn chunks are prepared, Minecraft computes concentric structure rings.
The vanilla preferred-biome search samples a 57-by-57 quart grid per stronghold
candidate. With Terrain Diffusion, repeatedly querying these points can fetch
large terrain tiles and contend with concurrent searches for the shared cache.

Terrain Diffusion skips only this ring-relocation call, returning no preferred
biome so Minecraft retains the original ring position. It submits no coarse or
exact terrain work for relocation. Merely bounding each search to a few tiles was
insufficient: many distant structure candidates still required minutes of AI work.

Lithostitched 1.8.1 wraps the source in `InjectorBiomeSource`. The ring-only policy
also checks its public `rootDelegate()` accessor: a wrapped Terrain Diffusion
source skips relocation, while wrapped ordinary sources retain normal searches.
Do not replace the wrapper for biome generation or use a global world flag here.
An unavailable accessor falls back to normal relocation with a warning. Skipping
relocation can change structure positions or biome-valid candidate counts; it does
not bypass the subsequent structure biome-validity checks.

Actual terrain, surface and cave biome generation remain unchanged. A structure's
separate biome-validity rules still apply. Other worlds retain their normal ring
search. Ordinary biome searches, including `/locate biome`, remain available and
can still be expensive because they query AI terrain on demand.

The upstream Terrain Diffusion source disables both horizontal and 3D biome search
globally. Do not restore those global null-return overrides just to fix startup;
the narrow ring callsite avoids also disabling player-requested biome searches.
The experimental coarse-ranked helpers remain unused by ring placement.

Run `python tools/test_ring_relocation.py --java-home "path/to/jdk-21"` to check
that the actual redirect submits no terrain work for Terrain Diffusion and forwards
other biome sources unchanged. This model-free test uses engine API stubs; build
against Minecraft and verify a fresh world for actual loader and timing behavior.

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is extremely simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules. This is the most immediate way to improve the quality of the terrain and is relatively easy, but takes time to get realistic. The entire biome classifier is [only 250 lines](https://github.com/xandergos/terrain-diffusion-mc/blob/master/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java).

The terrain diversity far outpaces the biome diversity and there's a real opportunity to close that gap. I'm hoping someone goes crazy with it.

## River generation modes (1.21.1)

Normal worlds use the separately installed **Streams Reflowing** mod for its
river generation and flowing-water behavior. This repository supplies biome
compatibility, a structure shield, and a versioned NeoForge integration that defers
speculative watershed prefetch on TD terrain. Streams retains its native river
routing, confluences, outlets, vegetation and water flow. New NeoForge TD worlds
with audited Streams 2.13.1/2.13.5/2.13.8 use a persisted **natural river profile v10**:
fine tributaries feed larger drainage-scaled rivers across a larger routing area,
while short independent coastal systems and unprotected short source twigs are
reduced. V4 additionally admits long tributaries with source spacing, preserves
complete protected headwater paths, and extends the narrow part of the width
progression. V6 fits narrow tributary elevations closer to the original terrain
before caching the connected graph, and retains existing bank soil instead of
building raised dirt crests. Grade transitions remain downstream-compatible;
wide channels, lake connections and plate boundaries constrain the correction.
V7 keeps background widening slow and concentrates gradual width growth after
tributary joins, weighted by their native width contribution. Water-adjacent mud
remains; distant native mud painting leaves the existing soil instead. This is
a visual width heuristic, not a discharge simulation. Native currents remain enabled.
V8 raises native source accumulation from a minimum of 6 to 24 before routing;
other V7 configuration remains unchanged. This reduces weak channels without
removing protected cross-plate connections. Existing V1–V7 worlds retain their
saved profiles, including their original source density and cache namespaces.
For narrow TD rivers, set `spillOverWater = false` in Streams' common config;
keep `vanillaWaterFlow` and `flowVanillaRivers` enabled. This restores vanilla
water spreading while retaining downstream currents. Already flooded saved
water can remain supported and needs separate repair in a world copy.
Terrain-confined bends, shallower asymmetric beds and restrained bars
remain. A TD-owned module conservatively smooths
gentle grades before Streams caches the graph. Existing worlds retain their
previous profile; installing the JAR does not rewrite saved rivers. See the
[Streams integration setup and validation](tools/streams_probe/README.md).
The Streams mod is installed separately.

V9 keeps V8 routing, narrows dry sediment aprons according to planned channel
width, and checks nearby lakes per column instead of vetoing an entire chunk.
Bank eligibility uses planned water rather than whichever neighboring chunks have
finished carving. With Streams 2.13.8 and Terrain Slabs 3.1.2, a guarded feature
hook prevents automatic slabs from replacing river water or its containing banks.
Generated waterlogged bottom slabs can look like dry, gridded riverbeds; inspect
block states before diagnosing missing water. Older saved profiles and existing
blocks are preserved; installing this JAR does not repair already generated strips.
Low lake rims receive a bounded, world-coordinate taper outside native wetting:
at most two soil blocks are cut, without lowering the containing bank below local
planned water. Run this lake-only finishing even when the native carver returns
false: a dry chunk can own the shore beside a lake in its neighbor. Skipping that
case reproduced a straight cut at a 16-block boundary. Only generated dry bottom
slabs may be cleared as cover; structure protection and higher cliffs are retained.

V10 retains V9 river routing and channel water levels. Streams 2.13.8's direct grass
recap and TD containment caps use the original land climate, not a river biome
overlay. Dry river-biome fringe cells and arid gravel shores also receive the
appropriate mineral surface. A cave can remove support beneath the original soil:
skipping falling-sand placement then leaves a green desert roof. V10 stabilizes
only the lowest existing soil layer with rock, leaving the cavity untouched.
Check generation-stage write traces for these failures; the final biome name
alone cannot identify which mod last wrote a surface. Existing V9 worlds stay V9.
On audited Terrain Slabs 3.1.2, native generated dry grass/dirt slabs on arid
mineral surfaces inherit sand or gravel. Unsupported caps use non-falling rock
slabs, preserving their half and fluid properties; manual, wet and cave slabs are
excluded. Validate final block states as well as bank code: native slab decoration
can change a surface height after its material changes.

On Streams 2.13.8, V10 also resolves nested natural lake masks to the lowest
waterline in their containment group when assembling both fresh and cached graphs.
One saved lake had a 1,543-cell mask at Y106 entirely inside a 1,764-cell mask at
Y107, producing raised source-water shelves around its edge. Masks, dry islands
and list order are preserved; separate, partially overlapping, differently gridded
and excavated lakes are excluded. V9 and older profiles retain their lake levels.
The slab guard now permits native waterlogged ocean slabs in V10 while retaining
the plan-defined river-mouth and bank exclusions and inland-water protection.
Generated bottom slabs in V10 require a connected terrain step, so decorations
and isolated one-block sand bumps/pits do not seed scattered slab patches.
Submerged tall ocean steps can receive waterlogged slabs in source water at the exposed seabed;
dry cliffs, inland water and covered underwater caves retain their exclusions.
Terralith 2.6.2 omits `deep_warm_ocean` from its Overworld tag, so a conditional
NeoForge modifier attaches Terrain Slabs 3.1.2's existing feature to that biome.
Use the separate `terrain_slabs_ocean` placed feature with constant count 1:
sharing the native placement creates a `Feature order cycle found` error with
Apotheosis 8.9.0: its six dungeon features follow native slabs in TD boreal forest
but precede the extra ocean slab pass. Minimal fixtures without Apotheosis miss it.
An ID-only alias is insufficient because Minecraft compares placed-feature values.
The native `--terrain-slab-audit` Streams suite checks controlled contours and
actual loaded biome features, requires the old cycle to reproduce when Apotheosis
is loaded, checks the modified biome graph and generates a fresh FULL chunk.
It also requires exactly one slab pass in all ten loaded ocean biomes.
It does not establish visual acceptance.

Landmarks 1.2.0's nine placed features use conditional NeoForge overrides with
rarity-filter denominators multiplied by three. Expected placement attempts are
one third of their original frequency, with other placement filters preserved.
The same native audit checks all nine loaded values and optional-mod absence.
These fixes affect new chunks; they do not rewrite existing saved blocks. Use the
river planner regressions and the native `RiverScreenshotAudit` to check lake
selectors, final source-water heights and actual Terrain Slabs admission/placement.

New TD Overworlds on Streams 2.13.8 select its native exact host-height sampler
without running its two generic terrain calibration passes. Those passes can
otherwise trigger hundreds of remote AI terrain tiles before the first chunk.
The world-local `data/terrain_diffusion_streams_exact_v1.txt` marker preserves
the choice on restart. Existing unmarked worlds keep their original sampler;
do not delete or transplant the marker. New river layouts may differ from the
previous approximate sampler, while TD's terrain and ecology rules stay unchanged.
Demanded terrain and river-network generation still take time.

The scale-1 same-seed four-chunk regression completed in 72.4 seconds versus
598.7 seconds before this change, with 77 versus 442 requested terrain tiles.
All 1,024 sampled original ground heights and ecology biome IDs matched.
These are isolated server-probe timings, not full modpack client-entry timings.
See the Streams probe guide for startup, persistence and river validation.

World shutdown closes terrain requests after saving, discards queued calculations,
and waits for running ONNX inference to finish. Late Streams requests remain bound
to the world that created them. Client exit also drains preview work and model
loading before JVM teardown; dedicated-server stop waits for model loading too.
Quitting can therefore briefly wait for an in-progress calculation instead of
interrupting native inference. Streams may log cancellation warnings for background
prefetch abandoned during shutdown.

New worlds save `fast_ground_sampling=true` in
`data/terrain_diffusion_world_settings.dat`. Height-only requests can then skip
biome/ecology classification and decode just the required elevation support. The
original full reference bounds still determine interpolation. Ground-only tiles
have a separate cache and never satisfy a request for biome or ecology data.
Existing settings without this field retain the complete classified-tile path;
the saved choice is reused when reopening a world. This avoids changing the
inference request order in existing saves. Decoder inference remains scalar.

Biome queries reuse complete classified tiles by default. An experimental 16x16
sampling path (`-Dtd.inference.legacySampling=false`) preserves numeric results but
can repeatedly reconstruct large reference planes when Streams interleaves regions;
it is unsuitable as the production default. Foreground requests can overtake queued
background sampling without crossing world-state barriers, and background work
retains bounded fairness.

For GPUs with sufficient free VRAM, `inference.offload_models=false` keeps models
resident and avoids repeated ONNX session creation. The default remains `true` for
memory-constrained clients. Measure with the actual shader pack before assuming server
probe VRAM usage represents client usage.

On the audited Incendium 5.4.x function bodies, TD defers origin helper initialization
until its chunk is ready, keeping server ticks responsive during generation. The helper
and persistent origin ticket are still created once; dependent helper features become
available after this brief initialization. Unknown function bodies retain vanilla
execution. `-Dtd.incendium.deferOrigin=false` disables this compatibility behavior.

The older experimental [finite river preview](tools/river_planner/README.md) is a separate
Terrain Diffusion river planner and carver. It requires Streams river/lake
generation and world structures disabled. It uses Minecraft water and does not
port Streams' custom flowing-water system. Its persisted terrain cache belongs
to the preview world and must accompany any copy of that world.

## Terralith compatibility (1.21.1)

Minecraft 1.21.1 reads `noise_router.initial_density_without_jaggedness` for its
preliminary surface. It must use the TD density function. The newer
`preliminary_surface_level` field is ignored on this version; leaving the former
at zero prevents Terralith's `above_preliminary_surface` rules from applying
(for example, gravel beaches become fallback grass). The compatibility generator
checks this contract before writing the pack.

Legacy Terralith land groups use globally addressed, jittered 128-block regions;
water remains local and beaches require smooth ocean terrain within 24 blocks.
The diffusion elevation and vanilla biome classification remain local. A TD-only
carver guard retains the upper eight solid blocks of lowlands from sea level to
eight blocks above it, preventing flooded cave openings from peppering flat
coasts. Deeper caves, higher cave entrances, and native Streams features remain
enabled. These generation changes require fresh chunks; use a new same-seed
world for comparison rather than mixing old and new generation.

New compatibility-preset worlds enable `natural_biomes: true`. Their land uses
19 ecological biomes controlled by a continuous TD climate field, with a filter
radius of 512 model pixels (1024 game blocks at scale two, rounded up to coarse
cells). All nineteen ecological biomes
share grass/foliage tints and the ordered ecological vegetation list. Tree density, compatible species
weights and broad soil patches depend on continuous climate controls, not biome
labels. Saved biome IDs, F3 biome names, feature ownership and biome locate results
remain exact. The final TD soil pass feathers surface materials by up to two blocks
across boundaries between natural TD land ecology biomes; it never borrows aquatic,
cave or non-TD climate, and keeps the target column's elevation and slope. Terrain
heights and native Streams generation stay in their existing generation paths.

Ordinary soil-covered hills retain forest potential: the slope multiplier now
tapers from rise/run 0.35 to 1.80 (about 19 to 61 degrees), retaining more than
70% at 0.85/about 40 degrees. Actual substrate and clearance still decide whether
a candidate tree survives; this does not plant trees on bare rock automatically.

Groundcover now uses one column-based climate pass after trees, replacing the
inherited biome-specific vegetation stage. Grass, ferns, flowers, berry bushes,
azalea, moss, mushrooms, dry shrubs, cactus and bamboo have overlapping climate
weights and spatial density patches. Placement respects dry air, substrate and
plant survival; grass and ferns cannot use sand even if another mod relaxes
survival. Coast, river and alpine terrain biomes remain outside this pass.
Cold tree cover, ground materials and snow follow the same regional cold state.
Altitude only supplies a bounded local temperature correction; it no longer
adds an independent snowline. Moisture separates snowy forest, woodland and
tundra, while terrain relief distinguishes exposed alpine ground.
Temperate woodland gains sparse coarse-dirt floor patches, and conifer podzol
uses the finer soil field. Grass/leaf tint remains a shared palette rather than
a client-synchronized climate gradient. Other mods can still inject decorations
through biome modifiers; those additions are not governed by this climate pass.
The old biome-specific stage also included incidental vines, melons, pumpkins
and sugar cane; this pass does not reproduce those placements.

`EcotoneContinuityTest` traverses all fifteen labels and checks expected plant
rates across moisture/temperature boundaries, cold treeline taper and spatial
continuity. These checks and the offline atlas do not replace a fresh-chunk
in-game placement/appearance check. Existing chunks are not rewritten.
Runtime probes with Streams 2.13.8 require NeoForge 21.1.173 or later (the
isolated ecology probe uses 21.1.249). The normal 21.1.80 compile target can build
successfully while that mod combination refuses to start; inspect the server
receipts and logs rather than relying on Gradle's successful task exit alone.

Streams 2.15.2 has separate audited bank-material and dry-bank hooks. It keeps
the native ring selection, patterns, biome memo, depth fill and random draws;
TD applies its existing shore and surface policies at the new call sites.
Configuration copies preserve `terrainFit`, `lakeSampleSpacing`, `legacyShaping`
and `adaptiveGridRead`. Exact TD terrain sampling remains tied to the saved
world marker. The 2.13.8 hooks remain available, and unknown versions are not
automatically admitted by these version gates.

For a 2.15.2 update, run `tools/streams_probe/audit_streams_compat.py --jar
2.15.2=path/to/Streams.jar --output-dir build/streams-audit`, then the startup,
sampler-priority and natural-adapter checks against that JAR. Prepare a fresh
fixture with `tools/streams_probe/prepare.py --streams-version 2.15.2` and use
`tools/streams_probe/run_suite.py NAME --gradle-user-home .gradle --topology-audit`.
The suite requires a fresh passing topology receipt when that option is selected.
Keep cold and restart evidence separately: saved chunks and river graphs make a
restart a different workload. Streams 2.15.2 imports its old common TOML into
`config/streamsreflowing.properties`; the old TOML becomes `.imported`.
Its stall dump distinguishes sampler threads waiting on TD inference from CPU
work. A large sampler pool does not imply parallel GPU inference.
Capture provider counters before shutdown: the river lifecycle reduces the tile
cache from its planning target to the normal target, so final eviction counts
can describe cleanup rather than repeated generation. The probe's `--inference-metrics`
option records ONNX timing and provider counters before cleanup; the suite requires
a metrics receipt when this option is requested. On this Windows/JDK 21 host, JFR
dumps at the non-ASCII workspace path were empty even in a standalone JVM, so use
the built-in timing counters rather than treating an empty recording as evidence.

Model window overlap-add decodes coordinates once per contiguous row instead of
once per float. It preserves row-major addition order, including overlapping
source/destination arrays. `python tools/test_float_tensor.py` compares the
production implementation with scalar coordinate enumeration across offsets,
empty regions, dimensions and aliases. Add `--benchmark` for the isolated tensor
operation; those times are not chunk-generation times. Compare native cold runs
separately, preserving the same fixture inputs and requested checks.

Ecological soil and tree groves use world-seeded, warped 128-model-pixel fields
(256 blocks at scale two). Soil combines this with cached physical elevation,
slow horizontal drift and a small detail field, so patches follow relief instead
of independent small islands. Relative elevation and slope also adjust moisture
retention within bounded limits; regional biome labels remain stable. This is an
independent implementation inspired by the surface-mixing idea described by
[Pufferfish's Biome Dither](https://www.curseforge.com/minecraft/mc-mods/puffish-biome-dither).
Desert margins blend sand, coarse dirt and grass across moisture 0.14 to 0.38; the biome-label cutoff
at 0.30 no longer switches the entire surface at once. Dry grassland retains a
grass matrix, deep warm deserts retain sand, and moist conifer forests can have
podzol. Sparse woodland forms groves and openings, while dense forest keeps a
higher background tree probability. These are placement controls, not guaranteed
canopy coverage: tree survival and other mods still affect the final result.
Dry woodland spans moisture 0.52–0.65 instead of the former narrow 0.635–0.65
interval; changing its label does not jump the continuous tree-cover control.

The NeoForge beach feature attachment lets the surface pass repair dry sandy
shores even in pure beach chunks; rocky, snowy and humid shores retain their
substrates. Streams 2.13.8 bank correction wraps only its exposed top write,
using local water observations and the same climate/material field. Depth fills
and water geometry are unchanged; unsupported falling material becomes coarse dirt.

Generated seagrass has separate vanilla and audited Streams 2.13.8 placement
guards in natural TD worlds. Submerged patches vary with seed, depth, substrate
and freshwater/marine context; exposed beach receives no seagrass. Tall pairs
are accepted or rejected together. This does not remove saved or player-placed
plants and does not change coral, kelp, or ordinary Greeneries grass.

Ecology IDs now include `snowy_boreal_forest`, `temperate_rainforest`,
`montane_forest` and `steppe` under `terrain-diffusion-mc:ecology/`. These are real
regional biome labels, available to `/locate biome`, not aliases. Elevation,
temperature and moisture select the subdivisions; the existing continuous cover
and surface fields still control transitions. Existing saved chunks retain their
biomes, so the new identities appear in newly generated terrain.

V7 wide river banks no longer add a dry block above the neighboring water for
containment. Protected dry soil on the outer slope can be lowered by at most two
blocks, retaining solid soil at the highest nearby planned water Y. Soil over
natural overworld stone is valid support; air, water and non-soil excavation are
still rejected. Steeper reaches retain additional bank relief. The
apron and material transition grow with river width and use coherent variation;
source, junction, structure and non-soil guards remain. Saved bank blocks are not
rewritten. Earlier river profiles retain their previous bank geometry.

With Greeneries on NeoForge, the 15 ecology biomes use their original source
biomes' non-tree vegetation, including flowers, and Greeneries' native providers.
The former TD grass/fern substitution pool and simultaneous climate groundcover
pass are removed. Base vegetation runs in BEFORE_EVERYTHING, before the mod's
additional ADD-phase patches; standard climate tags let Greeneries select its
own common/rare species. Trees remain TD-controlled. Without Greeneries, TD's
climate groundcover remains active.

The seven Greeneries grass/fern placements and nine restored vanilla groundcover
placements use `MOTION_BLOCKING_NO_LEAVES` to reach soil below a canopy. Native
providers, patch tries, counts, rarity and biome filters are unchanged. The seven
Greeneries resource overrides apply in every biome using those features; the
restored vanilla copies and soil thinning remain TD-specific.

Native grass/fern attempts, including five overridden vanilla worldgen providers,
are accepted at 100% on grassy substrates, 50% on ordinary soil and 10% on barren
soil. Bonemeal and original vanilla flower providers are not thinned. These are attempt probabilities, not
percentages of the ground guaranteed to contain grass; vanilla survival and patch
placement still apply. Sand, red sand, their slabs and waterlogged supports are
excluded. Native configured providers remain registry references. To avoid feature
order cycles, placed-feature definitions are copied from vanilla 1.21.1 under
`terrain-diffusion-mc:greeneries_native/`; packs replacing those placement definitions
must update the copies too. Regenerate with `generate_biomes.py --vanilla <resources.jar>
--neoforge-output neoforge/src/main/resources`.

Substrate categories are datapack-editable block tags under `terrain-diffusion-mc`:
`greeneries_grass_soils`, `greeneries_ordinary_soils`, and `greeneries_barren_soils`.
Grass/moss slabs and Immersive Weathering grassy soils use 100%; dirt/rooted
dirt/podzol/mud slabs and loam/silt/earthen clay/mulch use 50%; coarse dirt/slabs
and bare sandy dirt/permafrost use 10%. Grassy variants take priority over other
soil categories. Optional mod entries do not require those mods to be installed.

Terrain Slabs 3.1.2 already provides BushBlock survival and bottom-slab rendering
offsets when `enableVegetationOnSlabs` is enabled. TD accepts its tagged soils
before the normal survival check; slabs are not globally added to `minecraft:dirt`.
Tags alone would not bypass TD's former full-block-only support check. The native
slab mod remains responsible for partial-block attachment and neighbor updates.

Natural TD worlds share one regional cold state across biome identity, canopy,
soil, understory, and snow. The existing regional temperature receives a smooth
local lapse correction bounded to two degrees; absolute altitude cannot independently
create a snowy island. Five cold identities cover snowy boreal forest, snowy boreal
woodland, snowy tundra, alpine tundra, and glacial highlands. Moisture separates
forest, woodland and tundra; cold plus terrain relief produces treeless alpine
ground, with glacial identity reserved for severe cold. Existing numeric IDs 1–15
remain stable and the four additional identities use 16–19.

Cold vegetation and substrate taper before the core snowfield. Snow coverage
changes through the central cold transition with two-block detail, sharing each
column decision between crown and floor. Aspect, slope and concavity from the
existing smooth-height halo only perturb this transition; they do not create
another broad noise mask. Cold terrain uses exposed gravel/stone and sparse low
vegetation; Greeneries and Lush retain their native warm-climate behavior.
Cold surface boundaries retain their own column climate instead of borrowing
a warm soil donor. Floor scans pass through branch tiers (at most 96 blocks).

Vanilla snow/freezing, Snow Under Trees 1.5.1, Simple Snowy Fix 2.2.1 and Snow!
Real Magic! 12.2.3 weather use the shared eligibility while retaining their light,
fluid and support guards. Weather uses a cache-only lookup: a miss preserves
existing snow/ice eligibility and never starts terrain inference or paints an
entire biome from its registry temperature. Generation still places one snow
layer and preserves SRM plant conversion. Existing chunks are not regenerated;
compare newly generated terrain for biome, canopy and soil changes.
Generation primes the current chunk's reference climate before the freezing pass,
including experimental partial sampling; runtime weather stays cache-only.
Client rain/snow particles still use Minecraft's standard biome precipitation type.

`/locate biome` retains Minecraft's candidate order, predicate and cave-height
checks while reusing the current terrain tile within one search. Disable this
reuse with `-Dtd.locate.biomeCache=false` for comparison. It reduces repeated
lookups, not the number of distinct AI tiles required for a cold search.
`/locate structure` retains its search order and exact structure-start checks.
With Async Locator installed, TD defers speculative terrain work while its shared
structure-check monitor is locked, then waits for `STRUCTURE_STARTS` off the server
thread. A unique chunk ticket and one-at-a-time admission keep verification alive
until completion; shutdown closes admission before the terrain provider drains.
This removes two server-blocking paths, but cold AI terrain and disk scans can
still take time. Tags such as `/locate structure #minecraft:village` are supported
by the ordinary command.

For ungenerated terrain, permission-level 2 users can use a separate bounded search:

```text
/td-locate biome terrain-diffusion-mc:ecology/temperate_forest 8192
/td-locate structure minecraft:ruined_portal 8192
/td-locate biome #minecraft:is_forest 32768 300
/td-locate structure #minecraft:village 65536 600
/td-locate status
/td-locate cancel
```

The optional radius defaults to 32768 blocks (16–131072), measured on each horizontal
axis. The following optional time is seconds (1–1800, default 300). IDs and `#tags`
have registry completion; a tag accepts any member and reports the concrete matched
ID. Missing/empty tags are rejected. Nearby patches and progressively larger rings
retain local coverage as radius grows. Each batch of at most nine climate patches
is followed by detailed verification, so distant coarse work does not delay all
nearby verification. Only exact biome-source quart samples
or valid generated `STRUCTURE_STARTS` are reported. The result is not necessarily
the nearest. Biome samples can differ from later saved-chunk/F3 boundaries after
river processing. Concentric-ring and custom structure placements require vanilla
`/locate`; mixed tags explicitly report that these placements are skipped.

One search runs per server, with the chosen admission/verification time budget,
up to 81 climate patches, 256 detailed biome tiles or 128 structure-start requests.
Detailed work is shared across remaining batches so nearby misses do not consume
the entire work budget before outer rings are reached. Time can still expire first.
Structure searches enumerate native placement regions and share that calculation
between variants such as the five vanilla villages. Planning is limited to 32
regions per server task and 8192 per search, distributed across remaining patches
and placement groups. Additional angular samples fill gaps in the outer rings
within the existing 81-patch limit; this is still a sampled search, not an exhaustive
scan of every chunk in the radius. Range and time limits are unchanged.
An exhausted search does not prove absence. Already running native inference or
chunk requests must drain safely and can exceed that time; cancellation suppresses
results immediately and prevents another search until the worker drains. Structure
generation may internally need additional terrain. Background ranking reduces
unnecessary detailed generation, but shares CPU/GPU resources with gameplay and
cannot promise zero lag or a fixed speedup. Ordinary `/locate` keeps its usual
search semantics, with the Async Locator compatibility fix described above.

On NeoForge, the fifteen ecology biomes remove Immersive Weathering's raw-generation
`earthen_clay`, `sandy_dirt`, `loam` and `permafrost` placements. Those ore-style
soil patches replaced grass before TD's soil pass and escaped its vanilla soil
filter, producing competing patterns. Removal runs in NeoForge's REMOVE phase,
after ADD, with optional feature-tag entries for installations without IW.
Vanilla biome tags, other biomes, silt/aquifers and decorations remain intact.

NeoForge also checks the support of grass/fern candidates at `SimpleBlockFeature`
placement in TD ecology biomes. Its normal survival test remains in effect, but
sand (including Terrain Slabs' sand slab) is rejected even when another mod
permits survival there. The candidate
set includes vanilla grass/ferns and the optional Greeneries grass/fern tags;
reeds, cattails, dead bushes and cacti are not in this set. This is a generation
guard, not a cleanup of plants already stored in chunks. A saved-world audit
found both vanilla and Greeneries plants on sand; Greeneries' replacement
feature definitions alone do not establish which mod allowed their survival.

Towns and Towers 1.13.11 replaces all five vanilla village biome tags, which can
remove TD entries loaded earlier. NeoForge includes an additive village compatibility
pack at higher priority to restore the existing TD mappings, including legacy
`taiga_sparse`. It is required while T&T is installed and remains discoverable if
T&T is later removed. A deliberately higher-priority datapack can still replace
these tags. This does not change village spacing, weights, terrain checks, or
already generated structure starts. Check effective `Structure.biomes()` as well
as tag members when validating compatibility; a populated NeoForge modified-settings
object alone did not affect that getter in the tested mod stack.

In the earlier Structurify audit, version 2.0.42 with an empty per-structure `structures` list did not apply its
flatness/overlap filters to villages. Its global placement multiplier still applies:
1.4 turns a 34-chunk spacing into 47 chunks. This makes villages sparser but does
not explain missing TD entries in the allowed-biome tags.

### TD structure placement

TD owns structure reservations in TD-generated worlds. Independent structure starts
use horizontal bounding envelopes separated by at least 100 blocks, including
structures from the same namespace or structure set. A village's component houses
remain one assembly; they do not each require a 100-block gap. Pairwise rules use
the larger requested gap. Seed-based reservation priority is deterministic for fixed
candidate descriptors. Audited generators first assemble and validate a candidate without
loading neighboring chunks. Candidates that fail biome, density, footprint or terrain
checks do not reserve empty space. Arbitration uses the surviving assembly's actual
bounds, with a separate check that terrain influence areas do not overlap. Unaudited
custom generators retain conservative reservations.
An atomic final check also reserves each accepted start for its generator state,
including its terrain influence, until the server closes. This protects the gap even
if a mod's native assembly changes after a preflight cache eviction or concurrent
generation. Native layouts can differ between fresh runs; identical village interiors
or winner sets are not guaranteed when native descriptors differ. A failure after final
acceptance can leave a conservative in-memory reservation until restart.
Confirmed viable surface-step or explicitly ground-projected structures take priority
over underground candidates, so frequent dungeon grids do not consume scarce village
sites. Within each group, seed-based priority remains deterministic. This priority
does not opt a structure into terrain shaping or relax any distance rule.

`config/terrain-diffusion-structures.json` supplies defaults, also editable through
NeoForge's Mods > Terrain Diffusion > Config screen. Resolution is per field:
global, then namespace, then exact structure ID. Empty UI fields inherit. Example:

```json
{
  "version": 1,
  "enabled": true,
  "global": { "minGapBlocks": 100 },
  "namespaces": { "minecraft": { "minGapBlocks": 140 } },
  "structures": { "minecraft:village_plains": { "minGapBlocks": 180 } }
}
```

Namespaces are registry IDs, not display names: Epic Villages replaces Minecraft
templates, so its vanilla villages are configured under `minecraft:village_*`.

The **Village distribution** sub-screen edits `villageDistribution` in the same
file: `enabled`, `targetMeanNearestNeighborBlocks` (default 500),
`basicSharePercent` (default 70), and improved/legacy placement (`algorithmVersion`
2/1; new configurations default to 2). This opt-in
mode combines living vanilla-jigsaw villages into one seed-based candidate grid;
native locate uses that same grid. Original vanilla templates are isolated under
`terrain-diffusion-mc:basic_village_*` so Epic's replacement templates remain in
the distinctive group. Originals plus MMV form the basic group; Epic, T&T and
other living villages form the distinctive group. Alias biome tags additionally
admit matching TD woodlands without changing Epic's native biome tags.
The distance setting targets nearest-neighbor bounding-box centers, not a hard
500-block minimum. Terrain, biome and gap rejection still apply; actual accepted
spacing and group proportions must be measured, not inferred from grid spacing
or selection weights. Village settings refresh on world reload; aliases never
add a second native population when this mode is disabled.

Version 2 searches nine seeded candidate origins per common cell instead of four.
It retains one selected village per cell and all terrain, footprint and gap checks;
the extra origins look for viable ground rather than lowering placement standards.
Explicit version-1 configurations retain their original grid. Streams Reflowing's
`StructurePlatesPending` signal must escape speculative preflight: its chunk scheduler
waits for river data and retries. Treating that signal as an absent structure caches
a false rejection and can empty whole areas on a cold world. The optional
`run_preflight_tests.py <Streams.jar>` regression checks the real signal and cache retry.

Terrain rules are pinned at first TD world load in
`world/data/td-structure-placement.json`. Gap and density (`minGapBlocks`,
`spacingMultiplier`) changes from the GUI/default file apply on the next world load
to future generation, including existing worlds. Other rules, including enabled,
reservation radius and earthwork limits, remain pinned so partially generated starts
keep reproducible terrain. Multiplayer uses the server's defaults. Do not edit the
terrain pin in a populated world. Increasing a gap does not move previously placed
structures farther apart. A mean measured from accepted village centers does not
measure exploration coverage: clustered villages can satisfy it while leaving
large empty areas. Density audits must also report generated land area, viable
candidate cells and distances from land samples to villages; partial structure
starts must not be counted as fully generated villages.
At startup TD reads saved structure bounds from region files into a spatial index,
including starts from removed mods, without loading or changing those chunks. New
reservations also avoid these saved structures. Existing overlaps and floating
buildings are not moved or repaired. A corrupt/unreadable region stops indexing
rather than silently dropping its distance protection; large worlds can take longer
to open because saved chunk NBT must be scanned.

Defaults allow a maximum 112-block radius around the start chunk center, try up to nine
local jigsaw origins within that chunk (maximum offset seven blocks), and permit
at most two blocks of cutting and three blocks of filling. Foundations blend over
12 blocks; affected terrain and village paths are checked for at most one block of
rise over two horizontal blocks, with integer stair steps. Unsuitable candidates
are rejected before placement. `reservationRadiusBlocks` can be increased for large
native structures (up to 512); surface shaping must remain inside the existing
eight-chunk generation dependency. For audited candidates the radius limits accepted
extent and bounds the neighbor search; small buildings no longer occupy that entire
radius. Conservative custom candidates still reserve the full envelope.
New accepted starts store terrain policy 3: grade intervals use the same integer
1:2 rounding as the final slope check, avoiding false rejection of adjacent
foundations one block apart in height. Saved policies 1/2 retain their original
plans. Cut/fill, support, and one-/two-column slope checks remain mandatory.
As in policy 2, audited template contact columns
replace rectangular roof/air footprints, and terrace contours have rounded corners
with gentle world-coordinate variation. Every edited contour remains inside the
blend radius with a two-column grade-validation halo. Saved policy-one starts
retain their original rectangular terrain plan when remaining chunks generate.
Native moss rules are audited by their complete codec semantics: replacing only
cobblestone with mossy cobblestone preserves a template's contact mask. Merely
trusting a processor-list resource ID would allow datapack overrides to bypass
geometry checks. The original 4x3x4 well-bottom piece, when directly below and fully
covered by a rigid village parent, is an underground shaft rather than another
surface foundation. It retains voxel collision and external reservation checks.
Both refinements apply only to new policy-two terrain plans; processed policy-one
foundations keep their conservative boxes. `run_moss_tests.py` checks original
vanilla rules and rejects AIR/input/NBT mutations.
Exact native jigsaw candidates can try deterministic alternate layouts within the
same nine-attempt budget. The first attempt keeps the native layout seed; later
attempts derive only the assembly RNG, leaving terrain and the shared population
grid on the original world seed. Custom structure implementations retain native
seed semantics.
`spacingMultiplier` thins candidates approximately by its square; it does not
reproduce Structurify's placement-grid spacing changes. The default `1.0` adds no
thinning; `1.4` retains about 51% of candidates before other checks. Do not migrate a
Structurify spacing multiplier blindly when normal exploration density is desired.

Distance arbitration covers registered structures using standard random-spread or
concentric-ring placement, plus the inspected Cataclysm random-spread and YUNG
desert temple, jungle temple and stronghold subclasses. Raw reservations use their
seed-based grid origins; native biome, ring and exclusion checks still gate actual
generation. Preflight is limited to audited vanilla structure implementations and
vanilla placement predicates whose exclusion targets are also audited. The inspected
YUNG API 5.1.9 jigsaw assembler and Better Mineshafts 5.1.1 assembler are admitted only
when their audited class resources match the expected hashes; updates fall back to
conservative handling. Strongholds and other custom generators retain conservative
handling; a vanilla jigsaw structure can still use another mod's templates.
Do not invoke YUNG's biome-search predicates while
enumerating competing reservations: that would cause distant terrain generation
during arbitration.
Unknown placement implementations are rejected. AUTO
terrain shaping requires explicit ground projection, zero height offset, and
standard jigsaw pieces; underground, aquatic, airborne and ambiguous custom
structures retain their native terrain behavior. `surfaceMode` can be `AUTO`,
`PRESERVE`, or explicitly `SURFACE` for a known suitable structure. Custom features
that write buildings directly bypass the structure pipeline and need their own
adapter. Exact rigid legacy templates without custom processors use occupied-block
collision checks across all palettes, so overlapping empty template space does not
cancel a whole village. Unknown pieces and processors retain conservative bounding
checks. Different-height overlapping foundations still fail the terrain check;
native underground room/corridor intersections are preserved. Verified entity-only
legacy templates are not treated as buildings or foundations. This is not a universal
repair for every mod's custom generator.

Run `python tools/structure_placement/run_tests.py` and
`python tools/structure_placement/test_config.py` for geometry and config checks.
The opt-in `tools/structure_placement/probe.init.gradle` fixture additionally checks
actual STARTS, saved policy markers, pairwise distances, support after CARVERS,
and physical support after FEATURES and restart.
Keep diagnostic source out of production builds. A probe passes only when its JSON
reports success with actual adapted structures; Gradle success alone is insufficient.
Ensure fixture `generate_features` is enabled and supply cached model assets before
launch. Visual acceptance and custom-mod behavior still require an in-game check.
Terrain plans deliberately use an unmodifiable defensive `HashMap` copy: packed
X/Z `Long` keys collide heavily (`x ^ z`). JDK 21 `Map.copyOf` uses linear probing
and was observed spending most worldgen time in `MapN.probe` during neighborhood
grade checks. Preserve treeified hash bins; descriptor fingerprints sort keys
independently. A packed 128×128-grid lookup check reproduced the slowdown.

The separate `tools/structure_placement/density.init.gradle` census enumerates every
native candidate owner in a fixed area, not just TD winners, and records actual starts
and pairwise gap checks in `world/density-audit.json`. Use fresh worlds with identical
seed, mods, area and configuration for a before/after comparison. Ensure the saved
world has structure generation enabled; changing server.properties alone does not
change an existing world's generation options. Optional `verify-support.txt` checks
an actual adapted assembly through CARVERS and FEATURES; `reverse-order.txt` reverses
the census order for deterministic-generation comparisons.
The server's bare TD preset defaults to legacy biomes; it does not reproduce a
client world created with the natural-biome compatibility preset. Match the saved
`biome_source` (`natural_biomes`, `terralith`), dimension type and saved scale before
testing density or shoreline materials. The census records these resolved flags in
`fixture`; `require-ecology-scale1.txt` fails early on the wrong natural-biome setup.
A legacy-biome census previously produced grove outposts where the play world used
ecology steppe, so its village ratio must not be used to calibrate that play world.
`repeat-native.txt` containing `structure_id,chunkX,chunkZ` compares native assembly
NBT and nearby terrain heights twice before and twice after the census in one server
lifetime. Cross-process layout differences alone do not identify a threading bug.

Lithostitched redirects vanilla jigsaw assembly, so alternate origins are captured
in `JigsawStructure.findGenerationPoint` before that redirect; the vanilla fallback
must not apply the offset twice. Shape terrain before surface painting, not after:
post-paint material filters can skip terracotta and leave accepted foundations
unsupported. Cave carving runs after SURFACE and can remove even unchanged building
ground, so CARVERS composes a bounded protection mask for verified support and
sampled slope endpoints, retaining other masks. SURFACE/CARVERS may inspect their
existing radius-eight STARTS dependency, but must never request neighboring
REFERENCES or recursively generate neighboring starts. Check actual blocks after
CARVERS, not only SURFACE heights; after FEATURES, buildings themselves change
heightmaps, so verify foundation blocks instead of comparing roofs to ground heights.

A fresh-world follow-up identified Lush 2.2's `lush:lush_vegetation` as a
separate direct-write route. Lush treats unknown ecology IDs as boosted
grass/fern groundcover and does not use `SimpleBlockFeature`, so the ordinary
Greeneries placement hook cannot govern it. On installations with the audited
Lush `2.2` and Greeneries, TD restores that feature only in its nineteen natural
ecology biomes through a TD-owned placed-feature holder. The clone retains
Lush's configured feature and original count-40 placement chain; the distinct
holder prevents cross-biome feature-order cycles. Each attempted plant then uses
the actual target biome and shared soil categories, with a Lush-specific ordinary-soil rate:
100% on grassy soil, 30% on ordinary soil, 10% on barren soil, and 0% on sand or
fluid-bearing support. The shared soil tags include the supported Immersive
Weathering and Terrain Slabs variants, and one decision covers both halves of a
tall plant. Other Lush versions fail closed. Installations without Greeneries
retain the previous TD suppression, while Lush's rocks, mushrooms, non-TD
biomes, providers and placement counts remain unchanged. This scoped adapter
does not assert vegetation-density parity between different biome types.

Hybrid Aquatic `1.7.4` with Lithostitched `1.8.1` wraps the biome source.
TD identity/settings checks recognize that audited wrapper while biome queries
still use its actual injected result. This keeps the ecology/Lush and shoreline
policies active; exact-source fast locate and preview optimizations remain
restricted to their original source type.
On new worlds, the marine adapter supplies continentalness from raw TD seabed
height to Hybrid Aquatic's native carver and biome predicates. The native trench
band occupies pre-carve depths 48–64 blocks; matching-only vanilla aliases allow
Terralith marine biomes to participate. Native marine generation is retained.
This does not reproduce vanilla climate distribution or guarantee every cave
biome. The per-world `data/terrain_diffusion_hybrid_aquatic.properties` marker
persists admission and seed before generation; initialized worlds without it
are not retrofitted. Invalid markers or disabling biomes for an enrolled world
abort startup to avoid silently changing future terrain. Validate marker and
sampling logic with `tools/hybrid_aquatic/test_world_profile.py`, `test_profile.py`
and `test_density.py`; native generation requires the opt-in Streams probe's
`hybridAquaticAudit`, `hybridMarineAudit` and `lushDensityAudit` checks in a fresh
fixture containing Hybrid Aquatic, Lush, Greeneries and their dependencies.
The fresh seed-12345 integration fixture generated ecology land, shallow ocean
and `hybrid_aquatic:warm_trench` chunks successfully. At the trench sample the
pre-carve boundary was Y=9 and the carved ocean-floor boundary was Y=-38;
shallow/land density boundaries remained unchanged. The native Lush audit passed
all nineteen ecology registrations and soil/fluid placement checks. These are
bounded server-side generation checks, not a visual or full-client performance
benchmark. Candidate selection must use signed raw coarse elevation: the broad
ecology climate field clamps negative ocean elevation and cannot rank sea depth.

Hybrid Aquatic's `bull_kelp`, `delesseria` and `sargassum` originally evaluate
`noise_based_count` once at the chunk origin, producing rectangular density
steps. The optional adapter replaces that count with 48 candidate attempts and
thins candidates using a seed/species-specific continuous world-coordinate
probability field. It runs only in natural-biome TD Overworlds and only for the
original registered four-modifier chain (120/80/0 noise count, in-square,
OCEAN_FLOOR, biome). Changed datapack chains and other features retain their own
rules. Native biome, water, support and stem-growth checks remain in place;
saved chunks are not rewritten, and density parity with the original is not
claimed. Run `tools/hybrid_aquatic/test_kelp_policy.py`, then
`tools/memory_probe/run_compat_probe.py <fresh-fixture-name> --kelp` for the
combined native marine/Lush checks and virtual-ocean native kelp placement audit.
The latter validates all three registered features, fixed candidate counts,
nested scope restoration, dry rejection and excluded-biome rejection; it does
not replace visual acceptance in a client. Fresh fixtures prepared with
`tools/structure_placement/prepare_fixture.py --family streams-probe` must retain
`data/terrain_diffusion_hybrid_aquatic.properties` along with `level.dat`:
omitting the admission marker from an initialized world correctly disables the
marine adapter and invalidates the fixture.

`_WG` heightmaps stop updating after CARVERS, so neighbor decoration can exist
above the ground when the later ecology surface pass converts it to sand. That
conversion explicitly clears only grass/ferns and a matching double-plant upper
half; it does not clear shrubs, aquatic plants or rewrite saved chunks.

Greeneries reeds/cattails also retain vanilla survival checks and use a
world-coordinate density mask on TD natural worlds. Ocean/beach candidates are
rejected; river/swamp and other sheltered banks keep patchy vegetation. This is
a biome/substrate approximation, not a simulated salinity field. Fine reed patches
use fixed block distances so large terrain scales do not inflate individual clumps.

For Nature X Depth's elevated-river cattail rendering, enable the resource-pack
overlay `compat/nature-x-depth/terrain-diffusion-nature-x-depth` above Nature X
Depth, with NeoContinuity `3.0.0+0.0.1+1.21.1.neoforge` enabled. All five cattail
rules and their base masks share a local water-depth condition and explicit
freshwater biome restrictions. The root must be 0.5 or 1 block below the nominal
source-water surface, with no fluid cell above. Stock Nature X seagrass represents
the 1-block case; 0.5 requires a plant model on a waterlogged bottom slab in the
same cell. A slab below a seagrass cell is not treated as half depth. Source water
uses its nominal cell top, not the renderer's 8/9-block depression. Deep, flowing,
marine and unsupported plants retain ordinary seagrass. The optional client bridge
fails closed on other Continuity versions, leaving original seagrass visible.
There are no column scans or extra chunk loads. The separate surface-algae rule
retains its existing freshwater restriction. The source pack supplies the transparent
tile; the overlay is not standalone. This changes rendering, not block identity:
the block remains `minecraft:seagrass`. `NATURE_X_PACK` can point to the installed ZIP when running
`tools/ecology/test_cattail_compat.py` to verify those source resources.

Use `python -B tools/ecology/audit_offline.py` to compile the production ecology
math and render all fifteen profiles without launching Minecraft (JDK 21+ and
Python with Pillow required). Results go to
`build/ecology-audit`, with material fractions, spatial transition measurements,
tree-attempt probability maps and a climate classification map. The default
seeds are 91357, 0 and -71421 at scales two and five. These are synthetic diagnostic
inputs, not generated ONNX terrain or a simulation of other mods. The legacy
surface comparison represents the old TD soil rule only, excluding mod-added
soil patches. Existing saved chunks do not change when this generator is updated.
For observed block/biome maps, run `python -B tools/ecology/map_saved.py --world
"path/to/world" --x 5572 --z 1208 --radius 8`. This separate Pillow-based tool
reads 256 chunks, skips vegetation, and writes `build/ecology-audit/saved`; it
checks source-region hashes after reading and refuses output inside the save.
Its maps show existing blocks, not the outcome of regenerating that world.

Ocean, gravel coast and steep rock retain selected Terralith
biomes; underground biomes retain the depth-based Terralith selector. The legacy
mode still represents all 95 Terralith IDs. The natural land palette intentionally
omits fantasy biomes. Terralith density functions are not applied.

Use the matching Minecraft 1.21.1 loader builds of Terrain Diffusion, Terralith
2.6.2, and Lithostitched (Terralith requires at least 1.7.7). For NeoForge, build
Terrain Diffusion with Java 21 using `./gradlew :neoforge:build -PuseDml=true`.
The Windows variant also requires the existing `libs/onnxruntime-dml.jar`.
For an offline NeoForge-only build, add `--configure-on-demand --offline` to
avoid configuring Fabric Loom and requiring its separate Minecraft cache.

Enable the generated pack in
`compat/terralith/terrain-diffusion-terralith-surface` **when creating a new world**,
then select the **Terrain Diffusion** world type. The pack supplies the surface
rules and enables `terralith: true` and `natural_biomes: true` in the preset's biome-source settings. Put it
above the source packs in the data-pack priority list. Terralith and Lithostitched
remain required: the compatibility pack does not bundle their biomes or features.

Both options are saved in the world's biome-source codec and default to false
when absent. Existing Terralith-enabled saves keep their legacy biome selection;
installing Terralith alone does not switch the biome selector. Do not
add this pack to an existing world expecting seamless conversion of old chunks,
or remove the required mods from a Terralith-enabled world. A missing required
biome fails at registry loading rather than silently changing new terrain.

For maintenance, use `python tools/terralith/generate_compat.py --help` to regenerate
the pack from a local Terralith JAR. The generated inventory and audit describe
source references and retained height assumptions. Tune biome distribution in the
legacy classifier's resource table. Natural ecosystem thresholds live in
`EcologyClassifier`; shared tree/soil controls live in `FeatureEcologyMath`.
Natural shores use a 24-block water-distance bound and fixed 64/96-block
neighbourhood measurements. Sheltered wet banks retain their land ecosystem;
exposed low shores select sand or gravel, and steep shores select stone. The
96-block water fraction suppresses small enclosed ponds, but is a finite local
heuristic, not proof of ocean connectivity; sufficiently large lakes can have
beaches too. `ShoreClassifier` changes biome/material selection only, leaving TD
heights and native Streams channels intact. Shore widths vary with exposure,
moisture and a coherent 768-block substrate field. Gravel now requires coarse regional substrate together
with sufficient exposure and relief, rather than appearing on every rolling coast
or half of cool flat coasts. This is a terrain-based approximation, not a calibrated
global gravel percentage: the widely cited global sandy-shore survey combines sand
and gravel in one class. `tools/ecology/ShoreFrequencyAudit.java` measures controlled
input scenarios; the optional density probe `-PtdShoreAudit=true` records paired
old/new decisions on actual terrain inputs. Existing generated chunks
retain their old materials; compare fresh chunks or a new test world after an
update. The compatibility pack must be updated along with the mod JAR.
On the audited Streams 2.13.1 renderer, natural-world point bars use a coherent
96-block sand/gravel field before both top and depth-fill writes. Original TD
gravel and stone shores override point-bar sand with their own substrate, even
after Streams changes the saved biome to river. This changes sediment material,
not channel geometry or water flow. Rocky Mountains' direct snow decoration is
selected only below freezing; warm steep terrain uses Granite Cliffs instead.

World Preview 1.3.1 reads custom biome colors from
`data/c/worldgen/biome_colors.json` (the shared resource stack, not a mod-namespaced
`worldgen` folder). Keep all 14 TD biome IDs registered there. NeoForge bridges
the audited 1.3.1 work queue to exact, center-first TD tiles, publishing each batch
through Preview's own storage. The pending tile queue is bounded and continues
across the viewport; uncomputed areas can briefly show N/A. Each preview owns its
seed, scale, pipeline and cache, while ONNX execution remains serialized. Closing
or replacing a preview invalidates publication without changing gameplay state.
Validate this in the real Preview screen: a standalone color parser cannot prove
the resource loader visits the path. `tdProbe.previewSessionAudit=true` additionally
checks session data and state isolation in the diagnostic server.

Regenerate the ecological biome resources with
`python tools/ecology/generate_biomes.py --vanilla path/to/minecraft_1.21.1_client.jar`.
For separate playable examples, the TD biome source accepts an optional
`ecology_showcase` name from `EcologyClassifier.BIOMES`, with `terralith=true`
and `natural_biomes=true`. It fixes biome assignment and feature climate to that
profile while retaining TD heights and slope-dependent vegetation. Omitted or
empty values preserve normal world generation. These are representative comparison
worlds, not samples of the natural biome distribution.
Showcase worlds retain native Streams generation, banks, river biomes and flow.
The selected ecology is the surrounding land biome, not a restriction that removes
rivers. Streams may replace saved biome IDs inside its channels with a river biome.
Prepare river terrain before installation; do not disable Streams to shorten initial
loading. Verify actual client entry as well as headless generation, since player
chunk delivery also initializes the Streams engine through `StreamFlowNet.onChunkSent`.
`tools/ecology/prepare_showcases.py --template-world <dedicated-probe-world>
--output <new-directory>` stages all 15 named worlds without copying existing
chunks or players. Generate their spawn chunks before installing the worlds;
verify the selected land biome, actual tree/soil output and physical river water
with the Streams probe's `surfaceAudit`, `ecologyAudit` and `streamsTopologyAudit`
options. Native river biome IDs are expected. The template must retain TD scale 2.
When generating via a dedicated server, set `gamemode=creative`: its properties
override the staged world's game mode when saving. Recheck `GameType` before installation.
The coarse model stride is 256 native pixels, or 512 game blocks at scale 2;
30 meters per native pixel converts physical altitude, not this horizontal stride.
Check exact
95-ID registry coverage, synthetic selection reachability (83 surface + 11 cave
IDs; `warm_river` stays reserved), negative-coordinate/tile
continuity, underground depth guards, resource references, and feature-order
cycles before testing new seeds in Minecraft. Synthetic checks do not establish
that every biome occurs in every seed or that its scenery looks correct.

The legacy table is `common/src/main/resources/terralith-biomes.tsv` (tab-separated).
Surface elevation thresholds and horizontal regions use physical terrain meters;
the 8192-meter regions are roughly 546 blocks wide at scale 2. Underground region
coordinates and cave elevation thresholds use Minecraft blocks. Low coastal land
hosts island-themed biomes; shallow water retains the underlying ocean biome
instead of being classified as `warm_river`. River biomes are assigned by Streams
in its generated channels, not inferred from coastal temperature, depth or slope.
The `warm_river` palette entry and its legacy underwater guards remain for stable
IDs. This changes new biome generation; existing saved chunks are not repaired.
The island proxy does not detect island connectivity or generate river channels.

Run the focused checks from the repository root (replace the JAR argument):

```text
python -B tools/test_terralith.py --jar "path/to/Terralith_1.21.1_v2.6.2_Neoforge.jar"
```

Use `--java-home` for a specific JDK and `--vanilla` for a vanilla 1.21.1 client JAR
outside the default Gradle cache. This runs the standalone Java selector checks,
generator regression tests, and feature-order checks in both mod resource orders.
It needs no inference models. An actual game launch remains necessary to validate
NeoForge/Lithostitched hooks, terrain generation and appearance together.

The explorer's climate map still shows the original broad biome classification;
use in-game biome information and `/locate biome terralith:<name>` to inspect the
actual biome. Searches can be expensive because they request diffusion terrain.
Visual acceptance should cover coasts, dry/wet transitions, snowy mountains and
underground vegetation, at the intended world scale.

## Repeatable local validation

Run only the relevant independent checks, with one launcher owning their output
directories. For example:

```text
python tools/structure_placement/run_fast_tests.py --list
python tools/structure_placement/run_fast_tests.py --jobs 4 --only placement village component config
python tools/structure_placement/run_fast_tests.py --only preflight --streams-jar "path/to/Streams.jar"
```

If a restricted Windows session rejects temporary-file creation or atomic moves,
use a dedicated writable directory under `build` for Java's `java.io.tmpdir`
or Python's `tempfile.tempdir`, then rerun the failed checks. A partial PASS
before an `AccessDeniedException` is not a successful suite.

The audited task list also includes `basic-villages` and `moss`. Each check runs in
its existing isolated output directory; logs and a new ordered `report.json` are
written under `build/structure-placement/fast-tests`. Default concurrency is four
(or fewer available processors), bounded at eight. Missing optional dependencies
are explicitly skipped in the default selection; an explicitly requested missing
check fails. Any executed check failure makes the launcher fail. `--jobs 1` provides
the same checks for a sequential baseline. The launcher lock does not cover manual
invocations of the underlying scripts.

Use these starting subsets with `--only`; inspect changed callers before omitting
other checks. A shared geometry/config change needs the union of affected rows.

| Changed area | Starting checks |
| --- | --- |
| Village distribution and group selection | `village config` |
| Structure spacing, foundations or component geometry | `placement component config` |
| Basic-village template/pool generation | `basic-villages component village` |
| Moss processor geometry | `moss` |
| Streams structure preflight | `preflight` with `--streams-jar` |

These are focused pure checks, not complete coverage of game integration. Changes
to generator/mixin ordering or native placement still need the relevant server
probe. River/ecology changes use their existing focused harnesses; they are not
covered by this structure-only task list.

For structure-generation stalls with `ImmutableCollections$SetN.probe` under
`PlacementGeometry.Occupied`, retain the defensive unmodifiable `HashSet` storage.
The voxel record hash clusters dense coordinates in JDK 21's `Set.copyOf` table;
copying a 32-cubed volume reproduced the expensive probing. Collision footprints
are cached by piece identity only within one generated start's pair scan (including
unknown/null footprints), and must be discarded before movement or another layout
attempt. Check `placement component` plus a fresh density/support probe; compare
accepted starts and input manifests before interpreting timings.

Basic-village asset generation streams the pinned source JAR checksum, copies
fixed-width numeric NBT lists in bulk, and writes generated files only when their
bytes change. `--check` remains read-only and all assertions run on every test
invocation. Preserve exact template bytes (apart from intended pool references)
when optimizing this path; malformed numeric lists must still fail validation.
Profile repeated parsing and avoidable writes before adding a persistent cache
whose invalidation would need to cover sources, tools and dependencies.

Prepared density fixtures must be real directories directly under
`build/structure-density/<name>`, containing `server.properties`, `eula.txt`, the
required mods/config/models, and matching world metadata. No player-save paths,
symlinks or junctions are accepted. Use the fixture requirements in the structure
placement section; the runner does not create worlds or infer missing settings.

Prepare a fresh-chunk copy from an existing **workspace diagnostic fixture**:

```text
python tools/structure_placement/prepare_fixture.py --source build/<source-fixture> --target build/structure-density/trial --center 108,20 --radius 40
```

This copies mods, config, models, known probe controls, world metadata and any
existing river cache. It requires `world/level.dat` and canonical
`level-name=world`; it never copies generated chunks or the saved structure index.
Center/radius inherit the source controls, falling back to the probe defaults
of `0,0` and `96`. Optional order/support controls retain their source settings.
`input-manifest.json` records SHA-256 for every effective input file and whether
the river cache was copied. Both source and destination bytes are hashed: an
unchanged destination keeps its timestamp, and changed files use verified atomic
copies. No hardlinks are created. Repeating preparation is supported only before
the target is run; generated files, removed source inputs leaving stale target
files, links and overlapping paths cause refusal rather than deletion. Use a new
target name for another fresh-world experiment. A forced termination can leave a
temporary file that must be inspected before retrying; normal copy failures clean
up their temporary file. Hashing still reads input bytes, so reuse reduces writes,
not all preparation cost.

```text
python tools/structure_placement/run_suite.py --gradle-user-home .gradle --prefetch 8 build/structure-density/before build/structure-density/after
python tools/structure_placement/run_suite.py --gradle-user-home .gradle --shore-audit --verify-support build/structure-density/trial
python tools/streams_probe/run_suite.py --gradle-user-home .gradle prepared-before prepared-after
```

These runners serialize Gradle/CUDA launches under one repository lock. They pass
the inspected NeoForge 21.1.249/CUDA/offline arguments directly, stream logs to each
run's `launcher-output.txt`, and atomically publish `suite-status.json` in its build
family. A successful run needs process exit zero **and** a fresh successful density
JSON or Streams completion receipt. Stale/malformed/missing receipts fail the suite.
Status is refreshed every 30 seconds; queued runs continue without interactive
polling. Windows children are launched without console windows. The lock coordinates
these runners, not direct Gradle commands or a child left by a forcibly killed
parent. Keep other builds and GPU probes out of an active suite.

Density additionally supports `--shore-audit` and `--prefetch 1..32`; default prefetch
remains eight. Prefetch controls pending chunk requests, not a thread count. Receipts
record `availableProcessors`, `processCpuTimeDeltaSeconds`,
`averageUtilizedLogicalCores`, `percentAvailableLogicalCapacity` and `phaseSeconds`.
CPU time covers the whole server JVM, including native work, over the census window;
it excludes launcher/Gradle startup and is not a GPU utilization metric. Unsupported
CPU counters are JSON null. Average utilized cores are CPU seconds divided by wall
seconds, normalized against logical processors for the percentage.

`chunkCensusObserverSeconds` separates submission round trips, future waits,
ticket cleanup, direct chunk fetches and result scanning. These are elapsed times
observed by the diagnostic thread, including scheduling and blocking; they do not
isolate terrain CPU work or disk I/O. `chunkCensusInference` reports existing
executor counter deltas for tasks, cumulative queue time and execution time during
the census. Overlapping task waits must not be added to observer times or read as
GPU utilization. Serialization also has a separate phase timer.
Density batch tickets are admitted on the server thread, while future requests
use the public off-thread API from the audit worker. Calling that API on the
server thread would block each request before submitting the next one. Results
are still consumed in the configured owner order and tickets are cleaned up in
`finally`.

Census, native locate, saved-index and serialization checks already share one
server lifecycle. Add `--verify-support` to verify physical foundations, or
`--verify-well-support` to require a basic-village buried well as well. Explicit
checks fail when no eligible structure is verified. `--shore-audit` records shore
classifier samples on an eight-block lattice within the selected census bounds,
through the end of the combined checks. It requires fresh nonempty evidence;
an inland region without qualifying samples fails this requested check. These
samples compare classifier outputs and are not a full river-block or visual audit.
Combine these options when the same fixture/area serves the checks, rather than
launching one server per check. Separate seeds, settings and cold-cache comparisons
still need independent fixtures.

For comparisons, use identical source, mods, seed, resolved biome/scale settings,
area, owner order and initial cache contents, with independent fresh chunk data.
Copy mutable assets and caches rather than hardlinking them. Compare start IDs,
owners, bounds and piece counts along with time; thread-count changes must not
change results. Cold graph construction and warmed graph reuse are separate
benchmarks. `--daemon` is opt-in for repeated Gradle launches; it cannot remove
Minecraft startup costs. Reuse a populated Gradle user home and avoid routine
clean builds. Inspect the final release JAR for diagnostic classes/resources after
using an init script.

For repeated **structure-only** iterations, record the terrain inputs once and
replay them in a separate fresh fixture:

```text
python tools/structure_placement/run_suite.py --gradle-user-home .gradle --verify-support --record-terrain build/terrain-replay/structure-base build/structure-density/record
python tools/structure_placement/run_suite.py --gradle-user-home .gradle --verify-support --replay-terrain build/terrain-replay/structure-base build/structure-density/replay
```

Prepare both fixtures from the same source with identical area and controls using
`prepare_fixture.py`. Each invocation accepts one fresh fixture. Recording must
finish successfully before a snapshot is sealed; existing snapshots are never
overwritten. Replay reruns native chunk/structure generation, locate, serialization
and requested support assertions, using recorded complete height/biome/ecology
tiles. It is **not** fresh terrain inference or classifier validation. Default
invocations still run the full pipeline; `--shore-audit` refuses replay. Use full
validation for terrain, biome, river or inference changes and final integration.

The manifest hashes actual fixture inputs, models, mods, production sources,
resources, build definitions and diagnostic hooks. Changes invalidate reuse,
including added/removed files. Only `world/structure/`,
`config/StructurePlacementConfig.java` and fixture
`config/terrain-diffusion-structures.json` are excluded to allow structure policy
iteration. Inspect those changes for new terrain dependencies. Missing, altered
or incompatible tiles fail the run; there is no live-inference fallback for a
missing recorded tile. Changes that request terrain outside the recorded region
require another recording. Runtime metrics disclose replay hits, misses, I/O time,
bypassed computations and residual ONNX work. Partial/raw terrain paths are not
intercepted, and nonlegacy sampling is unsupported. Snapshots are workspace test
artifacts, not saved chunks or reusable test verdicts.

A matched local scale-1 test (129 candidate owners, four accepted starts,
foundation checks enabled) measured 121.9 s for the recording audit and
62.7 / 63.0 s for two independent fresh replays. Gradle/server launch through
shutdown took 148.7 s versus 88.7 / 88.7 s, excluding fixture preparation and
manifest hashing. All starts, locate/serialization checks and support results
matched. The 56 tiles occupied about 105 MiB; this fixture's replay made zero
ONNX calls versus 440 during recording. This is a structure regression speedup,
not a general world-generation speedup; other fixtures and unhooked paths may
still perform inference. The one-time recording cost must be amortized over
subsequent iterations.

The Python manifest and binary codec checks are also independent of Minecraft:

```text
python -m unittest tools.structure_placement.test_terrain_snapshot
python tools/structure_placement/run_terrain_replay_tests.py
```

The runner contracts and telemetry can be checked without starting Minecraft:

```text
python -m unittest tools.test_probe_suite tools.structure_placement.test_run_fast_tests
python -m unittest tools.structure_placement.test_prepare_fixture
python tools/structure_placement/run_density_telemetry_tests.py
```
