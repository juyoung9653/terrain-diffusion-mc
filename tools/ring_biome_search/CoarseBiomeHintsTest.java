import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.CoarseBiomeHints;

/** Framework-free behavioral tests for coarse biome ranking hints. */
public final class CoarseBiomeHintsTest {
    private CoarseBiomeHintsTest() { }

    public static void main(String[] args) {
        testWeightScalingDoesNotChangeHints();
        testNegativeCoordinatesAndAxisMappingAtEveryScale();
        System.out.println("CoarseBiomeHintsTest: PASS");
    }

    private static void testWeightScalingDoesNotChangeHints() {
        CoarseBiomeHints original = hints(2, 1f);
        CoarseBiomeHints scaled = hints(2, 7f);
        for (int blockZ : new int[] {-6, -5, -4, -3}) {
            for (int blockX : new int[] {-4, -3, -2, -1}) {
                check(original.vanillaAt(blockX, blockZ) == scaled.vanillaAt(blockX, blockZ),
                        "scaling all seven channels changed vanilla hint");
                check(original.terralithAt(blockX, blockZ) == scaled.terralithAt(blockX, blockZ),
                        "scaling all seven channels changed Terralith hint");
            }
        }
    }

    private static void testNegativeCoordinatesAndAxisMappingAtEveryScale() {
        for (int scale : new int[] {1, 2, 6}) {
            CoarseBiomeHints hints = hints(scale, 1f);
            // Only local (x=0, z=1) is ocean, so transposing x and z is observable.
            assertLand(hints, -2 * scale, -3 * scale, scale);
            assertLand(hints, -scale, -3 * scale, scale);
            assertOcean(hints, -2 * scale, -2 * scale, scale);
            assertLand(hints, -scale, -2 * scale, scale);
        }
    }

    private static void assertOcean(CoarseBiomeHints hints, int minBlockX, int minBlockZ, int scale) {
        for (int z : new int[] {minBlockZ, minBlockZ + scale - 1}) {
            for (int x : new int[] {minBlockX, minBlockX + scale - 1}) {
                check(hints.vanillaAt(x, z) == 46,
                        "cold ocean cell did not map to expected negative-coordinate block");
            }
        }
    }

    private static void assertLand(CoarseBiomeHints hints, int minBlockX, int minBlockZ, int scale) {
        for (int z : new int[] {minBlockZ, minBlockZ + scale - 1}) {
            for (int x : new int[] {minBlockX, minBlockX + scale - 1}) {
                short biome = hints.vanillaAt(x, z);
                check(biome != 41 && biome != 44 && biome != 46 && biome != 48,
                        "warm land cell mapped as an ocean at negative-coordinate block");
            }
        }
    }

    private static CoarseBiomeHints hints(int blocksPerCell, float multiplier) {
        int width = 2, height = 2, paddedWidth = width + 2, paddedHeight = height + 2;
        int plane = paddedWidth * paddedHeight;
        FloatTensor tensor = new FloatTensor(new int[] {7, paddedHeight, paddedWidth});
        for (int index = 0; index < plane; index++) {
            tensor.data[0 * plane + index] = 10f * multiplier;
            tensor.data[1 * plane + index] = 3f * multiplier;
            tensor.data[2 * plane + index] = 30f * multiplier;
            tensor.data[3 * plane + index] = 0f;
            tensor.data[4 * plane + index] = 100f * multiplier;
            tensor.data[5 * plane + index] = 0f;
            tensor.data[6 * plane + index] = multiplier;
        }
        // Interior cells: hot land, hot land / cold ocean, hot land.
        setCell(tensor, plane, paddedWidth, 1, 2, -1f, 0f, multiplier);
        return new CoarseBiomeHints(tensor, -2, -3, width, height, blocksPerCell, blocksPerCell);
    }

    private static void setCell(FloatTensor tensor, int plane, int paddedWidth, int x, int z,
                                float elevation, float temperature, float multiplier) {
        int index = z * paddedWidth + x;
        tensor.data[index] = elevation * multiplier;
        tensor.data[2 * plane + index] = temperature * multiplier;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
