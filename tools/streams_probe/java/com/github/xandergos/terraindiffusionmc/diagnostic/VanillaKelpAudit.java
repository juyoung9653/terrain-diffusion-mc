package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementPolicy;
import com.github.xandergos.terraindiffusionmc.compat.vanillakelp.VanillaKelpPlacementScope;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** Opt-in receipt for vanilla cold and warm kelp under the TD natural generator. */
final class VanillaKelpAudit {
    private static final List<Target> TARGETS = List.of(
            new Target(id("kelp_cold"), 120, 0x3EAE9B59A7F116C1L,
                    VanillaKelpPlacementPolicy.COLD_ACCEPTANCE),
            new Target(id("kelp_warm"), 80, 0x76D4F9082C5AB137L,
                    VanillaKelpPlacementPolicy.WARM_ACCEPTANCE));

    private VanillaKelpAudit() { }

    static String run(ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        StringBuilder rows = new StringBuilder();
        for (Target target : TARGETS) {
            PlacedFeature feature = registry.getHolder(ResourceKey.create(Registries.PLACED_FEATURE,
                    target.id)).orElseThrow(() -> new IllegalStateException("Missing vanilla kelp "
                    + target.id)).value();
            List<PlacementModifier> modifiers = feature.placement();
            verifyChain(target, modifiers);
            Holder<Biome> validBiome = level.registryAccess().registryOrThrow(Registries.BIOME)
                    .holders().filter(b -> b.value().getGenerationSettings().hasFeature(feature))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No native biome for "
                            + target.id));
            VirtualOcean ocean = new VirtualOcean(level, validBiome);
            var generator = level.getChunkSource().getGenerator();
            PlacementContext context = new PlacementContext(ocean.world, generator, Optional.of(feature));
            try (var scope = VanillaKelpPlacementScope.enter(target.id, modifiers, context)) {
                if (!VanillaKelpPlacementScope.replacesNoiseCount())
                    throw new IllegalStateException("scope missing for " + target.id);
                for (int x : new int[]{-32, -16, 0, 16, 32}) {
                    long attempts = modifiers.getFirst().getPositions(context, RandomSource.create(1),
                            new BlockPos(x, 64, 0)).count();
                    if (attempts != VanillaKelpPlacementPolicy.CANDIDATES_PER_CHUNK)
                        throw new IllegalStateException("candidate count not replaced: " + attempts);
                }
                try (var nested = VanillaKelpPlacementScope.enter(target.id,
                        modifiers.subList(0, 3), context)) {
                    if (VanillaKelpPlacementScope.replacesNoiseCount())
                        throw new IllegalStateException("custom-chain scope accepted");
                }
                if (!VanillaKelpPlacementScope.replacesNoiseCount())
                    throw new IllegalStateException("outer scope lost");
            }
            for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++)
                feature.placeWithBiomeCheck(ocean.world, generator,
                        RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            long plants = ocean.blocks.values().stream().filter(state -> state.is(Blocks.KELP)
                    || state.is(Blocks.KELP_PLANT)).count();
            if (plants == 0 || !ocean.observedScope)
                throw new IllegalStateException("native kelp not placed: " + target.id);
            int deepStalks = stalks(ocean.blocks);
            int oneBlockStalks = placeAtDepth(feature, ocean, generator, 1);
            int shelfSixStalks = placeAtDepth(feature, ocean, generator, 6);
            int transitionSevenStalks = placeAtDepth(feature, ocean, generator, 7);
            int deepTwelveStalks = placeAtDepth(feature, ocean, generator, 12);
            if (oneBlockStalks != 0 || shelfSixStalks != 0 || deepTwelveStalks == 0
                    || transitionSevenStalks > deepTwelveStalks)
                throw new IllegalStateException("native kelp littoral gate failed: " + target.id
                        + " one=" + oneBlockStalks + " shelfSix=" + shelfSixStalks
                        + " transitionSeven=" + transitionSevenStalks + " deepTwelve="
                        + deepTwelveStalks);
            ocean.blocks.clear();
            ocean.dry = true;
            for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++)
                feature.placeWithBiomeCheck(ocean.world, generator,
                        RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            if (!ocean.blocks.isEmpty()) throw new IllegalStateException("dry kelp placement");
            ocean.dry = false;
            ocean.biome = level.registryAccess().registryOrThrow(Registries.BIOME).holders()
                    .filter(b -> !b.value().getGenerationSettings().hasFeature(feature)).findFirst()
                    .orElseThrow();
            for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++)
                feature.placeWithBiomeCheck(ocean.world, generator,
                        RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
            if (!ocean.blocks.isEmpty()) throw new IllegalStateException("biome filter bypassed");
            if (VanillaKelpPlacementScope.replacesNoiseCount())
                throw new IllegalStateException("scope leaked");
            int shelfAccepted = 0;
            int transitionAccepted = 0;
            int deepAccepted = 0;
            for (int x = -128; x < 128; x++) for (int z = -128; z < 128; z++) {
                if (VanillaKelpPlacementPolicy.accept(level.getSeed(), target.salt,
                        target.acceptance, 6, x, z)) shelfAccepted++;
                if (VanillaKelpPlacementPolicy.accept(level.getSeed(), target.salt,
                        target.acceptance, 7, x, z)) transitionAccepted++;
                if (VanillaKelpPlacementPolicy.accept(level.getSeed(), target.salt,
                        target.acceptance, 12, x, z)) deepAccepted++;
            }
            if (shelfAccepted != 0 || deepAccepted == 0 || transitionAccepted > deepAccepted)
                throw new IllegalStateException("coordinate kelp littoral gate failed: " + target.id);
            if (rows.length() > 0) rows.append(',');
            rows.append("\"").append(target.id).append("\":{\"nativePlantBlocks\":")
                    .append(plants).append(",\"fixedCandidates\":")
                    .append(VanillaKelpPlacementPolicy.CANDIDATES_PER_CHUNK)
                    .append(",\"oneBlockNativeStalks\":").append(oneBlockStalks)
                    .append(",\"shelfSixNativeStalks\":").append(shelfSixStalks)
                    .append(",\"transitionSevenNativeStalks\":").append(transitionSevenStalks)
                    .append(",\"deepTwelveNativeStalks\":").append(deepTwelveStalks)
                    .append(",\"shelfSixAccepted\":").append(shelfAccepted)
                    .append(",\"transitionSevenAccepted\":").append(transitionAccepted)
                    .append(",\"deepAccepted\":").append(deepAccepted)
                    .append(",\"customChainRejected\":true,\"dryRejected\":true,"
                            + "\"excludedBiomeRejected\":true}");
        }
        return "{\"event\":\"vanilla_kelp_audit\",\"status\":\"passed\",\"targets\":{" + rows
                + "},\"limitation\":\"native registered placements on virtual flat ocean; no client visual acceptance\"}";
    }

    private static void verifyChain(Target target, List<PlacementModifier> modifiers) {
        if (modifiers.size() != 4) throw new IllegalStateException(target.id
                + " expected four native modifiers, got " + modifiers.size());
        String chain = modifiers.stream().map(VanillaKelpAudit::encoded)
                .reduce((a, b) -> a + ";" + b).orElse("");
        if (!chain.contains("noise_based_count") || !chain.contains("in_square")
                || !chain.contains("OCEAN_FLOOR_WG")
                || !chain.contains("biome"))
            throw new IllegalStateException(target.id + " changed native modifier chain: " + chain);
    }

    /** Same native feature, seeds and origins at each depth; KELP is one top per stalk. */
    private static void placeGrid(PlacedFeature feature, WorldGenLevel world,
                                  net.minecraft.world.level.chunk.ChunkGenerator generator) {
        for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++)
            feature.placeWithBiomeCheck(world, generator,
                    RandomSource.create(991L + x * 103L + z), new BlockPos(x * 16, 64, z * 16));
    }

    /** The virtual floor is Y=63, so waterTop yields exactly waterTop-64 cells. */
    private static int placeAtDepth(PlacedFeature feature, VirtualOcean ocean,
                                    net.minecraft.world.level.chunk.ChunkGenerator generator, int depth) {
        ocean.blocks.clear();
        ocean.waterTop = 64 + depth;
        placeGrid(feature, ocean.world, generator);
        return stalks(ocean.blocks);
    }

    private static int stalks(Map<BlockPos, BlockState> blocks) {
        return (int) blocks.values().stream().filter(state -> state.is(Blocks.KELP)).count();
    }

    private static final class VirtualOcean {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        Holder<Biome> biome;
        boolean dry, observedScope;
        int waterTop = 80;
        final WorldGenLevel world;

        VirtualOcean(ServerLevel level, Holder<Biome> biome) {
            this.biome = biome;
            world = (WorldGenLevel) Proxy.newProxyInstance(VanillaKelpAudit.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                        observedScope |= VanillaKelpPlacementScope.replacesNoiseCount();
                        String name = method.getName();
                        if (name.equals("getBiome")) return this.biome;
                        if (name.equals("getHeight") && args != null && args.length == 3) return 64;
                        if (name.equals("ensureCanWrite") || name.equals("hasChunk")
                                || name.equals("hasChunkAt")) return true;
                        if (name.equals("getBlockState") || name.equals("getFluidState")
                                || name.equals("isEmptyBlock")) {
                            BlockPos pos = (BlockPos) args[0];
                            BlockState state = blocks.getOrDefault(pos, pos.getY() < 64
                                    ? Blocks.SAND.defaultBlockState() : !dry && pos.getY() < waterTop
                                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
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

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private record Target(ResourceLocation id, int ratio, long salt, double acceptance) { }
}
