package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalBanks;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only synthetic columns exercise the runtime material guard without touching a world. */
final class BankSupportAudit {
    private BankSupportAudit() { }

    static String run() throws ReflectiveOperationException {
        Method guard = StreamsNaturalBanks.class.getDeclaredMethod("clearableSoil", WorldGenLevel.class,
                int.class, int.class, int.class, int.class, boolean.class);
        guard.setAccessible(true);
        Map<Integer, BlockState> blocks = new HashMap<>();
        blocks.put(76, Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(75, Blocks.DIRT.defaultBlockState());
        blocks.put(74, Blocks.DIRT.defaultBlockState());
        blocks.put(73, Blocks.STONE.defaultBlockState());
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState"))
                        return blocks.getOrDefault(((BlockPos) args[0]).getY(), Blocks.AIR.defaultBlockState());
                    throw new UnsupportedOperationException("Unexpected world query: " + method);
                });
        require(check(guard, level, true), "native stone support rejected for v7 wide bank");
        require(!check(guard, level, false), "legacy soil-only support changed");
        blocks.put(73, Blocks.DIRT.defaultBlockState());
        require(check(guard, level, false), "legacy soil support rejected");
        for (var invalid : new net.minecraft.world.level.block.Block[]{Blocks.AIR, Blocks.WATER, Blocks.OAK_LOG}) {
            blocks.put(73, invalid.defaultBlockState());
            require(!check(guard, level, true), "unsupported base accepted: " + invalid);
        }
        blocks.put(73, Blocks.STONE.defaultBlockState());
        blocks.put(74, Blocks.STONE.defaultBlockState());
        require(!check(guard, level, true), "retained native rock was converted into soil");
        blocks.put(74, Blocks.DIRT.defaultBlockState());
        blocks.put(75, Blocks.OAK_LOG.defaultBlockState());
        require(!check(guard, level, true), "non-soil excavation accepted");
        return "{\"event\":\"bank_support_audit\",\"passed\":true,\"cases\":8}";
    }

    private static boolean check(Method guard, WorldGenLevel level, boolean wide)
            throws ReflectiveOperationException {
        return (Boolean) guard.invoke(null, level, 606, 1017, 76, 74, wide);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
