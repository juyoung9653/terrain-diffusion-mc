package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Explicit finite-domain preview. No live chunk heights are used to plan or rasterize rivers. */
public final class RiverPreviewService {
    private static final Logger LOG = LoggerFactory.getLogger("terrain-diffusion-mc/rivers");
    private static final Map<ServerLevel, FutureTask<Preview>> PLANS = new ConcurrentHashMap<>();
    private static final FastNoiseLite SEDIMENT = sedimentNoise();
    private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static FastNoiseLite sedimentNoise() {
        var noise = new FastNoiseLite(72839);
        noise.SetFrequency(0.065f);
        return noise;
    }

    private RiverPreviewService() { }

    public record Column(boolean active, int groundY, int waterY, int originalGroundY, double halfWidth) { }
    private record Preview(RiverPreviewSettings settings, DrainagePlanner.Plan drainage,
                           RiverGeometry.Geometry geometry, Ground ground, double[] runoff, boolean[] ocean) { }

    public static void clear() { PLANS.clear(); }

    private static Preview preview(ServerLevel level) {
        RiverPreviewSettings settings = WorldRiverPreviewState.settings(level);
        if (!settings.enabled()) throw new IllegalStateException("River preview is disabled for this world");
        if (settings.seed() != level.getSeed()) throw new IllegalStateException("River preview seed does not match world");
        FutureTask<Preview> candidate = new FutureTask<>(() -> build(level, settings));
        FutureTask<Preview> task = PLANS.putIfAbsent(level, candidate);
        if (task == null) { task = candidate; task.run(); }
        try { return task.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while planning rivers", e);
        } catch (ExecutionException e) { throw new IllegalStateException("River preview planning failed", e.getCause()); }
    }

    private static Preview build(ServerLevel level, RiverPreviewSettings settings) throws IOException {
        // Structure interaction is intentionally not guessed in this finite preview.
        if (level.getServer().getWorldData().worldGenOptions().generateStructures()) {
            throw new IllegalStateException("River preview requires a separate world with generate-structures=false");
        }
        requireStreamsCarvingDisabled();
        long started = System.nanoTime();
        Ground sampler;
        var terrainSource = com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources
                .find(level.getChunkSource().getGenerator().getBiomeSource());
        int terrainProfile = terrainSource == null ? 1 : terrainSource.undergroundProfileVersion();
        int n = settings.size() / settings.spacing() + 1;
        double[] ground = new double[n * n], runoff = new double[n * n];
        boolean[] ocean = new boolean[n * n];
        Path cachePath = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(settings.cacheFileName());
        var cacheKey = new RiverPreviewCache.Key(settings.version(), settings.seed(), settings.scale(),
                settings.originX(), settings.originZ(), settings.size(), settings.spacing(),
                settings.minCatchment(), settings.maxFillDepth(), settings.maxCut(), terrainProfile);
        if (Files.exists(cachePath)) {
            var cached = RiverPreviewCache.read(cachePath, cacheKey);
            ground = cached.ground(); runoff = cached.runoff(); ocean = cached.ocean();
            sampler = new Ground(settings, cached.fullGround(), terrainProfile);
            LOG.info("Loaded immutable river terrain cache: {}", cachePath.getFileName());
        } else {
            if (!WorldRiverPreviewState.mayCreateFirstCache(level)) {
                throw new IOException("Existing river preview world is missing its immutable terrain cache; restore the world backup");
            }
            sampler = new Ground(terrainProfile);
            var generator = level.getChunkSource().getGenerator();
            var climate = level.getChunkSource().randomState().sampler();
            var registryOps = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
            Map<Biome, Double> precipitation = new java.util.IdentityHashMap<>();
            LOG.info("Planning finite rivers: seed={} origin={},{} size={} spacing={}", settings.seed(),
                    settings.originX(), settings.originZ(), settings.size(), settings.spacing());
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int wx = settings.originX() + x * settings.spacing();
                    int wz = settings.originZ() + z * settings.spacing();
                    int i = x + z * n;
                    ground[i] = sampler.ground(wx, wz);
                    Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(wx >> 2, ((int) ground[i]) >> 2, wz >> 2, climate);
                    ocean[i] = biome.is(BiomeTags.IS_OCEAN) && ground[i] <= 62;
                    // Relative runoff, not a physical discharge model. Dry cells contribute no local rain;
                    // the planner still carries all approved upstream contributions through them.
                    runoff[i] = precipitation.computeIfAbsent(biome.value(), value -> {
                        if (!value.hasPrecipitation()) return 0.0;
                        var json = Biome.DIRECT_CODEC.encodeStart(registryOps, value).getOrThrow().getAsJsonObject();
                        return Math.max(0.0, json.get("downfall").getAsDouble());
                    });
                }
                if (z % 32 == 0) LOG.info("River DEM sampling row {}/{}", z, n);
            }
            // CUDA terrain inference may differ by one block with a different batching
            // order. Freeze the exact planning AND bank-placement inputs before any
            // preview chunk is carved, then reuse them for this world's lifetime.
            sampler = sampler.freeze(settings);
            RiverPreviewCache.write(cachePath, new RiverPreviewCache.Data(cacheKey, ground, runoff, ocean, sampler.frozen));
            LOG.info("Saved immutable river terrain cache: {}", cachePath.getFileName());
        }
        DrainagePlanner.Plan drainage = DrainagePlanner.plan(settings.originX(), settings.originZ(), settings.spacing(),
                n, n, ground, runoff, new DrainagePlanner.Settings(62, settings.minCatchment(), 1, settings.maxFillDepth()), ocean);
        RiverGeometry.Geometry geometry = settings.version() == 1
                ? RiverGeometry.build(drainage, settings.maxCut(), sampler)
                : RiverGeometry.buildNatural(drainage, settings.maxCut(), sampler);
        Preview result = new Preview(settings, drainage, geometry, sampler, runoff, ocean);
        LOG.info("River preview planned {} segments, drainage={}, elapsedMs={}", geometry.segments().size(),
                drainage.diagnostics(), (System.nanoTime() - started) / 1_000_000);
        writePlan(level, result);
        return result;
    }

    private static void requireStreamsCarvingDisabled() throws IOException {
        try { Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen", false, RiverPreviewService.class.getClassLoader()); }
        catch (ClassNotFoundException absent) { return; }
        Path config = PlatformPaths.configDir().resolve("streamsreflowing-common.toml");
        String text = Files.readString(config);
        for (String key : List.of("generateStreams", "generateLakes")) {
            if (!java.util.regex.Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*false\\s*(?:#.*)?$").matcher(text).find()) {
                throw new IllegalStateException("River preview requires Streams " + key + "=false in its isolated test instance");
            }
        }
    }

    public static Column column(ServerLevel level, int x, int z) {
        var c = preview(level).geometry.column(x, z);
        return new Column(c.active(), c.groundY(), c.waterY(), c.originalGroundY(), c.halfWidth());
    }

    public static void apply(WorldGenRegion region, ChunkAccess chunk) {
        ServerLevel level = region.getLevel();
        RiverPreviewSettings settings = WorldRiverPreviewState.settings(level);
        if (!settings.enabled()) return;
        ChunkPos pos = chunk.getPos();
        if (pos.getMaxBlockX() < settings.originX() || pos.getMinBlockX() > settings.originX() + settings.size()
                || pos.getMaxBlockZ() < settings.originZ() || pos.getMinBlockZ() > settings.originZ() + settings.size()) return;
        Preview plan = preview(level);
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
            for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
                var c = plan.geometry.column(x, z);
                if (!c.active()) continue;
                changed = true;
                boolean wet = c.waterY() > c.groundY();
                // BiomeManager#getBiome can sample an unavailable neighbouring chunk at CARVERS.
                // The immutable biome source needs no chunk access and also supports Terralith.
                var biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                        x >> 2, c.originalGroundY() >> 2, z >> 2, level.getChunkSource().randomState().sampler());
                boolean sandy = !biome.value().hasPrecipitation();
                var surface = (sandy ? Blocks.SAND : wet ? Blocks.GRAVEL : Blocks.GRASS_BLOCK).defaultBlockState();
                if (settings.version() >= 2) {
                    float sediment = SEDIMENT.GetNoise(x, z);
                    if (wet) {
                        surface = (sandy || sediment > .23 ? Blocks.SAND
                                : sediment < -.25 ? Blocks.CLAY : Blocks.GRAVEL).defaultBlockState();
                    } else {
                        var original = chunk.getBlockState(block.set(x, c.originalGroundY(), z));
                        if (original.is(net.minecraft.tags.BlockTags.DIRT) || original.is(Blocks.GRAVEL)
                                || original.is(Blocks.SAND) || original.is(Blocks.RED_SAND)) surface = original;
                        // Sparse gravel bars extend inland in coherent patches. A bright,
                        // one-block sand rim around every reach reads as an artificial curb.
                        if (sediment > .12) {
                            int nearbyWater = Integer.MIN_VALUE;
                            int barWidth = sediment > .3 ? 3 : 2;
                            for (int[] side : CARDINAL)
                                nearbyWater = Math.max(nearbyWater, plan.geometry.column(x + side[0] * barWidth, z + side[1] * barWidth).waterY());
                            if (nearbyWater != Integer.MIN_VALUE && c.groundY() <= nearbyWater + 1)
                                surface = (sandy ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
                        }
                    }
                }
                // Clear through the actual pre-feature surface as well as the
                // snapshot top. CUDA +/-1 variation must not leave a lid above
                // cached water. All active banks/bed lining are restored to the
                // snapshot, including columns where live terrain is lower.
                int clearTop = Math.min(chunk.getMaxBuildHeight() - 1, Math.max(c.originalGroundY(),
                        chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15)));
                for (int y = Math.max(chunk.getMinBuildHeight(), c.sealY()); y <= clearTop; y++) {
                    var fill = settings.version() >= 2 && y >= c.groundY() - 2
                            ? (wet ? Blocks.GRAVEL : sandy ? Blocks.SANDSTONE : Blocks.DIRT) : Blocks.STONE;
                    var state = y < c.groundY() ? fill.defaultBlockState()
                            : y == c.groundY() ? surface
                            : wet && y <= c.waterY() ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    chunk.setBlockState(block.set(x, y, z), state, false);
                }
            }
        }
        if (changed) Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }

    /** Selects a join and its neighbouring chunks, then a wider downstream reach. */
    public static List<ChunkPos> diagnosticChunks(ServerLevel level, int maximum) {
        Preview p = preview(level);
        Map<Integer, Integer> inbound = new java.util.HashMap<>();
        for (var s : p.geometry.segments()) inbound.merge(s.target(), 1, Integer::sum);
        List<RiverGeometry.Segment> ordered = new ArrayList<>(p.geometry.segments());
        ordered.sort(Comparator.<RiverGeometry.Segment>comparingInt(s -> inbound.getOrDefault(s.source(), 0) >= 2 ? 0 : 1)
                .thenComparingInt(s -> s.water1() > 62 ? 0 : 1).thenComparing(Comparator.comparingDouble(RiverGeometry.Segment::width1).reversed())
                .thenComparingInt(RiverGeometry.Segment::source));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (var s : ordered) {
            int cx = Math.floorDiv(s.x1(), 16), cz = Math.floorDiv(s.z1(), 16);
            for (int[] d : new int[][]{{0,0},{-1,0},{0,-1},{-1,-1}}) {
                chunks.add(new ChunkPos(cx + d[0], cz + d[1]));
                if (chunks.size() >= maximum) return List.copyOf(chunks);
            }
        }
        return List.copyOf(chunks);
    }

    private static void writePlan(ServerLevel level, Preview preview) throws IOException {
        Path path = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("td-river-preview-plan.csv");
        List<String> lines = new ArrayList<>();
        lines.add("source,target,x1,z1,x2,z2,water1,water2,halfWidth1,halfWidth2");
        for (var s : preview.geometry.segments()) lines.add(s.source() + "," + s.target() + "," + s.x1() + "," + s.z1() + ","
                + s.x2() + "," + s.z2() + "," + s.water1() + "," + s.water2() + "," + s.width1() + "," + s.width2());
        Files.write(path, lines);
        if (preview.settings.version() >= 2) {
            List<String> curves = new ArrayList<>();
            curves.add("source,target,index,x,z,water,halfWidth");
            for (var s : preview.geometry.segments()) {
                int pointIndex = 0;
                for (var point : preview.geometry.path(s)) curves.add(s.source() + "," + s.target() + "," + pointIndex++
                        + "," + point.x() + "," + point.z() + "," + point.water() + "," + point.width());
            }
            Files.write(path.resolveSibling("td-river-preview-curves.csv"), curves);
        }
        List<String> dem = new ArrayList<>();
        dem.add("x,z,ground,drainage,renderedCatchment,terminal,channel,runoff,ocean");
        var d = preview.drainage;
        for (int i = 0; i < d.width() * d.height(); i++) {
            dem.add((d.originX() + i % d.width() * d.spacing()) + "," + (d.originZ() + i / d.width() * d.spacing())
                    + "," + d.ground(i) + "," + d.drainageLevel(i) + "," + d.renderedAccumulation(i)
                    + "," + d.terminal(i) + "," + d.channel(i) + "," + preview.runoff[i] + "," + preview.ocean[i]);
        }
        Files.write(path.resolveSibling("td-river-preview-dem.csv"), dem);
        if (Boolean.getBoolean("td.rivers.exportTerrain")) {
            // A diagnostic snapshot permits exact geometry replay without another GPU inference run.
            try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                    new java.util.zip.GZIPOutputStream(Files.newOutputStream(path.resolveSibling("td-river-preview-ground.bin.gz")))))) {
                int side = preview.settings.size() + 1;
                out.writeInt(0x54445231); out.writeInt(d.originX()); out.writeInt(d.originZ()); out.writeInt(side);
                for (int z = d.originZ(); z < d.originZ() + side; z++) {
                    for (int x = d.originX(); x < d.originX() + side; x++) out.writeShort(preview.ground.ground(x, z));
                }
            }
        }
    }

    private static final class Ground implements RiverGeometry.GroundSampler {
        private final int tileSize = TerrainDiffusionConfig.tileSize();
        // Retain a full row even when a smaller configured tile size is used.
        private final int maxGroundTiles = Math.max(64, 8192 / tileSize + 2);
        private final Map<Long, LocalTerrainProvider.HeightmapData> tiles = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, LocalTerrainProvider.HeightmapData> eldest) {
                return size() > maxGroundTiles;
            }
        };
        private final int originX, originZ, side;
        private final short[] frozen;
        private final int terrainProfile;
        Ground(int terrainProfile) {
            originX = 0; originZ = 0; side = 0; frozen = null; this.terrainProfile = terrainProfile;
        }
        Ground(RiverPreviewSettings settings, short[] frozen, int terrainProfile) {
            this.originX = settings.originX(); this.originZ = settings.originZ();
            this.side = settings.size() + 1; this.frozen = frozen;
            this.terrainProfile = terrainProfile;
        }
        Ground freeze(RiverPreviewSettings settings) {
            int size = settings.size() + 1;
            short[] snapshot = new short[Math.multiplyExact(size, size)];
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                int height = ground(settings.originX() + x, settings.originZ() + z);
                if (height < Short.MIN_VALUE || height > Short.MAX_VALUE) throw new IllegalStateException("River ground exceeds cache range");
                snapshot[x + z * size] = (short) height;
            }
            return new Ground(settings, snapshot, terrainProfile);
        }
        @Override public int ground(int x, int z) {
            long dx = (long) x - originX, dz = (long) z - originZ;
            if (frozen != null && dx >= 0 && dx < side && dz >= 0 && dz < side) {
                return frozen[(int) dx + (int) dz * side];
            }
            int tx = Math.floorDiv(x, tileSize), tz = Math.floorDiv(z, tileSize);
            long key = ((long) tx << 32) ^ (tz & 0xffffffffL);
            LocalTerrainProvider.HeightmapData tile;
            synchronized (tiles) {
                tile = tiles.get(key);
                if (tile == null) {
                    tile = LocalTerrainProvider.getInstance().fetchGroundHeightmap(
                            tz * tileSize, tx * tileSize, tz * tileSize + tileSize, tx * tileSize + tileSize);
                    tiles.put(key, tile);
                }
            }
            return HeightConverter.convertToMinecraftHeight(tile.heightmap[Math.floorMod(z, tileSize)][Math.floorMod(x, tileSize)],
                    com.github.xandergos.terraindiffusionmc.world.WorldScaleManager.getCurrentScale(), terrainProfile) - 1;
        }
    }
}
