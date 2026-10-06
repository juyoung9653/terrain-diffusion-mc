package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.compat.incendium.IncendiumOriginCompatibility;
import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Watches real first-tick function execution and validates eventual singleton helper. */
public final class IncendiumStartupAudit {
    public static String run(MinecraftServer server) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MINUTES.toNanos(8);
        long maxLatency = 0;
        int observedTicks = 0;
        boolean sawPending = false;
        String last = "";
        while (System.nanoTime() < deadline) {
            long submitted = System.nanoTime();
            String[] state = server.submit(() -> new String[] {
                    IncendiumOriginCompatibility.diagnostic(server), Integer.toString(server.getTickCount())
            }).get(8, TimeUnit.MINUTES);
            maxLatency = Math.max(maxLatency, System.nanoTime() - submitted);
            last = state[0];
            observedTicks = Integer.parseInt(state[1]);
            sawPending |= last.contains("phase=PENDING");
            if (last.contains("helpers=1, forced=true") && !last.contains("temporaryTicket=true")) {
                return new Gson().toJson(Map.of("event", "incendium_startup_audit", "status", "passed",
                        "sawPending", sawPending, "readyMs", (System.nanoTime() - started) / 1_000_000.0,
                        "maxServerTaskLatencyMs", maxLatency / 1_000_000.0, "ticks", observedTicks, "state", last));
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Incendium helper did not initialize: " + last);
    }
}
