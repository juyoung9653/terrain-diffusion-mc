# Photon DOF experiment

This experiment targets the locally modified Photon pack whose SHA-256 is pinned
in `build.py`. It reduces the cost of **enabled** depth of field. It does not claim
to beat DOF disabled, reduce scene rendering cost, or fix cloud reconstruction.

After Photon's existing hand exclusion and LOD depth conversion, pixels with a
circle-of-confusion radius of at most **0.25 output pixels** return the original
scene texel. Other pixels retain the original sample count, noise sequence, focus,
and blur kernel. The default source uses 40 taps. Noise is fetched only after the
new early return. `DOF_SKIP_SUBPIXEL` in Post-Processing toggles the experiment.
Cloud rendering and all geometry, textures, shadows, and materials are unchanged.

## Build an isolated ZIP

```powershell
python tools/photon_dof/build.py --source <installed-photon.zip> --settings <installed-photon.zip.txt> --output build/photon-dof-experiment-20261026/photon-dof-fastpath.zip
```

The destination must not exist. The builder embeds the current settings snapshot
as defaults, enables DOF and the new toggle, retains the source license and other
assets, verifies every output member, and records hashes in a sibling manifest.
It never edits the installed pack or Iris configuration. Rebuild from a newly
reviewed source if the input ZIP hash changes.

## Validation and comparison

`validate_gl.py` uses the already cached LWJGL libraries to compile/link the actual
DOF shaders in an invisible standalone OpenGL context. Its results cover shader
compilation, not Iris preprocessing, in-game appearance, or FPS. Check its report
for the combinations actually tested and any failed cases.

```powershell
python tools/photon_dof/validate_gl.py build/photon-dof-experiment-20261026/photon-dof-fastpath.zip --output build/photon-dof-experiment-20261026/validation/recheck
```

On 2026-10-26, the experiment passed all 36 compile/link combinations: three
dimensions, base/Voxy/Distant Horizons, TAAU on/off, and the new option on/off.
The archived report under `validation/fastpath-final/report.json` binds its result
to the output ZIP hash. In-game image comparison and FPS measurements are pending.

For in-game comparison, use a separate prepared scene with the same settings and
toggle only `DOF_SKIP_SUBPIXEL`; keep DOF on for both runs. Check focused thin
edges, distant and near blur, hands, Voxy terrain, and changing focus. Measure the
same uncapped scene after warmup, repeat promising results, and also measure DOF
off as a third baseline. Do not benchmark alongside another GPU/game workload.
Reduced texture fetches are not measured GPU-time or FPS improvement.
