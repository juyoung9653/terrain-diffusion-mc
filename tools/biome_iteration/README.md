# Fast feedback for biome changes

Start with one falsifiable report and one healthy control. [reef-example.json](reef-example.json)
uses the woodland coral report and a real deep reef on the same seed. Plan first:

```powershell
python -B tools/biome_iteration/local.py tools/biome_iteration/reef-example.json
```

To check an already generated, closed world, add `--world WORLD --output NEW_REPORT.json`.
The case runner verifies seed and saved TD scale. It exits 1 for a mismatched
expectation, 2 for insufficient coverage, and 0 when no declared expectation
failed. A material case without an automatic threshold still requires inspecting
its metrics. A passing local check cannot be used with the installer.

After a code change, saved chunks from the old candidate cannot show the new
result. Generate the reported patch **and** control in a fresh disposable
fixture. Example for this case (PowerShell, start in the repo root):

```powershell
python -B tools/build_neoforge.py
python -B tools/chunk_profile/run.py prepare reef-dev-01 --world '새로운 세계 (12)' --td-jar 'neoforge/build/libs/NeoForge-terrain-diffusion-mc-2.3.0-cuda+1.21.1.jar' --without-streams
python -B tools/chunk_profile/run.py run reef-dev-01 --side 1 '--regions=-119,98;-118,98;-60,103'
python -B tools/biome_iteration/local.py tools/biome_iteration/reef-example.json --world build/chunk-profile/reef-dev-01/saves/chunk-profile --output build/biome-iteration/reef-dev-01.json
```

Use a new fixture and output name for each changed candidate. The region list
comes from the plan; omit `--without-streams` when river routing, height, banks,
settled flow, feature order, or an unknown writer could affect the report.
This small native run gives saved-block feedback. It does **not** capture
shader views, prove fluid settlement, call an independent reviewer, or consume
a formal review round. For color, silhouettes or a waterline, inspect a targeted
scene in the disposable candidate before commissioning the full capture.

For custom sediment windows and before/after metrics, use inclusive bounds
`minX,minZ,maxX,maxZ`. Four-neighbor components, 2×2 diagonal contacts and
straight material edges describe different defects. A smaller component alone
does not prove a natural edge.

```powershell
python -B tools/biome_iteration/measure.py --world CANDIDATE --compare BASELINE --patch 'inlet:-1819,1613,-1799,1633' --min-y 48 --max-y 70 --output build/biome-iteration/inlet.json
```

Keep Y bounds appropriate to the question (coral blocks in a bounded volume
versus top substrate in a column); inspect missing, covered and unclassified
columns. An adjacent waterlogged block is not proof of open water clearance.
For existing native column census files, `preview.py` renders a zoomable material
map and records coverage and input hash. Its window arguments are named in X
then Z order: `--window=minX,maxX,minZ,maxZ`.

```powershell
python -B tools/biome_iteration/preview.py --columns COLUMNS.json --window=-1819,-1799,1613,1633 --output build/biome-iteration/inlet-map
```

No existing census is required: render aligned maps directly from two closed
saves with the same seed and scale. This is usually cheaper than starting a
client just to inspect a material boundary:

```powershell
python -B tools/biome_iteration/preview.py --world CANDIDATE --compare-world BASELINE --window=-1819,-1799,1613,1633 --min-y 48 --max-y 70 --output build/biome-iteration/inlet-pair
```

Keep a short note with each candidate: reported and control coordinates,
suspected writer and stage, what would disprove it, source change, inputs,
which mods were omitted, and the first useful result. If a control regresses
or coverage is unknown, investigate locally before spending a review round.
At candidate freeze, return to the [full integration and visual release
checks](../../README.md#independent-biome-realism-review).

To measure a completed run, use `python -B tools/biome_iteration/timing.py FIXTURE`
against its original directory. It measures launch-to-first-frame, capture
span, and finalization separately using the actual frame files. It does not
attribute startup time to individual mods. The optional diagnostic JVM flag
`-Dchunkprofile.adaptiveCaptureWait=true` can shorten same-site side/detail
waits after 24 clean frames and a full second in the requested camera pose.
The first view and the fallback still wait three seconds. Leave this off for
acceptance until a matched shader/camera comparison shows equivalent quality.
