import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import java.util.Arrays;
import java.util.Random;

/** Scalar reference checks for the production overlap-add used by every model stage. */
public final class FloatTensorAddTest {
    private static volatile float sink;

    public static void main(String[] args) {
        Random random = new Random(73129);
        for (int dims = 1; dims <= 5; dims++) for (int trial = 0; trial < 150; trial++) {
            int[] shape = new int[dims], sourceShape = new int[dims];
            int[][] dst = new int[dims][2], src = new int[dims][2];
            for (int d = 0; d < dims; d++) {
                int count = random.nextInt(6);
                dst[d][0] = random.nextInt(3); dst[d][1] = dst[d][0] + count;
                src[d][0] = random.nextInt(3); src[d][1] = src[d][0] + count;
                shape[d] = Math.max(1, dst[d][1] + random.nextInt(3));
                sourceShape[d] = Math.max(1, src[d][1] + random.nextInt(3));
            }
            FloatTensor actual = new FloatTensor(shape), source = new FloatTensor(sourceShape);
            fill(actual, random); fill(source, random);
            FloatTensor expected = new FloatTensor(shape, actual.data.clone());
            reference(expected, source, dst, src);
            actual.addFrom(source, dst, src);
            equal(expected, actual);
        }
        // In-place overlapping regions must keep the original sequential update order.
        for (int shift : new int[]{-3, -1, 0, 1, 3}) {
            FloatTensor actual = new FloatTensor(new int[]{3, 6, 12}); fill(actual, random);
            FloatTensor expected = new FloatTensor(actual.shape, actual.data.clone());
            int[][] dst = {{0,3},{1,5},{3 + shift,9 + shift}};
            int[][] src = {{0,3},{0,4},{3,9}};
            reference(expected, expected, dst, src); actual.addFrom(actual, dst, src);
            equal(expected, actual);
        }
        FloatTensor special = new FloatTensor(new int[]{1,1,6},
                new float[]{-0f,0f,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NaN,Float.MIN_VALUE});
        FloatTensor actual = new FloatTensor(special.shape), expected = new FloatTensor(special.shape);
        int[][] all = {{0,1},{0,1},{0,6}};
        reference(expected,special,all,all); actual.addFrom(special,all,all); equal(expected,actual);
        System.out.println("PASS FloatTensor: 750 offset/empty regions, ranks 1-5, overlapping aliases, special values");
        if (args.length > 0 && args[0].equals("--benchmark")) benchmark();
    }

    private static void fill(FloatTensor tensor, Random random) {
        for (int i=0;i<tensor.data.length;i++) tensor.data[i]=(random.nextFloat()-.5f)*1000;
    }

    private static void equal(FloatTensor expected, FloatTensor actual) {
        for (int i=0;i<expected.data.length;i++)
            if (Float.floatToIntBits(expected.data[i]) != Float.floatToIntBits(actual.data[i]))
                throw new AssertionError("Mismatch at " + i + " shape " + Arrays.toString(actual.shape));
    }

    // Independent coordinate enumeration, preserving the original row-major addition order.
    private static void reference(FloatTensor dst, FloatTensor src, int[][] dr, int[][] sr) {
        int[] index = new int[dr.length];
        for (int d=0;d<index.length;d++) if (dr[d][0]==dr[d][1]) return;
        while (true) {
            int to=0,from=0;
            for (int d=0;d<index.length;d++) {
                to=to*dst.shape[d]+dr[d][0]+index[d];
                from=from*src.shape[d]+sr[d][0]+index[d];
            }
            dst.data[to]+=src.data[from];
            int d=index.length-1;
            while (d>=0 && ++index[d]==dr[d][1]-dr[d][0]) { index[d]=0; d--; }
            if (d<0) return;
        }
    }

    private static void benchmark() {
        FloatTensor src=new FloatTensor(new int[]{6,256,256}), dst=new FloatTensor(new int[]{6,256,256});
        Arrays.fill(src.data,.125f);
        int[][] region={{0,6},{8,248},{4,252}};
        for (int i=0;i<100;i++) dst.addFrom(src,region,region);
        for (int round=0;round<5;round++) {
            long start=System.nanoTime();
            for (int i=0;i<150;i++) dst.addFrom(src,region,region);
            sink=dst.data[256*10+8];
            System.out.printf("overlap-add round=%d ms=%.3f checksum=%.3f%n",round,(System.nanoTime()-start)/1e6,sink);
        }
    }
}
