package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailDepthPolicy;
import com.github.xandergos.terraindiffusionmc.compat.continuity.CattailHeightMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.function.Predicate;

/** Opt-in title-screen test of the installed, genuinely Mixin-transformed Continuity classes. */
public final class CattailDepthAudit {
    private static boolean ran;
    private static int checks;

    private CattailDepthAudit() { }

    public static void tick() {
        if (ran || !Boolean.getBoolean("td.cattailDepthAudit")) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getOverlay() != null || minecraft.screen == null) return;
        ran = true;
        try {
            run();
            LoggerFactory.getLogger("CattailDepthAudit").info("[TD-CATTAIL-AUDIT] PASS checks={} actual Continuity properties/predicate; half/full/deep/covered/flow/lava/biome/unrelated", checks);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("CattailDepthAudit").error("[TD-CATTAIL-AUDIT] FAIL", failure);
        } finally {
            if (Boolean.getBoolean("td.cattailDepthAudit.exit")) minecraft.stop();
        }
    }

    private static void run() throws Exception {
        Class<?> propertiesType = Class.forName("me.pepperbell.continuity.client.properties.BaseCtmProperties");
        Class<?> predicateType = Class.forName("me.pepperbell.continuity.client.processor.BaseProcessingPredicate");
        Method fromProperties = predicateType.getMethod("fromProperties", propertiesType);
        Method process = java.util.Arrays.stream(predicateType.getMethods())
                .filter(method -> method.getName().equals("shouldProcessQuad")).findFirst().orElseThrow();
        // The controlled predicate below ignores biome identity; no world registry is loaded at title screen.
        // Continuity rejects a missing cached biome before invoking its biome predicate.
        // Build an unregistered fixture biome; no world, registry mutation, or datapack load is needed.
        Biome fixtureBiome = new Biome.BiomeBuilder().temperature(0.8F).downfall(0.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0)
                        .waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
        Object data = biomeData(fixtureBiome);
        BlockState seagrass = Blocks.SEAGRASS.defaultBlockState();
        BlockState halfSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                .setValue(SlabBlock.WATERLOGGED, true);

        for (int rule = 0; rule < 5; rule++) {
            Object properties = properties(propertiesType, "optifine/ctm/cattails1/" + rule + ".properties", true);
            require(get(propertiesType, properties, "heightPredicate") instanceof CattailHeightMarker, "properties hook " + rule);
            // Preserve the actual Continuity biome-predicate branch, with a controlled freshwater result.
            set(propertiesType, properties, "biomePredicate", (Predicate<Biome>) biome -> true);
            Object predicate = fromProperties.invoke(null, properties);
            require(get(predicateType, predicate, "heightPredicate") == null, "predicate constructor hook " + rule);
            for (int y : new int[]{-63, 61, 125, 319}) {
                BlockPos pos = new BlockPos(15, y, 15);
                require(process(process, predicate, pos, seagrass, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "full cell " + rule + " Y=" + y);
                require(process(process, predicate, pos, halfSlab, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "same-cell half slab " + rule);
                require(!process(process, predicate, pos, seagrass, Blocks.WATER.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "deep " + rule);
                require(!process(process, predicate, pos, seagrass, halfSlab, Blocks.STONE.defaultBlockState(), data), "waterlogged cell above " + rule);
                require(!process(process, predicate, pos, seagrass, Blocks.AIR.defaultBlockState(), halfSlab, data), "slab below is not half-depth " + rule);
                require(!process(process, predicate, pos, Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "flowing " + rule);
                require(!process(process, predicate, pos, Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "lava " + rule);
                require(!process(process, predicate, pos, Blocks.TALL_SEAGRASS.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "unsupported tall plant " + rule);
            }
            set(propertiesType, properties, "biomePredicate", (Predicate<Biome>) biome -> false);
            require(!process(process, fromProperties.invoke(null, properties), new BlockPos(0, 125, 0), seagrass,
                    Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), data), "marine result preserved " + rule);
        }

        Object unrelated = properties(propertiesType, "optifine/ctm/other/0.properties", true);
        require(!(get(propertiesType, unrelated, "heightPredicate") instanceof CattailHeightMarker), "unrelated rule unmarked");
        require(!process(process, fromProperties.invoke(null, unrelated), new BlockPos(0, 125, 0), seagrass,
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), null), "unrelated sentinel intact");
        Object ordinary = properties(propertiesType, "optifine/ctm/cattails1/0.properties", false);
        require(process(process, fromProperties.invoke(null, ordinary), new BlockPos(0, 125, 0), seagrass,
                Blocks.WATER.defaultBlockState(), Blocks.STONE.defaultBlockState(), null), "unmarked CTM unchanged even deep");
    }

    private static Object properties(Class<?> type, String path, boolean marked) throws Exception {
        Properties raw = new Properties();
        raw.setProperty("heights", marked ? CattailDepthPolicy.DISABLED_HEIGHT : "125");
        if (marked) raw.setProperty(CattailDepthPolicy.PROPERTY, CattailDepthPolicy.DEPTHS);
        PackResources pack = (PackResources) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{PackResources.class},
                (proxy, method, args) -> method.getName().equals("packId") ? "cattail-depth-audit" : null);
        Object properties = type.getConstructor(Properties.class, ResourceLocation.class, PackResources.class,
                int.class, ResourceManager.class, String.class).newInstance(raw,
                ResourceLocation.fromNamespaceAndPath("minecraft", path), pack, 0, Minecraft.getInstance().getResourceManager(), "fixed");
        Method parse = type.getDeclaredMethod("parseHeights");
        parse.setAccessible(true);
        parse.invoke(properties);
        return properties;
    }

    private static Object biomeData(Biome biome) throws Exception {
        Class<?> cacheType = Class.forName("me.pepperbell.continuity.client.processor.BaseProcessingPredicate$BiomeCache");
        Object cache = cacheType.getConstructor().newInstance();
        set(cacheType, cache, "biome", biome);
        set(cacheType, cache, "invalid", false);
        Class<?> providerType = Class.forName("me.pepperbell.continuity.api.client.ProcessingDataProvider");
        return Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType},
                (proxy, method, args) -> method.getName().equals("getData") ? cache : null);
    }

    private static boolean process(Method method, Object predicate, BlockPos pos, BlockState state,
            BlockState above, BlockState below, Object data) throws Exception {
        BlockAndTintGetter view = (BlockAndTintGetter) Proxy.newProxyInstance(CattailDepthAudit.class.getClassLoader(),
                new Class<?>[]{BlockAndTintGetter.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getBlockState" -> ((BlockPos) args[0]).equals(pos.below()) ? below
                            : ((BlockPos) args[0]).equals(pos.above()) ? above : state;
                    case "getFluidState" -> ((BlockPos) args[0]).equals(pos.above()) ? above.getFluidState() : Fluids.EMPTY.defaultFluidState();
                    case "getHeight" -> 384;
                    case "getMinBuildHeight" -> -64;
                    default -> throw new AssertionError("Unexpected mesh-view read: " + called);
                });
        return (boolean) method.invoke(predicate, null, null, view, state, state, pos, data);
    }

    private static Object get(Class<?> type, Object object, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    private static void require(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
}
