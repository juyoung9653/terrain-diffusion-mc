import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import org.lwjgl.opengl.GL;
import java.nio.file.*;

/** Tiny hidden-context compiler/linker used only by validate_gl.py. */
public final class ShaderCompileCheck {
  private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
  private static int compile(int type, String path) throws Exception {
    int shader = glCreateShader(type);
    glShaderSource(shader, Files.readString(Path.of(path)));
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
      throw new IllegalStateException(glGetShaderInfoLog(shader));
    return shader;
  }
  public static void main(String[] args) {
    if (args.length != 2) { System.err.println("usage: ShaderCompileCheck vertex fragment"); System.exit(2); }
    long window = 0;
    try {
      if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
      glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
      window = glfwCreateWindow(8, 8, "photon-static-check", 0, 0);
      if (window == 0) throw new IllegalStateException("Could not create OpenGL 4 compatibility context");
      glfwMakeContextCurrent(window); GL.createCapabilities();
      int vs = compile(GL_VERTEX_SHADER, args[0]), fs = compile(GL_FRAGMENT_SHADER, args[1]);
      int program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
      if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
      System.out.println("{\"ok\":true,\"renderer\":" + quote(glGetString(GL_RENDERER)) + ",\"version\":" + quote(glGetString(GL_VERSION)) + "}");
    } catch (Throwable e) {
      System.out.println("{\"ok\":false,\"error\":" + quote(e.toString()) + "}"); System.exit(1);
    } finally { if (window != 0) glfwDestroyWindow(window); glfwTerminate(); }
  }
}
