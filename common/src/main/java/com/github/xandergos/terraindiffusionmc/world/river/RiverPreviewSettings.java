package com.github.xandergos.terraindiffusionmc.world.river;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

/**
 * Immutable, persisted bounds for the finite opt-in river preview.
 */
public record RiverPreviewSettings(
        boolean enabled,
        int version,
        long seed,
        int scale,
        int originX,
        int originZ,
        int size,
        int spacing,
        int minCatchment,
        int maxFillDepth,
        int maxCut
) {
    public static final int VERSION = 2;
    public static final int DEFAULT_ORIGIN = -2048;
    public static final int DEFAULT_SIZE = 4096;
    public static final int DEFAULT_SPACING = 32;
    public static final int DEFAULT_MIN_CATCHMENT = 65_536;
    public static final int DEFAULT_MAX_FILL_DEPTH = 1;
    public static final int DEFAULT_MAX_CUT = 6;

    public RiverPreviewSettings {
        if (version < 0 || version > VERSION) {
            throw new IllegalArgumentException("Unsupported river preview settings version: " + version);
        }
        if (enabled && version == 0) {
            throw new IllegalArgumentException("Enabled river preview settings must have an algorithm version");
        }
        if (scale < 1 || scale > WorldScaleManager.MAX_SCALE) {
            throw new IllegalArgumentException("River preview scale must be from 1 through "
                    + WorldScaleManager.MAX_SCALE + ": " + scale);
        }
        validateBounds(originX, originZ, size, spacing, minCatchment, maxFillDepth, maxCut);
    }

    public String cacheFileName() {
        return "td-river-preview-cache-v" + version + ".bin.gz";
    }

    public static RiverPreviewSettings uninitialized() {
        return new RiverPreviewSettings(false, 0, 0L, WorldScaleManager.getCurrentScale(), DEFAULT_ORIGIN, DEFAULT_ORIGIN,
                DEFAULT_SIZE, DEFAULT_SPACING, DEFAULT_MIN_CATCHMENT,
                DEFAULT_MAX_FILL_DEPTH, DEFAULT_MAX_CUT);
    }

    public static RiverPreviewSettings disabled(long seed) {
        return new RiverPreviewSettings(false, VERSION, seed, WorldScaleManager.getCurrentScale(), DEFAULT_ORIGIN, DEFAULT_ORIGIN,
                DEFAULT_SIZE, DEFAULT_SPACING, DEFAULT_MIN_CATCHMENT,
                DEFAULT_MAX_FILL_DEPTH, DEFAULT_MAX_CUT);
    }

    /**
     * Reads JVM-only preview properties. Invalid supplied values are rejected so
     * a new world cannot silently persist an unintended river layout.
     */
    public static RiverPreviewSettings fromSystemProperties(long seed) {
        if (!previewRequested()) {
            return disabled(seed);
        }

        int originX = integerProperty("td.rivers.originX", DEFAULT_ORIGIN);
        int originZ = integerProperty("td.rivers.originZ", DEFAULT_ORIGIN);
        int size = integerProperty("td.rivers.size", DEFAULT_SIZE);
        int spacing = integerProperty("td.rivers.spacing", DEFAULT_SPACING);
        int minCatchment = integerProperty("td.rivers.minCatchment", DEFAULT_MIN_CATCHMENT);
        int maxFillDepth = integerProperty("td.rivers.maxFillDepth", DEFAULT_MAX_FILL_DEPTH);
        int maxCut = integerProperty("td.rivers.maxCut", DEFAULT_MAX_CUT);
        return new RiverPreviewSettings(true, VERSION, seed, WorldScaleManager.getCurrentScale(), originX, originZ, size, spacing,
                minCatchment, maxFillDepth, maxCut);
    }

    public static boolean previewRequested() {
        String configured = System.getProperty("td.rivers.preview");
        if (configured == null || configured.equalsIgnoreCase("false")) {
            return false;
        }
        if (configured.equalsIgnoreCase("true")) {
            return true;
        }
        throw new IllegalArgumentException("td.rivers.preview must be true or false, got: " + configured);
    }

    private static int integerProperty(String key, int defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + configured, exception);
        }
    }

    private static void validateBounds(int originX, int originZ, int size, int spacing,
                                       int minCatchment, int maxFillDepth, int maxCut) {
        if (size < 256 || size > 8192 || !isPowerOfTwo(size)) {
            throw new IllegalArgumentException("td.rivers.size must be a power of two from 256 through 8192: " + size);
        }
        if (spacing < 8 || spacing > 64 || !isPowerOfTwo(spacing)) {
            throw new IllegalArgumentException("td.rivers.spacing must be a power of two from 8 through 64: " + spacing);
        }
        if (size % spacing != 0) {
            throw new IllegalArgumentException("td.rivers.size must be divisible by td.rivers.spacing");
        }
        int gridSide = size / spacing + 1;
        if (gridSide > 513) {
            throw new IllegalArgumentException("river preview grid must contain at most 513 by 513 samples");
        }
        if (originX > Integer.MAX_VALUE - size || originZ > Integer.MAX_VALUE - size) {
            throw new IllegalArgumentException("river preview bounds exceed the integer coordinate range");
        }
        if (minCatchment < 1 || minCatchment > size * size) {
            throw new IllegalArgumentException("td.rivers.minCatchment must be from 1 through size squared: " + minCatchment);
        }
        if (maxFillDepth < 0 || maxFillDepth > 1) {
            throw new IllegalArgumentException("td.rivers.maxFillDepth must be from 0 through 1: " + maxFillDepth);
        }
        if (maxCut < 0 || maxCut > 64) {
            throw new IllegalArgumentException("td.rivers.maxCut must be from 0 through 64: " + maxCut);
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
