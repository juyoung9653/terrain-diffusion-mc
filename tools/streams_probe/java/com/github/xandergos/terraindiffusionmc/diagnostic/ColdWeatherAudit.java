package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Real transformed weather entry points, virtual substrate, and real cached climate; no world writes. */
public final class ColdWeatherAudit {
    private ColdWeatherAudit() { }

    public static String run(ServerLevel level, int chunkX, int chunkZ) throws ReflectiveOperationException {
        if (!level.hasChunk(chunkX, chunkZ)) return "{\"event\":\"cold_weather_audit\",\"loaded\":false}";
        int size = TerrainDiffusionConfig.tileSize();
        int x0 = Math.floorDiv(chunkX * 16, size) * size;
        int z0 = Math.floorDiv(chunkZ * 16, size) * size;
        var provider = LocalTerrainProvider.getInstance();
        var data = provider.fetchHeightmap(z0, x0, z0 + size, x0 + size);
        if (data.ecology == null) throw new AssertionError("Cold weather audit requires natural ecology");
        int minimum = 0, maximum = 0;
        for (int i = 1; i < size * size; i++) {
            float t = data.ecology.sample(i % size, i / size).temperature();
            if (t < data.ecology.sample(minimum % size, minimum / size).temperature()) minimum = i;
            if (t > data.ecology.sample(maximum % size, maximum / size).temperature()) maximum = i;
        }
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> cold = registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/snowy_boreal_forest")));
        Holder<Biome> warm = registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/tropical_forest")));
        Method srm = null;
        try {
            srm = Class.forName("snownee.snow.util.CommonProxy").getMethod("coldEnoughToSnow",
                    LevelReader.class, BlockPos.class, Holder.class);
        } catch (ClassNotFoundException absent) { }
        VirtualRegion region = new VirtualRegion(level, chunkX, chunkZ, cold);
        var before = provider.performanceSnapshot();
        int cases = 0, coldColumns = 0, warmColumns = 0;
        for (int index : new int[]{minimum, maximum}) {
            int dx = index % size, dz = index / size;
            region.pos = new BlockPos(x0 + dx, 100, z0 + dz);
            float temperature = data.ecology.sample(dx, dz).temperature();
            boolean snow = EcologyClassifier.naturalSnowCover(region.pos.getX(), region.pos.getZ(),
                    data.heightmap[dz][dx], temperature, data.ecology.snowRetention(dx, dz));
            boolean ice = EcologyClassifier.naturalIceCover(region.pos.getX(), region.pos.getZ(),
                    data.heightmap[dz][dx], temperature, data.ecology.snowRetention(dx, dz));
            if (snow) coldColumns++; else warmColumns++;
            for (Holder<Biome> biome : java.util.List.of(cold, warm)) {
                region.biome = biome; region.light = 0;
                region.at = Blocks.AIR.defaultBlockState();
                require(biome.value().shouldSnow(region, region.pos) == snow, "registry temperature bypassed cached snow climate");
                if (srm != null) require((boolean) srm.invoke(null, region, region.pos, biome) == snow,
                        "SRM bypassed cached snow climate");
                region.at = Blocks.WATER.defaultBlockState();
                require(biome.value().shouldFreeze(region, region.pos, false) == ice, "registry temperature bypassed cached ice climate");
                region.light = 12;
                require(!biome.value().shouldFreeze(region, region.pos, false), "ice light guard lost");
                region.at = Blocks.AIR.defaultBlockState();
                require(!biome.value().shouldSnow(region, region.pos), "snow light guard lost");
                region.light = 0; region.at = Blocks.WATER.defaultBlockState();
                require(!biome.value().shouldSnow(region, region.pos), "snow fluid guard lost");
                cases += srm == null ? 5 : 6;
            }
        }
        // Probe a nonresident coordinate without clearing the live world's caches or loading chunks.
        int missX = x0;
        boolean found = false;
        for (int n = 1; n <= 512; n++) {
            missX = x0 + n * size;
            if (provider.peekHeightmap(z0, missX, z0 + size, missX + size) == null) { found = true; break; }
        }
        require(found, "could not find a cache miss");
        region.pos = new BlockPos(missX, 100, z0); region.biome = cold;
        region.light = 0; region.at = Blocks.AIR.defaultBlockState();
        require(!cold.value().shouldSnow(region, region.pos), "cache miss painted snow from cold registry label");
        if (srm != null) require(!(boolean) srm.invoke(null, region, region.pos, cold), "SRM cache miss painted snow");
        region.at = Blocks.WATER.defaultBlockState();
        require(!cold.value().shouldFreeze(region, region.pos, false), "cache miss froze water from registry label");
        region.at = Blocks.SNOW.defaultBlockState();
        require(cold.value().shouldSnow(region, region.pos), "cache miss lost existing snow eligibility");
        require(Boolean.TRUE.equals(NaturalSnowCover.weatherEligible(region, region.pos, false)), "existing snow not retained");
        region.at = Blocks.ICE.defaultBlockState();
        require(Boolean.TRUE.equals(NaturalSnowCover.weatherEligible(region, region.pos, true)), "existing ice not retained");
        cases += srm == null ? 5 : 6;
        var after = provider.performanceSnapshot();
        long computations = after.computedWindows() - before.computedWindows();
        long misses = after.misses() - before.misses();
        require(computations == 0 && misses == 0, "weather lookup triggered inference");
        JsonObject result = new JsonObject();
        result.addProperty("event", "cold_weather_audit"); result.addProperty("passed", true);
        result.addProperty("status", "passed");
        result.addProperty("x0", x0); result.addProperty("z0", z0); result.addProperty("cases", cases);
        result.addProperty("minimumTemperature", data.ecology.sample(minimum % size, minimum / size).temperature());
        result.addProperty("maximumTemperature", data.ecology.sample(maximum % size, maximum / size).temperature());
        result.addProperty("snowEligibleColumns", coldColumns); result.addProperty("snowIneligibleColumns", warmColumns);
        result.addProperty("srmAvailable", srm != null); result.addProperty("computedWindows", computations);
        result.addProperty("providerMisses", misses); result.addProperty("actualWorldWrites", 0);
        return result.toString();
    }

    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }

    private static final class VirtualRegion extends WorldGenRegion {
        BlockPos pos;
        Holder<Biome> biome;
        BlockState at = Blocks.AIR.defaultBlockState();
        int light;
        VirtualRegion(ServerLevel level, int chunkX, int chunkZ, Holder<Biome> biome) {
            super(level, null, null, level.getChunk(chunkX, chunkZ));
            this.biome = biome;
        }
        @Override public BlockState getBlockState(BlockPos requested) {
            if (requested.equals(pos)) return at;
            return requested.equals(pos.below()) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos requested) { return getBlockState(requested).getFluidState(); }
        @Override public Holder<Biome> getBiome(BlockPos requested) { return biome; }
        @Override public int getBrightness(LightLayer layer, BlockPos requested) { return layer == LightLayer.BLOCK ? light : 15; }
        @Override public boolean setBlock(BlockPos requested, BlockState state, int flags, int recursion) {
            throw new AssertionError("Weather eligibility attempted a world write");
        }
    }
}
