package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Replaces only living village entries; other members of mixed native sets retain their placement. */
public final class UnifiedVillageSets {
    private static final java.util.Map<StructureSet,String> FILTERED=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final TagKey<Structure> VILLAGE=TagKey.create(Registries.STRUCTURE,
            ResourceLocation.withDefaultNamespace("village"));
    private UnifiedVillageSets() {}

    public static List<Holder<StructureSet>> rewrite(BiomeSource source,List<Holder<StructureSet>> nativeSets) {
        // Alias sets exist to expose original template holders; they must never add a native population.
        if (!(source instanceof TerrainDiffusionBiomeSource)) return withoutAliases(nativeSets);
        if (nativeSets.isEmpty()) return nativeSets;
        Structure member=nativeSets.stream().flatMap(set -> set.value().structures().stream())
                .map(entry -> entry.structure().value()).findFirst().orElse(null);
        if (member==null) return withoutAliases(nativeSets);
        var config=StructurePlacementConfig.snapshotMatching(identity -> identity instanceof net.minecraft.core.Registry<?> registry
                && registry.stream().anyMatch(value -> value==member));
        if (!config.enabled() || !config.villages().enabled()) return withoutAliases(nativeSets);
        var villages=new TreeMap<String,Holder<Structure>>();
        List<Holder<StructureSet>> result=new ArrayList<>();
        for (var holder:nativeSets) {
            List<StructureSet.StructureSelectionEntry> others=new ArrayList<>();
            for (var entry:holder.value().structures()) {
                String id=entry.structure().unwrapKey().map(k -> k.location().toString()).orElse("");
                // Only audited seed-local native Jigsaw assembly can participate in four-hop preflight.
                // Custom implementations retain their native placement and conservative reservation.
                if (VillageDistributionGeometry.isVillage(id,entry.structure().is(VILLAGE))
                        && entry.structure().value().getClass()==net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.class)
                    villages.put(id,entry.structure());
                else others.add(entry);
            }
            if (others.size()==holder.value().structures().size()) result.add(holder);
            else if (!others.isEmpty()) {
                var filtered=new StructureSet(others,holder.value().placement());
                holder.unwrapKey().ifPresent(key -> FILTERED.put(filtered,key.location().toString()));
                result.add(Holder.direct(filtered));
            }
        }
        if (!villages.isEmpty()) {
            var entries=villages.values().stream().map(h -> new StructureSet.StructureSelectionEntry(h,1)).toList();
            result.add(Holder.direct(new StructureSet(entries,new UnifiedVillagePlacement(config.villages(),epicLoaded()))));
        }
        return List.copyOf(result);
    }

    /** Stable identity for the one synthetic set and filtered native mixed sets. */
    public static String syntheticId(StructureSet set) {
        if (set.placement() instanceof UnifiedVillagePlacement) return VillageDistributionGeometry.SET_ID;
        return FILTERED.get(set);
    }

    private static List<Holder<StructureSet>> withoutAliases(List<Holder<StructureSet>> sets) {
        return sets.stream().filter(holder -> !holder.unwrapKey().map(key ->
                key.location().toString().equals("terrain-diffusion-mc:basic_villages")).orElse(false)).toList();
    }

    private static boolean epicLoaded() {
        // Keep loader classes out of the common signature; their query method names differ.
        for (String loader:List.of("net.fabricmc.loader.api.FabricLoader","net.neoforged.fml.ModList")) {
            try {
                Class<?> type=Class.forName(loader);
                Object instance=type.getMethod(loader.contains("fabric")?"getInstance":"get").invoke(null);
                return (boolean)type.getMethod(loader.contains("fabric") ? "isModLoaded" : "isLoaded",String.class)
                        .invoke(instance,"mr_epic_structuresvillages");
            } catch (ClassNotFoundException absent) {
                // The other loader is expected to be absent.
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot classify Epic's overridden vanilla villages",failure);
            }
        }
        return false;
    }
}
