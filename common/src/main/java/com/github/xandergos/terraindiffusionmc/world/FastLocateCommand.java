package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.BroadEcologyClimate;
import com.github.xandergos.terraindiffusionmc.pipeline.CoarseBiomeHints;
import com.github.xandergos.terraindiffusionmc.pipeline.InferenceExecutor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.github.xandergos.terraindiffusionmc.world.FastLocateSearch.*;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-owned commands with bounded provider work and main-thread world verification. */
public final class FastLocateCommand {
    private static final Logger LOG = LoggerFactory.getLogger(FastLocateCommand.class);
    private static final Map<MinecraftServer, Job> JOBS = new IdentityHashMap<>();
    private static final net.minecraft.server.level.TicketType<ChunkPos> SEARCH_TICKET =
            net.minecraft.server.level.TicketType.create("td-fast-locate", java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_DISTANCE = 33 - net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("td-locate").requires(source -> source.hasPermission(2))
                .then(branch("biome", false)).then(branch("structure", true))
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("cancel").executes(context -> cancel(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> branch(String name, boolean structures) {
        return structures ? branch(name, Registries.STRUCTURE, true) : branch(name, Registries.BIOME, false);
    }

    private static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
            String name, ResourceKey<Registry<T>> registry, boolean structures) {
        return literal(name).then(argument("id", ResourceOrTagKeyArgument.resourceOrTagKey(registry))
                .executes(context -> start(context.getSource(),
                        context.getArgument("id", ResourceOrTagKeyArgument.Result.class), DEFAULT_RADIUS, DEFAULT_SECONDS, structures))
                .then(argument("radius", IntegerArgumentType.integer(16, MAX_RADIUS))
                        .executes(context -> start(context.getSource(),
                                context.getArgument("id", ResourceOrTagKeyArgument.Result.class),
                                IntegerArgumentType.getInteger(context, "radius"), DEFAULT_SECONDS, structures))
                        .then(argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                                .executes(context -> start(context.getSource(),
                                        context.getArgument("id", ResourceOrTagKeyArgument.Result.class),
                                        IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "seconds"), structures)))));
    }

    private static <T> List<Holder<T>> resolve(ResourceOrTagKeyArgument.Result<?> target, Registry<T> registry) {
        var typed = target.cast(registry.key()).orElseThrow();
        return typed.unwrap().map(key -> registry.getHolder(key)
                        .map(holder -> List.<Holder<T>>of(holder)).orElse(List.of()),
                tag -> registry.getTag(tag).map(holders -> holders.stream().toList()).orElse(List.of()));
    }

    /** Call before provider shutdown. No interrupt or native future cancellation is safe here. */
    public static void stop(MinecraftServer server) {
        synchronized (JOBS) {
            Job job = JOBS.remove(server);
            if (job != null) { job.stopped = true; job.budget.cancel(); job.releaseTicket(); }
        }
    }

    private static int status(CommandSourceStack source) {
        Job job;
        synchronized (JOBS) { job = JOBS.get(source.getServer()); }
        if (job == null) tell(source, "[td-locate] IDLE 진행 중인 탐색이 없습니다.");
        else tell(source, "[td-locate] " + (job.budget.cancelled() ? "DRAINING 취소됨, 진행 중인 호출 정리 중" : "RUNNING " + job.phase)
                + " · " + job.counts());
        return job == null ? 0 : 1;
    }

    private static int cancel(CommandSourceStack source) {
        Job job;
        synchronized (JOBS) { job = JOBS.get(source.getServer()); }
        if (job == null) return status(source);
        job.budget.cancel();
        tell(source, "[td-locate] CANCELLED 탐색을 취소했습니다. 진행 중인 호출이 끝나면 새 탐색을 시작할 수 있습니다.");
        return 1;
    }

    private static int start(CommandSourceStack source, ResourceOrTagKeyArgument.Result<?> target, int radius, int seconds, boolean structures) {
        String id = target.asPrintable();
        MinecraftServer server = source.getServer();
        synchronized (JOBS) {
            if (JOBS.containsKey(server)) {
                source.sendFailure(Component.literal("[td-locate] BUSY 이미 탐색 중입니다. /td-locate status 또는 cancel"));
                return 0;
            }
        }
        ServerLevel level = source.getLevel();
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource biomeSource)
                || biomeSource.getClass() != TerrainDiffusionBiomeSource.class) {
            source.sendFailure(Component.literal("[td-locate] 이 차원은 빠른 탐색을 지원하지 않습니다. 기본 /locate를 사용하세요."));
            return 0;
        }
        try {
            BlockPos origin = BlockPos.containing(source.getPosition());
            Set<Holder<Biome>> accepted;
            List<PlacementTarget> placements = new ArrayList<>();
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            if (structures) {
                var registry = source.registryAccess().registryOrThrow(Registries.STRUCTURE);
                var holders = resolve(target, registry);
                if (holders.isEmpty()) {
                    source.sendFailure(Component.literal("[td-locate] Missing structure or empty/missing tag: " + id)); return 0;
                }
                Set<Holder<Biome>> biomes = new HashSet<>();
                int unsupported = 0;
                for (var holder : holders) {
                    boolean supported = false;
                    for (var placement : state.getPlacementsForStructure(holder)) {
                        if (placement instanceof RandomSpreadStructurePlacement random) {
                            placements.add(new PlacementTarget(holder, random)); supported = true;
                        } else unsupported++;
                    }
                    if (supported) holder.value().biomes().forEach(biomes::add);
                }
                if (placements.isEmpty()) {
                    source.sendFailure(Component.literal("[td-locate] No supported random-spread placements in this dimension: " + id
                            + ". Use /locate structure " + id)); return 0;
                }
                if (unsupported > 0) tell(source, "[td-locate] Skipping " + unsupported
                        + " unsupported ring/custom placements in " + id + "; use /locate for those.");
                accepted = Set.copyOf(biomes);
            } else {
                var registry = source.registryAccess().registryOrThrow(Registries.BIOME);
                var holders = resolve(target, registry);
                if (holders.isEmpty()) {
                    source.sendFailure(Component.literal("[td-locate] Missing biome or empty/missing tag: " + id)); return 0;
                }
                accepted = Set.copyOf(holders.stream().filter(biomeSource.possibleBiomes()::contains).toList());
                if (accepted.isEmpty()) {
                    source.sendFailure(Component.literal("[td-locate] No biome in this target is generated by this source: " + id)); return 0;
                }
            }
            Job job = new Job(source, level, biomeSource, LocalTerrainProvider.getInstance(), origin,
                    radius, seconds, id, accepted, structures, List.copyOf(placements), state);
            synchronized (JOBS) { JOBS.put(server, job); }
            tell(source, "[td-locate] START " + id + " · 반경 " + radius
                    + " · 후보 우선 탐색 후 실제 검증 (가장 가까운 결과 보장 없음), 탐색 예산 " + seconds + "초 (진행 중인 계산 정리는 더 걸릴 수 있음)");
            Thread thread = new Thread(job::run, "td-fast-locate");
            thread.setDaemon(true);
            thread.start();
            return 1;
        } catch (Exception failure) {
            LOG.warn("Could not start fast locate", failure);
            source.sendFailure(Component.literal("[td-locate] ERROR 탐색 준비에 실패했습니다. 서버 로그를 확인하세요."));
            return 0;
        }
    }

    private static void tell(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private record PlacementTarget(Holder<Structure> structure, RandomSpreadStructurePlacement placement) { }
    private record PlacementGroup(RandomSpreadStructurePlacement placement, List<Holder<Structure>> structures) { }
    private record StructureCandidate(Point point, PlacementGroup group) { }
    private record Match(BlockPos position, ResourceLocation matchedId, String semantics) { }

    private static final class Job {
        final CommandSourceStack source;
        final MinecraftServer server;
        final ServerLevel level;
        final ServerChunkCache chunks;
        final TerrainDiffusionBiomeSource biomeSource;
        final LocalTerrainProvider provider;
        final BlockPos origin;
        final Point center;
        final int radius, tileSize, scale;
        final int[] heights;
        final String id;
        final Set<Holder<Biome>> accepted;
        final boolean searchingStructures;
        final List<Point> searchPatches;
        final ChunkGeneratorStructureState state;
        final List<PlacementGroup> placementGroups;
        final Map<PlacementGroup, Set<Point>> visitedRegions = new HashMap<>();
        final Map<Point, List<StructureCandidate>> structuresByPoint = new HashMap<>();
        final Set<Point> visitedTiles = new HashSet<>();
        final Set<Point> visitedStarts = new HashSet<>();
        final UUID playerId;
        final Budget budget;
        volatile boolean stopped;
        volatile String phase = "광역 기후 후보 준비";
        // Server-thread owned. UNKNOWN tickets expire after one tick; remote async
        // structure generation needs its own ticket until verification/drain ends.
        ChunkPos heldTicket;

        void releaseTicket() {
            if (heldTicket != null) {
                chunks.removeRegionTicket(SEARCH_TICKET, heldTicket, TICKET_DISTANCE, heldTicket);
                heldTicket = null;
            }
        }

        Job(CommandSourceStack source, ServerLevel level, TerrainDiffusionBiomeSource biomeSource,
            LocalTerrainProvider provider, BlockPos origin, int radius, int seconds, String id,
            Set<Holder<Biome>> accepted, boolean searchingStructures,
            List<PlacementTarget> placements, ChunkGeneratorStructureState state) {
            this.source = source; this.server = source.getServer(); this.level = level;
            this.chunks = level.getChunkSource(); this.biomeSource = biomeSource; this.provider = provider;
            this.origin = origin; this.center = new Point(origin.getX(), origin.getZ());
            this.radius = radius; this.id = id; this.accepted = accepted; this.searchingStructures = searchingStructures;
            this.budget = new Budget(System::nanoTime, TimeUnit.SECONDS.toNanos(seconds));
            this.searchPatches = patches(center, radius); this.state = state;
            this.tileSize = LocalTerrainProvider.sampleTileSize(); this.scale = WorldScaleManager.getCurrentScale();
            this.heights = Mth.outFromOrigin(Mth.clamp(origin.getY(), level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1),
                    level.getMinBuildHeight() + 1, level.getMaxBuildHeight(), 32).map(y -> Math.floorDiv(y, 4) * 4).distinct().toArray();
            this.playerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
            // Village variants share one placement. Snapshot only the immutable grouping here;
            // enumerate coordinates lazily in bounded server tasks after the command returns.
            Map<RandomSpreadStructurePlacement, List<Holder<Structure>>> grouped = new LinkedHashMap<>();
            for (PlacementTarget target : placements)
                grouped.computeIfAbsent(target.placement(), ignored -> new ArrayList<>()).add(target.structure());
            this.placementGroups = grouped.entrySet().stream()
                    .map(entry -> new PlacementGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        }

        List<StructureCandidate> planStructures(Point patch, int extent, int remainingPatches) throws Exception {
            List<StructureCandidate> result = new ArrayList<>();
            int patchEnd = budget.regions() + regionAllowance(REGION_LIMIT - budget.regions(), remainingPatches);
            for (int groupIndex = 0; groupIndex < placementGroups.size(); groupIndex++) {
                if (!budget.live() || budget.regions() >= patchEnd) break;
                PlacementGroup group = placementGroups.get(groupIndex);
                int allowance = regionAllowance(patchEnd - budget.regions(), placementGroups.size() - groupIndex);
                Set<Point> seen = visitedRegions.computeIfAbsent(group, ignored -> new HashSet<>());
                List<Point> regions = placementRegions(patch, extent, center, radius, group.placement().spacing())
                        .stream().filter(region -> !seen.contains(region)).limit(allowance).toList();
                for (int first = 0; first < regions.size() && budget.live(); first += REGION_BATCH) {
                    List<Point> batch = regions.subList(first, Math.min(first + REGION_BATCH, regions.size()));
                    List<StructureCandidate> planned = main(() -> {
                        List<StructureCandidate> candidates = new ArrayList<>();
                        for (Point region : batch) {
                            if (!budget.takeRegion()) break;
                            seen.add(region);
                            ChunkPos chunk = group.placement().getPotentialStructureChunk(state.getLevelSeed(),
                                    region.x() * group.placement().spacing(), region.z() * group.placement().spacing());
                            Point point = new Point(chunk.getMinBlockX(), chunk.getMinBlockZ());
                            if (inside(point, center, radius)) candidates.add(new StructureCandidate(point, group));
                        }
                        return candidates;
                    });
                    for (StructureCandidate candidate : planned) {
                        structuresByPoint.computeIfAbsent(candidate.point(), ignored -> new ArrayList<>()).add(candidate);
                        result.add(candidate);
                    }
                    if (budget.regions() >= REGION_LIMIT) break;
                }
            }
            return result;
        }

        List<Point> probes(Point patch) {
            List<Point> result = new ArrayList<>();
            int step = Math.min(256, Math.max(4, radius / 4));
            for (int z : new int[]{-step, 0, step}) for (int x : new int[]{-step, 0, step}) {
                Point point = new Point(patch.x() + x, patch.z() + z).quart();
                if (inside(point, center, radius)) result.add(point);
            }
            return result;
        }

        String counts() { return "기후 " + budget.patches() + "/" + searchPatches.size() + ", 지형 " + budget.tiles() + "/" + TILE_LIMIT + ", 구조물 시작 " + budget.starts() + "/" + START_LIMIT
                + (searchingStructures ? ", 배치 영역 " + budget.regions() + "/" + REGION_LIMIT : ""); }

        /** All player/world liveness reads happen on the server. */
        boolean valid() {
            if (stopped || budget.cancelled()) return false;
            synchronized (JOBS) { if (JOBS.get(server) != this) return false; }
            if (playerId != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || player.serverLevel() != level) { budget.cancel(); return false; }
            }
            return true;
        }

        <T> T main(Callable<T> action) throws Exception {
            if (stopped || budget.cancelled()) throw new CancellationException();
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                if (!valid()) { future.completeExceptionally(new CancellationException()); return; }
                try { future.complete(action.call()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            while (true) {
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException wait) { if (stopped || budget.cancelled()) throw new CancellationException(); }
                catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
                    throw failure;
                }
            }
        }

        void run() {
            try {
                Match match = provider.withBinding(() -> provider.withPriority(InferenceExecutor.Priority.BACKGROUND, () -> {
                    for (int first = 0; first < searchPatches.size() && budget.live(); first += PATCH_BATCH) {
                        if (searchingStructures ? budget.starts() >= START_LIMIT : budget.tiles() >= TILE_LIMIT) break;
                        int allowance = batchAllowance(searchingStructures ? START_LIMIT - budget.starts() : TILE_LIMIT - budget.tiles(),
                                searchPatches.size() - first);
                        List<Candidate> candidates = coarse(first, Math.min(first + PATCH_BATCH, searchPatches.size()));
                        Match result = searchingStructures ? structures(candidates, allowance) : biomes(candidates, allowance);
                        if (result != null) return result;
                    }
                    return null;
                }));
                main(() -> {
                    if (match == null || !budget.live()) tell(source, "[td-locate] EXHAUSTED 탐색 예산/후보 내에서 검증된 결과를 찾지 못했습니다. 존재하지 않는다는 뜻은 아닙니다. " + counts());
                    else {
                        BlockPos pos = match.position();
                        tell(source, "[td-locate] FOUND " + match.matchedId() + " (" + id + ") [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                + "] · " + match.semantics() + " · 가장 가까운 결과 보장 없음 · " + counts());
                    }
                    return null;
                });
            } catch (Exception failure) {
                if (!budget.cancelled() && !stopped) {
                    LOG.warn("Fast locate failed for {}", id, failure);
                    try { main(() -> { source.sendFailure(Component.literal("[td-locate] ERROR 탐색/검증에 실패했습니다. 서버 로그를 확인하세요.")); return null; }); }
                    catch (Exception ignored) { }
                }
            } finally {
                // Keep ownership until main-thread cleanup completes: a new job
                // must not share a chunk ticket that this job is about to remove.
                if (!stopped) server.execute(() -> {
                    releaseTicket();
                    synchronized (JOBS) { JOBS.remove(server, this); }
                });
            }
        }

        List<Candidate> coarse(int first, int end) throws Exception {
            phase = "광역 기후 후보 준비";
            List<Candidate> candidates = new ArrayList<>();
            int sector = first;
            for (Point patch : searchPatches.subList(first, end)) {
                if (!budget.takePatch()) break;
                main(() -> null); // Reject disconnected/moved owners before every native batch.
                int extent = Math.min(512, Math.max(16, radius / 2));
                List<StructureCandidate> localStructures = searchingStructures
                        ? planStructures(patch, extent, searchPatches.size() - sector) : List.of();
                if (searchingStructures && localStructures.isEmpty()) { sector++; continue; }
                if (!budget.live()) break;
                BroadEcologyClimate climate = biomeSource.naturalBiomes()
                        ? LocalTerrainProvider.getBroadEcologyClimate(patch.x() - extent, patch.z() - extent,
                                patch.x() + extent + 1, patch.z() + extent + 1, scale) : null;
                CoarseBiomeHints hints = climate == null
                        ? LocalTerrainProvider.getCoarseBiomeHints(patch.x() - extent, patch.z() - extent,
                                patch.x() + extent + 1, patch.z() + extent + 1, scale) : null;
                if (!budget.live()) break;
                if (!searchingStructures) {
                    for (Point point : probes(patch)) {
                        Holder<Biome> hint = biomeSource.fastLocateHint(point.x(), point.z(), climate, hints);
                        candidates.add(new Candidate(point, hint != null && accepted.contains(hint) ? 1 : 0, sector));
                    }
                } else {
                    for (StructureCandidate candidate : localStructures) {
                        // A region's start can fall beyond this climate patch. Keep it as an
                        // unranked fallback instead of borrowing an unrelated clamped biome.
                        Holder<Biome> hint = inside(candidate.point(), patch, extent)
                                ? biomeSource.fastLocateHint(candidate.point().x(), candidate.point().z(), climate, hints) : null;
                        candidates.add(new Candidate(candidate.point(), hint != null && accepted.contains(hint) ? 1 : 0, sector));
                    }
                }
                sector++;
                if (sector % 5 == 0) main(() -> { tell(source, "[td-locate] PROGRESS " + phase + " · " + counts()); return null; });
            }
            return ranked(candidates, center, !searchingStructures ? tileSize : 16);
        }

        Match biomes(List<Candidate> candidates, int allowance) throws Exception {
            phase = "정확한 지형 타일/Y 바이옴 검증";
            List<Candidate> unseen = candidates.stream().filter(c -> !visitedTiles.contains(c.point().tile(tileSize)))
                    .limit(allowance).toList();
            return findVerified(unseen, budget, candidate -> {
                visitedTiles.add(candidate.point().tile(tileSize));
                main(() -> null);
                Point tile = candidate.point().tile(tileSize);
                var data = provider.fetchBiomeHeightmap(tile.z(), tile.x(), tile.z() + tileSize, tile.x() + tileSize);
                for (int z = tile.z(); z < tile.z() + tileSize; z += 4) {
                    if (!budget.live()) return null;
                    for (int x = tile.x(); x < tile.x() + tileSize; x += 4) {
                        if (!inside(new Point(x, z), center, radius)) continue;
                        for (int y : heights) {
                            Holder<Biome> actual = biomeSource.fastLocateBiome(x, y, z, tile.x(), tile.z(), data);
                            if (accepted.contains(actual))
                                return new Match(new BlockPos(x, y, z), actual.unwrapKey().orElseThrow().location(), "바이옴 소스의 정확한 quart 표본 (저장 청크/F3 경계와 다를 수 있음)");
                        }
                    }
                }
                if (budget.tiles() % 8 == 0) main(() -> { tell(source, "[td-locate] PROGRESS " + phase + " · " + counts()); return null; });
                return null;
            });
        }

        Match structures(List<Candidate> candidates, int allowance) throws Exception {
            phase = "구조물 배치 및 실제 시작 검증";
            int batchEnd = budget.starts() + allowance;
            for (Candidate candidate : candidates) {
                if (!budget.live() || budget.starts() >= batchEnd) break;
                if (visitedStarts.contains(candidate.point())) continue;
                StructureCandidate chosen = main(() -> {
                    for (StructureCandidate option : structuresByPoint.get(candidate.point()))
                        if (option.group().placement().isStructureChunk(state, option.point().x() >> 4, option.point().z() >> 4)) return option;
                    return null;
                });
                if (chosen == null) continue;
                if (!budget.takeStart()) break;
                visitedStarts.add(candidate.point());
                main(() -> {
                    releaseTicket();
                    if (!budget.live()) throw new CancellationException();
                    heldTicket = new ChunkPos(chosen.point().x() >> 4, chosen.point().z() >> 4);
                    chunks.addRegionTicket(SEARCH_TICKET, heldTicket, TICKET_DISTANCE, heldTicket);
                    return null;
                });
                // This public method explicitly bridges off-thread callers to the chunk processor.
                // Calling it ON the server would managedBlock; never do that here.
                var future = chunks.getChunkFuture(chosen.point().x() >> 4, chosen.point().z() >> 4, ChunkStatus.STRUCTURE_STARTS, true);
                while (!future.isDone()) {
                    try { future.get(100, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException wait) {
                        if (stopped) throw new CancellationException();
                        // Keep the job slot while an admitted chunk request drains. No more admissions.
                        if (budget.live()) {
                            try { main(() -> null); }
                            catch (CancellationException cancelled) {
                                if (stopped) throw cancelled;
                                // Owner left or cancelled: drain this admitted request before releasing the slot.
                            }
                        }
                    }
                }
                if (!budget.live()) return null;
                Match match = main(() -> {
                    var chunk = future.join().orElse(null);
                    if (chunk == null) throw new IllegalStateException("Structure-start chunk unavailable: " + future.join().getError());
                    // Planning is incremental, so inspect every requested variant in this exact
                    // chunk, including variants whose region would be encountered in a later patch.
                    for (PlacementGroup group : placementGroups) {
                        boolean placementChecked = false;
                        for (Holder<Structure> structure : group.structures()) {
                            var start = chunk.getStartForStructure(structure.value());
                            if (start == null || !start.isValid()) continue;
                            if (!placementChecked && !group.placement().isStructureChunk(state,
                                    candidate.point().x() >> 4, candidate.point().z() >> 4)) break;
                            placementChecked = true;
                            BlockPos pos = group.placement().getLocatePos(start.getChunkPos());
                            if (!inside(new Point(pos.getX(), pos.getZ()), center, radius)) continue;
                            return new Match(pos, structure.unwrapKey().orElseThrow().location(),
                                    "STRUCTURE_STARTS의 유효한 실제 구조물 시작 확인");
                        }
                    }
                    return null;
                });
                if (match != null) return match;
                if (budget.starts() % 4 == 0) main(() -> { tell(source, "[td-locate] PROGRESS " + phase + " · " + counts()); return null; });
            }
            return null;
        }
    }
    private FastLocateCommand() { }
}
