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
with audited Streams 2.13.1/2.13.5/2.13.8 use a persisted **natural river profile v7**:
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

Terrain queries reuse complete classified tiles by default. An experimental 16x16
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
11 ecological biomes controlled by a continuous TD climate field, with a filter
radius of 512 model pixels (1024 game blocks at scale two, rounded up to coarse
cells). All eleven ecological biomes
share grass/foliage tints and the ordered ecological vegetation list. Tree density, compatible species
weights and broad soil patches depend on continuous climate controls, not biome
labels. Biome IDs are never randomly dithered at a boundary. Terrain heights and
native Streams generation stay in their existing generation paths.

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
Cold upland tree cover tapers between 900 and 1600 model-elevation metres before
the alpine selector takes over; these are not Minecraft block Y coordinates.
Temperate woodland gains sparse coarse-dirt floor patches, and conifer podzol
uses the finer soil field. Grass/leaf tint remains a shared palette rather than
a client-synchronized climate gradient. Other mods can still inject decorations
through biome modifiers; those additions are not governed by this climate pass.
The old biome-specific stage also included incidental vines, melons, pumpkins
and sugar cane; this pass does not reproduce those placements.

`EcotoneContinuityTest` traverses all eleven labels and checks expected plant
rates across moisture/temperature boundaries, cold treeline taper and spatial
continuity. These checks and the offline atlas do not replace a fresh-chunk
in-game placement/appearance check. Existing chunks are not rewritten.
Runtime probes with Streams 2.13.8 require NeoForge 21.1.173 or later (the
isolated ecology probe uses 21.1.249). The normal 21.1.80 compile target can build
successfully while that mod combination refuses to start; inspect the server
receipts and logs rather than relying on Gradle's successful task exit alone.

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

`/locate biome` retains Minecraft's candidate order, predicate and cave-height
checks while reusing the current terrain tile within one search. Disable this
reuse with `-Dtd.locate.biomeCache=false` for comparison. It reduces repeated
lookups, not the number of distinct AI tiles required for a cold search.
`/locate structure` retains its existing generation/presence checks.

For ungenerated terrain, permission-level 2 users can use a separate bounded search:

```text
/td-locate biome terrain-diffusion-mc:ecology/temperate_forest 8192
/td-locate structure minecraft:ruined_portal 8192
/td-locate status
/td-locate cancel
```

The optional radius defaults to 8192 blocks (16–32768), measured on each horizontal
axis. Coarse climate ranks candidates first; only exact biome-source quart samples
or valid generated `STRUCTURE_STARTS` are reported. The result is not necessarily
the nearest. Biome samples can differ from later saved-chunk/F3 boundaries after
river processing. Individual registry IDs have completion; tags are not supported.
Concentric-ring and custom structure placements require vanilla `/locate`.

One search runs per server, with a 60-second admission/verification budget, up to
25 climate patches, 64 detailed biome tiles or 32 structure-start requests.
An exhausted search does not prove absence. Already running native inference or
chunk requests must drain safely and can exceed that time; cancellation suppresses
results immediately and prevents another search until the worker drains. Structure
generation may internally need additional terrain. Background ranking reduces
unnecessary detailed generation, but shares CPU/GPU resources with gameplay and
cannot promise zero lag or a fixed speedup. Ordinary `/locate` remains unchanged.

On NeoForge, the eleven ecology biomes remove Immersive Weathering's raw-generation
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

A fresh-world follow-up identified Lush 2.2's `lush:lush_vegetation` as a
separate direct-write route: it boosts unknown ecology IDs with grass/ferns and
spills across chunks. This duplicate vegetation feature is now removed only from
the eleven ecology biomes; Lush's rocks, mushrooms and other biomes are retained.
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
Depth, with Continuity enabled. All five general cattail rules are paired with
the base mask at Y=62..319 and restricted to explicit freshwater biome IDs. The
surface-algae rule receives the same freshwater restriction. Ocean seagrass keeps
the source pack's ordinary seagrass appearance. The source pack supplies the transparent tile;
the overlay is not standalone. This changes rendering, not block identity:
the block remains `minecraft:seagrass`. It retains the source pack's absolute
height heuristic, which does not distinguish shallow water from deep highland
lakes. `NATURE_X_PACK` can point to the installed ZIP when running
`tools/ecology/test_cattail_compat.py` to verify those source resources.

Use `python -B tools/ecology/audit_offline.py` to compile the production ecology
math and render all eleven profiles without launching Minecraft (JDK 21+ and
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

Ocean, gravel coast, steep rock and cold alpine terrain retain selected Terralith
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
moisture and a coherent 768-block substrate field. Existing generated chunks
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
--output <new-directory>` stages all 11 named worlds without copying existing
chunks or players. Generate their spawn chunks before installing the worlds;
verify the selected land biome, actual tree/soil output and physical river water
with the Streams probe's `surfaceAudit`, `ecologyAudit` and `streamsTopologyAudit`
options. Native river biome IDs are expected. The template must retain TD scale 2.
When generating via a dedicated server, set `gamemode=creative`: its properties
override the staged world's game mode when saving. Recheck `GameType` before installation.
The coarse model stride is 256 native pixels, or 512 game blocks at scale 2;
30 meters per native pixel converts physical altitude, not this horizontal stride.
Check exact
95-ID coverage, synthetic selection reachability, negative-coordinate/tile
continuity, underground depth guards, resource references, and feature-order
cycles before testing new seeds in Minecraft. Synthetic checks do not establish
that every biome occurs in every seed or that its scenery looks correct.

The legacy table is `common/src/main/resources/terralith-biomes.tsv` (tab-separated).
Surface elevation thresholds and horizontal regions use physical terrain meters;
the 8192-meter regions are roughly 546 blocks wide at scale 2. Underground region
coordinates and cave elevation thresholds use Minecraft blocks. Low coastal land
hosts island-themed biomes; warm shallow water is an estuary proxy for `warm_river`.
These proxies do not detect island connectivity or generate river channels.

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
