package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import net.minecraft.server.level.ServerLevel;

/** Opt-in export of actual terrain with previous and current snow decisions, without block edits. */
public final class TerrainSnowAudit {
    private static final FastNoiseLite PATCH = noise(82741, 72);
    private static final FastNoiseLite WARP = noise(62479, 160);
    private static final FastNoiseLite EDGE = noise(91283, 3);
    private TerrainSnowAudit() { }

    public static String run(ServerLevel level, int x, int z) {
        int size = TerrainDiffusionConfig.tileSize();
        int x0 = Math.floorDiv(x, size) * size, z0 = Math.floorDiv(z, size) * size;
        var provider = LocalTerrainProvider.getInstance();
        var data = provider.fetchHeightmap(z0, x0, z0 + size, x0 + size);
        var before = provider.performanceSnapshot();
        if (data.ecology == null) throw new IllegalStateException("ecology missing");
        Path output = Path.of(System.getProperty("td.streamsProbe.terrainSnowOutput", "terrain-snow.csv"));
        int oldCount = 0, newCount = 0, terrainChanged = 0;
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("x,z,elevation,temperature,slope,retention,margin,oldSnow,newSnow\n");
            for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) {
                int bx = x0 + dx, bz = z0 + dz;
                var climate = data.ecology.sample(dx, dz);
                float elevation = data.heightmap[dz][dx];
                float retention = data.ecology.snowRetention(dx, dz);
                float t = Math.max(0, Math.min(1, (climate.temperature() + 6) / 8));
                float coverage = Math.max(1 - t * t * (3 - 2 * t), Math.min(1,
                        EcologyClassifier.alpineCoverage(bx, bz, elevation, climate.temperature()) / .85f));
                float wx = bx + 24 * WARP.GetNoise(bx, bz);
                float wz = bz + 24 * WARP.GetNoise(bx + 917, bz - 433);
                float threshold = Math.max(.02f, Math.min(.98f, .5f + PATCH.GetNoise(wx, wz)
                        + .035f * EDGE.GetNoise(bx, bz)));
                boolean oldSnow = coverage >= 1 || coverage > 0 && coverage > threshold;
                boolean newSnow = EcologyClassifier.naturalSnowCover(bx, bz, elevation, climate.temperature(), retention);
                boolean flatSnow = EcologyClassifier.naturalSnowCover(bx, bz, elevation, climate.temperature(), 0);
                oldCount += oldSnow ? 1 : 0; newCount += newSnow ? 1 : 0;
                terrainChanged += newSnow != flatSnow ? 1 : 0;
                writer.write(bx + "," + bz + "," + elevation + "," + climate.temperature() + ","
                        + climate.slope() + "," + retention + ","
                        + EcologyClassifier.naturalSnowMargin(elevation, climate.temperature(), retention) + ","
                        + (oldSnow ? 1 : 0) + "," + (newSnow ? 1 : 0) + "\n");
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("event", "terrain_snow_audit");
        receipt.addProperty("seed", level.getSeed());
        receipt.addProperty("x0", x0); receipt.addProperty("z0", z0); receipt.addProperty("size", size);
        receipt.addProperty("oldSnow", oldCount); receipt.addProperty("newSnow", newCount);
        receipt.addProperty("terrainChanged", terrainChanged); receipt.addProperty("csv", output.toAbsolutePath().toString());
        var after = provider.performanceSnapshot();
        receipt.addProperty("decisionProviderCalls", after.hits() + after.misses() - before.hits() - before.misses());
        receipt.addProperty("decisionComputedWindows", after.computedWindows() - before.computedWindows());
        return receipt.toString();
    }
    private static FastNoiseLite noise(int seed, int scale) {
        var result = new FastNoiseLite(seed);
        result.SetNoiseType(FastNoiseLite.NoiseType.Perlin); result.SetFrequency(1f / scale);
        return result;
    }
}
