package com.github.xandergos.terraindiffusionmc.compat.streams;

import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** New v4 chunks only: connect raster corners, then contain shallow lateral water steps. */
public final class StreamsRiverBanks {
    private StreamsRiverBanks() { }
    public static void finish(WorldGenLevel level, int minX, int minZ) {
        if (WorldNaturalRiverState.version(level.getLevel()) != 4) return;
        try {
            Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
            Object engine = worldgen.getMethod("engine", WorldGenLevel.class).invoke(null, level);
            Object config = engine.getClass().getMethod("config").invoke(engine);
            if (StreamsNaturalRiverAdapter.version(config) != 4) return;
            if ((Boolean) engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class)
                    .invoke(engine, minX, minZ, 8)) return;
            Class<?> carver = Class.forName("dev.streamsreflowing.worldgen.RiverCarverFeature");
            Method shields = carver.getDeclaredMethod("a", WorldGenLevel.class, ChunkPos.class);
            Method shielded = carver.getDeclaredMethod("a", int[].class, int.class, int.class, int.class);
            shields.setAccessible(true); shielded.setAccessible(true);
            int[] protection = (int[]) shields.invoke(null, level, new ChunkPos(minX >> 4, minZ >> 4));
            Method blockedBiome = Class.forName("dev.streamsreflowing.worldgen.StreamBiomeBlacklist")
                    .getMethod("isBlocked", Holder.class);
            int sea = (int) StreamsNaturalNetwork.n(config, "seaLevel");
            Method plan = engine.getClass().getMethod("plan", int.class, int.class);
            Class<?> column = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            Method channel = column.getMethod("inChannel"), corridor = column.getMethod("inCorridor"),
                    surface = column.getMethod("waterSurfaceY"), width = column.getMethod("width"),
                    distance = column.getMethod("distance"), source = column.getMethod("source"), terminal = column.getMethod("terminal");
            BankPlan[][] plans = new BankPlan[18][18];
            for (int x = 0; x < 18; x++) for (int z = 0; z < 18; z++) {
                Object p = plan.invoke(engine, minX+x-1, minZ+z-1);
                plans[x][z] = new BankPlan((Boolean)channel.invoke(p), (Boolean)corridor.invoke(p),
                        ((Number)surface.invoke(p)).intValue(), ((Number)width.invoke(p)).doubleValue(),
                        ((Number)distance.invoke(p)).doubleValue(), (Boolean)source.invoke(p), (Boolean)terminal.invoke(p));
            }
            for (int x = 1; x <= 16; x++) for (int z = 1; z <= 16; z++) {
                BankPlan p = plans[x][z];
                if (p.channel || !p.corridor || p.source || p.terminal || p.surface <= sea+2 || p.distance > p.width+2) continue;
                if (p.distance <= p.width+.5 && connectingCorner(plans, x, z)) {
                    BlockPos cut = new BlockPos(minX+x-1, p.surface, minZ+z-1);
                    boolean clear = soil(level.getBlockState(cut)) && soil(level.getBlockState(cut.below()));
                    for (int dy = 1; dy <= 2; dy++) {
                        BlockState cover = level.getBlockState(cut.above(dy));
                        clear &= cover.canBeReplaced() && cover.getFluidState().isEmpty();
                    }
                    if (clear && !(Boolean) shielded.invoke(null, protection, cut.getX(), cut.getZ(), p.surface-1)
                            && !(Boolean) blockedBiome.invoke(null, level.getBiome(cut))) {
                        level.setBlock(cut.below(), Blocks.MUD.defaultBlockState(), 2);
                        for (int dy = 0; dy <= 2; dy++) level.setBlock(cut.above(dy), Blocks.AIR.defaultBlockState(), 2);
                    }
                    // Never dam a required corner, including already wet or protected corners.
                    // Native fluid ticks fill a successful excavation; no water source is added.
                    continue;
                }
                int crest = Integer.MIN_VALUE;
                for (BankPlan n : new BankPlan[]{plans[x-1][z],plans[x+1][z],plans[x][z-1],plans[x][z+1]})
                    if (n.channel && n.surface <= p.surface+6) crest = Math.max(crest,n.surface+1);
                // One block of freeboard also contains source-level settling at
                // steps. A natural cliff beside a fall may need a deeper bank fill.
                if (crest == Integer.MIN_VALUE || crest < p.surface || crest > p.surface+7) continue;
                BlockPos pos = new BlockPos(minX+x-1, crest, minZ+z-1);
                BlockState at = level.getBlockState(pos);
                if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) continue;
                BlockState ground = level.getBlockState(pos.below());
                int depth = 1;
                // Native decoration may leave tall grass in a two-block bank gap.
                // Dry replaceable plants are clearance, just like air; water is not.
                while (depth < 6 && ground.canBeReplaced() && ground.getFluidState().isEmpty()) {
                    depth++; ground = level.getBlockState(pos.below(depth));
                }
                if (!soil(ground)) continue;
                if ((Boolean) shielded.invoke(null, protection, pos.getX(), pos.getZ(), crest-depth)
                        || (Boolean) blockedBiome.invoke(null, level.getBiome(pos.below(depth)))) continue;
                for (int dy=1;dy<depth;dy++) level.setBlock(pos.below(dy), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(pos, ground.is(Blocks.MUD) ? Blocks.MUD.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Streams v4 bank adapter", failure);
        }
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
