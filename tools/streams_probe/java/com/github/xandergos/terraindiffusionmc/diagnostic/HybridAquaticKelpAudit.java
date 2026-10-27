package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import com.github.xandergos.terraindiffusionmc.compat.hybridaquatic.HybridAquaticKelpPlacementScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** Opt-in registry receipt for the three native Hybrid Aquatic kelp features. */
final class HybridAquaticKelpAudit {
    private static final List<ResourceLocation> TARGETS = List.of(id("hybrid_aquatic:bull_kelp"),
            id("hybrid_aquatic:delesseria"), id("hybrid_aquatic:sargassum"));

    private HybridAquaticKelpAudit() { }

    static String run(ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        StringBuilder rows = new StringBuilder();
        for (ResourceLocation target : TARGETS) {
            PlacedFeature feature = registry.getHolder(ResourceKey.create(Registries.PLACED_FEATURE, target))
                    .orElseThrow(() -> new IllegalStateException("Missing Hybrid Aquatic kelp feature " + target)).value();
            List<PlacementModifier> modifiers = feature.placement();
            if (modifiers.size() != 4) throw new IllegalStateException(target + " expected four native modifiers, got " + modifiers.size());
            String chain = modifiers.stream().map(HybridAquaticKelpAudit::encoded).reduce((a, b) -> a + ";" + b).orElse("");
            if (!chain.contains("noise_based_count") || !chain.contains("in_square")
                    || !chain.contains("OCEAN_FLOOR") || !chain.contains("biome"))
                throw new IllegalStateException(target + " lost native modifier chain: " + chain);
            if (rows.length() > 0) rows.append(',');
            Holder<Biome> validBiome = level.registryAccess().registryOrThrow(Registries.BIOME)
                    .holders().filter(b -> b.value().getGenerationSettings().hasFeature(feature))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No native biome for " + target));
            VirtualOcean ocean = new VirtualOcean(level, validBiome);
            var generator = level.getChunkSource().getGenerator();
            PlacementContext context = new PlacementContext(ocean.world, generator, Optional.of(feature));
            try (var scope = HybridAquaticKelpPlacementScope.enter(target, modifiers, context)) {
                if (!HybridAquaticKelpPlacementScope.replacesNoiseCount()) throw new IllegalStateException("scope missing");
                for (int x : new int[]{-32, -16, 0, 16, 32}) {
                    long attempts = modifiers.getFirst().getPositions(context, RandomSource.create(1), new BlockPos(x, 64, 0)).count();
                    if (attempts != 48) throw new IllegalStateException("chunk count was not replaced: " + attempts);
                }
                try (var nested = HybridAquaticKelpPlacementScope.enter(id("minecraft:kelp_warm"), modifiers, context)) {
                    if (HybridAquaticKelpPlacementScope.replacesNoiseCount()) throw new IllegalStateException("nested scope leak");
                }
                if (!HybridAquaticKelpPlacementScope.replacesNoiseCount()) throw new IllegalStateException("outer scope lost");
            }
            for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++)
                feature.placeWithBiomeCheck(ocean.world, generator, RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            long stems = ocean.blocks.values().stream().filter(s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals("hybrid_aquatic")).count();
            if (stems == 0 || !ocean.observedScope) throw new IllegalStateException("native kelp not placed: " + target);
            ocean.blocks.clear();
            ocean.dry = true;
            for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++)
                feature.placeWithBiomeCheck(ocean.world, generator, RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            if (!ocean.blocks.isEmpty()) throw new IllegalStateException("dry kelp placement");
            ocean.dry = false;
            ocean.biome = level.registryAccess().registryOrThrow(Registries.BIOME).holders()
                    .filter(b -> !b.value().getGenerationSettings().hasFeature(feature)).findFirst().orElseThrow();
            for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++)
                feature.placeWithBiomeCheck(ocean.world, generator, RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            if (!ocean.blocks.isEmpty()) throw new IllegalStateException("biome filter bypassed");
            if (HybridAquaticKelpPlacementScope.replacesNoiseCount()) throw new IllegalStateException("scope leaked");
            rows.append("\"").append(target).append("\":{\"nativePlantBlocks\":").append(stems)
                    .append(",\"fixedCandidates\":48,\"dryRejected\":true,\"excludedBiomeRejected\":true}");
        }
        return "{\"event\":\"hybrid_aquatic_kelp_audit\",\"status\":\"passed\",\"targets\":{"
                + rows + "},\"limitation\":\"native registered placements on virtual flat ocean; no visual acceptance\"}";
    }

    private static final class VirtualOcean {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        Holder<Biome> biome;
        boolean dry, observedScope;
        final WorldGenLevel world;

        VirtualOcean(ServerLevel level, Holder<Biome> biome) {
            this.biome = biome;
            world = (WorldGenLevel) Proxy.newProxyInstance(HybridAquaticKelpAudit.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                        observedScope |= HybridAquaticKelpPlacementScope.replacesNoiseCount();
                        String name = method.getName();
                        if (name.equals("getBiome")) return this.biome;
                        if (name.equals("getHeight") && args != null && args.length == 3) return 64;
                        if (name.equals("ensureCanWrite") || name.equals("hasChunk") || name.equals("hasChunkAt")) return true;
                        if (name.equals("getBlockState") || name.equals("getFluidState") || name.equals("isEmptyBlock")) {
                            BlockPos p = (BlockPos) args[0];
                            BlockState state = blocks.getOrDefault(p, p.getY() < 64 ? Blocks.SAND.defaultBlockState()
                                    : !dry && p.getY() < 80 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                            if (name.equals("isEmptyBlock")) return state.isAir();
                            return name.equals("getFluidState") ? state.getFluidState() : state;
                        }
                        if (name.equals("setBlock")) {
                            blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                            return true;
                        }
                        try { return method.invoke(level, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
        }
    }

    private static String encoded(PlacementModifier modifier) {
        return PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).getOrThrow().toString();
    }
    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
}
