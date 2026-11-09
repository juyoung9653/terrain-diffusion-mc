package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Opt-in, read-only diagnosis of loaded river columns; never generates additional chunks. */
public final class RiverClimateAudit {
    private RiverClimateAudit() { }

    public static List<String> run(ServerLevel level) {
        List<String> output = new ArrayList<>();
        try {
            output.add(BankSupportAudit.run());
            Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                    .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            for (int[] site : sites()) {
                if (!level.hasChunk(site[0] >> 4, site[2] >> 4)) continue;
                JsonObject entry = sample(level, plan, engine, site[0], site[1], site[2]);
                entry.addProperty("event", "river_climate_audit");
                entry.addProperty("seed", level.getSeed());
                entry.addProperty("profile", WorldNaturalRiverState.version(level));
                if (site[0] == 985 || site[0] == 606) {
                    JsonArray cross = new JsonArray();
                    for (int axis = 0; axis < 2; axis++) for (int d = -24; d <= 24; d += 2) {
                        int x = site[0] + (axis == 0 ? d : 0), z = site[2] + (axis == 1 ? d : 0);
                        if (!level.hasChunk(x >> 4, z >> 4)) continue;
                        cross.add(sample(level, plan, engine, x, site[1], z));
                    }
                    entry.add("crossSections", cross);
                }
                output.add(entry.toString());
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("River climate audit requires the installed Streams plan API", error);
        }
        return output;
    }

    private static List<int[]> sites() {
        String configured = System.getProperty("td.streamsProbe.riverClimateSites",
                "985,85,1007;976,85,1007;977,85,1007;969,85,1007;973,85,1007;961,85,975;606,75,1017");
        List<int[]> sites = new ArrayList<>();
        for (String site : configured.split(";")) {
            String[] fields = site.split(",");
            if (fields.length != 3 || sites.size() >= 32)
                throw new IllegalArgumentException("Expected at most 32 riverClimateSites as X,Y,Z triples");
            sites.add(new int[]{Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2])});
        }
        return sites;
    }

    private static JsonObject sample(ServerLevel level, Method planMethod, Object engine, int x, int y, int z)
            throws ReflectiveOperationException {
        JsonObject row = new JsonObject();
        row.addProperty("x", x); row.addProperty("y", y); row.addProperty("z", z);
        BlockPos pos = new BlockPos(x, y, z);
        var generator = level.getChunkSource().getGenerator();
        var climate = NaturalEcology.surfaceSample(generator, x, z);
        if (climate != null) {
            row.addProperty("terrainBiomeId", climate.terrainBiomeId());
            row.addProperty("ecologyBiomeId", climate.climate().biomeId());
            row.addProperty("elevation", climate.elevation());
            row.addProperty("temperature", climate.climate().temperature());
            row.addProperty("moisture", climate.climate().moisture());
            row.addProperty("slope", climate.climate().slope());
        }
        Holder<Biome> source = generator.getBiomeSource().getNoiseBiome(x >> 2, y >> 2, z >> 2,
                level.getChunkSource().randomState().sampler());
        Holder<Biome> stored = level.getBiome(pos);
        row.addProperty("sourceBiome", source.unwrapKey().map(key -> key.location().toString()).orElse("unknown"));
        row.addProperty("storedBiome", stored.unwrapKey().map(key -> key.location().toString()).orElse("unknown"));
        row.addProperty("sourceCold", source.value().coldEnoughToSnow(pos));
        row.addProperty("storedCold", stored.value().coldEnoughToSnow(pos));
        row.addProperty("sourceFreeze", source.value().shouldFreeze(level, pos, false));
        row.addProperty("storedFreeze", stored.value().shouldFreeze(level, pos, false));
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        row.addProperty("surfaceY", top);
        row.addProperty("at", level.getBlockState(pos).toString());
        row.addProperty("above", level.getBlockState(pos.above()).toString());
        row.addProperty("below", level.getBlockState(pos.below()).toString());
        row.addProperty("climateGrassReplacement",
                com.github.xandergos.terraindiffusionmc.compat.streams.StreamsBankMaterialPolicy
                        .climateBankTop(level, x, y, z, Blocks.GRASS_BLOCK.defaultBlockState()).toString());
        row.addProperty("top", level.getBlockState(new BlockPos(x, top, z)).toString());
        row.addProperty("plan", planMethod.invoke(engine, x, z).toString());
        return row;
    }
}
