package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** World-local eligibility for Lush Cave Ruins; native placement and geometry stay intact. */
public final class LushCaveRuinsCompatibility {
    public static final String STRUCTURE_ID = "keeb_struc_lush:keeb_struc_lush";
    private static final ResourceKey<Biome> JUNGLE = ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("terralith", "cave/underground_jungle"));

    private LushCaveRuinsCompatibility() { }

    private static boolean target(Holder<Structure> structure) {
        return structure.unwrapKey().map(key -> STRUCTURE_ID.equals(key.location().toString())).orElse(false);
    }

    private static boolean hasJungle(BiomeSource source) {
        return TerrainDiffusionBiomeSources.isTerrainDiffusion(source)
                && source.possibleBiomes().stream().anyMatch(biome -> biome.is(JUNGLE));
    }

    public static boolean retainsSet(StructureSet set, BiomeSource source) {
        return set.placement() instanceof RandomSpreadStructurePlacement && hasJungle(source)
                && set.structures().stream().anyMatch(entry -> target(entry.structure()));
    }

    /** The native-only underground start must not become a speculative surface-planner rival. */
    public static boolean isNativeOnlySet(StructureSet set) {
        return set.structures().size() == 1 && target(set.structures().getFirst().structure());
    }

    /** Vanilla filters biome eligibility again when constructing the locate placement map. */
    public static void addPlacements(BiomeSource source, List<Holder<StructureSet>> sets,
                                     Map<Structure, List<StructurePlacement>> placements) {
        if (!hasJungle(source)) return;
        for (Holder<StructureSet> holder : sets) {
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement)) continue;
            for (var entry : set.structures()) {
                if (!target(entry.structure())) continue;
                List<StructurePlacement> values = placements.computeIfAbsent(entry.structure().value(),
                        ignored -> new ArrayList<>());
                if (!values.contains(set.placement())) values.add(set.placement());
            }
        }
    }

    public static Predicate<Holder<Biome>> validBiomes(BiomeSource source, String structureId,
                                                      Predicate<Holder<Biome>> original) {
        if (!STRUCTURE_ID.equals(structureId) || !hasJungle(source))
            return original;
        return biome -> original.test(biome) || biome.is(JUNGLE);
    }
}
