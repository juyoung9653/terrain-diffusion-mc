import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Base64;
import java.util.Map;

/** Run with Java 21: java -cp <runtime.jar> tools/onnx/RuntimeSmokeTest.java [CPU|CUDA|DML] [DirectML.dll] */
class RuntimeSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) System.load(args[1]);
        String provider = args.length == 0 ? "CPU" : args[0];
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        System.out.println("ONNX Runtime " + env.getVersion() + ": " + OrtEnvironment.getAvailableProviders());
        // ONNX Add(x, x), float[1], IR 8, opset 13: exercises a kernel without downloads.
        byte[] model = Base64.getDecoder().decode("CAg6OQoOCgF4CgF4EgF5IgNBZGQSBXNtb2tlWg8KAXgSCgoICAESBAoCCAFiDwoBeRIKCggIARIECgIIAUICEA0=");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            if (!"CPU".equals(provider)) options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            if ("DML".equals(provider)) {
                options.setMemoryPatternOptimization(false);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.addDirectML(0);
            } else if ("CUDA".equals(provider)) {
                options.addCUDA(0);
            } else if (!"CPU".equals(provider)) {
                throw new IllegalArgumentException("Expected CPU, CUDA or DML");
            }
            try (OrtSession session = env.createSession(model, options);
                 OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{42}), new long[]{1});
                 OrtSession.Result result = session.run(Map.of("x", input))) {
                float actual = ((float[]) result.get(0).getValue())[0];
                if (actual != 84) throw new AssertionError("Expected 84, got " + actual);
                System.out.println(provider + " session and inference: PASS");
            }
        }
    }
}
