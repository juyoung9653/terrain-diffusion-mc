package com.github.xandergos.terraindiffusionmc.diagnostic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import com.github.xandergos.terraindiffusionmc.world.river.WorldNaturalRiverState;

/** Plan correlation; optional block audit generates only the explicitly requested probe chunks. */
public final class RiverScreenshotAudit {
    private RiverScreenshotAudit() { }

    public static void run(ServerLevel level, Path output) throws Exception {
        int expectedVersion = Integer.getInteger("td.streamsProbe.expectedNaturalProfile", 0);
        if (expectedVersion > 0 && WorldNaturalRiverState.version(level) != expectedVersion)
            throw new IllegalStateException("Expected river profile " + expectedVersion
                    + ", found " + WorldNaturalRiverState.version(level));
        int expectedScale = Integer.getInteger("td.streamsProbe.expectedScale", 0);
        int actualScale = com.github.xandergos.terraindiffusionmc.world.WorldScaleManager.getCurrentScale();
        if (expectedScale > 0 && actualScale != expectedScale)
            throw new IllegalStateException("Expected scale " + expectedScale + ", found " + actualScale);
        Object biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        boolean naturalBiomes = (Boolean) biomeSource.getClass().getMethod("naturalBiomes").invoke(biomeSource);
        var terralithField = biomeSource.getClass().getDeclaredField("terralith");
        terralithField.setAccessible(true);
        boolean terralith = terralithField.getBoolean(biomeSource);
        if (Boolean.getBoolean("td.streamsProbe.expectedNaturalBiomes") && (!naturalBiomes || !terralith))
            throw new IllegalStateException("Expected user ecology biome source, found natural=" + naturalBiomes + " terralith=" + terralith);
        System.out.println("RiverScreenshotBiomeSource: naturalBiomes=" + naturalBiomes + " terralith=" + terralith);
        verifyLakeCapAdmission();
        verifySandRoof(level);
        // Fresh probe worlds have not requested chunks yet. Initialize only the
        // native planning engine on the server thread, without loading chunks.
        Object engine = level.getServer().submit(() -> {
            try {
                return Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen")
                        .getMethod("engine", WorldGenLevel.class).invoke(null, level);
            } catch (ReflectiveOperationException failure) {
                failure.printStackTrace();
                throw new IllegalStateException("Native river engine initialization failed", failure);
            }
        }).get(Long.getLong("td.riverScreenshot.initTimeoutSeconds", 600L), java.util.concurrent.TimeUnit.SECONDS);
        if (engine == null) throw new IllegalStateException("Native river engine unavailable");
        var plan = engine.getClass().getMethod("plan", int.class, int.class);
        // V9's dry candidate consults this plan; legacy admission does not.
        // Warm it explicitly in both runs so profile comparisons have the same
        // native network query order before any screenshot-region queries.
        plan.invoke(engine, 600, 688);
        verifySlabAdmission(level);
        var lakeAt = engine.getClass().getMethod("lakeAt", int.class, int.class);
        var lakeNear = engine.getClass().getMethod("lakeNear", int.class, int.class, int.class);
        var chunkLake = engine.getClass().getMethod("lakeNearChunk", int.class, int.class, int.class);
        var terrain = engine.getClass().getMethod("terrainHeight", int.class, int.class);
        Files.createDirectories(output);
        for (String area : System.getProperty("td.streamsProbe.screenshotAreas",
                "steppe:2944:3007:1216:1279;desert:576:639:656:735").split(";")) {
            String[] parts = area.split(":");
            if (parts.length != 5 || !parts[0].matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException(area);
            String name = parts[0];
            int minX = Integer.parseInt(parts[1]), maxX = Integer.parseInt(parts[2]);
            int minZ = Integer.parseInt(parts[3]), maxZ = Integer.parseInt(parts[4]);
            if (maxX < minX || maxZ < minZ || (long)(maxX-minX+1)*(maxZ-minZ+1)>65536) throw new IllegalArgumentException(area);
            try (var writer = Files.newBufferedWriter(output.resolve(name + "-native-plans.csv"), StandardCharsets.UTF_8)) {
                writer.write("x,z,inChannel,inCorridor,distance,width,waterSurfaceY,bedY,slope,source,terminal,terrainHeight,lakeAt,lakeNearChunk,lakeSpacing,lakeCoverage,lakeLevel,lakeDigDepth,terrainBiome,ecologyBiome,temperature,moisture,treeCover\n");
                for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                    Object column = plan.invoke(engine, x, z);
                    writer.write(x + "," + z);
                    for (String field : List.of("inChannel", "inCorridor", "distance", "width", "waterSurfaceY",
                            "bedY", "slope", "source", "terminal"))
                        writer.write("," + column.getClass().getMethod(field).invoke(column));
                    Object lake = lakeAt.invoke(engine, x, z);
                    writer.write("," + terrain.invoke(engine, x, z) + "," + (lake != null) + ","
                            + chunkLake.invoke(engine, x & ~15, z & ~15, 8));
                    if (lake == null) lake = lakeNear.invoke(engine, x, z, 1);
                    writer.write(lake == null ? ",0,0,0,0" : "," + lake.getClass().getMethod("spacing").invoke(lake)
                            + "," + lake.getClass().getMethod("coverageSmooth", int.class, int.class).invoke(lake, x, z)
                            + "," + lake.getClass().getMethod("level").invoke(lake)
                            + "," + lake.getClass().getMethod("digDepth").invoke(lake));
                    var ecology = com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology.surfaceSample(level.getChunkSource().getGenerator(), x, z);
                    writer.write(ecology == null ? ",,,,," : "," + ecology.terrainBiomeId() + "," + ecology.climate().biomeId()
                            + "," + ecology.climate().temperature() + "," + ecology.climate().moisture() + "," + ecology.climate().treeCover());
                    writer.newLine();
                }
            }
        }
        System.out.println("RiverScreenshotAudit: wrote requested native columns; seed=" + level.getSeed());
        if (Boolean.getBoolean("td.streamsProbe.riverScreenshotBlocks")) auditBlocks(level, output);
    }

    private static void verifyLakeCapAdmission() throws Exception {
        var test = Class.forName("com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalBanks")
                .getDeclaredMethod("generatedDrySlab", BlockState.class);
        test.setAccessible(true);
        BlockState slab = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("terrain_slabs:grass_slab"))
                .defaultBlockState();
        var generated = (net.minecraft.world.level.block.state.properties.BooleanProperty) slab.getProperties().stream()
                .filter(property -> property.getName().equals("generated")).findFirst().orElseThrow();
        BlockState dry = slab.setValue(generated, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false);
        if (!(Boolean) test.invoke(null, dry)
                || (Boolean) test.invoke(null, dry.setValue(generated, false))
                || (Boolean) test.invoke(null, dry.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true))
                || (Boolean) test.invoke(null, dry.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP))
                || (Boolean) test.invoke(null, dry.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.DOUBLE))
                || (Boolean) test.invoke(null, Blocks.STONE_SLAB.defaultBlockState()))
            throw new IllegalStateException("Lake cap admission changed player slabs, wet slabs or vanilla slabs");
        System.out.println("LakeCapAdmissionAudit: PASS generated dry bottom admitted; player, wet, top, double and vanilla slabs rejected");
    }

    private static void auditBlocks(ServerLevel level, Path output) throws Exception {
        String[] sites = System.getProperty("td.streamsProbe.screenshotBlockSites", "steppe:186:77;desert:37:42").split(";");
        int[][] starts = new int[sites.length][2];
        for (int i=0;i<sites.length;i++) {
            String[] fields=sites[i].split(":");
            if(fields.length!=3 || !fields[0].matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException(sites[i]);
            starts[i]=new int[]{Integer.parseInt(fields[1]),Integer.parseInt(fields[2])};
        }
        for (int[] start : starts) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            int cx = start[0] + dx, cz = start[1] + dz;
            level.getServer().submit(() -> level.getChunk(cx, cz))
                    .get(600, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("RiverScreenshotBlocks: generated " + cx + "," + cz);
        }
        for (int area = 0; area < starts.length; area++) {
            int[] start = starts[area];
            List<String> rows = level.getServer().submit(() -> {
                List<String> result = new java.util.ArrayList<>();
                result.add("x,z,groundY,ground,waterY,generatedWaterloggedSlabs,surfaceBottomSlabs,biome");
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                int slabCount = 0, surfaceCount = 0;
                for (int z = start[1] * 16; z < start[1] * 16 + 32; z++)
                    for (int x = start[0] * 16; x < start[0] * 16 + 32; x++) {
                        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                        if (chunk == null) chunk = level.getChunk(x >> 4, z >> 4);
                        int groundY = Integer.MIN_VALUE, waterY = Integer.MIN_VALUE, slabs = 0, surfaceSlabs = 0;
                        String ground = "none";
                        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.isAir()) continue;
                            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            boolean water = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                            if (water && waterY == Integer.MIN_VALUE) waterY = y;
                            if (water && id.startsWith("terrain_slabs:") && state.toString().contains("generated=true")) {
                                slabs++;
                                if (y >= waterY - 2 && state.toString().contains("type=bottom")) surfaceSlabs++;
                            }
                            if (groundY == Integer.MIN_VALUE && !state.getCollisionShape(level, pos).isEmpty()) {
                                groundY = y;
                                ground = id;
                            }
                        }
                        slabCount += slabs;
                        surfaceCount += surfaceSlabs;
                        result.add(x + "," + z + "," + groundY + "," + ground + "," + waterY + "," + slabs + "," + surfaceSlabs
                                + "," + level.getBiome(new BlockPos(x,groundY,z)).unwrapKey().orElseThrow().location());
                    }
                System.out.println("RiverScreenshotBlocks: sampled 1024 columns at " + start[0] + "," + start[1]
                        + " generatedWaterloggedSlabs=" + slabCount + " surfaceBottomSlabs=" + surfaceCount);
                return result;
            }).get(60, java.util.concurrent.TimeUnit.SECONDS);
            Files.write(output.resolve(sites[area].split(":")[0] + "-blocks.csv"), rows, StandardCharsets.UTF_8);
        }
    }

    private static void verifySandRoof(ServerLevel level) throws Exception {
        var method = com.github.xandergos.terraindiffusionmc.world.ecology.EcoSurfaceFeature.class
                .getDeclaredMethod("placeSupportedMineral", WorldGenLevel.class, BlockPos.class, BlockState.class, BlockState.class);
        method.setAccessible(true);
        boolean modern = WorldNaturalRiverState.version(level) >= 10;
        int checked = 0;
        for (boolean gravel : List.of(false,true)) for (int layers=1;layers<=3;layers++) for (var support : List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.WATER, Blocks.STONE)) {
            var mineral=gravel?Blocks.GRAVEL:Blocks.SAND;
            var rock=gravel?Blocks.STONE:Blocks.SANDSTONE;
            var states = new java.util.HashMap<BlockPos,BlockState>();
            var top = new BlockPos(0, 80, 0);
            for(int i=0;i<layers;i++) states.put(top.below(i), (i==0?Blocks.GRASS_BLOCK:Blocks.DIRT).defaultBlockState());
            BlockPos bottom=top.below(layers);
            states.put(bottom,support.defaultBlockState());
            var before=java.util.Map.copyOf(states);
            var fake=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
                    (proxy,called,args)->switch(called.getName()) {
                        case "getLevel" -> level;
                        case "getBlockState" -> states.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState());
                        case "getFluidState" -> states.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState()).getFluidState();
                        case "setBlock" -> { states.put(((BlockPos)args[0]).immutable(),(BlockState)args[1]); yield true; }
                        default -> throw new IllegalStateException("Unexpected sand roof query: "+called);
                    });
            method.invoke(null,fake,top,mineral.defaultBlockState(),rock.defaultBlockState());
            boolean air=support==Blocks.AIR || support==Blocks.CAVE_AIR;
            boolean changed=support==Blocks.STONE || modern&&air;
            if(!changed && !states.equals(before)) throw new IllegalStateException("Unsafe sand roof mutation");
            if(!states.get(bottom).equals(before.get(bottom))) throw new IllegalStateException("Sand roof filled cave/fluid/support");
            if(changed) for(int i=0;i<layers;i++) {
                var expected=air&&i==layers-1?rock:mineral;
                if(!states.get(top.below(i)).is(expected)) throw new IllegalStateException("Wrong stable roof layer");
            }
            checked++;
        }
        System.out.println("SandRoofAudit: PASS profile="+WorldNaturalRiverState.version(level)+" cases="+checked);
    }

    /** Calls the actual transformed native candidate method; the fake level rejects every write. */
    private static void verifySlabAdmission(ServerLevel level) throws Exception {
        Class<?> feature;
        try { feature = Class.forName("net.countered.terrainslabs.generation.SlabFeature"); }
        catch (ClassNotFoundException absent) {
            System.out.println("TerrainSlabAdmissionAudit: SKIP Terrain Slabs absent");
            return;
        }
        Object instance = feature.getConstructors()[0].newInstance(NoneFeatureConfiguration.CODEC);
        var candidate = feature.getDeclaredMethod("shouldPlaceBottomSlab", WorldGenLevel.class,
                BlockPos.class, boolean.class, boolean.class);
        candidate.setAccessible(true);
        int version = WorldNaturalRiverState.version(level);
        boolean withWall = candidate(level, instance, candidate, Blocks.WATER.defaultBlockState(), true, 62);
        boolean withoutWall = candidate(level, instance, candidate, Blocks.WATER.defaultBlockState(), false, 62);
        boolean drySlope = candidate(level, instance, candidate, Blocks.AIR.defaultBlockState(), true, -40);
        boolean flat = candidate(level, instance, candidate, Blocks.AIR.defaultBlockState(), false, -40);
        if (withWall != (version < 9) || withoutWall || !drySlope || flat)
            throw new AssertionError("Terrain Slabs admission v" + version + ": waterWithWall=" + withWall
                    + " waterWithoutWall=" + withoutWall + " drySlope=" + drySlope + " flat=" + flat);
        System.out.println("TerrainSlabAdmissionAudit: PASS v" + version + " waterWithWall=" + withWall
                + " waterWithoutWall=" + withoutWall + " drySlope=" + drySlope + " flat=" + flat);
    }

    private static boolean candidate(ServerLevel level, Object feature, java.lang.reflect.Method method,
                                     BlockState centre, boolean wall, int y) throws Exception {
        BlockPos origin = new BlockPos(600, y, 688);
        java.util.function.Function<BlockPos, BlockState> states = pos -> {
            if (pos.getY() < y) return Blocks.GRAVEL.defaultBlockState();
            if (pos.equals(origin)) return centre;
            if (wall && pos.equals(origin.north())) return Blocks.STONE.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        };
        WorldGenLevel fixture = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getLevel" -> level;
                    case "getBlockState" -> states.apply((BlockPos) args[0]);
                    case "getFluidState" -> states.apply((BlockPos) args[0]).getFluidState();
                    case "getBiome" -> level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
                    default -> throw new AssertionError("Unexpected fixture access: " + called.getName());
                });
        return (Boolean) method.invoke(feature, fixture, origin, false, false);
    }
}
