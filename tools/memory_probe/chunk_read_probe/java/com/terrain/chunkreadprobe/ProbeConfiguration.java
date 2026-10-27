package com.terrain.chunkreadprobe;

import net.neoforged.fml.ModList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit opt-in and exact supported-C2ME gate for this temporary diagnostic. */
public final class ProbeConfiguration {
    private static final String PROPERTY = "td.chunkReadProbe.output";
    private static final String EXPECTED_C2ME = "0.4.0-alpha.0.123+1.21.1";
    private static final Path CONTROL = Path.of("td-chunk-read-probe-output.txt");
    private ProbeConfiguration() { }
    public static String outputPath() {
        String value = System.getProperty(PROPERTY, "").strip();
        if (!value.isEmpty()) return value;
        try { if (Files.isRegularFile(CONTROL)) return Files.readString(CONTROL).strip(); }
        catch (IOException ignored) { }
        return "";
    }
    public static boolean supportedC2me() {
        try { return ModList.get().getModContainerById("c2me_rewrites_chunk_system")
                .map(container -> EXPECTED_C2ME.equals(container.getModInfo().getVersion().toString())).orElse(false); }
        catch (RuntimeException unavailable) { return false; }
    }
}
