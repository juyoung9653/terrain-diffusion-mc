package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.ecology.EcoSurfaceFeature;
import com.github.xandergos.terraindiffusionmc.world.ecology.EcoTreesFeature;
import com.github.xandergos.terraindiffusionmc.world.ecology.EcoGroundcoverFeature;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Loader-neutral lifecycle and command logic for terrain-diffusion-mc.
 */
public final class TerrainDiffusionLifecycle {
    public static final String MOD_ID = "terrain-diffusion-mc";
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionLifecycle.class);
    public static final ResourceLocation TERRAIN_DIFFUSION_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion");
    public static final ResourceLocation ECO_TREES_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "eco_trees");
    public static final ResourceLocation ECO_SURFACE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "eco_surface");
    public static final ResourceLocation ECO_GROUNDCOVER_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "eco_groundcover");
    private static boolean initialized;

    private TerrainDiffusionLifecycle() {
    }

    /**
     * Loader entrypoints call this once with their platform-provided runtime paths.
     */
    public static synchronized void bootstrap(Path configDir, Path gameDir) {
        PlatformPaths.configure(configDir, gameDir);
        initialize();
    }

    /**
     * Runs common mod initialization once per loader.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();
    }


    /**
     * Registers the common biome source codec through the active loader's registry hook.
     */
    public static void registerBiomeSourceCodecs(CodecRegistrar<MapCodec<? extends BiomeSource>> registrar) {
        registrar.register(TERRAIN_DIFFUSION_ID, TerrainDiffusionBiomeSource.CODEC);
    }

    /**
     * Registers the common density function codec through the active loader's registry hook.
     */
    public static void registerDensityFunctionCodecs(CodecRegistrar<MapCodec<? extends DensityFunction>> registrar) {
        registrar.register(TERRAIN_DIFFUSION_ID, TerrainDiffusionDensityFunction.CODEC);
    }

    /** Registers the ecological worldgen feature types used by the opt-in biome palette. */
    public static void registerEcologyFeatures(CodecRegistrar<Feature<?>> registrar) {
        registrar.register(ECO_TREES_ID, new EcoTreesFeature());
        registrar.register(ECO_SURFACE_ID, new EcoSurfaceFeature());
        registrar.register(ECO_GROUNDCOVER_ID, new EcoGroundcoverFeature());
    }

    @FunctionalInterface
    public interface CodecRegistrar<T> {
        void register(ResourceLocation id, T value);
    }

    /**
     * Called before level creation (NeoForge AboutToStart / Fabric SERVER_STARTING).
     */
    public static void onServerStarting() {
        LocalTerrainProvider.beginGameplay();
    }

    /**
     * Called by each loader when a server level is loaded.
     */
    public static void onWorldLoad(ServerLevel world) {
        if (isPreviewServer(world.getServer())) return;
        if (world.dimension() == Level.OVERWORLD) {
            WorldScaleManager.initializeForWorld(world);
            LocalTerrainProvider.init(world.getSeed());
        }
    }

    /** World Preview's temporary server must not consume world-creation settings or reseed gameplay. */
    public static boolean isPreviewServer(net.minecraft.server.MinecraftServer server) {
        return server != null && server.getClass().getName().equals(
                "com.caeruleusTait.world.preview.backend.stubs.DummyMinecraftServer");
    }

    /**
     * Called by each loader when the server is stopping.
     */
    public static void onServerStopping(net.minecraft.server.MinecraftServer server) {
        com.github.xandergos.terraindiffusionmc.world.FastLocateCommand.stop(server);
        com.github.xandergos.terraindiffusionmc.world.AsyncStructureLocate.stop(server);
        ExplorerServer.stop();
    }

    /** Server saving/chunk shutdown is complete; no new gameplay inference may start. */
    public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        com.github.xandergos.terraindiffusionmc.world.structure.StructurePlacementRuntime.clear();
        com.github.xandergos.terraindiffusionmc.world.structure.SavedStructureIndex.clear();
        com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.unbindWorld(
                server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE));
        LocalTerrainProvider.shutdownGameplay();
        // A dedicated server can abort startup while native sessions are still being built.
        // Integrated servers leave that shared client loader available for menus/previews.
        if (server.isDedicatedServer()) PipelineModels.stopLoadingAndAwait();
    }

    /** Runs on ordinary client teardown, before System.exit can start OrtEnvCloser. */
    public static void onClientStopping() {
        ExplorerServer.stop();
        LocalTerrainProvider.shutdownAll();
    }

    /**
     * Registers common server commands on the loader-provided command dispatcher.
     */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.github.xandergos.terraindiffusionmc.world.FastLocateCommand.register(dispatcher);
        dispatcher.register(Commands.literal("td-explore").executes(TerrainDiffusionLifecycle::executeExplore));
    }

    private static int executeExplore(CommandContext<CommandSourceStack> ctx) {
        try {
            int port = ExplorerServer.startIfNotRunning();
            String url = "http://localhost:" + port;
            MutableComponent link = Component.literal(url)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withUnderlined(true));
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Terrain Explorer: ").append(link),
                    false);
        } catch (Exception e) {
            LOG.error("Failed to start terrain explorer", e);
            ctx.getSource().sendFailure(Component.literal("Failed to start terrain explorer: " + e.getMessage()));
        }
        return 1;
    }
}
