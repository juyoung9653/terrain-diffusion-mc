package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankFillPolicy;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;

/** Connect raster corners and contain lateral steps; v5 first softens dry narrow-stream banks. */
public final class StreamsRiverBanks {
    private static final int[] CARDINAL_X = {-1, 1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, -1, 1};
    private static final Set<Long> BANK_TRACE_POSITIONS = bankTracePositions();
    /** A dry-lake callback and a native-carver callback may visit one block; retain both phases. */
    private static final Set<String> BANK_TRACED = ConcurrentHashMap.newKeySet();
    private static final Set<String> BANK_PROFILE_TRACED = ConcurrentHashMap.newKeySet();

    private StreamsRiverBanks() { }
    public static void finish(WorldGenLevel level, int minX, int minZ) {
        finish(level, minX, minZ, true);
    }
    /** Native dry chunks can still own the dry side of a lake shore. */
    public static void finishDryLakeShore(WorldGenLevel level, int minX, int minZ) {
        if (WorldNaturalRiverState.version(level.getLevel()) >= 9) finish(level, minX, minZ, false);
    }
    private static void finish(WorldGenLevel level, int minX, int minZ, boolean nativeCarved) {
        Map<Long, String> traceBefore = bankTraceProfiles(level, minX, minZ);
        try {
            int riverStateVersion = WorldNaturalRiverState.version(level.getLevel());
            if (riverStateVersion < 4) {
                traceChunk(minX, minZ, nativeCarved, "early-river-state version=" + riverStateVersion);
                return;
            }
            Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
            Object engine = worldgen.getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            int version = StreamsNaturalRiverAdapter.version(config);
            if (version < 4) {
                traceChunk(minX, minZ, nativeCarved, "early-adapter-version riverState=" + riverStateVersion
                        + " adapter=" + version);
                return;
            }
            if (version < 9 && (Boolean) engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class)
                    .invoke(engine, minX, minZ, 8)) {
                traceChunk(minX, minZ, nativeCarved, "early-near-lake riverState=" + riverStateVersion
                        + " adapter=" + version);
                return;
            }
            StreamsBankLakeMask lakes = null;
            if (!nativeCarved) {
                if (version < 9) {
                    traceChunk(minX, minZ, nativeCarved, "early-dry-lake-profile riverState=" + riverStateVersion
                            + " adapter=" + version);
                    return;
                }
                lakes = new StreamsBankLakeMask(engine, minX, minZ);
                if (!lakes.hasLake()) {
                    traceChunk(minX, minZ, nativeCarved, "early-no-lake riverState=" + riverStateVersion
                            + " adapter=" + version);
                    return;
                }
            }
            Class<?> carver = Class.forName("dev.streamsreflowing.worldgen.RiverCarverFeature");
            Method shields = carver.getDeclaredMethod("a", WorldGenLevel.class, ChunkPos.class);
            Method shielded = carver.getDeclaredMethod("a", int[].class, int.class, int.class, int.class);
            shields.setAccessible(true); shielded.setAccessible(true);
            int[] protection = (int[]) shields.invoke(null, level, new ChunkPos(minX >> 4, minZ >> 4));
            Method blockedBiome = Class.forName("dev.streamsreflowing.worldgen.StreamBiomeBlacklist")
                    .getMethod("isBlocked", Holder.class);
            int sea = (int) StreamsNaturalNetwork.n(config, "seaLevel");
            if (!nativeCarved) {
                StreamsNaturalBanks.refineLakeShore(level, minX, minZ, engine, protection, shielded, blockedBiome, lakes);
                traceChunk(minX, minZ, nativeCarved, "dry-lake-refined riverState=" + riverStateVersion
                        + " adapter=" + version);
                return;
            }
            if (version == 5) {
                StreamsNaturalBanks.refine(level, minX, minZ, engine, sea, protection, shielded, blockedBiome);
            }
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                    surface = column.getMethod("waterSurfaceY"), width = column.getMethod("width"),
                    distance = column.getMethod("distance"), source = column.getMethod("source"), terminal = column.getMethod("terminal");
            BankPlan[][] plans = new BankPlan[18][18];
            boolean wideBank = false;
            for (int x = 0; x < 18; x++) for (int z = 0; z < 18; z++) {
                Object p = plan.invoke(engine, minX+x-1, minZ+z-1);
                plans[x][z] = new BankPlan((Boolean)channel.invoke(p), (Boolean)corridor.invoke(p),
                        ((Number)surface.invoke(p)).intValue(), ((Number)width.invoke(p)).doubleValue(),
                        ((Number)distance.invoke(p)).doubleValue(), (Boolean)source.invoke(p), (Boolean)terminal.invoke(p));
                BankPlan bank = plans[x][z];
                // Skip the larger shaping halo entirely unless this chunk has
                // a high dry wide-bank candidate. Its channels remain read-only.
                if (version >= 7 && !wideBank && x >= 1 && x <= 16 && z >= 1 && z <= 16
                        && !bank.channel && bank.corridor && !bank.source && !bank.terminal
                        && bank.width >= 4 && bank.surface > sea + 2
                        && bank.distance - bank.width > .5
                        && bank.distance - bank.width < NaturalRiverBankShape.wideCorridor(bank.width)) {
                    int top = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, minX + x - 1, minZ + z - 1) - 1;
                    wideBank = top > bank.surface
                            && soil(level.getBlockState(new BlockPos(minX + x - 1, top, minZ + z - 1)));
                }
            }
            if (version >= 7 && wideBank) {
                if (version >= 9) lakes = new StreamsBankLakeMask(engine, minX, minZ);
                StreamsNaturalBanks.refine(level, minX, minZ, engine, sea, protection, shielded, blockedBiome, true, lakes);
            }
            for (int x = 1; x <= 16; x++) for (int z = 1; z <= 16; z++) {
                BankPlan p = plans[x][z];
                int worldX = minX + x - 1, worldZ = minZ + z - 1;
                if (p.channel || !p.corridor || p.source || p.terminal || p.surface <= sea+2 || p.distance > p.width+2) {
                    traceBank(worldX, worldZ, "plan-rejected", riverStateVersion, version, nativeCarved, p,
                            Integer.MIN_VALUE, 0, 0, false, null, null, null);
                    continue;
                }
                if (version >= 9 && lakes == null) lakes = new StreamsBankLakeMask(engine, minX, minZ);
                if (lakes != null && lakes.excludes(worldX, worldZ, 2)) {
                    traceBank(worldX, worldZ, "lake-mask", riverStateVersion, version, nativeCarved, p,
                            Integer.MIN_VALUE, 0, 0, false, null, null, null);
                    continue;
                }
                if (p.distance <= p.width+.5 && connectingCorner(plans, x, z)) {
                    BlockPos cut = new BlockPos(worldX, p.surface, worldZ);
                    boolean clear = soil(level.getBlockState(cut)) && soil(level.getBlockState(cut.below()));
                    for (int dy = 1; dy <= 2; dy++) {
                        BlockState cover = level.getBlockState(cut.above(dy));
                        clear &= cover.canBeReplaced() && cover.getFluidState().isEmpty();
                    }
                    if (clear && !(Boolean) shielded.invoke(null, protection, cut.getX(), cut.getZ(), p.surface-1)
                            && !(Boolean) blockedBiome.invoke(null, level.getBiome(cut))) {
                        boolean tracing = traceRequested(worldX, worldZ);
                        BlockState before = tracing ? level.getBlockState(cut) : null;
                        level.setBlock(cut.below(), Blocks.MUD.defaultBlockState(), 2);
                        for (int dy = 0; dy <= 2; dy++) level.setBlock(cut.above(dy), Blocks.AIR.defaultBlockState(), 2);
                        traceBank(worldX, worldZ, "corner-excavation", riverStateVersion, version, nativeCarved, p,
                                p.surface, 0, 0, true, cut, before,
                                tracing ? level.getBlockState(cut) : null);
                    } else {
                        traceBank(worldX, worldZ, "corner-rejected", riverStateVersion, version, nativeCarved, p,
                                p.surface, 0, 0, false, cut,
                                traceRequested(worldX, worldZ) ? level.getBlockState(cut) : null, null);
                    }
                    // Never dam a required corner, including already wet or protected corners.
                    // Native fluid ticks fill a successful excavation; no water source is added.
                    continue;
                }
                // V6's narrow tributaries use original containing soil.  Keep
                // the preceding wide-river repair outside that guarded scope.
                if (version >= 6 && p.width < 4.0) {
                    traceBank(worldX, worldZ, "narrow-tributary", riverStateVersion, version, nativeCarved, p,
                            Integer.MIN_VALUE, 0, 0, false, null, null, null);
                    continue;
                }
                int crest = Integer.MIN_VALUE;
                int adjacentChannels = 0;
                for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                    BankPlan n = plans[x + CARDINAL_X[direction]][z + CARDINAL_Z[direction]];
                    if (n.channel && n.surface <= p.surface + 6) {
                        crest = Math.max(crest, NaturalRiverBankMargin.containmentCrest(version, n.surface));
                        adjacentChannels++;
                    }
                }
                // V7 fills through the highest adjacent water block, retaining
                // lateral containment at falls without adding a dry one-block parapet.
                // Frozen older profiles keep their original freeboard.
                int freeboard = version >= 7 ? 0 : 1;
                if (crest == Integer.MIN_VALUE || crest < p.surface - 1 + freeboard
                        || crest > p.surface + 6 + freeboard) {
                    traceBank(worldX, worldZ, "crest-rejected", riverStateVersion, version, nativeCarved, p,
                            crest, 0, adjacentChannels, false, null, null, null);
                    continue;
                }
                BlockPos pos = new BlockPos(worldX, crest, worldZ);
                BlockState at = level.getBlockState(pos);
                if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) {
                    traceBank(worldX, worldZ, "crest-occupied", riverStateVersion, version, nativeCarved, p,
                            crest, 0, adjacentChannels, false, pos, at, null);
                    continue;
                }
                BlockState ground = level.getBlockState(pos.below());
                int depth = 1;
                // Native decoration may leave tall grass in a two-block bank gap.
                // Dry replaceable plants are clearance, just like air; water is not.
                // Match the former support floor: removing the cap must not make
                // previously unsupported deep gaps eligible for new soil fills.
                while (depth < 5 + freeboard && ground.canBeReplaced() && ground.getFluidState().isEmpty()) {
                    depth++; ground = level.getBlockState(pos.below(depth));
                }
                if (!soil(ground)) {
                    traceBank(worldX, worldZ, "gap-unsupported", riverStateVersion, version, nativeCarved, p,
                            crest, depth, adjacentChannels, false, pos, ground, null);
                    continue;
                }
                int crestSupportNeighbors = 0;
                for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                    int dx = CARDINAL_X[direction], dz = CARDINAL_Z[direction];
                    BankPlan neighbor = plans[x + dx][z + dz];
                    // A low exposed riverbed is not a bank. Require an existing
                    // dry soil column beside the cap and immediately below it,
                    // so every newly filled level attaches to the actual bank.
                    if (!neighbor.channel && soil(level.getBlockState(pos.offset(dx, 0, dz)))
                            && soil(level.getBlockState(pos.offset(dx, -1, dz)))) {
                        crestSupportNeighbors++;
                    }
                }
                boolean allowed = NaturalRiverBankFillPolicy.allows(version, adjacentChannels, crestSupportNeighbors, depth);
                if (!allowed) {
                    traceBank(worldX, worldZ, "policy-rejected", riverStateVersion, version, nativeCarved, p,
                            crest, depth, adjacentChannels, false, pos, ground, null, crestSupportNeighbors);
                    continue;
                }
                if ((Boolean) shielded.invoke(null, protection, pos.getX(), pos.getZ(), crest-depth)
                        || (Boolean) blockedBiome.invoke(null, level.getBiome(pos.below(depth)))) {
                    traceBank(worldX, worldZ, "protected-or-biome", riverStateVersion, version, nativeCarved, p,
                            crest, depth, adjacentChannels, true, pos, ground, null, crestSupportNeighbors);
                    continue;
                }
                BlockState beforeCap = at;
                for (int dy=1;dy<depth;dy++) level.setBlock(pos.below(dy), Blocks.DIRT.defaultBlockState(), 2);
                BlockState cap = ground.is(Blocks.MUD) ? Blocks.MUD.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                if (version >= 10) cap = StreamsBankMaterialPolicy.climateBankTop(level,
                        pos.getX(), pos.getY(), pos.getZ(), cap);
                level.setBlock(pos, cap, 2);
                traceBank(worldX, worldZ, "vertical-fill", riverStateVersion, version, nativeCarved, p,
                        crest, depth, adjacentChannels, true, pos, beforeCap, cap,
                        crestSupportNeighbors);
            }
            if (version >= 9) {
                if (lakes == null) lakes = new StreamsBankLakeMask(engine, minX, minZ);
                StreamsNaturalBanks.refineLakeShore(level, minX, minZ, engine, protection, shielded, blockedBiome, lakes);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v4 bank adapter", failure);
        } finally {
            traceProfilesAfter(level, traceBefore, nativeCarved);
        }
    }

    /** Opt-in post-Streams evidence. It is intentionally independent of the
     * terrain-provider trace: this code runs after the wrapped feature writes. */
    private static Set<Long> bankTracePositions() {
        String configured = System.getProperty("td.streams.bankTracePositions", "").trim();
        if (configured.isEmpty()) return Set.of();
        Set<Long> positions = new HashSet<>();
        for (String entry : configured.split(";")) {
            String[] pair = entry.trim().split(",");
            if (pair.length != 2 || positions.size() >= 8) continue;
            try {
                positions.add(traceKey(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim())));
            } catch (NumberFormatException ignored) { }
        }
        return Set.copyOf(positions);
    }

    private static long traceKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static boolean traceRequested(int x, int z) {
        return BANK_TRACE_POSITIONS.contains(traceKey(x, z));
    }

    /** Captures only tracked in-chunk columns; it never forces neighboring chunks to load. */
    private static Map<Long, String> bankTraceProfiles(WorldGenLevel level, int minX, int minZ) {
        if (BANK_TRACE_POSITIONS.isEmpty()) return Map.of();
        Map<Long, String> profiles = new HashMap<>();
        for (long key : BANK_TRACE_POSITIONS) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16)
                profiles.put(key, bankTraceProfile(level, x, z));
        }
        return profiles;
    }

    private static void traceProfilesAfter(WorldGenLevel level, Map<Long, String> before, boolean nativeCarved) {
        for (Map.Entry<Long, String> entry : before.entrySet()) {
            long key = entry.getKey();
            if (!BANK_PROFILE_TRACED.add(tracePhaseKey(key, nativeCarved))) continue;
            int x = (int) (key >> 32);
            int z = (int) key;
            System.err.println("TD Streams bank profile x=" + x + " z=" + z
                    + " nativeCarved=" + nativeCarved + " y54..74 before=" + entry.getValue()
                    + " after=" + bankTraceProfile(level, x, z));
        }
    }

    private static String bankTraceProfile(WorldGenLevel level, int x, int z) {
        StringBuilder profile = new StringBuilder();
        int start = 54;
        BlockState previous = level.getBlockState(new BlockPos(x, start, z));
        for (int y = start + 1; y <= 75; y++) {
            BlockState current = y <= 74 ? level.getBlockState(new BlockPos(x, y, z)) : null;
            if (y <= 74 && current.equals(previous)) continue;
            if (!profile.isEmpty()) profile.append(',');
            profile.append(start).append("..").append(y - 1).append(':')
                    .append(BuiltInRegistries.BLOCK.getKey(previous.getBlock()));
            start = y;
            previous = current;
        }
        return profile.toString();
    }

    private static void traceChunk(int minX, int minZ, boolean nativeCarved, String outcome) {
        for (long key : BANK_TRACE_POSITIONS) {
            int x = (int) (key >> 32), z = (int) key;
            if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16)
                traceOnce(key, nativeCarved, "TD Streams bank trace x=" + x + " z=" + z
                        + " nativeCarved=" + nativeCarved + " outcome=" + outcome);
        }
    }

    private static void traceBank(int x, int z, String outcome, int riverStateVersion, int adapterVersion,
                                  boolean nativeCarved, BankPlan plan, int crest, int depth,
                                  int adjacentChannels, boolean policyAllowed, BlockPos pos,
                                  BlockState observed, BlockState result) {
        traceBank(x, z, outcome, riverStateVersion, adapterVersion, nativeCarved, plan, crest, depth,
                adjacentChannels, policyAllowed, pos, observed, result, -1);
    }

    private static void traceBank(int x, int z, String outcome, int riverStateVersion, int adapterVersion,
                                  boolean nativeCarved, BankPlan plan, int crest, int depth,
                                  int adjacentChannels, boolean policyAllowed, BlockPos pos,
                                  BlockState observed, BlockState result, int crestSupportNeighbors) {
        long key = traceKey(x, z);
        if (!BANK_TRACE_POSITIONS.contains(key) || BANK_TRACED.contains(tracePhaseKey(key, nativeCarved))) return;
        String position = pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
        String writeSpan = "vertical-fill".equals(outcome) && crest != Integer.MIN_VALUE
                ? (crest - depth + 1) + ".." + crest : "none";
        traceOnce(key, nativeCarved, "TD Streams bank trace x=" + x + " z=" + z + " outcome=" + outcome
                + " riverState=" + riverStateVersion + " adapter=" + adapterVersion
                + " nativeCarved=" + nativeCarved + " channel=" + plan.channel
                + " corridor=" + plan.corridor + " source=" + plan.source + " terminal=" + plan.terminal
                + " surface=" + plan.surface + " width=" + plan.width + " distance=" + plan.distance
                + " crest=" + crest + " gapDepth=" + depth + " adjacentChannels=" + adjacentChannels
                + " crestSupportNeighbors=" + crestSupportNeighbors + " policyAllowed=" + policyAllowed
                + " pos=" + position + " observed=" + observed + " writeSpan=" + writeSpan
                + " result=" + result);
    }

    private static String tracePhaseKey(long key, boolean nativeCarved) {
        return nativeCarved + ":" + key;
    }

    private static void traceOnce(long key, boolean nativeCarved, String message) {
        if (BANK_TRACE_POSITIONS.contains(key) && BANK_TRACED.add(tracePhaseKey(key, nativeCarved)))
            System.err.println(message);
    }

    private static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MUD);
    }
    /** A one-cell gap with upstream/downstream channels but no existing local face-connected detour. */
    private static boolean connectingCorner(BankPlan[][] plans, int x, int z) {
        BankPlan p = plans[x][z];
        int[] dx = {-1,1,0,0}, dz = {0,0,-1,1};
        java.util.List<Integer> upstream = new java.util.ArrayList<>();
        java.util.Set<Integer> downstream = new java.util.HashSet<>();
        int upstreamSurface = p.surface;
        for (int i = 0; i < 4; i++) {
            BankPlan n = plans[x+dx[i]][z+dz[i]];
            if (n.channel && n.surface <= p.surface+3) upstreamSurface = Math.max(upstreamSurface, n.surface);
        }
        for (int i = 0; i < 4; i++) {
            BankPlan n = plans[x+dx[i]][z+dz[i]];
            if (!n.channel) continue;
            int key = (dx[i]+1)*3+dz[i]+1;
            // A lower incoming arm may already reach downstream while the highest
            // arm remains disconnected. Do not let it hide the actual raster gap.
            if (n.surface >= p.surface && n.surface == upstreamSurface) upstream.add(key);
            if (n.surface <= p.surface && n.surface >= p.surface-1) downstream.add(key);
        }
        if (upstream.isEmpty() || downstream.isEmpty()) return false;
        for (int start : upstream) {
            if (downstream.size() == 1 && downstream.contains(start)) continue;
            boolean[] seen = new boolean[9];
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            seen[start] = true; queue.add(start);
            boolean connected = false;
            while (!queue.isEmpty()) {
                int key = queue.remove(), lx = key/3, lz = key%3;
                if (key != start && downstream.contains(key)) { connected = true; break; }
                for (int i = 0; i < 4; i++) {
                    int nx = lx+dx[i], nz = lz+dz[i];
                    if (nx < 0 || nx > 2 || nz < 0 || nz > 2) continue;
                    int next = nx*3+nz;
                    BankPlan n = plans[x+nx-1][z+nz-1];
                    if (!seen[next] && n.channel && n.surface <= plans[x+lx-1][z+lz-1].surface) {
                        seen[next] = true; queue.add(next);
                    }
                }
            }
            if (!connected) return true;
        }
        return false;
    }
    private record BankPlan(boolean channel, boolean corridor, int surface, double width, double distance,
                            boolean source, boolean terminal) { }
}
