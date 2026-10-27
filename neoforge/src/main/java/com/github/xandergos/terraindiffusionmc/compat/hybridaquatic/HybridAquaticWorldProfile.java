package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.Set;

/**
 * Immutable admission record for the native Hybrid Aquatic marine profile.
 *
 * <p>The router is changed before a {@code ServerLevel} and its saved-data
 * storage exist, so this deliberately uses a small, dependency-free world file.
 * An initialized world without this record is never enrolled retroactively.</p>
 */
public final class HybridAquaticWorldProfile {
    private static final String FILE_NAME = "terrain_diffusion_hybrid_aquatic.properties";
    private static final String VERSION = "1";
    private static final Set<String> KEYS = Set.of("profileVersion", "enabled", "seed");

    private HybridAquaticWorldProfile() { }

    /**
     * Resolves the profile exactly once. A missing profile on an existing world
     * remains absent and disables this adapter.
     */
    public static boolean resolve(Path worldRoot, boolean newWorld, long seed,
                                  boolean configuredEnabled) throws IOException {
        Path marker = worldRoot.resolve("data").resolve(FILE_NAME);
        if (Files.exists(marker)) return read(marker, seed, configuredEnabled);
        if (!newWorld) return false;

        Properties properties = new Properties();
        properties.setProperty("profileVersion", VERSION);
        properties.setProperty("enabled", Boolean.toString(configuredEnabled));
        properties.setProperty("seed", Long.toString(seed));
        writeNew(marker, properties);
        return configuredEnabled;
    }

    private static boolean read(Path marker, long expectedSeed, boolean configuredEnabled)
            throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Hybrid Aquatic world profile is malformed: " + marker, malformed);
        }
        if (!properties.stringPropertyNames().equals(KEYS)
                || !VERSION.equals(properties.getProperty("profileVersion"))) {
            throw new IOException("Hybrid Aquatic world profile has an unsupported format: " + marker);
        }
        long recordedSeed;
        try {
            recordedSeed = Long.parseLong(properties.getProperty("seed"));
        } catch (RuntimeException malformed) {
            throw new IOException("Hybrid Aquatic world profile has an invalid seed: " + marker, malformed);
        }
        if (recordedSeed != expectedSeed) {
            throw new IOException("Hybrid Aquatic world profile seed does not match this world: " + marker);
        }
        String enabled = properties.getProperty("enabled");
        if (!"true".equals(enabled) && !"false".equals(enabled)) {
            throw new IOException("Hybrid Aquatic world profile has an invalid enabled value: " + marker);
        }
        if ("true".equals(enabled) && !configuredEnabled) {
            throw new IOException("This world requires Hybrid Aquatic biomes enabled to preserve its marine profile");
        }
        return "true".equals(enabled);
    }

    private static void writeNew(Path marker, Properties properties) throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = Files.createTempFile(marker.getParent(), FILE_NAME, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "Terrain Diffusion Hybrid Aquatic marine profile");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Cannot atomically persist Hybrid Aquatic world profile", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
