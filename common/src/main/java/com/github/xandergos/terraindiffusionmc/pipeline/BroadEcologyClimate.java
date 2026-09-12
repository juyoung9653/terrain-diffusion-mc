package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * A coarse-model-only, continuously sampled ecological climate field.  Its
 * source planes are raw unweighted coarse-model temperature, precipitation,
 * and precipitation-CV values; it deliberately does not apply local lapse
 * correction or read native terrain.
 */
public final class BroadEcologyClimate {
    static final int ECOLOGY_BLOCK_RADIUS = 1024;

    public record Values(float temperature, float precipitation, float precipitationCv, float elevation) { }

    private final int blockI0, blockJ0, blockI1, blockJ1, scale, coarseStride;
    private final int latticeI0, latticeJ0, latticeH, latticeW;
    private final float[] temperature, precipitation, precipitationCv, elevation;

    /**
     * Pure source constructor.  The supplied source must include the full
     * Gaussian and bilinear border required by the requested block region.
     */
    public BroadEcologyClimate(float[] rawElevation, float[] rawTemperature, float[] rawPrecipitation, float[] rawPrecipitationCv,
                               int rawI0, int rawJ0, int rawH, int rawW,
                               int blockI0, int blockJ0, int blockI1, int blockJ1, int scale, int coarseStride) {
        if (scale <= 0 || coarseStride <= 0 || blockI1 <= blockI0 || blockJ1 <= blockJ0)
            throw new IllegalArgumentException("invalid broad ecology bounds");
        int rawLength = rawH * rawW;
        if (rawH < 2 || rawW < 2 || rawElevation.length < rawLength || rawTemperature.length < rawLength
                || rawPrecipitation.length < rawLength || rawPrecipitationCv.length < rawLength)
            throw new IllegalArgumentException("invalid broad ecology source planes");
        this.blockI0 = blockI0; this.blockJ0 = blockJ0; this.blockI1 = blockI1; this.blockJ1 = blockJ1;
        this.scale = scale; this.coarseStride = coarseStride;
        int radius = smoothingRadius(scale, coarseStride);
        latticeI0 = floorCoarse(blockI0, scale, coarseStride);
        latticeJ0 = floorCoarse(blockJ0, scale, coarseStride);
        int latticeI1 = floorCoarse(blockI1 - 1, scale, coarseStride) + 2;
        int latticeJ1 = floorCoarse(blockJ1 - 1, scale, coarseStride) + 2;
        latticeH = latticeI1 - latticeI0; latticeW = latticeJ1 - latticeJ0;
        requireHalo(rawI0, rawJ0, rawH, rawW, latticeI0 - radius, latticeJ0 - radius,
                latticeI1 - 1 + radius, latticeJ1 - 1 + radius);
        float[] weights = gaussianWeights(radius);
        elevation = filterElevation(rawElevation, rawI0, rawJ0, rawW, latticeI0, latticeJ0, latticeH, latticeW, weights);
        temperature = filter(rawTemperature, rawI0, rawJ0, rawW, latticeI0, latticeJ0, latticeH, latticeW, weights);
        precipitation = filter(rawPrecipitation, rawI0, rawJ0, rawW, latticeI0, latticeJ0, latticeH, latticeW, weights);
        precipitationCv = filter(rawPrecipitationCv, rawI0, rawJ0, rawW, latticeI0, latticeJ0, latticeH, latticeW, weights);
    }

    public Values sample(int blockX, int blockZ) {
        if (blockZ < blockI0 || blockZ >= blockI1 || blockX < blockJ0 || blockX >= blockJ1)
            throw new IllegalArgumentException("position outside broad ecology region");
        double gy = coarseCoordinate(blockZ, scale, coarseStride) - latticeI0;
        double gx = coarseCoordinate(blockX, scale, coarseStride) - latticeJ0;
        return new Values(bilinear(temperature, gy, gx), bilinear(precipitation, gy, gx), bilinear(precipitationCv, gy, gx), bilinear(elevation, gy, gx));
    }

    static int smoothingRadius(int scale, int stride) {
        return Math.max(1, (ECOLOGY_BLOCK_RADIUS + scale * stride - 1) / (scale * stride));
    }
    static double coarseCoordinate(int block, int scale, int stride) {
        return (((double) block + .5d) / scale) / stride - .5d;
    }
    static int floorCoarse(int block, int scale, int stride) {
        return (int) Math.floor(coarseCoordinate(block, scale, stride));
    }

    private static void requireHalo(int rawI0, int rawJ0, int rawH, int rawW, int minI, int minJ, int maxI, int maxJ) {
        if (minI < rawI0 || minJ < rawJ0 || maxI >= rawI0 + rawH || maxJ >= rawJ0 + rawW)
            throw new IllegalArgumentException("broad ecology source lacks smoothing/interpolation halo");
    }
    private static float[] gaussianWeights(int radius) {
        float[] result = new float[2 * radius + 1]; float sigma = Math.max(1f, radius * .5f), total = 0;
        for (int d = -radius; d <= radius; d++) { float value = (float)Math.exp(-.5f * d * d / (sigma * sigma)); result[d + radius] = value; total += value; }
        for (int i = 0; i < result.length; i++) result[i] /= total;
        return result;
    }
    private static float[] filter(float[] raw, int rawI0, int rawJ0, int rawW,
                                  int outI0, int outJ0, int outH, int outW, float[] weights) {
        int radius = weights.length / 2; float[] horizontal = new float[(outH + 2 * radius) * outW];
        for (int r = -radius; r < outH + radius; r++) for (int c = 0; c < outW; c++) {
            float value = 0; for (int dx = -radius; dx <= radius; dx++)
                value += weights[dx + radius] * raw[(outI0 + r - rawI0) * rawW + outJ0 + c + dx - rawJ0];
            horizontal[(r + radius) * outW + c] = value;
        }
        float[] result = new float[outH * outW];
        for (int r = 0; r < outH; r++) for (int c = 0; c < outW; c++) {
            float value = 0; for (int dy = -radius; dy <= radius; dy++) value += weights[dy + radius] * horizontal[(r + dy + radius) * outW + c];
            result[r * outW + c] = value;
        }
        return result;
    }
    private static float[] filterElevation(float[] raw, int rawI0, int rawJ0, int rawW,
                                           int outI0, int outJ0, int outH, int outW, float[] weights) {
        float[] meters = raw.clone();
        for (int i = 0; i < meters.length; i++) { float value = Math.max(0f, meters[i]); meters[i] = value * value; }
        return filter(meters, rawI0, rawJ0, rawW, outI0, outJ0, outH, outW, weights);
    }
    private float bilinear(float[] values, double y, double x) {
        int y0 = (int)Math.floor(y), x0 = (int)Math.floor(x);
        if (y0 < 0 || x0 < 0 || y0 + 1 >= latticeH || x0 + 1 >= latticeW)
            throw new IllegalStateException("broad ecology interpolation halo was miscomputed");
        float fy = (float)(y - y0), fx = (float)(x - x0);
        float a = values[y0*latticeW+x0], b=values[y0*latticeW+x0+1], c=values[(y0+1)*latticeW+x0], d=values[(y0+1)*latticeW+x0+1];
        return (a+(b-a)*fx) + ((c+(d-c)*fx)-(a+(b-a)*fx))*fy;
    }
}
