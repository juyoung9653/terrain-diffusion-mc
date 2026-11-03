import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;

/** Executes real pack vertex entry points, capturing pre-projection and clip positions. */
public final class WavingProbe {
    static final float[] ID = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    static int feedback, vertices, program;
    static void uniform(String name, float value) {
        glUniform1f(glGetUniformLocation(program, name), value);
    }
    static void vec3(String name, float x, float y, float z) {
        glUniform3f(glGetUniformLocation(program, name), x,y,z);
    }
    static void matrix(String name, float[] value) {
        glUniformMatrix4fv(glGetUniformLocation(program, name), false, value);
    }
    static void attribute(String name, float x, float y, float z) {
        int location = glGetAttribLocation(program, name);
        if (location >= 0) glVertexAttrib3f(location, x,y,z);
    }
    static int load(Path file) throws Exception {
        int shader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(shader, Files.readString(file)); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(file + ": " + glGetShaderInfoLog(shader));
        int p = glCreateProgram(); glAttachShader(p, shader);
        glTransformFeedbackVaryings(p, new String[]{"probe_position", "gl_Position"}, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(file + ": " + glGetProgramInfoLog(p));
        glDeleteShader(shader);
        return p;
    }
    static void setup(int p, float px, float pz) {
        program = p; glUseProgram(p);
        for (String name : new String[]{"gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse", "shadowModelView", "shadowModelViewInverse"}) matrix(name, ID);
        vec3("eyePosition",px,72,pz); vec3("cameraPosition",0,0,0);
        uniform("world_age",120); uniform("rainStrength",.3f); uniform("wetness",.2f);
        uniform("biome_temperature",.8f); uniform("biome_humidity",.6f);
        uniform("time_noon",1); uniform("day_factor",1);
        uniform("near",.05f); uniform("far",256);
        glUniform1i(glGetUniformLocation(p,"noisetex"),0);
        glUniform2i(glGetUniformLocation(p,"atlasSize"),1024,1024);
        glUniform2f(glGetUniformLocation(p,"taa_offset"),0,0);
        glMatrixMode(GL_PROJECTION); glLoadIdentity();
        glMatrixMode(GL_MODELVIEW); glLoadIdentity();
        glMatrixMode(GL_TEXTURE); glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glMultiTexCoord2f(GL_TEXTURE0,.5f,0);
        glMultiTexCoord2f(GL_TEXTURE1,240,240);
        glColor4f(1,1,1,1); glNormal3f(0,1,0);
        attribute("at_tangent",1,0,0);
        attribute("mc_midTexCoord",.5f,.5f,0);
    }
    static float[] capture(float x,float z,float dx,float dz,int material,float time) {
        uniform("frameTimeCounter",time);
        attribute("at_midBlock",-dx*64,-.25f*64,-dz*64);
        attribute("mc_Entity",material,0,0);
        glBindBuffer(GL_ARRAY_BUFFER,vertices);
        glBufferData(GL_ARRAY_BUFFER,new float[]{x+dx,70.75f,z+dz,1},GL_STREAM_DRAW);
        glVertexPointer(4,GL_FLOAT,0,0L); glEnableClientState(GL_VERTEX_ARRAY);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
        glBeginTransformFeedback(GL_POINTS); glDrawArrays(GL_POINTS,0,1); glEndTransformFeedback();
        float[] result = new float[7];
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
        glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER,0,result);
        for (float value : result) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite GPU output");
        int error=glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
        return result;
    }
    static float distance(float[] a,float[] b,int first,int end) {
        float maximum=0;
        for (int i=first;i<end;i++) maximum=Math.max(maximum,Math.abs(a[i]-b[i]));
        return maximum;
    }
    public static void main(String[] args) throws Exception {
        long window=0;
        try {
            if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
            glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,0);
            glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_COMPAT_PROFILE);
            window=glfwCreateWindow(8,8,"Photon GPU motion probe",0,0);
            if (window==0) throw new IllegalStateException("No GL context");
            glfwMakeContextCurrent(window); GL.createCapabilities();
            glEnable(GL_RASTERIZER_DISCARD);
            vertices=glGenBuffers(); feedback=glGenBuffers();
            glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
            glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER,28,GL_STREAM_READ);
            int texture=glGenTextures(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,new float[]{.5f,.7f,.3f,1});
            int[] materials={10002,10003,10004,10005,10059,10901,10915,10918,10922};
            float[][] players={{.25f,.25f},{15.999f,-.001f},{-16.001f,16}};
            float[][] corners={{-.5f,-.5f},{.75f,.75f}};
            List<float[]> terrainResults=new ArrayList<>();
            for (int fileIndex=0;fileIndex<args.length;fileIndex++) {
                Path path=Path.of(args[fileIndex]); int p=load(path);
                int inside=0,outside=0,insideMoving=0,outsideMoving=0,index=0;
                float maxOutside=0,maxInside=0,maxPassDifference=0;
                for (float[] player : players) {
                    setup(p,player[0],player[1]);
                    int cx=(int)Math.floor(player[0]/16),cz=(int)Math.floor(player[1]/16);
                    for (int ox=-2;ox<=2;ox++) for (int oz=-2;oz<=2;oz++) {
                        boolean active=Math.abs(ox)<=1 && Math.abs(oz)<=1;
                        for (int material : materials) for (float[] corner : corners) {
                            // Use the last block in each chunk to exercise shared chunk edges.
                            float x=(cx+ox)*16+15.5f,z=(cz+oz)*16+15.5f;
                            float[] a=capture(x,z,corner[0],corner[1],material,0);
                            float[] b=capture(x,z,corner[0],corner[1],material,7.25f);
                            float[] c=capture(x,z,corner[0],corner[1],material,23.5f);
                            float delta=Math.max(distance(a,b,0,3),distance(a,c,0,3));
                            if (active) { inside++; maxInside=Math.max(maxInside,delta); if(delta>1e-6f) insideMoving++; }
                            else { outside++; maxOutside=Math.max(maxOutside,delta); if(delta>1e-6f) outsideMoving++; }
                            for (float[] value : new float[][]{a,b,c}) {
                                if (fileIndex==0) terrainResults.add(value);
                                else maxPassDifference=Math.max(maxPassDifference,distance(terrainResults.get(index),value,0,3));
                                index++;
                            }
                        }
                    }
                }
                // With animation time frozen, rotate the light transform for an outside leaf.
                setup(p,0,0);
                float[] before=capture(47.5f,47.5f,-.5f,-.5f,10005,0);
                float angle=.02f,co=(float)Math.cos(angle),si=(float)Math.sin(angle);
                float[] rotation={co,si,0,0,-si,co,0,0,0,0,1,0,0,0,0,1};
                float[] inverse={co,-si,0,0,si,co,0,0,0,0,1,0,0,0,0,1};
                glMatrixMode(GL_MODELVIEW); glLoadMatrixf(rotation);
                matrix("shadowModelView",rotation); matrix("shadowModelViewInverse",inverse);
                float[] rotated=capture(47.5f,47.5f,-.5f,-.5f,10005,0);
                System.out.printf(Locale.ROOT,"{\"program\":\"%s\",\"inside_samples\":%d,\"outside_samples\":%d,\"inside_moving\":%d,\"outside_moving\":%d,\"max_inside_motion\":%.9f,\"max_outside_motion\":%.9f,\"max_terrain_shadow_difference\":%.9f,\"light_rotation_clip_motion\":%.9f,\"light_rotation_world_motion\":%.9f}%n",
                    path.getFileName(),inside,outside,insideMoving,outsideMoving,maxInside,maxOutside,maxPassDifference,distance(before,rotated,3,7),distance(before,rotated,0,3));
                glDeleteProgram(p);
            }
        } finally { if(window!=0) glfwDestroyWindow(window); glfwTerminate(); }
    }
}
