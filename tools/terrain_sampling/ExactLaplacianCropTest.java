import com.github.xandergos.terraindiffusionmc.pipeline.ExactLaplacianCrop;
import com.github.xandergos.terraindiffusionmc.pipeline.LaplacianUtils;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

/** Raw-bit differential checks and optional CPU-only crop benchmark. */
public final class ExactLaplacianCropTest {
    private static final Random RANDOM = new Random(0x5eedL);
    private ExactLaplacianCropTest() { }
    public static void main(String[] args) {
        int checks = 0;
        checks += exercise(64, 72, 8, 9, 5f, false);
        checks += exercise(65, 63, 8, 8, 5f, false);
        checks += exercise(17, 21, 1, 1, 5f, false);
        checks += exercise(31, 25, 4, 3, 5f, false);
        checks += exercise(64, 64, 8, 8, 5f, true);
        System.out.println("ExactLaplacianCropTest: PASS raw-bit-crops=" + checks);
        if (args.length == 1 && args[0].equals("--benchmark")) benchmark();
    }
    private static int exercise(int height, int width, int lowHeight, int lowWidth, float sigma, boolean exceptional) {
        float[][] residual = random(height, width), low = random(lowHeight, lowWidth);
        if (exceptional) { residual[0][0] = Float.intBitsToFloat(0x7fc01234); residual[height - 1][width - 1] = Float.NEGATIVE_INFINITY; low[0][0] = Float.POSITIVE_INFINITY; }
        float[][] expected = LaplacianUtils.laplacianDecode(residual, LaplacianUtils.laplacianDenoise(residual, low, sigma));
        int checks = 0;
        int[][] crops = {{0, 0, 1, 1}, {height - 1, width - 1, 1, 1}, {0, width - 3, 3, 3}, {height / 3, width / 4, Math.max(1, height / 2), Math.max(1, width / 2)}};
        for (int[] crop : crops) {
            int r = Math.min(crop[0], height - crop[2]), c = Math.min(crop[1], width - crop[3]);
            TrackingReader residualReader = new TrackingReader(residual), lowReader = new TrackingReader(low);
            float[] actual = ExactLaplacianCrop.decode(height, width, lowHeight, lowWidth, r, c, crop[2], crop[3], sigma, residualReader, lowReader);
            require(residualReader.calls == 1 && residualReader.inBounds, "residual rectangle");
            require(lowReader.calls == 1 && lowReader.row == 0 && lowReader.column == 0 && lowReader.height == lowHeight && lowReader.width == lowWidth, "low rectangle");
            for (int rr = 0; rr < crop[2]; rr++) for (int cc = 0; cc < crop[3]; cc++) require(Float.floatToRawIntBits(actual[rr * crop[3] + cc]) == Float.floatToRawIntBits(expected[r + rr][c + cc]), "raw bits at " + r + "," + c + " + " + rr + "," + cc);
            checks += crop[2] * crop[3];
        }
        return checks;
    }
    private static void benchmark() {
        float[][] residual = random(432, 432), low = random(54, 54);
        benchmarkCrop("326x326", residual, low, 53, 53, 326, 326);
        benchmarkCrop("132x132", residual, low, 150, 150, 132, 132);
    }
    private static void benchmarkCrop(String label, float[][] residual, float[][] low, int row, int column, int height, int width) {
        Runnable reference = () -> consume(referenceCrop(residual, low, row, column, height, width));
        Runnable sparse = () -> consume(sparseCrop(residual, low, row, column, height, width, null));
        for (int i = 0; i < 5; i++) { reference.run(); sparse.run(); }
        long[] referenceTimes = new long[9], sparseTimes = new long[9];
        for (int i = 0; i < 9; i++) {
            if ((i & 1) == 0) { referenceTimes[i] = timed(reference); sparseTimes[i] = timed(sparse); }
            else { sparseTimes[i] = timed(sparse); referenceTimes[i] = timed(reference); }
        }
        TrackingReader rr = new TrackingReader(residual), lr = new TrackingReader(low);
        float[] sparseValue = sparseCrop(residual, low, row, column, height, width, new TrackingReader[]{rr, lr});
        float[] referenceValue = referenceCrop(residual, low, row, column, height, width);
        require(Arrays.equals(rawBits(referenceValue), rawBits(sparseValue)), "benchmark raw bits " + label);
        long referenceAllocated = allocatedBytes(reference), cropAllocated = allocatedBytes(sparse);
        System.out.println("benchmark " + label + ": referenceMedianNs=" + median(referenceTimes)
                + " cropMedianNs=" + median(sparseTimes) + " referenceAllocatedBytes=" + referenceAllocated
                + " cropAllocatedBytes=" + cropAllocated + " residualReadPixels=" + rr.pixels()
                + " lowReadPixels=" + lr.pixels() + " outputPixels=" + (height * width));
    }
    private static long allocatedBytes(Runnable action) {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocated) || !allocated.isThreadAllocatedMemorySupported()) return -1;
        if (!allocated.isThreadAllocatedMemoryEnabled()) allocated.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId(), before = allocated.getThreadAllocatedBytes(thread);
        action.run();
        return allocated.getThreadAllocatedBytes(thread) - before;
    }
    private static long timed(Runnable action) { long start = System.nanoTime(); action.run(); return System.nanoTime() - start; }
    private static long median(long[] values) { long[] copy = values.clone(); Arrays.sort(copy); return copy[copy.length / 2]; }
    private static float[] referenceCrop(float[][] residual, float[][] low, int row, int column, int height, int width) {
        float[][] full = LaplacianUtils.laplacianDecode(residual, LaplacianUtils.laplacianDenoise(residual, low, 5f));
        float[] result = new float[height * width]; for (int r = 0; r < height; r++) System.arraycopy(full[row + r], column, result, r * width, width); return result;
    }
    private static float[] sparseCrop(float[][] residual, float[][] low, int row, int column, int height, int width, TrackingReader[] readers) {
        TrackingReader rr = readers == null ? new TrackingReader(residual) : readers[0], lr = readers == null ? new TrackingReader(low) : readers[1];
        return ExactLaplacianCrop.decode(432, 432, 54, 54, row, column, height, width, 5f, rr, lr);
    }
    private static int[] rawBits(float[] values) { int[] bits = new int[values.length]; for (int i = 0; i < bits.length; i++) bits[i] = Float.floatToRawIntBits(values[i]); return bits; }
    private static volatile int SINK;
    private static void consume(float[] values) { SINK ^= Float.floatToRawIntBits(values[values.length / 2]); }
    private static float[][] random(int h, int w) { float[][] values = new float[h][w]; for (float[] row : values) for (int c = 0; c < w; c++) row[c] = RANDOM.nextFloat() * 8f - 4f; return values; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TrackingReader implements ExactLaplacianCrop.PlaneReader {
        final float[][] source; int calls, row, column, height, width; boolean inBounds = true;
        TrackingReader(float[][] source) { this.source = source; }
        @Override public float[][] read(int row, int column, int height, int width) {
            calls++; this.row = row; this.column = column; this.height = height; this.width = width;
            inBounds = row >= 0 && column >= 0 && height > 0 && width > 0 && row + height <= source.length && column + width <= source[0].length;
            if (!inBounds) throw new AssertionError("out-of-bounds reader request");
            float[][] result = new float[height][width]; for (int r = 0; r < height; r++) System.arraycopy(source[row + r], column, result[r], 0, width); return result;
        }
        int pixels() { return height * width; }
    }
}