package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.structure.InstanceStructureCompatibility;
import com.github.xandergos.terraindiffusionmc.world.structure.StructureReservations;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Read-only, world-local proof for the TD structure adapters.  Registry biome
 * tags intentionally remain unchanged: this checks the predicate used by TD's
 * structure runtime instead.  Optional targets are already-generated FULL
 * chunk coordinates (id@chunkX,chunkZ), which makes physical proof bounded.
 */
public final class ModStructureCompatibilityAudit {
    private static final Map<String, String> MAPPINGS = new LinkedHashMap<>();
    static {
        MAPPINGS.put("hopo:portal/overworld/portal_lush", "terralith:cave/underground_jungle");
        MAPPINGS.put("hopo:portal/overworld/portal_dripstone", "terralith:cave/deep_caves");
        MAPPINGS.put("cataclysm:amethyst_nest", "terralith:cave/underground_jungle");
        MAPPINGS.put("structory:swamp_ruin", "terrain-diffusion-mc:ecology/mangrove_swamp");
        MAPPINGS.put("terralith:underground/oak_cabin", "terralith:cave/underground_jungle");
        MAPPINGS.put("bettermineshafts:mineshaft_lush", "terralith:cave/underground_jungle");
        MAPPINGS.put("bettermineshafts:mineshaft_dripstone", "terralith:cave/deep_caves");
        MAPPINGS.put("bettermineshafts:mineshaft_mushroom", "terralith:cave/fungal_caves");
    }
    private static final String GALLEON = "supplementaries:galleon";
    private static final String GALLEON_SET = "supplementaries:galleons";
    private static final String GALLEON_PLACEMENT =
            "net.mehvahdjukaar.supplementaries.common.worldgen.RandomSpreadStructurePlacementWithExclusion";

    private ModStructureCompatibilityAudit() { }

    public static JsonObject run(ServerLevel level) {
        JsonObject result = new JsonObject();
        result.addProperty("event", "mod_structure_compatibility_audit");
        result.addProperty("scope", "world_local_effective_predicates");
        try {
            var source = level.getChunkSource().getGenerator().getBiomeSource();
            Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var state = level.getChunkSource().getGeneratorState();
            JsonArray mappings = new JsonArray();
            for (var entry : MAPPINGS.entrySet()) {
                Holder<Structure> structure = required(structures, entry.getKey(), "structure");
                Holder<Biome> target = required(biomes, entry.getValue(), "mapped biome");
                Predicate<Holder<Biome>> original = structure.value().biomes()::contains;
                Predicate<Holder<Biome>> effective = InstanceStructureCompatibility.validBiomes(source,
                        entry.getKey(), original);
                boolean retainedSet = state.possibleStructureSets().stream().anyMatch(set ->
                        set.value().structures().stream().anyMatch(candidate -> candidate.structure().equals(structure)));
                boolean placement = !state.getPlacementsForStructure(structure).isEmpty();
                require(effective.test(target), entry.getKey() + " did not admit " + entry.getValue());
                require(retainedSet, entry.getKey() + " structure set missing from TD generator state");
                require(placement, entry.getKey() + " locate placement missing from TD generator state");
                JsonObject row = new JsonObject();
                row.addProperty("structure", entry.getKey()); row.addProperty("mappedBiome", entry.getValue());
                row.addProperty("registryBiomeUnchanged", !original.test(target));
                row.addProperty("effectivePredicateAdmitsMappedBiome", true);
                row.addProperty("possibleStructureSetRetained", true); row.addProperty("locatePlacementRetained", true);
                mappings.add(row);
            }
            result.add("mappings", mappings);
            Holder<Structure> galleon = required(structures, GALLEON, "galleon structure");
            StructureSet galleonSet = state.possibleStructureSets().stream()
                    .map(Holder::value).filter(set -> set.structures().stream()
                            .anyMatch(candidate -> candidate.structure().equals(galleon))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Supplementaries galleon set absent from TD state"));
            String placement = galleonSet.placement().getClass().getName();
            require(GALLEON_PLACEMENT.equals(placement), "unexpected galleon placement " + placement);
            require(!state.getPlacementsForStructure(galleon).isEmpty(), "galleon locate placement missing");
            boolean plannerRetained = plannerRetains(state, structures);
            require(plannerRetained, "Supplementaries galleon set was rejected by TD reservation planner");
            JsonObject galleonRow = new JsonObject();
            galleonRow.addProperty("structure", GALLEON); galleonRow.addProperty("placementClass", placement);
            galleonRow.addProperty("plannerWhitelistClass", GALLEON_PLACEMENT);
            galleonRow.addProperty("possibleStructureSetRetained", true); galleonRow.addProperty("locatePlacementRetained", true);
            galleonRow.addProperty("plannerSet", GALLEON_SET); galleonRow.addProperty("plannerRetained", true);
            result.add("galleon", galleonRow);

            JsonArray physical = physicalEvidence(level, structures);
            result.add("physicalTargets", physical);
            boolean requested = !System.getProperty("td.streamsProbe.wetlandStructureTargets", "").isBlank();
            result.addProperty("physicalEvidenceRequested", requested);
            result.addProperty("physicalEvidenceLevel", requested ? "full_chunk_start_piece_nbt" : "not_requested");
            result.addProperty("status", "passed");
        } catch (RuntimeException failure) {
            result.addProperty("status", "failed");
            result.addProperty("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return result;
    }

    private static JsonArray physicalEvidence(ServerLevel level, Registry<Structure> structures) {
        JsonArray rows = new JsonArray();
        String configured = System.getProperty("td.streamsProbe.wetlandStructureTargets", "").trim();
        if (configured.isEmpty()) return rows;
        for (String raw : configured.split(";")) {
            String[] parts = raw.trim().split("@", 2);
            if (parts.length != 2 || !parts[1].matches("-?\\d+,-?\\d+"))
                throw new IllegalArgumentException("structure target must be id@chunkX,chunkZ: " + raw);
            String[] chunk = parts[1].split(",", 2);
            Holder<Structure> structure = required(structures, parts[0], "target structure");
            ChunkPos pos = new ChunkPos(Integer.parseInt(chunk[0]), Integer.parseInt(chunk[1]));
            var full = level.getChunk(pos.x, pos.z);
            var start = full.getStartForStructure(structure.value());
            require(start != null && start.isValid(), "no valid native start at " + raw);
            require(!start.getPieces().isEmpty(), "native start has no pieces at " + raw);
            JsonObject row = new JsonObject();
            row.addProperty("target", raw); row.addProperty("fullChunk", true); row.addProperty("nativeStart", true);
            row.addProperty("pieces", start.getPieces().size());
            var context = StructurePieceSerializationContext.fromLevel(level);
            var tag = start.createTag(context, pos);
            var restored = net.minecraft.world.level.levelgen.structure.StructureStart.loadStaticStart(context, tag, level.getSeed());
            require(restored != null && restored.isValid() && restored.getPieces().size() == start.getPieces().size(),
                    "structure start NBT did not round-trip at " + raw);
            row.addProperty("startNbtHash", tag.toString().hashCode());
            var box = start.getBoundingBox();
            BlockPos anchor = new BlockPos((box.minX() + box.maxX()) >> 1, (box.minY() + box.maxY()) >> 1,
                    (box.minZ() + box.maxZ()) >> 1);
            String habitat = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(anchor.getX() >> 2,
                    anchor.getY() >> 2, anchor.getZ() >> 2, level.getChunkSource().randomState().sampler())
                    .unwrapKey().map(key -> key.location().toString()).orElse("unkeyed");
            row.addProperty("habitatAnchorBiome", habitat);
            String expected = MAPPINGS.get(parts[0]);
            // This is a reproducible centre sample, not an admission-point claim:
            // long jigsaws can select a biome at another native anchor.
            row.addProperty("expectedTdHabitat", expected == null ? "not_mapped" : expected);
            row.addProperty("centerBiomeMatchesExpectedHabitat", expected == null || expected.equals(habitat));
            rows.add(row);
        }
        return rows;
    }

    private static <T> Holder<T> required(Registry<T> registry, String value, String what) {
        ResourceLocation id = ResourceLocation.parse(value);
        return registry.getHolder(ResourceKey.create(registry.key(), id))
                .orElseThrow(() -> new IllegalStateException(what + " is not registered: " + value));
    }
    /** The plan list is deliberately private production state; reflection keeps this diagnostic read-only. */
    private static boolean plannerRetains(net.minecraft.world.level.chunk.ChunkGeneratorStructureState state,
                                          Registry<Structure> structures) {
        try {
            StructureReservations planner = new StructureReservations(state, structures,
                    StructurePlacementConfig.snapshotFor(structures));
            var field = StructureReservations.class.getDeclaredField("sets");
            field.setAccessible(true);
            for (Object plan : (Iterable<?>) field.get(planner)) {
                var id = plan.getClass().getDeclaredMethod("id");
                id.setAccessible(true);
                if (GALLEON_SET.equals(id.invoke(plan))) return true;
            }
            return false;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot inspect TD reservation planner", failure);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
