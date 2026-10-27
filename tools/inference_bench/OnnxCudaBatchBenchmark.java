import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CUDA-only batch feasibility benchmark for the pinned Terrain Diffusion models.
 *
 * <p>It intentionally creates Java tensors and copies the output for every run,
 * matching {@code OnnxModel.runWithSession}; it is not a device-only kernel test.
 * Input is deterministic synthetic data.  Timings are medians of complete model
 * calls after warmup, and parity compares each lane of a batch against that same
 * lane submitted as N=1 in the same CUDA session.
 */
public final class OnnxCudaBatchBenchmark {
    private static final int[] BATCHES = {1, 2, 4, 8};

    private record ModelSpec(String name, String file, int channels, int outputChannels, int side,
                             int conditionCount, int conditionWidth) {}
    private record Result(float[] values, long nanos) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <model-dir> <warmup-runs> <measured-runs> "
                    + "[--models=coarse,decoder,base] [--algorithm=HEURISTIC|EXHAUSTIVE] [--decoder-lane-parity]");
        }
        Path modelDir = Path.of(args[0]);
        int warmup = Integer.parseInt(args[1]);
        int measured = Integer.parseInt(args[2]);
        if (warmup < 0 || measured < 1) throw new IllegalArgumentException("invalid iteration counts");
        String modelNames = "coarse,decoder";
        String algorithm = "HEURISTIC";
        boolean decoderLaneParity = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--models=")) modelNames = args[i].substring("--models=".length());
            else if (args[i].startsWith("--algorithm=")) algorithm = args[i].substring("--algorithm=".length());
            else if ("--decoder-lane-parity".equals(args[i])) decoderLaneParity = true;
            else throw new IllegalArgumentException("unknown argument: " + args[i]);
        }
        if (!algorithm.equals("HEURISTIC") && !algorithm.equals("EXHAUSTIVE")) {
            throw new IllegalArgumentException("--algorithm must be HEURISTIC or EXHAUSTIVE");
        }

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        // Java ORT 1.20 names this provider "CUDA" in getAvailableProviders().
        if (!environment.getAvailableProviders().toString().contains("CUDA")) {
            throw new IllegalStateException("CUDA provider unavailable: " + environment.getAvailableProviders());
        }
        System.out.println("META\tort=" + environment.getVersion() + "\tproviders=" + environment.getAvailableProviders());
        System.out.println("META\twarmup=" + warmup + "\tmeasured=" + measured
                + "\tmodels=" + modelNames + "\talgorithm=" + algorithm);
        if (decoderLaneParity) {
            decoderLaneParity(environment, modelDir, warmup, algorithm);
            return;
        }
        System.out.println("model\tbatch\tmedian_batch_ms\tmedian_per_sample_ms\tbit_mismatches\tvalues\tmax_abs_diff");

        for (String modelName : modelNames.split(",")) {
            ModelSpec spec = modelSpec(modelName);
            try (OrtSession.SessionOptions options = cudaOptions(algorithm);
                 OrtSession session = environment.createSession(modelDir.resolve(spec.file()).toString(), options)) {
                // Receipt records the binary's real metadata, including base output shape.
                System.out.println("MODEL_META\t" + spec.name() + "\texpectedSide=" + spec.side()
                        + "\tinputs=" + session.getInputInfo()
                        + "\toutputs=" + session.getOutputInfo());
                for (int batch : BATCHES) {
                    float[] x = values(batch * spec.channels() * spec.side() * spec.side(),
                            0x1234ABCDL + batch * 17L + spec.channels());
                    float[] labels = values(batch, 0x774411L + batch);
                    float[][] conditions = spec.conditionCount() == 0 ? null : new float[spec.conditionCount()][];
                    if (conditions != null) {
                        for (int c = 0; c < conditions.length; c++) {
                            int width = spec.conditionWidth() == 0 ? 1 : spec.conditionWidth();
                            conditions[c] = values(batch * width, 0xBEEF00L + c * 97L + batch);
                        }
                    }
                    for (int i = 0; i < warmup; i++) run(environment, session, spec, batch, x, labels, conditions);
                    long[] samples = new long[measured];
                    Result batched = null;
                    for (int i = 0; i < measured; i++) {
                        Result result = run(environment, session, spec, batch, x, labels, conditions);
                        samples[i] = result.nanos();
                        if (i == measured - 1) batched = result;
                    }
                    Parity parity = parity(environment, session, spec, batch, x, labels, conditions, batched.values());
                    Arrays.sort(samples);
                    double medianMs = samples[samples.length / 2] / 1_000_000.0;
                    System.out.printf("%s\t%d\t%.6f\t%.6f\t%d\t%d\t%.9g%n",
                            spec.name(), batch, medianMs, medianMs / batch, parity.bitMismatches,
                            parity.values, parity.maxAbsDiff);
                }
            }
        }
    }

    private static ModelSpec modelSpec(String name) {
        return switch (name) {
            case "coarse" -> new ModelSpec("coarse", "coarse_model.onnx", 11, 6, 64, 5, 0);
            case "base" -> new ModelSpec("base", "base_model.onnx", 5, 5, 64, 1, 58);
            case "decoder" -> new ModelSpec("decoder", "decoder_model.onnx", 5, 1, 256, 0, 0);
            default -> throw new IllegalArgumentException("unknown model in --models: " + name);
        };
    }

    private static OrtSession.SessionOptions cudaOptions(String algorithm) throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        try (OrtCUDAProviderOptions cuda = new OrtCUDAProviderOptions(0)) {
            cuda.add("arena_extend_strategy", "kSameAsRequested");
            cuda.add("cudnn_conv_algo_search", algorithm);
            cuda.add("do_copy_in_default_stream", "1");
            options.addCUDA(cuda);
        }
        return options;
    }

    /**
     * Establishes whether a fixed N=8 decoder call makes a target lane's result
     * independent of its position and of unrelated lanes. N=2/N=4 are also
     * compared with N=8 lane zero, which is the decision point for padding
     * partial requests to a fixed N=8 shape.
     */
    private static void decoderLaneParity(OrtEnvironment environment, Path modelDir, int warmup, String algorithm) throws OrtException {
        ModelSpec decoder = modelSpec("decoder");
        float[] targetX = values(decoder.channels() * decoder.side() * decoder.side(), 0xD3C0DE55L);
        float targetLabel = values(1, 0xD3C0DE56L)[0];
        try (OrtSession.SessionOptions options = cudaOptions(algorithm);
             OrtSession session = environment.createSession(modelDir.resolve(decoder.file()).toString(), options)) {
            BatchInput referenceInput = decoderBatchWithTarget(decoder, 8, 0, targetX, targetLabel);
            for (int i = 0; i < warmup; i++) run(environment, session, decoder, 8,
                    referenceInput.x(), referenceInput.labels(), null);
            float[] reference = lane(run(environment, session, decoder, 8,
                    referenceInput.x(), referenceInput.labels(), null).values(), 0, 1, decoder.side());
            System.out.println("decoder_lane_parity\tcase\tbatch\ttarget_lane\tbit_mismatches\tvalues\tmax_abs_diff");
            for (int batch : new int[] {2, 4, 8}) {
                BatchInput input = decoderBatchWithTarget(decoder, batch, 0, targetX, targetLabel);
                reportParity("shape", batch, 0, reference,
                        lane(run(environment, session, decoder, batch, input.x(), input.labels(), null).values(), 0, 1,
                                decoder.side()));
            }
            for (int targetLane = 0; targetLane < 8; targetLane++) {
                BatchInput input = decoderBatchWithTarget(decoder, 8, targetLane, targetX, targetLabel);
                reportParity("lane", 8, targetLane, reference,
                        lane(run(environment, session, decoder, 8, input.x(), input.labels(), null).values(), targetLane, 1,
                                decoder.side()));
            }
        }
    }

    private static BatchInput decoderBatchWithTarget(ModelSpec decoder, int batch, int targetLane,
                                                     float[] targetX, float targetLabel) {
        int stride = decoder.channels() * decoder.side() * decoder.side();
        float[] x = new float[batch * stride];
        float[] labels = new float[batch];
        for (int lane = 0; lane < batch; lane++) {
            float[] unrelated = values(stride, 0x51A7E00L + 131L * batch + lane);
            System.arraycopy(unrelated, 0, x, lane * stride, stride);
            labels[lane] = values(1, 0x5EED00L + 131L * batch + lane)[0];
        }
        System.arraycopy(targetX, 0, x, targetLane * stride, stride);
        labels[targetLane] = targetLabel;
        return new BatchInput(x, labels);
    }

    private static float[] lane(float[] all, int lane, int outputChannels, int side) {
        int stride = outputChannels * side * side;
        return Arrays.copyOfRange(all, lane * stride, (lane + 1) * stride);
    }

    private static void reportParity(String kind, int batch, int targetLane, float[] reference, float[] actual) {
        Parity parity = compare(reference, actual);
        System.out.printf("decoder_lane_parity\t%s\t%d\t%d\t%d\t%d\t%.9g%n", kind, batch, targetLane,
                parity.bitMismatches(), parity.values(), parity.maxAbsDiff());
    }

    private static Result run(OrtEnvironment environment, OrtSession session, ModelSpec spec, int batch,
                              float[] x, float[] labels, float[][] conditions) throws OrtException {
        long started = System.nanoTime();
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try {
            inputs.put("x", OnnxTensor.createTensor(environment, FloatBuffer.wrap(x),
                    new long[] {batch, spec.channels(), spec.side(), spec.side()}));
            inputs.put("noise_labels", OnnxTensor.createTensor(environment, FloatBuffer.wrap(labels), new long[] {batch}));
            if (conditions != null) {
                for (int i = 0; i < conditions.length; i++) {
                    long[] shape = spec.conditionWidth() == 0 ? new long[] {batch}
                            : new long[] {batch, spec.conditionWidth()};
                    inputs.put("cond_" + i, OnnxTensor.createTensor(environment, FloatBuffer.wrap(conditions[i]), shape));
                }
            }
            try (OrtSession.Result result = session.run(inputs)) {
                FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
                float[] output = new float[buffer.remaining()];
                buffer.get(output);
                return new Result(output, System.nanoTime() - started);
            }
        } finally {
            for (OnnxTensor input : inputs.values()) input.close();
        }
    }

    private static Parity parity(OrtEnvironment environment, OrtSession session, ModelSpec spec, int batch,
                                 float[] x, float[] labels, float[][] conditions, float[] batched) throws OrtException {
        int inputStride = spec.channels() * spec.side() * spec.side();
        int outputStride = spec.outputChannels() * spec.side() * spec.side();
        long bits = 0;
        double maxAbs = 0;
        for (int lane = 0; lane < batch; lane++) {
            float[] oneX = Arrays.copyOfRange(x, lane * inputStride, (lane + 1) * inputStride);
            float[] oneLabels = {labels[lane]};
            float[][] oneConditions = null;
            if (conditions != null) {
                oneConditions = new float[conditions.length][];
                int conditionStride = spec.conditionWidth() == 0 ? 1 : spec.conditionWidth();
                for (int c = 0; c < conditions.length; c++) {
                    oneConditions[c] = Arrays.copyOfRange(conditions[c], lane * conditionStride,
                            (lane + 1) * conditionStride);
                }
            }
            float[] single = run(environment, session, spec, 1, oneX, oneLabels, oneConditions).values();
            for (int i = 0; i < outputStride; i++) {
                float a = single[i], b = batched[lane * outputStride + i];
                if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(b)) bits++;
                maxAbs = Math.max(maxAbs, Math.abs((double) a - b));
            }
        }
        return new Parity(bits, (long) batch * outputStride, maxAbs);
    }

    private static Parity compare(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("parity shape mismatch");
        long bits = 0;
        double maxAbs = 0;
        for (int i = 0; i < left.length; i++) {
            if (Float.floatToRawIntBits(left[i]) != Float.floatToRawIntBits(right[i])) bits++;
            maxAbs = Math.max(maxAbs, Math.abs((double) left[i] - right[i]));
        }
        return new Parity(bits, left.length, maxAbs);
    }

    private static float[] values(int length, long seed) {
        float[] result = new float[length];
        long state = seed;
        for (int i = 0; i < length; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            result[i] = ((state & 0xffffL) / 32768.0f) - 1.0f;
        }
        return result;
    }

    private record Parity(long bitMismatches, long values, double maxAbsDiff) {}
    private record BatchInput(float[] x, float[] labels) {}
}
