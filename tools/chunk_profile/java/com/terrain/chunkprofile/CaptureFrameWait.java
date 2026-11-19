package com.terrain.chunkprofile;

import net.minecraft.client.Minecraft;

/** Client-thread only. The first view after teleport retains the full warm-up. */
final class CaptureFrameWait {
    private static final long FULL_WAIT = 3_000_000_000L;
    // Opt-in until matched captures establish shader/renderer quality parity.
    private static final boolean ADAPTIVE = Boolean.getBoolean("chunkprofile.adaptiveCaptureWait");
    private static String active;
    private static long started, cleanSince;
    private static int cleanFrames;
    private static boolean sameSite;
    private static double cameraX, cameraY, cameraZ;
    private static float cameraYaw, cameraPitch;

    static void afterFrame() {
        if (active == null || !sameSite) return;
        long now = System.nanoTime();
        if (now - started > FULL_WAIT) return;
        Minecraft mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var eye = camera.getPosition();
        boolean posed = Math.abs(eye.x - cameraX) < .25 && Math.abs(eye.z - cameraZ) < .25
                && Math.abs(eye.y - cameraY) < .45
                && Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.getYRot() - cameraYaw)) < 1
                && Math.abs(camera.getXRot() - cameraPitch) < 1;
        if (!posed || mc.screen != null || mc.getOverlay() != null || !mc.levelRenderer.hasRenderedAllSections()) {
            cleanFrames = 0; cleanSince = 0; return;
        }
        if (cleanFrames++ == 0) cleanSince = now;
    }

    record Sample(boolean ready, String mode, long elapsedNanos, int frames) {}

    static Sample poll(String id, boolean arrived, boolean subsequent, double x, double eyeY,
                       double z, float yaw, float pitch) {
        long now = System.nanoTime();
        if (!arrived) { active = null; return new Sample(false, "loading", 0, 0); }
        if (!id.equals(active)) {
            active = id; sameSite = subsequent && ADAPTIVE; started = now; cleanSince = 0; cleanFrames = 0;
            cameraX = x; cameraY = eyeY; cameraZ = z; cameraYaw = yaw; cameraPitch = pitch;
        }
        long elapsed = now - started;
        boolean stable = sameSite && cleanFrames >= 24 && now - cleanSince >= 1_000_000_000L && elapsed < FULL_WAIT;
        boolean done = stable || elapsed >= FULL_WAIT;
        if (done) active = null;
        return new Sample(done, stable ? "rendered-frames" : "full-wait", elapsed, cleanFrames);
    }
}
