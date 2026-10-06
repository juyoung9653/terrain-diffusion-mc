package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankMargin;
import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalBanks;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** Read-only geometry/material export of loaded columns at the two reported banks. */
public final class BankWidthAudit {
    private BankWidthAudit() { }

    public static void run(ServerLevel level) throws Exception {
        Object engine = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                .getMethod("engine", WorldGenLevel.class).invoke(null, level);
        var planMethod = engine.getClass().getMethod("plan", int.class, int.class);
        Class<?> type = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
        var channel = type.getMethod("inChannel");
        var corridor = type.getMethod("inCorridor");
        var width = type.getMethod("width");
        var distance = type.getMethod("distance");
        var water = type.getMethod("waterSurfaceY");
        var slope = type.getMethod("slope");
        var source = type.getMethod("source");
        var terminal = type.getMethod("terminal");
        var junction = type.getMethod("distToJunction");
        int radius = Math.max(1, Math.min(64, Integer.getInteger("td.streamsProbe.bankWidthRadius", 24)));
        StringBuilder csv = new StringBuilder("site,x,z,inChannel,inCorridor,halfWidth,distance,plannedWater,slope,marginWidth,solidTop,topMaterial,waterTop,source,terminal,distToJunction\n");
        Map<String, Integer> materials = new TreeMap<>();
        int loaded = 0, missing = 0, wet = 0, dryBanks = 0, raisedBanks = 0;
        int[][] centers = {{-841, -1020}, {-3954, 90}};
        for (int site = 0; site < centers.length; site++) {
            int cx = centers[site][0], cz = centers[site][1];
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) { missing++; continue; }
                    loaded++;
                    Object plan = planMethod.invoke(engine, x, z);
                    boolean inChannel = (Boolean) channel.invoke(plan), inCorridor = (Boolean) corridor.invoke(plan);
                    double halfWidth = ((Number) width.invoke(plan)).doubleValue();
                    double lateralDistance = ((Number) distance.invoke(plan)).doubleValue();
                    int plannedWater = ((Number) water.invoke(plan)).intValue();
                    int scanTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    int solidTop = Integer.MIN_VALUE, waterTop = Integer.MIN_VALUE;
                    String material = "none";
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    // No height assumptions about the target, and no neighbouring chunk loads.
                    for (int y = scanTop; y >= level.getMinBuildHeight(); y--) {
                        pos.set(x, y, z);
                        var state = chunk.getBlockState(pos);
                        if (waterTop == Integer.MIN_VALUE && state.getFluidState().is(FluidTags.WATER)) waterTop = y;
                        if (solidTop == Integer.MIN_VALUE && state.getFluidState().isEmpty()
                                && !state.canBeReplaced() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)
                                && !state.getCollisionShape(level, pos).isEmpty()) {
                            solidTop = y;
                            material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        }
                        // Only surface-connected water is relevant; do not count aquifers under land.
                        if (solidTop != Integer.MIN_VALUE) break;
                    }
                    materials.merge(material, 1, Integer::sum);
                    if (waterTop != Integer.MIN_VALUE) wet++;
                    if (inCorridor && !inChannel && waterTop == Integer.MIN_VALUE) {
                        dryBanks++;
                        if (solidTop > plannedWater + 1) raisedBanks++;
                    }
                    csv.append(String.format(Locale.ROOT, "%d,%d,%d,%s,%s,%.6f,%.6f,%d,%.6f,%.6f,%d,%s,%d,%s,%s,%.6f%n",
                            site, x, z, inChannel, inCorridor, halfWidth, lateralDistance, plannedWater,
                            ((Number) slope.invoke(plan)).doubleValue(), NaturalRiverBankMargin.width(halfWidth, x, z),
                            solidTop, material, waterTop, source.invoke(plan), terminal.invoke(plan),
                            ((Number) junction.invoke(plan)).doubleValue()));
                }
            }
        }
        var root = level.getServer().getWorldPath(LevelResource.ROOT);
        Files.writeString(root.resolve("td-bank-width-columns.csv"), csv);
        String summary = "loaded=" + loaded + " missing=" + missing + " wet=" + wet
                + " dryCorridorBanks=" + dryBanks + " banksAboveWaterPlusOne=" + raisedBanks
                + " shapedColumns=" + StreamsNaturalBanks.shapedColumns()
                + " removedSoilBlocks=" + StreamsNaturalBanks.removedBlocks() + " materials=" + materials;
        Files.writeString(root.resolve("td-bank-width-summary.txt"), summary + "\n");
        System.out.println("TD BankWidthAudit: " + summary);
        if (loaded == 0) throw new IllegalStateException("BankWidthAudit found no loaded target columns");
    }
}
