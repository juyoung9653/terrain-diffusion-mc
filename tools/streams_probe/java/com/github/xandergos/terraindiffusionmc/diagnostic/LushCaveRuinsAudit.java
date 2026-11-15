package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native, bounded acceptance audit for Lush Cave Ruins. It only accepts a
 * random-spread start discovered by Minecraft's own locator; it never invokes a
 * structure's direct generation API or /place command.
 */
public final class LushCaveRuinsAudit {
    private static final ResourceLocation ID = ResourceLocation.parse("keeb_struc_lush:keeb_struc_lush");
    private static final ResourceLocation LUSH_CAVES = ResourceLocation.withDefaultNamespace("lush_caves");
    private static final ResourceLocation TD_CAVE = ResourceLocation.parse("terralith:cave/underground_jungle");
    // The installed size-5 jigsaw has a measured 88-chunk, 229-piece native start.
    private static final int FULL_CHUNK_CAP = 128;
    /** Mud bricks are placed by this structure; the other palette blocks occur naturally underground. */
    private static final Set<String> DURABLE_BLOCKS = Set.of("minecraft:mud_bricks");

    private LushCaveRuinsAudit() { }

    private record AnchorObservation(BlockPos position, String biome) { }
    private static final ConcurrentHashMap<ChunkPos, AnchorObservation> ANCHORS = new ConcurrentHashMap<>();

    /** Called only by the opt-in observer after native biome admission succeeds. */
    public static void observeAnchor(Structure structure, Structure.GenerationContext context,
                                     Structure.GenerationStub stub) {
        if (!ID.equals(context.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure))) return;
        BlockPos position = stub.position().immutable();
        String biome = context.biomeSource().getNoiseBiome(position.getX() >> 2, position.getY() >> 2,
                position.getZ() >> 2, context.randomState().sampler()).unwrapKey().orElseThrow().location().toString();
        ANCHORS.put(context.chunkPos(), new AnchorObservation(position, biome));
    }

    public static JsonObject run(MinecraftServer server, ServerLevel level) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("event", "lush_cave_ruins_audit");
        result.addProperty("structure", ID.toString());
        try {
            int radius = bounded("td.streamsProbe.lushRuinsRadiusRings", 6, 1, 8);
            BlockPos center = center(level);
            Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Holder<Structure> structure = structures.getHolder(ResourceKey.create(Registries.STRUCTURE, ID))
                    .orElseThrow(() -> new IllegalStateException("Lush Cave Ruins structure is not registered"));
            StructureSetEntry set = structureSet(level, structure);
            if (!(set.placement instanceof RandomSpreadStructurePlacement spread))
                throw new IllegalStateException("Lush Cave Ruins lost its random-spread placement: "
                        + set.placement.getClass().getName());
            result.addProperty("structureSet", set.id.toString());
            result.addProperty("placement", "random_spread");
            result.addProperty("spacing", spread.spacing());
            result.addProperty("separation", spread.separation());
            require(spread.separation() >= 63, "LCR placement does not enforce the requested minimum distance");
            result.addProperty("minimumReferenceDistanceBlocks", (spread.separation() + 1) * 16);
            result.addProperty("sampledMinimumReferenceDistanceSquared", auditSeparation(level, spread));
            result.addProperty("spreadType", String.valueOf(spread.spreadType()));
            result.addProperty("radiusRegionRings", radius);
            result.addProperty("center", center.getX() + "," + center.getZ());

            for (String name : List.of("lush_cave_ruins_pot", "lush_cave_ruins_common")) {
                var lootKey = ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.fromNamespaceAndPath("keeb_struc_lush", name));
                require(server.reloadableRegistries().get().registryOrThrow(Registries.LOOT_TABLE)
                        .containsKey(lootKey.location()), "LCR loot table failed to decode: " + name);
                require(server.reloadableRegistries().getLootTable(lootKey)
                        != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "empty LCR loot table: " + name);
            }
            result.addProperty("lootTablesLoaded", true);

            Set<String> allowed = biomeIds(structure.value().biomes());
            result.add("allowedBiomes", strings(allowed));
            require(allowed.contains(LUSH_CAVES.toString()), "original minecraft:lush_caves predicate is absent");

            require(server.submit(() -> {
                var state = level.getChunkSource().getGeneratorState();
                return state.possibleStructureSets().stream().anyMatch(holder -> holder.value().equals(set.structureSet))
                        && state.getPlacementsForStructure(structure).contains(set.placement);
            }).get(), "native generator state did not retain the Lush Cave Ruins set and original placement");

            HolderSet<Structure> target = HolderSet.direct(structure);
            // The locator's radius is structure-placement region rings, not chunks.  It must run on the server thread.
            var found = server.submit(() -> level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, target, center, radius, false)).get();
            if (found == null) {
                result.add("candidateEvidence", server.submit(() -> candidateEvidence(level, spread, center, radius)).get());
                throw new IllegalStateException("No native Lush Cave Ruins start within " + radius
                        + " placement-region rings of " + center.getX() + "," + center.getZ());
            }
            require(found.getSecond().is(structure.unwrapKey().orElseThrow()), "native locate returned another structure");
            BlockPos located = found.getFirst();
            ChunkPos owner = new ChunkPos(located);
            result.addProperty("located", located.getX() + "," + located.getY() + "," + located.getZ());
            result.addProperty("ownerChunk", owner.x + "," + owner.z);
            require(server.submit(() -> spread.isStructureChunk(level.getChunkSource().getGeneratorState(), owner.x, owner.z)).get(),
                    "located start is not a native random-spread candidate");
            result.addProperty("locateMatched", true);

            ChunkAccess startsChunk = await(server.submit(() -> level.getChunkSource()
                    .getChunkFuture(owner.x, owner.z, ChunkStatus.STRUCTURE_STARTS, true)).get())
                    .orElseThrow(() -> new IllegalStateException("missing STRUCTURE_STARTS chunk for native locate"));
            StructureStart start = server.submit(() -> startsChunk.getStartForStructure(structure.value())).get();
            require(start != null && start.isValid(), "native locate did not retain a valid saved structure start");
            result.addProperty("nativeStart", true);
            BoundingBox box = start.getBoundingBox();
            result.addProperty("boundingBox", box.toString());
            result.addProperty("pieces", start.getPieces().size());
            require(!start.getPieces().isEmpty(), "native start has no pieces");

            AnchorObservation observation = ANCHORS.get(owner);
            require(observation != null, "native generation anchor was not observed for this fresh start");
            BlockPos anchor = observation.position();
            result.addProperty("generationAnchor", anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
            result.addProperty("anchorObservedDuringGeneration", true);
            String biome = observation.biome();
            result.addProperty("biome", biome);
            require(TD_CAVE.toString().equals(biome), "native generation-anchor biome is not TD underground jungle: " + biome);

            JsonArray depths = server.submit(() -> pieceDepths(level, start)).get();
            result.add("pieceDepths", depths);
            require(depths.size() == start.getPieces().size(), "did not sample every generated piece depth");
            for (var depth : depths) require(depth.getAsJsonObject().get("belowSurface").getAsBoolean(),
                    "a native piece reaches local world surface");
            result.addProperty("structureMinY", box.minY()); result.addProperty("structureMaxY", box.maxY());

            List<ChunkPos> touched = touchedChunks(start, owner);
            require(!touched.isEmpty(), "native start has no touched chunks");
            require(touched.size() <= FULL_CHUNK_CAP, "structure touches " + touched.size() + " chunks, cap=" + FULL_CHUNK_CAP);
            List<ChunkAccess> full = new ArrayList<>();
            for (ChunkPos pos : touched) {
                full.add(await(server.submit(() -> level.getChunkSource().getChunkFuture(pos.x, pos.z,
                        ChunkStatus.FULL, true)).get())
                        .orElseThrow(() -> new IllegalStateException("missing FULL chunk " + pos.x + "," + pos.z)));
            }
            result.addProperty("fullChunks", full.size());
            JsonObject blocks = server.submit(() -> physicalStructureBlocks(full, box)).get();
            result.add("physicalStructureBlocks", blocks);
            require(blocks.size() > 0, "no structure-specific mud bricks found after FULL generation");
            result.addProperty("structureBlocks", total(blocks));

            String tag = server.submit(() -> serializationRoundTrip(level, start, owner)).get();
            result.addProperty("startNbtSha256", sha256(tag));
            result.addProperty("status", "passed");
            return result;
        } catch (Exception failure) {
            result.addProperty("status", "failed");
            result.addProperty("error", failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()));
            Path evidence = server.getWorldPath(LevelResource.ROOT).resolve("td-lush-cave-ruins-audit-failed.json");
            Files.writeString(evidence, new GsonBuilder().disableHtmlEscaping().create().toJson(result), StandardCharsets.UTF_8);
            result.addProperty("failureEvidence", evidence.getFileName().toString());
            throw failure;
        }
    }

    private static StructureSetEntry structureSet(ServerLevel level, Holder<Structure> structure) {
        Registry<StructureSet> sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        for (Holder<StructureSet> candidate : sets.holders().toList()) {
            if (candidate.value().structures().stream().anyMatch(entry -> entry.structure().equals(structure)))
                return new StructureSetEntry(candidate.unwrapKey().orElseThrow().location(), candidate.value(), candidate.value().placement());
        }
        throw new IllegalStateException("Lush Cave Ruins structure has no registered structure set");
    }

    private static long auditSeparation(ServerLevel level, RandomSpreadStructurePlacement spread) {
        List<ChunkPos> candidates = new ArrayList<>();
        // Exercise Minecraft's actual candidate sampler on both sides of zero;
        // no biome, terrain or chunk generation is needed for this spacing check.
        for (int z = -10; z <= 10; z++) for (int x = -10; x <= 10; x++)
            candidates.add(spread.getPotentialStructureChunk(level.getSeed(), x * spread.spacing(), z * spread.spacing()));
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) for (int j = 0; j < i; j++)
            minimum = Math.min(minimum, squaredDistance(candidates.get(i), candidates.get(j)) * 256L);
        require(minimum >= 1_000L * 1_000L, "native LCR candidates are less than 1,000 blocks apart");
        return minimum;
    }

    private static Set<String> biomeIds(HolderSet<net.minecraft.world.level.biome.Biome> biomes) {
        Set<String> result = new LinkedHashSet<>();
        biomes.stream().forEach(holder -> result.add(holder.unwrapKey().orElseThrow().location().toString()));
        return result;
    }

    private static List<ChunkPos> touchedChunks(StructureStart start, ChunkPos owner) {
        Set<ChunkPos> result = new LinkedHashSet<>();
        for (var piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            for (int z = Math.floorDiv(box.minZ(), 16); z <= Math.floorDiv(box.maxZ(), 16); z++)
                for (int x = Math.floorDiv(box.minX(), 16); x <= Math.floorDiv(box.maxX(), 16); x++)
                    result.add(new ChunkPos(x, z));
        }
        List<ChunkPos> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparingLong(pos -> squaredDistance(pos, owner)));
        return sorted;
    }

    private static JsonObject physicalStructureBlocks(List<ChunkAccess> chunks, BoundingBox bounds) {
        JsonObject result = new JsonObject();
        for (ChunkAccess chunk : chunks) {
            int minX = Math.max(bounds.minX(), chunk.getPos().getMinBlockX());
            int maxX = Math.min(bounds.maxX(), chunk.getPos().getMaxBlockX());
            int minZ = Math.max(bounds.minZ(), chunk.getPos().getMinBlockZ());
            int maxZ = Math.min(bounds.maxZ(), chunk.getPos().getMaxBlockZ());
            for (int y = Math.max(bounds.minY(), chunk.getMinBuildHeight()); y <= Math.min(bounds.maxY(), chunk.getMaxBuildHeight() - 1); y++)
                for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(chunk.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                    if (DURABLE_BLOCKS.contains(id)) result.addProperty(id, result.has(id) ? result.get(id).getAsInt() + 1 : 1);
                }
        }
        return result;
    }

    /** A bounded, read-only record of native random-spread candidates for a failed locate. */
    private static JsonObject candidateEvidence(ServerLevel level, RandomSpreadStructurePlacement spread,
                                                BlockPos center, int rings) {
        JsonObject result = new JsonObject();
        JsonArray candidates = new JsonArray();
        var state = level.getChunkSource().getGeneratorState();
        var generator = level.getChunkSource().getGenerator();
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        int regionX = Math.floorDiv(centerChunkX, spread.spacing());
        int regionZ = Math.floorDiv(centerChunkZ, spread.spacing());
        int considered = 0;
        int nativeCandidates = 0;
        for (int z = regionZ - rings; z <= regionZ + rings; z++) for (int x = regionX - rings; x <= regionX + rings; x++) {
            considered++;
            ChunkPos owner = spread.getPotentialStructureChunk(level.getSeed(), x * spread.spacing(), z * spread.spacing());
            if (!spread.isStructureChunk(state, owner.x, owner.z)) continue;
            nativeCandidates++;
            int blockX = owner.getMinBlockX() + 8;
            int blockZ = owner.getMinBlockZ() + 8;
            int projectedY = generator.getBaseHeight(blockX, blockZ, Heightmap.Types.OCEAN_FLOOR_WG, level,
                    level.getChunkSource().randomState()) - 75;
            JsonObject row = new JsonObject();
            row.addProperty("owner", owner.x + "," + owner.z);
            row.addProperty("projectedAnchorY", projectedY);
            row.addProperty("biome", nativeBiome(level, new BlockPos(blockX, projectedY, blockZ)));
            candidates.add(row);
        }
        result.addProperty("regionsConsidered", considered);
        result.addProperty("nativeCandidates", nativeCandidates);
        result.add("candidates", candidates);
        return result;
    }

    private static String nativeBiome(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getGenerator().getBiomeSource()
                .getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2,
                        level.getChunkSource().randomState().sampler())
                .unwrapKey().orElseThrow().location().toString();
    }

    private static JsonArray pieceDepths(ServerLevel level, StructureStart start) {
        JsonArray result = new JsonArray();
        var generator = level.getChunkSource().getGenerator();
        for (int index = 0; index < start.getPieces().size(); index++) {
            BoundingBox piece = start.getPieces().get(index).getBoundingBox();
            int x = (piece.minX() + piece.maxX()) >> 1;
            int z = (piece.minZ() + piece.maxZ()) >> 1;
            int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                    level.getChunkSource().randomState());
            JsonObject row = new JsonObject();
            row.addProperty("index", index);
            row.addProperty("sample", x + "," + z);
            row.addProperty("pieceMaxY", piece.maxY());
            row.addProperty("surfaceY", surface);
            row.addProperty("belowSurface", piece.maxY() < surface);
            result.add(row);
        }
        return result;
    }

    private static String serializationRoundTrip(ServerLevel level, StructureStart start, ChunkPos owner) {
        var context = StructurePieceSerializationContext.fromLevel(level);
        var tag = start.createTag(context, owner);
        StructureStart restored = StructureStart.loadStaticStart(context, tag, level.getSeed());
        require(restored != null && restored.isValid() && restored.getPieces().size() == start.getPieces().size(),
                "native structure-start NBT did not round-trip");
        return tag.toString();
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(Integer.getInteger("td.streamsProbe.lushRuinsTimeoutSeconds", 180), TimeUnit.SECONDS);
    }
    private static BlockPos center(ServerLevel level) {
        int x = Integer.getInteger("td.streamsProbe.centerChunkX", level.getSharedSpawnPos().getX() >> 4) * 16 + 8;
        int z = Integer.getInteger("td.streamsProbe.centerChunkZ", level.getSharedSpawnPos().getZ() >> 4) * 16 + 8;
        return new BlockPos(Integer.getInteger("td.streamsProbe.lushRuinsCenterX", x),
                0, Integer.getInteger("td.streamsProbe.lushRuinsCenterZ", z));
    }
    private static int bounded(String key, int fallback, int min, int max) { return Math.max(min, Math.min(max, Integer.getInteger(key, fallback))); }
    private static long squaredDistance(ChunkPos a, ChunkPos b) { long x = (long) a.x - b.x, z = (long) a.z - b.z; return x * x + z * z; }
    private static String safe(String value) { return value == null ? "" : value.replace('\n', ' ').replace('\r', ' '); }
    private static int total(JsonObject object) { return object.entrySet().stream().mapToInt(entry -> entry.getValue().getAsInt()).sum(); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static String sha256(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static JsonArray strings(Set<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private record StructureSetEntry(ResourceLocation id, StructureSet structureSet, StructurePlacement placement) { }
}
