package com.terrain.leafocclusion;

import java.util.concurrent.atomic.LongAdder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Culls only a fully enclosed group of three verified opaque vanilla leaf models.
 * It is intentionally disabled until the active resource-manager bytes match the
 * generated manifest for this exact pack stack.
 */
@Mod(LeafOcclusion.MODID)
public final class LeafOcclusion {
    public static final String MODID = "td_leaf_occlusion";
    private static final Logger LOG = LogUtils.getLogger();
    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder CULLED = new LongAdder();
    private static final LongAdder REJECTED_WATERLOGGED = new LongAdder();
    private static final LongAdder REJECTED_NEIGHBOR = new LongAdder();
    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static long clientTicks;

    public LeafOcclusion() {
        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) NeoForge.EVENT_BUS.addListener(LeafOcclusion::clientTick);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        if (++clientTicks % 1200L == 0L) LOG.info("TD leaf occlusion {} guard={}", statsJson(), LeafAssetGuard.status());
    }

    static void onGuardReload(boolean active, String reason) { LOG.info("TD leaf occlusion asset guard active={} reason={}", active, reason); }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ClientModBus {
        @SubscribeEvent
        public static void registerReloadListener(RegisterClientReloadListenersEvent event) { LeafOcclusion.registerReloadListener(event); }
    }

    private static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new LeafAssetGuard());
    }

    /** Called from Sodium's worker-owned BlockRenderer with its LevelSlice snapshot. */
    public static boolean shouldCull(BlockAndTintGetter snapshot, BlockState center, BlockPos pos) {
        if (!LeafAssetGuard.isEnabled() || snapshot == null || !isVerifiedLeaf(center) || waterlogged(center)) return false;
        CANDIDATES.increment();
        final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        BlockPos.MutableBlockPos cursor = SCRATCH.get();
        // Face neighbors reject most candidates before the 20 diagonal checks.
        for (int axis = 0; axis < 6; axis++) {
            int dx = axis == 0 ? -1 : axis == 1 ? 1 : 0;
            int dy = axis == 2 ? -1 : axis == 3 ? 1 : 0;
            int dz = axis == 4 ? -1 : axis == 5 ? 1 : 0;
            if (!eligible(snapshot, cursor.set(x + dx, y + dy, z + dz))) { REJECTED_NEIGHBOR.increment(); return false; }
        }
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            if ((Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) == 1) continue;
            if (!eligible(snapshot, cursor.set(x + dx, y + dy, z + dz))) { REJECTED_NEIGHBOR.increment(); return false; }
        }
        CULLED.increment();
        return true;
    }

    private static boolean eligible(BlockAndTintGetter snapshot, BlockPos pos) {
        BlockState state = snapshot.getBlockState(pos);
        if (waterlogged(state)) { REJECTED_WATERLOGGED.increment(); return false; }
        if (isVerifiedLeaf(state)) return true;
        // Deliberately do not accept generic solid blocks: resource packs can
        // make their visual geometry non-cubic. The three verified leaves are
        // the entire first candidate's closed halo.
        return false;
    }

    private static boolean waterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static boolean isVerifiedLeaf(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.OAK_LEAVES || block == Blocks.BIRCH_LEAVES || block == Blocks.SPRUCE_LEAVES;
    }

    /** Immutable-counter snapshot usable from a shutdown/report hook without world access. */
    public static String statsJson() {
        return "{\"asset_guard_enabled\":" + LeafAssetGuard.isEnabled() + ",\"compiled_candidates\":" + CANDIDATES.sum()
            + ",\"culled_models\":" + CULLED.sum() + ",\"rejected_waterlogged\":" + REJECTED_WATERLOGGED.sum()
            + ",\"rejected_neighbor\":" + REJECTED_NEIGHBOR.sum() + "}";
    }
}
