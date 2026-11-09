# Terrain Diffusion MC: local work and validation

Preserve unrelated working-tree changes. Use the user's global orchestration and
approval instructions. The validation commands and limitations below are documented
in [README.md](README.md#repeatable-local-validation).

## Choose the smallest useful validation

- Inspect the affected path and existing evidence before launching a server. Run
  focused pure checks first; use `tools/structure_placement/run_fast_tests.py --only`
  for independent structure checks. Default four jobs; more jobs are not inherently
  faster. Its lock covers this launcher, not manually launched standalone scripts.
- Run Minecraft only for behavior that depends on native/modded world generation.
  Combine applicable census, locate, support, serialization and material checks in
  the existing probe lifecycle. Do not repeat a passing check without a changed
  dependency, new failure or an explicit benchmark/restart requirement.
- For repeated structure-only iterations, use the density runner's explicit
  `--record-terrain` / `--replay-terrain` with identically prepared fresh fixtures.
  Replay reuses complete terrain inputs, not chunks or test verdicts. The source
  and input fingerprint must match; missing tiles fail closed. It does not validate
  terrain/biome/inference changes, forbids shore audits and nonlegacy sampling,
  and may still execute unhooked raw/partial inference. Inspect structure changes
  for new terrain dependencies; use full validation for terrain changes and final
  integration. Never relabel replay evidence as a full pipeline pass.
- Density census/locate/serialization can share a launch with `--verify-support`
  and `--shore-audit`. Requested checks require actual evidence; select a fixture
  containing adapted structures and qualifying shore samples. Shore evidence is
  classifier sampling, not physical river-block or visual acceptance.
- Use the density/Streams suite runners for prepared fixtures. They serialize
  Gradle/CUDA jobs, validate fresh receipts, retain logs and publish status. Keep
  direct Gradle builds, other probe launchers and modifications to compiled sources
  out of an active run. An interrupted parent can leave a child running; inspect
  the task's processes before retrying. Never stop unrelated Java/game processes.
- Specify the already-populated `--gradle-user-home`; the recent local NeoForge
  workflow uses the repository `.gradle` directory as its user home. Do not switch
  cache directories or run `clean` routinely. Clean the diagnostic source outputs
  only when needed to ensure a release JAR contains no probe classes/resources.
- For the local NeoForge CUDA disk-library build, use `python -B tools/build_neoforge.py`
  instead of composing a PowerShell Gradle command. It supplies the JDK/cache
  environment and exact version argument under the shared probe lock;
  `--check-only` validates the invocation without building.

## Reproducible measurements

- The local host has 16 physical / 32 logical CPUs. Distinguish available processors,
  runnable threads, average utilized logical cores and elapsed time. Density receipts
  now report process CPU-time delta and phase timing; historical logs without those
  counters cannot prove full utilization. GPU execution intentionally serializes.
- Keep default density prefetch at 8 unless matched measurements justify changing
  it. `--prefetch` changes outstanding chunk requests, not CPU thread count. Compare
  the same seed, mods, settings, region, source revision, order and cache contents;
  verify identical starts before claiming a speedup. Repeat a promising result.
- `ServerChunkCache.getChunkFuture` managed-blocks when called on the server
  thread. For diagnostic batches, admit tickets there, then call the public
  off-thread future API from the audit worker; it schedules native admission.
  Keep ordered result consumption and finally-based ticket cleanup. Wrapping
  the whole future-request loop in `server.submit` silently serializes the batch.
- Use dedicated workspace fixtures, never player saves. Fresh-chunk tests must not
  copy region/entities/poi data or the saved structure index. Record whether river
  caches are fresh or copied. Copy writable model/config/cache files; do not hardlink
  them to an instance or another independently running fixture.
- Use `tools/structure_placement/prepare_fixture.py` for hash-verified incremental
  preparation. Reuse only an unrun target; generated/stale targets are refused.
  Preserve source order/check controls and inspect the input manifest. For another
  fresh-world run use a new destination, not deletion or overwrite of saved chunks.
- A probe requires both process success and a fresh successful diagnostic receipt.
  `BUILD SUCCESSFUL` alone can accompany a server/probe failure. Inspect selected
  test logs when JDK ZIPFS reports `AccessDeniedException`; a printed PASS alone
  is insufficient evidence after a sandbox write failure.
- For village density, preserve fixed-area and land-coverage measurements and report
  sample count/censoring. Candidate spacing is not the realized mean; a 70% draw
  among viable groups is not a guarantee of 70% accepted villages in each region.

## Keep investigation efficient

- Profile actual repeated work before adding caches or increasing concurrency.
  The basic-village generator already bulk-copies numeric NBT lists, streams its
  source checksum and preserves unchanged output files. Retain byte-equivalence
  checks; a cached successful test result is not fresh validation.
- Prefer compact JSON summaries and targeted log matches. Wait on a running process
  for 60–180 seconds rather than repeatedly reading its log every few seconds;
  provide user updates at the required cadence. Queue known-independent probe runs
  before waiting, but keep dependent experiments conditional on the first result.
- Consult existing README constraints and prior result receipts before rediscovering
  failures. Match receipts to their actual launch, source and fixture; stale logs
  or a different world profile are not valid baseline comparisons.
- When auditing previous sessions, filter session metadata by this repository first,
  then inspect only relevant tool records. Do not dump complete transcripts, images,
  environment files, or unrelated project history into the conversation.
