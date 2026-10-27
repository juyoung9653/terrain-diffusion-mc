package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds Voxy's cold section caches without changing section data or its storage format.
 * Defaults deliberately preserve Voxy's audited capacities: an LRU reduction needs a
 * long-travel benchmark because a cache miss reloads the section from storage. Properties are
 * separate from Voxy's own config because these fields are not exposed by the inspected
 * Voxy 0.2.12-alpha and 1.1.0 builds.
 * A memory-first profile may use {@code voxy.secondary_cache_sections=1536} and
 * {@code voxy.array_reuse_sections=128}, trading revisits and array reuse for lower retention.
 */
public final class VoxyMemoryBudget {
    public static final String FILE_NAME = "terrain-diffusion-mc-memory.properties";
    public static final String SECONDARY_CACHE_PROPERTY = "voxy.secondary_cache_sections";
    public static final String ARRAY_REUSE_PROPERTY = "voxy.array_reuse_sections";

    private static final Logger LOG = LoggerFactory.getLogger(VoxyMemoryBudget.class);
    private static final int DEFAULT_SECONDARY_CACHE = 2048;
    private static final int DEFAULT_ARRAY_REUSE = 400;
    private static final int MIN_SECONDARY_CACHE = 256;
    private static final int MAX_SECONDARY_CACHE = 2048;
    private static final int MAX_ARRAY_REUSE = 400;
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private static volatile Budget cached;

    private VoxyMemoryBudget() { }

    public static int secondaryCacheSections(int upstreamDefault) {
        return Math.min(upstreamDefault, budget().secondaryCacheSections());
    }

    public static int arrayReuseSections(int upstreamDefault) {
        return Math.min(upstreamDefault, budget().arrayReuseSections());
    }

    /**
     * Avoids transforming Voxy at all for the safe default. This runs during mixin selection,
     * so an override takes effect only after a game restart.
     */
    public static boolean hasConfiguredReduction() {
        return isReduction(budget());
    }

    static boolean isReduction(Budget result) {
        return result.secondaryCacheSections() < DEFAULT_SECONDARY_CACHE
                || result.arrayReuseSections() < DEFAULT_ARRAY_REUSE;
    }

    static Budget parse(Properties properties) {
        int secondary = bounded(properties.getProperty(SECONDARY_CACHE_PROPERTY),
                DEFAULT_SECONDARY_CACHE, MIN_SECONDARY_CACHE, MAX_SECONDARY_CACHE);
        int reuse = bounded(properties.getProperty(ARRAY_REUSE_PROPERTY),
                DEFAULT_ARRAY_REUSE, 0, MAX_ARRAY_REUSE);
        return new Budget(secondary, reuse);
    }

    private static Budget budget() {
        Budget result = cached;
        if (result != null) return result;
        synchronized (VoxyMemoryBudget.class) {
            result = cached;
            if (result == null) {
                Properties properties = new Properties();
                Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
                if (Files.isRegularFile(file)) {
                    try (Reader reader = Files.newBufferedReader(file)) {
                        properties.load(reader);
                    } catch (IOException | IllegalArgumentException exception) {
                        LOG.warn("Could not read Voxy memory budget {}. Using defaults.", file, exception);
                    }
                }
                result = parse(properties);
                cached = result;
                if (LOGGED.compareAndSet(false, true)) {
                    LOG.info("Voxy memory budgets: secondary cache {}, array reuse {} sections (override in {}).",
                            result.secondaryCacheSections(), result.arrayReuseSections(), file);
                }
            }
            return result;
        }
    }

    private static int bounded(String raw, int fallback, int minimum, int maximum) {
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record Budget(int secondaryCacheSections, int arrayReuseSections) { }
}
