package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import java.nio.file.Path;

/** One process per case keeps TerrainDiffusionConfig static initialization isolated. */
public final class ConfigTileSizeTest {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("config dir and expected tile size required");
        PlatformPaths.configure(Path.of(args[0]), Path.of(args[0]));
        int expected = Integer.parseInt(args[1]);
        for (int call = 0; call < 10_000; call++) {
            if (TerrainDiffusionConfig.tileSize() != expected)
                throw new AssertionError("tile size call " + call + " expected=" + expected
                        + " actual=" + TerrainDiffusionConfig.tileSize());
        }
        System.out.println("ConfigTileSizeTest: PASS tile_size=" + expected);
    }
}
