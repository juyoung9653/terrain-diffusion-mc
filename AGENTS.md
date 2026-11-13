# Terrain Diffusion MC: local work and validation

Preserve unrelated working-tree changes. Use the user's global orchestration and
approval instructions. Commands and detailed constraints live in
[README.md](README.md#repeatable-local-validation); keep procedures there instead
of duplicating them here.

## Choose validation

- Inspect affected callers and existing evidence first. Start with focused pure
  checks; structure checks use `tools/structure_placement/run_fast_tests.py --only`
  (default four jobs). See [check selection](README.md#offline-checks).
- Run Minecraft for native/modded world-generation behavior. Combine applicable
  census, locate, support, serialization and material checks in one lifecycle.
  Do not repeat passing checks without changed dependencies, a new failure or an
  explicit benchmark/restart requirement.
- Reproduce mod interactions with the installed mods, settings and cache policy
  before reducing the fixture. For visual regressions, inspect saved coordinates,
  actual plant/support blocks and generation order before changing models.
  See [reproduction constraints](README.md#fixtures-and-reproductions).
- For zero-start village results, inspect the selected owner and rejection/rival
  before widening bounds or eligibility. Isolated support fixtures are not
  production density evidence; see [density measurements](README.md#density-measurements).
- Terrain replay is only for structure iterations with matching inputs and fresh
  fixtures. Inspect new terrain dependencies; terrain/biome/inference changes and
  final integration require the full pipeline. Never report replay as a full pass.
  See [replay limits](README.md#terrain-replay).

## Run safely

- Use dedicated workspace fixtures, never the original player save. Same-instance
  tests use a separately named copied world. Fresh tests exclude region/entities/
  poi and saved structure indices; record fresh/copied river caches. Copy writable
  models/config/caches, never hardlink them between instances or fixtures.
- Use `tools/structure_placement/prepare_fixture.py`; inspect the input manifest.
  Only unrun targets can be reused. Choose a new destination for fresh-world runs;
  do not delete or overwrite generated chunks.
- Use density/Streams suite runners for prepared fixtures. Keep other Gradle/CUDA
  jobs and compiled-source edits out of an active run. Interrupted parents can
  leave children running: inspect task-specific processes before retrying and
  never stop unrelated Java/game processes. The fast-test lock covers only its
  launcher, not direct scripts. See [probe execution](README.md#probe-execution-and-evidence).
- Reuse the populated Gradle user home (locally `.gradle`); avoid routine `clean`.
  Use `python -B tools/build_neoforge.py` for the local CUDA disk-library build.
  Installation requires reviewed `--validation` evidence and the matching JAR
  sidecar; do not bypass the installer or fabricate earlier receipts.
  See [build and install checks](README.md#builds-and-installation).

## Review evidence and avoid repeated work

- Require process success and a fresh successful diagnostic receipt. A printed
  PASS or `BUILD SUCCESSFUL` is insufficient after a probe or ZIPFS failure;
  inspect selected logs, especially `AccessDeniedException`.
- Review diagnostic callers and final artifacts, including delegated work.
  Measurements must occur after the claimed lifecycle phase and reach the final
  receipt. Plans, initial snapshots and post-tick blocks are different evidence;
  CSV shape checks do not establish measurement semantics or visual acceptance.
- Match source, mods, seed, settings, region, order and initial caches for timing
  comparisons; verify identical starts and repeat promising results. Default
  density prefetch stays 8; it is pending requests, not CPU thread count.
  Distinguish available CPUs, runnable threads and measured utilization.
- Profile repeated work before adding caches or concurrency. Retain basic-village
  byte-equivalence checks; cached test success is not fresh validation.
- Read compact summaries and targeted log matches. Wait 60-180 seconds on running
  work; keep user updates timely. Queue independent probes, and make dependent
  experiments conditional on prior results. Match historical receipts to the
  actual launch, sources and fixture before treating them as a baseline.
- For session audits, filter metadata to this repository before inspecting relevant
  tool records. Do not dump transcripts, images, environment files or unrelated
  project history.
