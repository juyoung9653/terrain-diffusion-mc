# TD leaf occlusion candidate

This is a client-only experimental optimization for the installed 1.21.1 Sodium
pipeline. It cancels `BlockRenderer.renderModel` only for an oak, birch, or
spruce leaf block when every one of its 26 surrounding snapshot states is one of
those same three non-waterlogged leaf types. It never reads `Minecraft.level`;
the mixin retrieves Sodium's worker-owned `BlockAndTintGetter` snapshot through
an accessor.

The mod starts disabled. At every client resource reload it compares the active
resource-manager bytes against `leaf-assets.sha256`, generated from the current
selected pack stack. The manifest includes the three blockstates, six models,
their referenced textures, every active CTM selector under `optifine/ctm`, and
the selector tiles that can replace these leaves. It also requires the runtime
CTM selector keyset to exactly match. Any new pack, changed asset, lookup error,
or unexpected selector disables culling.

`generate_asset_manifest.py` must be rerun before rebuilding if the intended
resource pack stack changes. `tests/test_guard_and_neighbors.py` validates the
effective hashes, model bounds from -1 to 2 blocks, and a missing diagonal
neighbor rejection. `build.py` directly compiles and packages the standalone
JAR; it does not copy it into an instance.

The runtime emits immutable candidate/cull counters every 1,200 client ticks.
Leaf waving can move extended planes, so screenshots and a matched FPS trial are
required before deployment; this artifact does not claim visual equivalence.
