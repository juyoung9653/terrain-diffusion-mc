package dev.streamsreflowing.worldgen;
import com.github.xandergos.terraindiffusionmc.diagnostic.RiverRasterHarness;
import net.minecraft.world.level.WorldGenLevel;
public final class StreamsWorldgen {
    public static RiverRasterHarness.Engine current;
    private StreamsWorldgen() { }
    public static RiverRasterHarness.Engine engine(WorldGenLevel level) { return current; }
}
