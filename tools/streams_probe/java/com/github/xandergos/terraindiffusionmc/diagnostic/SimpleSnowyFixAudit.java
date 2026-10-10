package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.snow.NaturalSnowCover;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/** Exercises the optional mod's real entry point with virtual blocks and the actual column climate. */
final class SimpleSnowyFixAudit {
    private SimpleSnowyFixAudit() { }

    static String run(ServerLevel level, int chunkX, int chunkZ) throws ReflectiveOperationException {
        if (!level.hasChunk(chunkX, chunkZ))
            return "{\"event\":\"simple_snowy_fix_audit\",\"loaded\":false}";
        Class<?> mod;
        try { mod = Class.forName("dev.kostromdan.mods.simple_snowy_fix.SimpleSnowyFixMod"); }
        catch (ClassNotFoundException absent) {
            return "{\"event\":\"simple_snowy_fix_audit\",\"available\":false}";
        }
        Method place = mod.getMethod("placeSnowOnLeaves", LevelAccessor.class, BlockPos.class, BlockPos.class);
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        BlockPos leaf = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 3, z);
        boolean eligible = NaturalSnowCover.eligible(level.getChunkSource().getGenerator(), x, z);
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> cold = registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/snowy_boreal_forest")));
        Holder<Biome> warm = registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "ecology/tropical_forest")));
        VirtualRegion region = new VirtualRegion(level, chunkX, chunkZ, leaf, cold);
        check(place, region, cold, Blocks.AIR.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 0, eligible);
        check(place, region, warm, Blocks.AIR.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 0, eligible);
        check(place, region, cold, Blocks.AIR.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 12, false);
        check(place, region, cold, Blocks.WATER.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 0, false);
        check(place, region, cold, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), 0, false);
        check(place, region, cold, Blocks.SNOW.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 0, false);
        JsonObject result = new JsonObject();
        result.addProperty("event", "simple_snowy_fix_audit"); result.addProperty("passed", true);
        result.addProperty("eligible", eligible); result.addProperty("x", x); result.addProperty("z", z);
        result.addProperty("cases", 6); result.addProperty("actualWorldWrites", 0);
        return result.toString();
    }

    private static void check(Method place, VirtualRegion region, Holder<Biome> biome,
                              BlockState at, BlockState leaf, int light, boolean expected)
            throws ReflectiveOperationException {
        region.biome = biome; region.at = at; region.support = leaf; region.light = light; region.writes = 0;
        place.invoke(null, region, region.leaf, region.leaf);
        if ((region.writes == 1) != expected || !region.support.equals(leaf)
                || !expected && !region.at.equals(at))
            throw new AssertionError("Simple Snowy Fix changed a guarded column: expected=" + expected
                    + " writes=" + region.writes + " support=" + leaf + " at=" + at + " light=" + light);
    }

    private static final class VirtualRegion extends WorldGenRegion {
        private final BlockPos leaf;
        private Holder<Biome> biome;
        private BlockState at, support;
        private int light, writes;

        private VirtualRegion(ServerLevel level, int chunkX, int chunkZ, BlockPos leaf, Holder<Biome> biome) {
            // All spatial reads/writes are overridden; the superclass only supplies real generator context.
            super(level, null, null, level.getChunk(chunkX, chunkZ));
            this.leaf = leaf; this.biome = biome;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.equals(leaf)) return support;
            return pos.equals(leaf.above()) ? at : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { return leaf.getY() + 1; }
        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) { return layer == LightLayer.BLOCK ? light : 15; }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            if (!pos.equals(leaf.above()) || !state.is(Blocks.SNOW) || flags != 19)
                throw new AssertionError("Unexpected Simple Snowy Fix write: " + pos + " " + state + " flags=" + flags);
            at = state; writes++; return true;
        }
    }
}
