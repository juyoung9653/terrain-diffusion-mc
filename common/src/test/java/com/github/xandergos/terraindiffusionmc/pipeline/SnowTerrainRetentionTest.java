package com.github.xandergos.terraindiffusionmc.pipeline;

/** Physical-unit, orientation, halo and cached-crop regressions without a Minecraft runtime. */
public final class SnowTerrainRetentionTest {
    public static void main(String[] args) {
        float flat = at(0, 0, 0, 2);
        float north = at(0, .7f, 0, 2), south = at(0, -.7f, 0, 2);
        float east = at(.7f, 0, 0, 2), west = at(-.7f, 0, 0, 2);
        require(flat == 0, "flat has no invented terrain retention");
        require(north > flat && south < flat, "+Z rising terrain must retain more snow on its north-facing slope");
        close(east, west, "east/west rotation must not invent a north-facing bias");
        require(at(2, 0, 0, 2) < east, "steep terrain sheds more snow");
        require(at(0, 0, .01f, 2) > flat, "bowl retains more snow");
        require(at(0, 0, -.01f, 2) < flat, "ridge retains less snow");
        for (float spacing : new float[]{1, 4, 30, 120}) {
            close(at(.4f, .7f, 0, spacing), at(.4f, .7f, 0, 2), "slope/aspect must use metre spacing");
            close(at(0, 0, .002f, spacing), at(0, 0, .002f, 2), "curvature must use squared metre spacing");
        }
        require(Math.abs(at(0, 0, 1000, 1)) <= .12f, "extreme bowl must remain bounded");
        require(Math.abs(at(0, 0, -1000, 1)) <= .12f, "extreme ridge must remain bounded");
        float shaded = EcologyClassifier.naturalSnowCoverage(-1.5f, north);
        float sunny = EcologyClassifier.naturalSnowCoverage(-1.5f, south);
        require(shaded > sunny, "aspect should affect an existing cold transition");
        require(EcologyClassifier.naturalSnowCoverage(4, north) == 0
                && EcologyClassifier.naturalSnowCoverage(-6, south) == 1, "terrain cannot invent warm snow or erase cold cores");
        checkHaloAndCrop();
        expectInvalid(new float[9], 1, 1, 0);
        expectInvalid(new float[8], 1, 1, 1);
        expectInvalid(new float[9], 1, 1, Float.NaN);
        float[] missing = new float[9]; missing[4] = Float.NaN;
        require(EcologyClassifier.snowRetention(missing, 1, 1, 1)[0] == 0, "invalid model value must use neutral retention");
        System.out.println("SnowTerrainRetentionTest: passed slopes/aspects/bowls/ridges, physical scales, halo/crop, invalid inputs");
    }

    private static void checkHaloAndCrop() {
        int width = 35, height = 27, x0 = -19, z0 = -17;
        float[] retention = EcologyClassifier.snowRetention(halo(x0, z0, width, height), height, width, 3);
        EcologyData full = new EcologyData(width, height, new short[width * height], new short[width * height],
                new float[width * height], new float[width * height], new float[width * height],
                new float[width * height], retention);
        int[] originsX = {0, 11, 19}, originsZ = {0, 9, 15};
        for (int startX : originsX) for (int startZ : originsZ) {
            int w = Math.min(16, width - startX), h = Math.min(12, height - startZ);
            float[] independent = EcologyClassifier.snowRetention(halo(x0 + startX, z0 + startZ, w, h), h, w, 3);
            EcologyData crop = full.crop(startX, startZ, w, h);
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
                float cached = full.snowRetention(startX + x, startZ + z);
                require(Float.floatToIntBits(cached) == Float.floatToIntBits(crop.snowRetention(x, z)), "crop changed retention");
                require(Float.floatToIntBits(cached) == Float.floatToIntBits(independent[z * w + x]), "independent halo caused seam");
                int px = x0 + startX + x, pz = z0 + startZ + z;
                require(EcologyClassifier.naturalSnowCover(px, pz, 1450, -1, cached)
                        == EcologyClassifier.naturalSnowCover(px, pz, 1450, -1, independent[z * w + x]),
                        "cropping changed final snow eligibility at negative/chunk coordinates");
                require(EcologyClassifier.naturalIceCover(px, pz, 1450, -1, cached)
                        == EcologyClassifier.naturalIceCover(px, pz, 1450, -1, independent[z * w + x]),
                        "cropping changed final ice eligibility at negative/chunk coordinates");
            }
        }
    }

    private static float[] halo(int x0, int z0, int w, int h) {
        float[] values = new float[(w + 2) * (h + 2)];
        for (int z = -1; z <= h; z++) for (int x = -1; x <= w; x++) {
            double px = x0 + x, pz = z0 + z;
            values[(z + 1) * (w + 2) + x + 1] = (float) (1450 + .5 * px + .25 * pz
                    + 3 * Math.sin(px / 9) + 2 * Math.cos(pz / 7));
        }
        return values;
    }

    private static float at(float dx, float dz, float curvature, float spacing) {
        float[] values = new float[9];
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            float px = x * spacing, pz = z * spacing;
            values[(z + 1) * 3 + x + 1] = dx * px + dz * pz + curvature * (px * px + pz * pz);
        }
        return EcologyClassifier.snowRetention(values, 1, 1, spacing)[0];
    }

    private static void expectInvalid(float[] field, int h, int w, float spacing) {
        try { EcologyClassifier.snowRetention(field, h, w, spacing); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid halo/spacing accepted");
    }
    private static void close(float a, float b, String message) { require(Math.abs(a - b) < .00001f, message + ": " + a + " vs " + b); }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
