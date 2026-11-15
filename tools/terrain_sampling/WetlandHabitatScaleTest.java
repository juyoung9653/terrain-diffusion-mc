package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Pure regression checks for the landscape-scale inland-wetland field.  The
 * fixture deliberately combines a broad concavity with four-block relief: the
 * latter must not punch dry holes through the former.
 */
public final class WetlandHabitatScaleTest {
    private static final int PAD = 192;

    private WetlandHabitatScaleTest() { }

    public static void main(String[] args) {
        planarFilteringPreservesGradient();
        landscapeFormsHaveExpectedPotential();
        overlapIsBitExactAtNativeAndUpscaledResolution();
        fourBlockRippleKeepsWetlandTransitionConnected();
        System.out.println("WetlandHabitatScaleTest: PASS cases=4 scales=1,4");
    }

    private static void planarFilteringPreservesGradient() {
        int h = 97, w = 101;
        float[] plane = new float[h * w];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++)
            plane[r * w + c] = 83.5f + .125f * r - .0625f * c;
        float[] filtered = MacroBiomeContext.habitatHeights(plane, h, w);
        for (int r = 16; r < h - 16; r++) for (int c = 16; c < w - 16; c++)
            near(filtered[r * w + c], plane[r * w + c], 0.0002f, "planar habitat " + r + "," + c);
    }

    private static void landscapeFormsHaveExpectedPotential() {
        BiomeClassifier.TerralithContext planar = build(1, (x, z) -> 100f + .04f * x);
        BiomeClassifier.TerralithContext convex = build(1, (x, z) -> 100f - .012f * (x * x + z * z));
        BiomeClassifier.TerralithContext concave = build(1, (x, z) -> 100f + .012f * (x * x + z * z));
        int center = 96 * 193 + 96;
        check(planar.shore().valleyPotential(center) < .001f, "planar hill must stay dry");
        check(convex.shore().valleyPotential(center) < .001f, "convex hill must stay dry");
        check(concave.shore().valleyPotential(center) > .95f, "concave valley must retain inland potential");
    }

    private static void overlapIsBitExactAtNativeAndUpscaledResolution() {
        for (int scale : new int[] {1, 4}) {
            Field field = field(scale, (x, z) -> 120f + .004f * x * x + .002f * z * z
                    + (float) Math.sin(x * .31f) * 1.75f + (float) Math.cos(z * .27f) * 1.25f);
            BiomeClassifier.TerralithContext whole = context(field, -73, -61, 171, 159);
            BiomeClassifier.TerralithContext overlap = context(field, -31, -19, 83, 77);
            for (int r = 0; r < 83; r++) for (int c = 0; c < 77; c++) {
                int a = (r + 42) * 159 + c + 42, b = r * 77 + c;
                bits(whole.shore().valleyPotential(a), overlap.shore().valleyPotential(b),
                        "overlap valley scale=" + scale + " at " + r + "," + c);
                bits(whole.elevation()[a], overlap.elevation()[b],
                        "overlap elevation scale=" + scale + " at " + r + "," + c);
                bits(whole.slope()[a], overlap.slope()[b],
                        "overlap slope scale=" + scale + " at " + r + "," + c);
            }
        }
    }

    private static void fourBlockRippleKeepsWetlandTransitionConnected() {
        Field field = field(1, (x, z) -> 96f + .010f * x * x
                + (((((int) Math.floor(z)) & 7) < 4) ? 2f : -2f));
        BiomeClassifier.TerralithContext context = context(field, -32, -96, 65, 193);
        int wetColumns = 0;
        for (int c = 0; c < 193; c++) {
            int index = 32 * 193 + c;
            EcologyClassifier.Selection selection = EcologyClassifier.select(0, c - 96,
                    context.elevation()[index], context.slope()[index],
                    context.temperature()[index], context.precipitation()[index], context.elevation()[index],
                    Float.POSITIVE_INFINITY, 0, 0, context.shore().valleyPotential(index));
            check(selection.sample().wetness() > .50f,
                    "four-block ripple fragmented broad wetland at z=" + (c - 96)
                            + " wet=" + selection.sample().wetness() + " valley="
                            + context.shore().valleyPotential(index) + " slope=" + context.slope()[index]);
            check(selection.sample().biomeId() >= 20 && selection.sample().biomeId() <= 23,
                    "wetland transition became dry at z=" + (c - 96));
            wetColumns++;
        }
        check(wetColumns == 193, "all valley columns must remain habitat candidates");
    }

    private static BiomeClassifier.TerralithContext build(int scale, Height height) {
        return context(field(scale, height), -96, -96, 193, 193);
    }

    private static BiomeClassifier.TerralithContext context(Field field, int i0, int j0, int height, int width) {
        return MacroBiomeContext.build(field.elevation, field.climate, field.i0, field.j0, field.height, field.width,
                field.scale, 1f, i0, j0, height, width);
    }

    private static Field field(int scale, Height surface) {
        int i0 = -512 / scale, j0 = -512 / scale, height = 1024 / scale, width = 1024 / scale;
        float[] elevation = new float[height * width];
        float[] climate = new float[4 * height * width];
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            float x = (r + i0 + .5f) * scale - .5f, z = (c + j0 + .5f) * scale - .5f;
            int index = r * width + c;
            elevation[index] = surface.at(x, z);
            climate[index] = 15f;
            climate[height * width + index] = 4f;
            climate[2 * height * width + index] = 1100f;
            climate[3 * height * width + index] = .2f;
        }
        return new Field(i0, j0, height, width, scale, elevation, climate);
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void bits(float actual, float expected, String message) {
        check(Float.floatToRawIntBits(actual) == Float.floatToRawIntBits(expected),
                message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private interface Height { float at(float x, float z); }
    private record Field(int i0, int j0, int height, int width, int scale, float[] elevation, float[] climate) { }
}
