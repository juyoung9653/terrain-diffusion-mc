import com.github.xandergos.terraindiffusionmc.compat.streams.StreamsNaturalConfig;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverProfile;

/** Exercises the production reflective adapter without loading Minecraft or a world. */
public final class StreamsNaturalConfigTest {
    public record Hydraulics(double widthSlope, double widthClimate, double widthVariation,
                             double depthRatio, double bendAsymmetry, double channelBars, double deltaMouths) { }
    public record Config(double minRiverAccumulation, double growDensity, int minSeaChainNodes,
                         int gridSpacing, int minLengthScope, boolean confineToBanks,
                         double meanderStrength, double streamDepth, double bankNoise,
                         Hydraulics hydraulics, int plateScale, double widthScale) { }

    public static void main(String[] args) {
        for (int scale = 1; scale <= 6; scale++) for (double accumulation : new double[]{4, 24, 128}) {
            Config original = new Config(accumulation, .5, 4, 16, 0, false, 1, .5, .5,
                    new Hydraulics(1, 1, 1, 1, 1, 1, 1), 1024, .8);
            for (int version = 1; version <= NaturalRiverProfile.VERSION; version++)
                require(StreamsNaturalConfig.apply(original, scale, version) != null, "supported profile " + version);
            Config v8 = (Config) StreamsNaturalConfig.apply(original, scale, 8);
            Config v9 = (Config) StreamsNaturalConfig.apply(original, scale, 9);
            Config v10 = (Config) StreamsNaturalConfig.apply(original, scale, 10);
            Config v11 = (Config) StreamsNaturalConfig.apply(original, scale, 11);
            require(v9.equals(v8), "V9 bank/slab fix must preserve V8 routing at scale " + scale);
            require(v10.equals(v9), "V10 biome bank fix must preserve V9 routing at scale " + scale);
            require(v11.equals(v10), "V11 planform pass must preserve V10 native configuration at scale " + scale);
            require(v9.minRiverAccumulation == Math.max(24, accumulation), "source threshold");
            require(original.minRiverAccumulation == accumulation && original.plateScale == 1024,
                    "native input must remain immutable");
            for (int invalid : new int[]{0, NaturalRiverProfile.VERSION + 1}) {
                try {
                    StreamsNaturalConfig.apply(original, scale, invalid);
                    throw new AssertionError("Unsupported profile accepted: " + invalid);
                } catch (IllegalArgumentException expected) { }
            }
        }
        System.out.println("StreamsNaturalConfigTest: passed profiles1-" + NaturalRiverProfile.VERSION
                + ", scales1-6, V8/V9/V10 routing equality, immutable input and unsupported versions");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
