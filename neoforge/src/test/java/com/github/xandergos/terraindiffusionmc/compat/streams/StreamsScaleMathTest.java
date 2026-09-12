package com.github.xandergos.terraindiffusionmc.compat.streams;

/** Standalone checks for Streams scale arithmetic, with no Minecraft runtime dependency. */
public final class StreamsScaleMathTest {
    private StreamsScaleMathTest() {
    }

    public static void main(String[] args) {
        preservesDefaultScaleInputs();
        scalesFiveConservatively();
        staysBoundedAtEverySupportedScale();
        handlesInvalidArithmeticInputs();
        System.out.println("StreamsScaleMathTest: PASS");
    }

    private static void preservesDefaultScaleInputs() {
        for (int scale : new int[]{1, 2}) {
            require(StreamsScaleMath.plateScale(1024, scale) == 1024,
                    "scale " + scale + " must preserve plate scale");
            require(StreamsScaleMath.widthScale(1.0D, scale) == 1.0D,
                    "scale " + scale + " must preserve width scale");
            require(StreamsScaleMath.maxWidth(16.0D, scale) == 16.0D,
                    "scale " + scale + " must preserve maximum width");
        }
    }

    private static void scalesFiveConservatively() {
        require(StreamsScaleMath.plateScale(1024, 5) == 2560,
                "scale five must extend a 1024-block plate to 2560 blocks");
        require(StreamsScaleMath.widthScale(1.0D, 5) == 1.3D,
                "scale five must apply the modest width multiplier");
        require(StreamsScaleMath.maxWidth(16.0D, 5) == 32.0D,
                "scale five must cap the native half-width at 32");
        require(StreamsScaleMath.plateScale(4096, 5) == 8192,
                "scale five must cap direct plate extent at 8192 blocks");
    }

    private static void staysBoundedAtEverySupportedScale() {
        for (int scale = 1; scale <= 6; scale++) {
            int plate = StreamsScaleMath.plateScale(1024, scale);
            require(plate >= 1 && plate <= StreamsScaleMath.MAX_NATIVE_PLATE_BLOCKS,
                    "direct plate extent must remain within native bounds at scale " + scale);
            require(StreamsScaleMath.maxWidth(16.0D, scale) <= StreamsScaleMath.MAX_NATIVE_HALF_WIDTH,
                    "maximum width must remain within native bounds at scale " + scale);
        }
    }

    private static void handlesInvalidArithmeticInputs() {
        require(StreamsScaleMath.plateScale(0, 5) == 3,
                "nonpositive native plate scale must fall back to one before scaling");
        require(StreamsScaleMath.plateScale(0, 2) == 0,
                "unadapted default scales must preserve native input exactly");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
