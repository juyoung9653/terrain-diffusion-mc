import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;

/** Captures the final shadow clip position for fixed caster IDs and chunk boundaries. */
public final class CasterClipProbe {
    static final float[] ID = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    static int feedback, vertices, program;
    record Material(String name, int rawId) {}
    record Position(String name, float x, float z, boolean near) {}
    static Material[] materials(String mode) {
        // Cube mode retains stairs/slabs at their original material ID.  The combined mode encodes them as 26000 + ID.
        int stairsSlabRaw = mode.equals("cube") ? 10001 : 26001;
        return new Material[]{new Material("full_cube", 22001), new Material("stairs_slab", stairsSlabRaw),
            new Material("leaf", 10005), new Material("unassigned", -1)};
    }
    // The reconstructed owner is floor(pos + at_midBlock / 64).  These exercise chunk +/-1 and +/-2.
    static final Position[] POSITIONS = {
        new Position("near_positive_chunk_1", 31.5f, 31.5f, true),
        new Position("near_negative_chunk_minus1", -0.5f, -0.5f, true),
        new Position("far_positive_chunk_2", 47.5f, 47.5f, false),
        new Position("far_negative_chunk_minus2", -16.5f, -16.5f, false)
    };

    static void uniform(String name, float value) { glUniform1f(glGetUniformLocation(program, name), value); }
    static void vec3(String name, float x, float y, float z) { glUniform3f(glGetUniformLocation(program, name), x, y, z); }
    static void matrix(String name, float[] value) { glUniformMatrix4fv(glGetUniformLocation(program, name), false, value); }
    static void attribute(String name, float x, float y, float z) {
        int location = glGetAttribLocation(program, name);
        if (location >= 0) glVertexAttrib3f(location, x, y, z);
    }
    static int load(Path file) throws Exception {
        int shader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(shader, Files.readString(file)); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(file + ": " + glGetShaderInfoLog(shader));
        int result = glCreateProgram(); glAttachShader(result, shader);
        glTransformFeedbackVaryings(result, new String[]{"gl_Position"}, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(result);
        if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(file + ": " + glGetProgramInfoLog(result));
        glDeleteShader(shader);
        return result;
    }
    static void setup(int loaded) {
        program = loaded; glUseProgram(loaded);
        for (String name : new String[]{"gbufferModelView", "gbufferModelViewInverse", "gbufferProjection",
                "gbufferProjectionInverse", "shadowModelView", "shadowModelViewInverse", "shadowProjection",
                "shadowProjectionInverse"}) matrix(name, ID);
        vec3("eyePosition", .5f, 72f, .5f); vec3("cameraPosition", 0, 0, 0);
        uniform("frameTimeCounter", 0); uniform("world_age", 120); uniform("rainStrength", .3f);
        uniform("wetness", .2f); uniform("biome_temperature", .8f); uniform("biome_humidity", .6f);
        uniform("time_noon", 1); uniform("day_factor", 1); uniform("near", .05f); uniform("far", 256);
        glUniform1i(glGetUniformLocation(program, "noisetex"), 0);
        glUniform2i(glGetUniformLocation(program, "atlasSize"), 1024, 1024);
        glUniform2f(glGetUniformLocation(program, "taa_offset"), 0, 0);
        glMatrixMode(GL_PROJECTION); glLoadIdentity(); glMatrixMode(GL_MODELVIEW); glLoadIdentity();
        glMatrixMode(GL_TEXTURE); glLoadIdentity(); glMatrixMode(GL_MODELVIEW);
        glMultiTexCoord2f(GL_TEXTURE0, .5f, 0); glMultiTexCoord2f(GL_TEXTURE1, 240, 240);
        glColor4f(1, 1, 1, 1); glNormal3f(0, 1, 0);
        attribute("at_tangent", 1, 0, 0); attribute("mc_midTexCoord", .5f, .5f, 0);
    }
    static float[] capture(Position position, Material material) {
        // The offset and at_midBlock reconstruct an owner exactly at position.xz.
        attribute("at_midBlock", 0, -.25f * 64, 0);
        attribute("mc_Entity", material.rawId, 0, 0);
        glBindBuffer(GL_ARRAY_BUFFER, vertices);
        glBufferData(GL_ARRAY_BUFFER, new float[]{position.x, 70.75f, position.z, 1}, GL_STREAM_DRAW);
        glVertexPointer(4, GL_FLOAT, 0, 0L); glEnableClientState(GL_VERTEX_ARRAY);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, feedback);
        glBeginTransformFeedback(GL_POINTS); glDrawArrays(GL_POINTS, 0, 1); glEndTransformFeedback();
        float[] value = new float[4]; glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, feedback);
        glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0, value);
        for (float part : value) if (!Float.isFinite(part)) throw new IllegalStateException("Nonfinite GPU output");
        int error = glGetError(); if (error != GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
        return value;
    }
    static String json(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || (!args[0].equals("cube") && !args[0].equals("cube-stairs-slabs")))
            throw new IllegalArgumentException("Usage: CasterClipProbe <cube|cube-stairs-slabs> <shadow.vsh> [...]");
        String mode = args[0]; Material[] materials = materials(mode);
        long window = 0;
        try {
            if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0); glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
            window = glfwCreateWindow(8, 8, "Photon caster clip probe", 0, 0);
            if (window == 0) throw new IllegalStateException("No GL context");
            glfwMakeContextCurrent(window); GL.createCapabilities(); glEnable(GL_RASTERIZER_DISCARD);
            vertices = glGenBuffers(); feedback = glGenBuffers(); glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, feedback);
            glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, 16, GL_STREAM_READ);
            int texture = glGenTextures(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, new float[]{.5f, .7f, .3f, 1});
            for (int argument = 1; argument < args.length; argument++) {
                String arg = args[argument];
                Path path = Path.of(arg); int loaded = load(path); setup(loaded);
                for (Position position : POSITIONS) for (Material material : materials) {
                    float[] clip = capture(position, material);
                    boolean clipped = Math.abs(clip[0] - 2) < 1e-6 && Math.abs(clip[1] - 2) < 1e-6 &&
                        Math.abs(clip[2] - 2) < 1e-6 && Math.abs(clip[3] - 1) < 1e-6;
                    System.out.printf(Locale.ROOT,
                        "{\"program\":%s,\"position\":%s,\"near\":%s,\"material\":%s,\"raw_id\":%d,\"clipped\":%s,\"gl_position\":[%.9f,%.9f,%.9f,%.9f]}%n",
                        json(path.getFileName().toString()), json(position.name), position.near, json(material.name), material.rawId,
                        clipped, clip[0], clip[1], clip[2], clip[3]);
                }
                glDeleteProgram(loaded);
            }
        } finally { if (window != 0) glfwDestroyWindow(window); glfwTerminate(); }
    }
}
