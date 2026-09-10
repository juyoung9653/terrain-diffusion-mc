# Custom biome compatibility

Terrain Diffusion's `forest_sparse`, `taiga_sparse`, and `snowy_taiga_sparse`
need biome tags in addition to biome definitions. The additive resource tags in
`common/src/main/resources/data/minecraft/tags/worldgen/biome` match the
corresponding vanilla forest, taiga, and snowy taiga structure eligibility.

| Custom biome | Vanilla structure candidates after tag resolution |
| --- | --- |
| forest_sparse | Mineshaft, ruined portal, stronghold, trial chambers |
| taiga_sparse | Above, plus pillager outpost, trail ruins, taiga village |
| snowy_taiga_sparse | Forest list, plus igloo and trail ruins |

Streams Reflowing 2.13.1's `stream_biomes` tag includes `#minecraft:is_overworld`.
Adding the three biomes to that tag also attaches its river, river flow, water
skin, stream vegetation, and snow cleanup placed features. NeoForge's common
forest/taiga/overworld tags inherit the corresponding vanilla groups.

Verified with actual server registry receipts in
`build/streams-probe/cliff-fixed-tags/logs/latest.log` (`biome_registry` events).
This verifies generation eligibility and feature attachment; structures still
require enabled structure generation, valid terrain, and their normal placement
conditions. Mods using explicit biome ID lists need their own compatibility tags.

The same eight additive JSON resources are exported as
`build/libs/terrain-diffusion-biome-compat-1.21.1.zip` (data pack format 48).
Use the ZIP in the data pack selection when creating a new 1.21.1 world, or in
an existing world's `datapacks` directory while that world is closed. New terrain
uses the tags after loading the pack. Existing generated chunks are not repaired
or repopulated by these additions. No player world or installed mod was edited.

The diagnostic can force any of the three custom biomes using
`prepare.py --fixed-biome terrain-diffusion-mc:forest_sparse` (or the two taigas).
Fixed-biome experiments are synthetic and do not establish natural biome
frequency. The runtime audit also records the three biomes in ordinary worlds.

## Reproduced chunk-edge channel cut

Seed `-6994897899453639960`, effective scale 2, X `-1345/-1344`, Z `1320..1327`:
the player's saved channel ends at a chunk boundary, with dry grass on one side
and water/mud on the other. This is inside a single 256-block terrain tile.

Two fresh minimal TD + Streams worlds used the same seed, configuration, source,
and requested chunks. The control data pack restores the original vanilla values
at the eight changed tag paths (`replace:true` only in the test control). Runtime
receipts confirm the control's custom biomes have no Streams placed features or
vanilla structure candidates; the fixed run has the intended memberships.

- All 1,024 sampled base heights and natural biome identities matched.
- Original tags: boundary ground-height differences `[3,3,3,4,4,4,5,6]`.
- Fixed tags: `[0,0,0,0,0,0,0,0]`, with mud/water continuing across the boundary.
- The east chunk is FULL; the west neighbor is INITIALIZE_LIGHT in **both** runs,
  after the feature-generation stage. This is a matched feature-stage terrain
  comparison, not an all-FULL or complete player-modpack validation.

Evidence: `build/cliff-diagnosis/tag-control-edge-comparison.json` and `.png`,
`tag-control-columns.json`, and the `cliff-original-tags` / `cliff-fixed-tags`
run receipts. These establish the tag-dependent cut at this location. They do
not claim that all cliffs have this cause or that existing chunks are repaired.
