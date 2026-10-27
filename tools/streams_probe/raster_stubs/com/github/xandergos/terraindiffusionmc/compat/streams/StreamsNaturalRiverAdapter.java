package com.github.xandergos.terraindiffusionmc.compat.streams;
public final class StreamsNaturalRiverAdapter {
    private StreamsNaturalRiverAdapter() { }
    public static int version(Object config) { return ((RasterConfig) config).version(); }
    public record RasterConfig(int version, double ditchInset) { }
}
