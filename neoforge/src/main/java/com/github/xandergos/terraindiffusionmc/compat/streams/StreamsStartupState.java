package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Immutable world-local sampler choice. Unmarked existing worlds retain native calibration. */
public final class StreamsStartupState {
    private static final String MARKER = "terrain_diffusion_streams_exact_v1.txt";

    private StreamsStartupState() { }

    public static boolean hasMarker(Path world) { return Files.exists(world.resolve("data").resolve(MARKER)); }

    public static boolean resolve(Path world, boolean eligibleNewWorld, long seed, int scale)
            throws IOException {
        Path marker = world.resolve("data").resolve(MARKER);
        String expected = "TD_STREAMS_EXACT_V1\n" + seed + "\n" + scale + "\n";
        if (Files.exists(marker)) {
            if (!Files.readString(marker).equals(expected)) {
                throw new IOException("Invalid TD Streams sampler marker or world seed/scale mismatch");
            }
            return true;
        }
        // The initialized-world flag is primary. Native state also protects unusual
        // imported worlds with reset level metadata. Never migrate their sampler.
        if (!eligibleNewWorld || Files.exists(world.resolve("streamsreflowing"))) return false;
        Files.createDirectories(marker.getParent());
        Path temporary = Files.createTempFile(marker.getParent(), MARKER, ".tmp");
        try {
            Files.writeString(temporary, expected);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker);
            }
            if (!Files.readString(marker).equals(expected)) throw new IOException("Sampler marker readback failed");
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
