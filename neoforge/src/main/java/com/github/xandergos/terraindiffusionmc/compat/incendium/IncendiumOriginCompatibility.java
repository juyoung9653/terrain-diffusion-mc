package com.github.xandergos.terraindiffusionmc.compat.incendium;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionLifecycle;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.commands.functions.PlainTextFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Defers only the audited origin request and helper initialization, without blocking chunk futures. */
public final class IncendiumOriginCompatibility {
    private static final Logger LOG = LoggerFactory.getLogger("TerrainDiffusion/IncendiumOrigin");
    private static final ChunkPos ORIGIN = new ChunkPos(0, 0);
    private static final ResourceLocation LOAD = ResourceLocation.parse(IncendiumOriginPolicy.LOAD);
    private static final ResourceLocation HELPER = ResourceLocation.parse(IncendiumOriginPolicy.HELPER);
    private static final TicketType<ChunkPos> TICKET = TicketType.create(
            "td_incendium_origin", Comparator.comparingLong(ChunkPos::toLong));
    // Distance 2 yields level 31, matching vanilla's forced entity-ticking ticket.
    private static final int DISTANCE = 2;
    private static final Map<MinecraftServer, Pending> SERVERS = Collections.synchronizedMap(new IdentityHashMap<>());

    private IncendiumOriginCompatibility() { }

    public static List<UnboundEntryAction<CommandSourceStack>> entries(
            InstantiatedFunction<CommandSourceStack> function, CommandSourceStack source) {
        List<UnboundEntryAction<CommandSourceStack>> entries = function.entries();
        if (!function.id().equals(LOAD) && !function.id().equals(HELPER)) return entries;
        MinecraftServer server = source.getServer();
        if (server == null || !server.isSameThread()) return entries;
        Pending pending = SERVERS.get(server);
        if (function.id().equals(HELPER)) {
            if (pending != null && pending.state.suppressHelper()
                    && !commands(entries).equals(IncendiumOriginPolicy.HELPER_COMMANDS)) {
                pending.state.fallback();
                LOG.warn("Incendium origin: helper changed while pending; preserving current vanilla helper behavior");
                // Restore the deferred original force request before handing the changed
                // helper back to vanilla. Unsupported reloads may synchronously load it.
                pending.level.setChunkForced(0, 0, true);
                release(pending);
            }
            return pending != null && pending.state.suppressHelper()
                    && commands(entries).equals(IncendiumOriginPolicy.HELPER_COMMANDS) ? List.of() : entries;
        }
        if (pending != null) {
            if (source.getLevel() != pending.level || source.getEntity() != null) return entries;
            if (pending.state.suppressHelper() && !commands(entries).equals(IncendiumOriginPolicy.LOAD_COMMANDS)) {
                pending.state.fallback();
                release(pending);
                LOG.warn("Incendium origin: load changed while pending; preserving current vanilla load behavior");
            }
            return pending.state.suppressHelper() && commands(entries).equals(IncendiumOriginPolicy.LOAD_COMMANDS)
                    ? entries.subList(2, entries.size()) : entries;
        }
        if (!Boolean.parseBoolean(System.getProperty("td.incendium.deferOrigin", "true"))
                || source.getEntity() != null || !source.getLevel().dimension().equals(Level.OVERWORLD)
                || TerrainDiffusionLifecycle.isPreviewServer(server)
                || !(server.overworld().getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return entries;
        }
        ServerLevel level = server.overworld();
        pending = new Pending(level, source);
        SERVERS.put(server, pending);
        if (!IncendiumOriginPolicy.supports(commands(entries), functionCommands(server, HELPER))) {
            pending.state.fallback();
            LOG.warn("Incendium origin: unsupported function bodies; preserving synchronous vanilla behavior");
            return entries;
        }
        if (ready(level)) {
            pending.state.beginApply(true, true);
            pending.state.complete();
            return entries;
        }
        // Adding a ticket schedules work through the normal chunk pipeline. Never call
        // getChunkFuture here: on the server thread it performs managedBlock internally.
        level.getChunkSource().addRegionTicket(TICKET, ORIGIN, DISTANCE, ORIGIN);
        pending.ownsTicket = true;
        LOG.info("Incendium origin: pending asynchronous overworld 0,0; scoreboard setup continues; helper waits for chunk and entities");
        return entries.subList(2, entries.size());
    }

    public static void tick(MinecraftServer server) {
        Pending pending = SERVERS.get(server);
        if (pending == null || !pending.state.suppressHelper()) return;
        if (!IncendiumOriginPolicy.supports(functionCommands(server, LOAD), functionCommands(server, HELPER))) {
            // A datapack reload changed the contract during generation. Return control to
            // the current pack, explicitly accepting its original synchronous behavior.
            pending.state.fallback();
            release(pending);
            LOG.warn("Incendium origin: functions changed while pending; resuming current vanilla load function");
            server.getFunctions().get(LOAD).ifPresent(function -> server.getFunctions().execute(function, pending.source));
            return;
        }
        if (!pending.state.beginApply(pending.level.getChunkSource().getChunkNow(0, 0) != null,
                pending.level.areEntitiesLoaded(ORIGIN.toLong()))) {
            if (!pending.warned && System.nanoTime() - pending.started > 60_000_000_000L) {
                pending.warned = true;
                LOG.warn("Incendium origin: still pending after 60s; one temporary ticket retained, server thread is not waiting");
            }
            return;
        }
        // Chunk is already FULL; the vanilla persistent forceload cannot enter generation.
        // Existing-world entity storage is also loaded before the original singleton check.
        pending.level.setChunkForced(0, 0, true);
        server.getFunctions().get(HELPER).ifPresent(function -> server.getFunctions().execute(function, pending.source));
        int helpers = helperCount(pending.level);
        if (helpers == 1) pending.state.complete();
        else {
            pending.state.fallback();
            LOG.warn("Incendium origin: original helper returned {} markers; persistent ticket kept for vanilla 7s repair", helpers);
        }
        release(pending);
        LOG.info("Incendium origin: {} after {}ms; helpers={}, forced={}", pending.state.phase(),
                (System.nanoTime() - pending.started) / 1_000_000L, helpers,
                pending.level.getForcedChunks().contains(ORIGIN.toLong()));
    }

    /** Called before shutdown starts; no worker callbacks can reapply work to this server. */
    public static void stop(MinecraftServer server) {
        Pending pending = SERVERS.remove(server);
        if (pending == null) return;
        pending.state.stop();
        release(pending);
    }

    /** Run on the server thread; useful to isolated-world runtime probes. */
    public static String diagnostic(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Incendium diagnostics require server thread");
        Pending pending = SERVERS.get(server);
        return "phase=" + (pending == null ? "INACTIVE" : pending.state.phase())
                + ", temporaryTicket=" + (pending != null && pending.ownsTicket)
                + ", helpers=" + helperCount(server.overworld())
                + ", forced=" + server.overworld().getForcedChunks().contains(ORIGIN.toLong());
    }

    private static boolean ready(ServerLevel level) {
        return level.getChunkSource().getChunkNow(0, 0) != null && level.areEntitiesLoaded(ORIGIN.toLong());
    }

    private static int helperCount(ServerLevel level) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity.getType() == EntityType.MARKER && entity.getTags().contains("in.helper_entity")) count++;
        }
        return count;
    }

    private static List<String> functionCommands(MinecraftServer server, ResourceLocation id) {
        CommandFunction<CommandSourceStack> function = server.getFunctions().get(id).orElse(null);
        return function instanceof PlainTextFunction<CommandSourceStack> plain ? commands(plain.entries()) : List.of();
    }

    private static List<String> commands(List<UnboundEntryAction<CommandSourceStack>> entries) {
        if (entries.stream().anyMatch(entry -> !(entry instanceof BuildContexts.Unbound<?>))) return List.of();
        return entries.stream().map(Object::toString).toList();
    }

    private static void release(Pending pending) {
        if (!pending.ownsTicket) return;
        pending.level.getChunkSource().removeRegionTicket(TICKET, ORIGIN, DISTANCE, ORIGIN);
        pending.ownsTicket = false;
    }

    private static final class Pending {
        final ServerLevel level;
        final CommandSourceStack source;
        final IncendiumOriginPolicy.State state = new IncendiumOriginPolicy.State();
        final long started = System.nanoTime();
        boolean ownsTicket;
        boolean warned;

        Pending(ServerLevel level, CommandSourceStack source) {
            this.level = level;
            this.source = source;
        }
    }
}
