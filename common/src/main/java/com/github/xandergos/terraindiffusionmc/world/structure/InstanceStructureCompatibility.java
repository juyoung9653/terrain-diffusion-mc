package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * World-local adapters for structures whose original biome is deliberately not
 * part of TD's natural biome palette.  These adapters never change a normal
 * world's predicate, placement, or registry data.
 */
public final class InstanceStructureCompatibility {
    private static final ResourceKey<Biome> UNDERGROUND_JUNGLE = biome("terralith", "cave/underground_jungle");
    private static final ResourceKey<Biome> DEEP_CAVES = biome("terralith", "cave/deep_caves");
    private static final ResourceKey<Biome> FUNGAL_CAVES = biome("terralith", "cave/fungal_caves");
    private static final ResourceKey<Biome> MANGROVE_SWAMP = biome("terrain-diffusion-mc", "ecology/mangrove_swamp");

    private static final Map<String, ResourceKey<Biome>> BIOMES = Map.ofEntries(
            Map.entry("hopo:portal/overworld/portal_lush", UNDERGROUND_JUNGLE),
            Map.entry("hopo:portal/overworld/portal_dripstone", DEEP_CAVES),
            Map.entry("cataclysm:amethyst_nest", UNDERGROUND_JUNGLE),
            Map.entry("structory:swamp_ruin", MANGROVE_SWAMP),
            Map.entry("terralith:underground/oak_cabin", UNDERGROUND_JUNGLE),
            Map.entry("terralith:underground_cabin", UNDERGROUND_JUNGLE),
            Map.entry("bettermineshafts:mineshaft_lush", UNDERGROUND_JUNGLE),
            Map.entry("bettermineshafts:mineshaft_dripstone", DEEP_CAVES),
            Map.entry("bettermineshafts:mineshaft_mushroom", FUNGAL_CAVES));

    private InstanceStructureCompatibility() { }

    private static ResourceKey<Biome> biome(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    private static String id(Holder<Structure> structure) {
        return structure.unwrapKey().map(key -> key.location().toString()).orElse("");
    }

    private static boolean hasBiome(BiomeSource source, ResourceKey<Biome> target) {
        return TerrainDiffusionBiomeSources.isTerrainDiffusion(source)
                && source.possibleBiomes().stream().anyMatch(biome -> biome.is(target));
    }

    private static boolean applies(Holder<Structure> structure, BiomeSource source) {
        ResourceKey<Biome> target = BIOMES.get(id(structure));
        return target != null && hasBiome(source, target);
    }

    /** Preserve a set when TD supplies an explicit replacement biome for a member. */
    public static boolean retainsSet(StructureSet set, BiomeSource source) {
        return TerrainDiffusionBiomeSources.isTerrainDiffusion(source)
                && set.structures().stream().anyMatch(structure -> applies(structure.structure(), source));
    }

    /** Keep locate's placement map aligned with {@link #retainsSet}. */
    public static void addPlacements(BiomeSource source, List<Holder<StructureSet>> sets,
                                     Map<Structure, List<StructurePlacement>> placements) {
        if (!TerrainDiffusionBiomeSources.isTerrainDiffusion(source)) return;
        for (Holder<StructureSet> holder : sets) {
            for (var entry : holder.value().structures()) {
                if (!applies(entry.structure(), source)) continue;
                List<StructurePlacement> values = placements.computeIfAbsent(entry.structure().value(),
                        ignored -> new ArrayList<>());
                if (!values.contains(holder.value().placement())) values.add(holder.value().placement());
            }
        }
    }

    /** Add only the mapped TD biome while preserving the structure's original predicate. */
    public static Predicate<Holder<Biome>> validBiomes(BiomeSource source, String structureId,
                                                        Predicate<Holder<Biome>> original) {
        ResourceKey<Biome> target = BIOMES.get(structureId);
        if (target == null || !hasBiome(source, target)) return original;
        return biome -> original.test(biome) || biome.is(target);
    }

    /** Keep fast locate's coarse candidate ranking on the same eligibility rule. */
    public static void addEligibleBiomes(BiomeSource source, String structureId,
                                         java.util.Set<Holder<Biome>> accepted) {
        ResourceKey<Biome> target = BIOMES.get(structureId);
        if (target == null || !TerrainDiffusionBiomeSources.isTerrainDiffusion(source)) return;
        source.possibleBiomes().stream().filter(biome -> biome.is(target)).forEach(accepted::add);
    }
}
