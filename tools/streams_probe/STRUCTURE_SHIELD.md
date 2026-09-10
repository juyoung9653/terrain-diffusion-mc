# Streams / Better Mineshafts surface cut

This is separate from the custom-biome tag omission documented in
`BIOME_COMPAT.md`. The second reported world had the correct tag pack enabled.

## Reproduction

Minecraft 1.21.1, NeoForge 21.1.249, Streams Reflowing 2.13.1, YUNG's Better
Mineshafts 5.1.1, YUNG's API 5.1.8, TD scale 2, seed `5193905912846707943`.
The sampled chunks are `(73,32)`, `(74,32)`, `(73,33)`, `(74,33)`, `(73,34)`,
and `(74,34)`. All six reached FULL in both isolated baseline runs.

- TD + Streams, structures disabled: maximum adjacent ground step at
  X `1182/1183`, Z `512..559`, was 2 blocks.
- Adding Better Mineshafts and enabling structures reproduced the long cut,
  with a maximum step of 13 blocks. No C2ME or other terrain mods were needed.
- The overgrown mineshaft start at `(70,39)` has aggregate bounding box
  `[1057,-52,492,1179,400,651]` and 124 pieces. Its vertical entrance piece is
  `[1099,-48,603,1147,400,651]`; the pieces near the cut are deep underground.

Streams' structure-shield collector projects each aggregate structure box onto
the surface, padding X/Z by 3. A column is protected if the stored maximum Y is
at least `groundY - 6`. The entrance's Y400 therefore protects the entire mine's
horizontal footprint. `1179 + 3 = 1182` explains the exact intact edge, followed
by the carved channel at X1183.

## Correction contract

Only TD worlds, Better Mineshafts starts, and the audited Streams 2.13.1 collector
are targeted. Replace aggregate protection with padded bounds of the individual
pieces. Keep the existing height test and real entrance protection. Preserve
other structures, duplicate aggregate tuples, and the original array on an
unexpected layout. Read only already referenced structure-start chunks.

The biome data pack cannot change this private Java implementation. This fix
requires the NeoForge mod code. Existing generated chunks are not repaired by
changing the code or data packs.

Local measurements and raw receipts are under `build/second-cut-analysis/` and
`build/streams-probe/second-cut-*`. These generated artifacts are not versioned.

## Verified correction

- `second-cut-fixed`: all six comparison chunks reached FULL; all 1,536 base
  heights and original biome identities matched the mineshaft baseline. Maximum
  ground step on the 48-column boundary fell from 13 to 2 blocks, matching the
  no-mineshaft control's edge profile.
- Actual injected collector: surface `(1176,73,536)` is no longer shielded by
  the remote mine; entrance `(1128,98,632)` remains shielded. Entrance chunk
  `(70,39)` also reached FULL.
- `second-cut-c2me`: installed C2ME 0.4.0-alpha.0.120 plus the same mineshaft
  setup completed with the same 48 edge steps and preserved entrance shield.
  This does not assert whole-world block determinism.
- `shield-no-streams`: vanilla server startup and registry check succeeded with
  the Streams jar disabled, confirming optional loading.
- `shield-vanilla`: invoked the actual transformed callback in a vanilla world;
  it retained the input array by identity and did not cancel the original return.
- Pure JDK 21 regressions cover collision counts, missing/malformed tuples,
  expanded-chunk overlap at 1..3 blocks, entrance protection, and a large
  underground aggregate without a local surface piece.

The fix is deliberately gated to Streams **2.13.1** on NeoForge. Other versions
skip this private-method patch until their bytecode contract is checked. It is
not a global disable switch for structure protection.

Reviewable results: `build/second-cut-analysis/verification.json` and
`shield-fix-comparison.png`. The test-only `-ShieldAudit`, `-RegistryOnly`, and
`-NonTdShieldCheck` switches in `run.ps1` record these checks without shipping
the diagnostic source in normal builds.

## Build artifact

Normal `:neoforge:build -PuseCuda=true -Pneoforge_version=21.1.249` passed without
the probe init script. The reviewed output is copied to
`build/libs/NeoForge-terrain-diffusion-mc-2.3.0-streamsfix-cuda+1.21.1.jar`.
Archive validation checks the optional mixin/plugin/helper, eight biome tags,
bundled ONNX dependency metadata, CRC integrity, and absence of diagnostic classes.
`build/second-cut-analysis/artifact.json` records its SHA-256.

This is a replacement for the installed Terrain Diffusion NeoForge CUDA jar,
not an additional second copy of that mod. The separate Terralith surface data
pack remains separate. The player's installation and saved chunks were not edited.
