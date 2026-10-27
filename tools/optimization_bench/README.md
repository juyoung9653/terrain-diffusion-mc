# Memory optimization validation

`memory_plan.py snapshot-baseline` copies only the currently installed Terrain Diffusion JAR and records hashes of the relevant settings. It does not start Minecraft. After the release JAR is built, `memory_plan.py plan --optimized <jar>` writes a serial A/B launch plan.

The launcher supports only explicit collector and heap profiles. `--replace-mod` swaps an exact JAR only inside a new isolated run. `--config-line` changes a property only inside that clone. It never edits Prism's live instance.

Run the baseline and optimized ZGC pair first. The G1 and ModernFix dynamic-resource commands are conditional, separate experiments. The existing probe is a fixed render scene; use the terrain/Streams integration suite plus a live heap capture for terrain-generation retention.
