package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.level.ServerLevel;

/** Opt-in cached ecological decisions over real model terrain; never edits world blocks. */
public final class ColdBiomeSpatialAudit {
    private ColdBiomeSpatialAudit() { }

    public static String run(ServerLevel level) {
        try { return export(level); }
        catch (Exception failure) { throw new IllegalStateException("Cold spatial audit failed", failure); }
    }

    private static String export(ServerLevel level) throws Exception {
        int tile = TerrainDiffusionConfig.tileSize();
        int x0 = Integer.getInteger("td.streamsProbe.coldX", -2048);
        int z0 = Integer.getInteger("td.streamsProbe.coldZ", -1792);
        int tiles = Integer.getInteger("td.streamsProbe.coldTiles", 8);
        int step = Integer.getInteger("td.streamsProbe.coldStep", 4);
        if (tiles < 1 || tiles > 8 || step < 1 || step > 16 || tile % step != 0)
            throw new IllegalArgumentException("Bounded tile grid and dividing sample step required");
        x0 = Math.floorDiv(x0, tile) * tile;
        z0 = Math.floorDiv(z0, tile) * tile;
        int scale = WorldScaleManager.getCurrentScale();
        var provider = LocalTerrainProvider.getInstance();
        Path output = Path.of(System.getProperty("td.streamsProbe.coldOutput", "cold-biomes.csv"));
        long calls = 0, windows = 0;
        int samples = 0, snow = 0;
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("x,z,elevation,regionalT,regionalElevation,localT,moisture,slope,treeCover,biomeId,terrainId,coldState,retention,snow,previousHeightMask\n");
            for (int tz = 0; tz < tiles; tz++) for (int tx = 0; tx < tiles; tx++) {
                int x = x0 + tx * tile, z = z0 + tz * tile;
                var data = provider.fetchHeightmap(z, x, z + tile, x + tile);
                var broad = LocalTerrainProvider.getBroadEcologyClimate(x, z, x + tile, z + tile, scale);
                if (data.ecology == null) throw new IllegalStateException("Missing ecology");
                var before = provider.performanceSnapshot();
                for (int dz = 0; dz < tile; dz += step) for (int dx = 0; dx < tile; dx += step) {
                    int bx = x + dx, bz = z + dz;
                    var climate = data.ecology.sample(dx, dz);
                    var regional = broad.sample(bx, bz);
                    float elevation = data.heightmap[dz][dx];
                    float retention = data.ecology.snowRetention(dx, dz);
                    boolean cover = EcologyClassifier.naturalSnowCover(bx, bz, elevation, climate.temperature(), retention);
                    float oldT = regional.temperature() - .006f * (Math.max(0, elevation) - Math.max(0, regional.elevation()));
                    float oldMargin = Math.max(-2 - oldT, Math.min((elevation - 1450) / 200, -.5f - oldT));
                    oldMargin += Math.max(-.65f, Math.min(.65f, retention)) * (1 - EcologyClassifier.smooth(1, 2, Math.abs(oldMargin)));
                    writer.write(bx + "," + bz + "," + elevation + "," + regional.temperature() + "," + regional.elevation()
                            + "," + climate.temperature() + "," + climate.moisture() + "," + climate.slope() + "," + climate.treeCover()
                            + "," + climate.biomeId() + "," + data.ecology.terrainBiomeId(dx, dz)
                            + "," + EcologyClassifier.coldState(climate.temperature()) + "," + retention + "," + (cover ? 1 : 0)
                            + "," + (oldMargin >= 0 ? 1 : 0) + "\n");
                    samples++;
                    if (cover) snow++;
                }
                var after = provider.performanceSnapshot();
                calls += after.hits() + after.misses() - before.hits() - before.misses();
                windows += after.computedWindows() - before.computedWindows();
            }
        }
        if (calls != 0 || windows != 0) throw new IllegalStateException("Cold decisions fetched terrain");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("event", "cold_biome_spatial_audit");
        receipt.addProperty("status", "passed");
        receipt.addProperty("seed", level.getSeed());
        receipt.addProperty("scale", scale);
        receipt.addProperty("samples", samples);
        receipt.addProperty("snow", snow);
        receipt.addProperty("decisionProviderCalls", calls);
        receipt.addProperty("decisionComputedWindows", windows);
        receipt.addProperty("csv", output.toAbsolutePath().toString());
        return receipt.toString();
    }
}
