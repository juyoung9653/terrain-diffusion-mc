package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.mixin.JigsawStructureAccess;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative automatic adapter discovery from the registered codec, once per world/type. */
public final class StructureSurfaceMetadata {
    private static final Map<Object,Map<Structure,Boolean>> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private StructureSurfaceMetadata() {}
    public static void clear() { CACHE.clear(); }

    public static boolean projectsToGround(Structure structure, RegistryAccess access) {
        if (structure instanceof JigsawStructure) {
            var jigsaw=(JigsawStructureAccess)(Object)structure;
            var projection=jigsaw.terrainDiffusion$surfaceProjection().orElse(null);
            return surfaceMap(projection) && jigsaw.terrainDiffusion$startHeight() instanceof ConstantHeight constant
                    && constant.getValue().equals(VerticalAnchor.absolute(0));
        }
        Map<Structure,Boolean> cache;
        synchronized (CACHE) { cache=CACHE.computeIfAbsent(access.registryOrThrow(Registries.STRUCTURE),key -> new ConcurrentHashMap<>()); }
        return cache.computeIfAbsent(structure,key -> decodeContract(key,access));
    }

    private static boolean surfaceMap(Heightmap.Types projection) {
        return projection==Heightmap.Types.WORLD_SURFACE_WG || projection==Heightmap.Types.WORLD_SURFACE
                || projection==Heightmap.Types.MOTION_BLOCKING || projection==Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
    }

    private static boolean decodeContract(Structure structure, RegistryAccess access) {
        try {
            JsonElement encoded=Structure.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,access),structure).result().orElse(null);
            if (encoded==null || !encoded.isJsonObject()) return false;
            JsonObject object=encoded.getAsJsonObject();
            // Custom terrain adapters cannot be inferred safely from ordinary jigsaw fields.
            for (String key:object.keySet()) {
                String lower=key.toLowerCase(Locale.ROOT);
                if (!key.equals("terrain_adaptation") && (lower.contains("terrain") || lower.contains("adaptation"))) return false;
            }
            JsonElement projection=object.get("project_start_to_heightmap");
            if (projection==null || !projection.isJsonPrimitive()) return false;
            String name=projection.getAsString().toUpperCase(Locale.ROOT);
            if (!Set.of("WORLD_SURFACE_WG","WORLD_SURFACE","MOTION_BLOCKING","MOTION_BLOCKING_NO_LEAVES").contains(name)) return false;
            JsonElement height=object.get("start_height");
            if (height==null || !height.isJsonObject()) return false;
            JsonObject provider=height.getAsJsonObject();
            // HeightProvider.CODEC permits the compact {absolute:0} constant form.
            if (provider.has("type")) {
                if (!provider.get("type").getAsString().equals("minecraft:constant") || !provider.has("value")
                        || !provider.get("value").isJsonObject()) return false;
                provider=provider.getAsJsonObject("value");
            }
            return provider.size()==1 && provider.has("absolute") && provider.get("absolute").getAsJsonPrimitive().isNumber()
                    && provider.get("absolute").getAsDouble()==0.0;
        } catch (RuntimeException ignored) {
            // A mod codec that cannot round-trip is outside the automatic adapter contract.
            return false;
        }
    }
}
