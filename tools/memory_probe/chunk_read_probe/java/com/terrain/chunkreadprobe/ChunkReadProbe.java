package com.terrain.chunkreadprobe;

import net.neoforged.fml.common.Mod;
import java.nio.file.Path;

@Mod("td_chunk_read_probe")
public final class ChunkReadProbe {
    public ChunkReadProbe() {
        String output = ProbeConfiguration.outputPath();
        if (!output.isEmpty() && ProbeConfiguration.supportedC2me()) ChunkReadCounter.configure(Path.of(output));
    }
}
