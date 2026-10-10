package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only actual-column snow rejection audit. Does not load or modify chunks. */
public final class SnowGroundAudit {
    private static final FastNoiseLite OLD_PATCHES = noise(82741, 24);
    private static final FastNoiseLite OLD_EDGE = noise(91283, 3);
    private SnowGroundAudit() { }

    public static List<String> run(ServerLevel level, int chunkX, int chunkZ, int radius) {
        Map<String, Integer> counts = new TreeMap<>();
        JsonArray samples = new JsonArray();
        Map<String, Integer> sampled = new TreeMap<>();
        var generator = level.getChunkSource().getGenerator();
        int boundedRadius = Math.max(0, Math.min(3, radius));
        for (int cz = chunkZ - boundedRadius; cz <= chunkZ + boundedRadius; cz++) {
            for (int cx = chunkX - boundedRadius; cx <= chunkX + boundedRadius; cx++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (int z = cz * 16; z < cz * 16 + 16; z++) for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    var surface = NaturalEcology.surfaceSample(generator, x, z);
                    if (surface == null) continue;
                    float coverage = EcologyClassifier.naturalSnowCoverage(x, z, surface.elevation(),
                            surface.climate().temperature());
                    boolean eligible = NaturalSnowCover.eligible(generator, x, z);
                    boolean oldEligible = coverage >= 1 || coverage > 0 && coverage > Math.max(.02f,
                            Math.min(.98f, .5f + OLD_PATCHES.GetNoise(x, z) + .12f * OLD_EDGE.GetNoise(x, z)));
                    int roofY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    BlockPos roof = new BlockPos(x, roofY, z);
                    var top = level.getBlockState(roof.below());
                    boolean canopy = NaturalSnowCover.raw(level, roof.below(), top).is(BlockTags.LEAVES)
                            || NaturalSnowCover.raw(level, roof.below(2), level.getBlockState(roof.below(2))).is(BlockTags.LEAVES);
                    boolean cap = NaturalSnowCover.isSnow(top) || NaturalSnowCover.isSnow(level.getBlockState(roof));
                    BlockPos ground = NaturalSnowCover.floor(level, x, z);
                    String reason;
                    if (ground == null) reason = "blocked_by_trunk_fluid_or_depth";
                    else {
                        var at = level.getBlockState(ground);
                        if (NaturalSnowCover.isSnow(at)) reason = "snow_present";
                        else if (!eligible) reason = "mask_out";
                        else if (!at.getFluidState().isEmpty()) reason = "fluid";
                        else if (NaturalSnowCover.raw(level, ground, at).is(BlockTags.LEAVES)) reason = "ground_occupied_by_leaves";
                        else if (level.getBrightness(LightLayer.BLOCK, ground) >= 10) reason = "block_light";
                        else if (!Blocks.SNOW.defaultBlockState().canSurvive(level, ground)) reason = "unsupported_shape";
                        else if (at.isAir()) reason = "placeable_air_bare";
                        else reason = "plant_conversion_or_occupied";
                    }
                    String key = (canopy ? "canopy/" : "open/") + reason;
                    counts.merge(key, 1, Integer::sum);
                    if (canopy && cap) counts.merge("snow_capped_canopy", 1, Integer::sum);
                    if (canopy && cap && !oldEligible) counts.merge("snow_capped_canopy_old_mask_out", 1, Integer::sum);
                    if (eligible) counts.merge("eligible_columns", 1, Integer::sum);
                    if (oldEligible) counts.merge("old_eligible_columns", 1, Integer::sum);
                    if (sampled.getOrDefault(key, 0) >= 4) continue;
                    sampled.merge(key, 1, Integer::sum);
                    JsonObject row = new JsonObject();
                    row.addProperty("reason", key);
                    row.addProperty("x", x); row.addProperty("z", z);
                    row.addProperty("eligible", eligible); row.addProperty("coverage", coverage);
                    row.addProperty("oldEligible", oldEligible);
                    row.addProperty("elevation", surface.elevation());
                    row.addProperty("temperature", surface.climate().temperature());
                    row.addProperty("roofY", roofY); row.addProperty("snowCap", cap);
                    row.addProperty("roofSupport", top.toString());
                    if (ground != null) {
                        row.addProperty("groundY", ground.getY());
                        row.addProperty("groundState", level.getBlockState(ground).toString());
                        row.addProperty("support", level.getBlockState(ground.below()).toString());
                        row.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, ground));
                        row.addProperty("survives", Blocks.SNOW.defaultBlockState().canSurvive(level, ground));
                    }
                    samples.add(row);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("event", "snow_ground_audit");
        result.addProperty("seed", level.getSeed());
        result.addProperty("chunkX", chunkX); result.addProperty("chunkZ", chunkZ);
        JsonObject totals = new JsonObject();
        counts.forEach(totals::addProperty);
        result.add("counts", totals); result.add("samples", samples);
        try {
            return List.of(result.toString(), SimpleSnowyFixAudit.run(level, chunkX, chunkZ));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Simple Snowy Fix runtime audit failed", error);
        }
    }

    private static FastNoiseLite noise(int seed, int wavelength) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / wavelength);
        return noise;
    }
}
