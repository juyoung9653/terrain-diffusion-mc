package com.github.xandergos.terraindiffusionmc.diagnostic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Opt-in client-only observation of the already-synced Streams current grid.
 * It never sends packets, changes chunks, or enables the server-side probe.
 */
@EventBusSubscriber(modid = "terrain_diffusion_mc", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientFlowProbe {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusionClientFlowProbe");
    private static long nextTick = Long.MIN_VALUE;
    private static boolean announced;

    private ClientFlowProbe() { }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("td.clientFlowProbe")) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || minecraft.isPaused()) return;
        long tick = level.getGameTime();
        if (tick < nextTick) return;
        nextTick = tick + Math.max(20, Integer.getInteger("td.clientFlowProbe.interval", 100));
        if (!announced) {
            announced = true;
            LOG.info("[TD-CLIENT-FLOW] enabled sodium={} renderer={}", ModList.get().isLoaded("sodium"), minecraft.levelRenderer.getClass().getName());
        }
        BlockPos sample = nearbyWater(level, player.blockPosition());
        if (sample == null) {
            LOG.info("[TD-CLIENT-FLOW] tick={} player={},{},{} water=none withinRadius=4 vertical=8", tick, player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
            return;
        }
        FluidState state = level.getFluidState(sample);
        Vec3 flow = state.getFlow(level, sample);
        NativeFlow nativeFlow = nativeFlow(level, sample);
        LOG.info("[TD-CLIENT-FLOW] tick={} player={},{},{} sample={},{},{} source={} gridBytes={} angle={} vector={},{},{} vectorLength={}",
                tick, player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
                sample.getX(), sample.getY(), sample.getZ(), state.isSource(), nativeFlow.gridBytes, nativeFlow.angle,
                round(flow.x), round(flow.y), round(flow.z), round(Math.hypot(flow.x, flow.z)));
    }

    /** Finds water by distance only; it never selects a cell because its current vector is nonzero. */
    private static BlockPos nearbyWater(ClientLevel level, BlockPos center) {
        for (int distance = 0; distance <= 4; distance++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    for (int dz = -distance; dz <= distance; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                        int x = center.getX() + dx;
                        int z = center.getZ() + dz;
                        if (!level.hasChunk(x >> 4, z >> 4)) continue;
                        BlockPos pos = new BlockPos(x, center.getY() + dy, z);
                        if (level.getFluidState(pos).is(FluidTags.WATER)) return pos;
                    }
                }
            }
        }
        return null;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.5f", value);
    }

    /** Streams Reflowing is optional at compile time, just as it is for the server probe. */
    private static NativeFlow nativeFlow(ClientLevel level, BlockPos pos) {
        try {
            Class<?> gridClass = Class.forName("dev.streamsreflowing.flow.ClientFlowGrids");
            Method gridAt = gridClass.getMethod("gridAt", int.class, int.class);
            byte[] grid = (byte[]) gridAt.invoke(null, pos.getX() >> 4, pos.getZ() >> 4);
            Class<?> flowClass = Class.forName("dev.streamsreflowing.flow.StreamFlowGrid");
            Method angleForEntityPush = flowClass.getMethod("angleForEntityPush", net.minecraft.world.level.BlockGetter.class, int.class, int.class, int.class);
            byte angle = (Byte) angleForEntityPush.invoke(null, level, pos.getX(), pos.getY(), pos.getZ());
            return new NativeFlow(grid == null ? -1 : grid.length, angle);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            LOG.warn("[TD-CLIENT-FLOW] native grid reflection failed: {}", failure.toString());
            return new NativeFlow(-2, (byte) -2);
        }
    }

    private record NativeFlow(int gridBytes, byte angle) { }
}
