package com.github.xandergos.terraindiffusionmc.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Reject broad coordinate-shaped holes; terrain supplies the connected snowline geometry. */
public final class SnowPatchGeometryTest {
    private static final int SIZE = 512;

    public static void main(String[] args) throws Exception {
        FastNoiseLite oldBroad = noise(82741, 72), warp = noise(62479, 160), edge = noise(91283, 3);
        boolean[] oldMask = new boolean[SIZE * SIZE], newMask = new boolean[SIZE * SIZE];
        boolean[] terrainMask = new boolean[SIZE * SIZE], iceMask = new boolean[SIZE * SIZE];
        float[] padded = new float[(SIZE + 2) * (SIZE + 2)];
        for (int z = -1; z <= SIZE; z++) for (int x = -1; x <= SIZE; x++)
            padded[(z + 1) * (SIZE + 2) + x + 1] = 1300 + x;
        float[] retention = EcologyClassifier.snowRetention(padded, SIZE, SIZE, 1);
        int altitudeChanges = 0;
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int px = x - SIZE / 2, pz = z - SIZE / 2, index = z * SIZE + x;
            float warpedX = px + 24 * warp.GetNoise(px, pz);
            float warpedZ = pz + 24 * warp.GetNoise(px + 917, pz - 433);
            oldMask[index] = .5f > .5f + oldBroad.GetNoise(warpedX, warpedZ) + .035f * edge.GetNoise(px, pz);
            newMask[index] = EcologyClassifier.naturalSnowCover(px, pz, 300, -2);
            terrainMask[index] = EcologyClassifier.naturalSnowCover(px, pz, 1300 + x, -3f * x / SIZE, retention[index]);
            iceMask[index] = EcologyClassifier.naturalIceCover(px, pz, 1300 + x, -3f * x / SIZE, retention[index]);
            if (x < 150) require(!terrainMask[index], "snow leaked below the terrain snowline");
            if (x > 370) require(terrainMask[index], "coordinate noise punched a hole above the terrain snowline");
            boolean valley = EcologyClassifier.naturalSnowCover(px, pz, 1000, -.5f);
            boolean summit = EcologyClassifier.naturalSnowCover(px, pz, 2400, -.5f);
            require(!valley || summit, "raising the actual ground removed snow");
            if (valley != summit) altitudeChanges++;
            require(!EcologyClassifier.naturalSnowCover(px, pz, 300, 10), "warm core changed");
            require(EcologyClassifier.naturalSnowCover(px, pz, 300, -8), "cold core changed");
            require(EcologyClassifier.naturalSnowCover(px, pz, 2400, -3), "alpine core changed");
            require(EcologyClassifier.naturalIceCover(px, pz, 300, 10) == false,
                    "warm water core changed");
            require(EcologyClassifier.naturalIceCover(px, pz, 300, -8),
                    "cold water core changed");
        }
        Metrics old = metrics(invert(oldMask)), current = metrics(invert(newMask));
        System.out.println("Old flat bare components: " + old);
        System.out.println("New flat bare components: " + current);
        require(current.largest < 64 && current.largest < old.largest / 100,
                "uniform marginal flat retains large connected green holes");
        require(current.snow < SIZE * SIZE * .15, "fine recesses overwhelmed the marginal flat");
        Metrics terrain = metrics(terrainMask);
        Metrics ice = metrics(iceMask);
        require(terrain.largest > terrain.snow * .90, "terrain snowfield must remain connected");
        require(terrain.edges < SIZE * 80, "fine detail must stay inside the climate transition");
        System.out.println("Terrain snowline: " + terrain);
        require(ice.edges == terrain.edges && ice.snow == terrain.snow,
                "water ice must use the same column climate transition as snow");
        System.out.println("Ice transition: " + ice);
        require(altitudeChanges == 0, "height must not invent an independent snow island");
        System.out.println("Height-only snow changes: " + altitudeChanges);
        if (args.length > 0) {
            Path out = Path.of(args[0]); Files.createDirectories(out);
            writePgm(out.resolve("snow-old.pgm"), oldMask);
            writePgm(out.resolve("snow-new.pgm"), newMask);
            writePgm(out.resolve("snow-terrain.pgm"), terrainMask);
        }
        System.out.println("SnowPatchGeometryTest: passed");
    }

    private static boolean[] invert(boolean[] field) {
        boolean[] inverse = new boolean[field.length];
        for (int i = 0; i < field.length; i++) inverse[i] = !field[i];
        return inverse;
    }

    private static Metrics metrics(boolean[] field) {
        int snow = 0, edges = 0, components = 0, tinyCells = 0, largest = 0;
        boolean[] seen = new boolean[field.length];
        for (int i = 0; i < field.length; i++) {
            int x = i % SIZE, z = i / SIZE;
            if (field[i]) snow++;
            if (x + 1 < SIZE && field[i] != field[i + 1]) edges++;
            if (z + 1 < SIZE && field[i] != field[i + SIZE]) edges++;
            if (!field[i] || seen[i]) continue;
            components++; int area = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(i); seen[i] = true;
            while (!queue.isEmpty()) {
                int p = queue.remove(); area++;
                int px = p % SIZE, pz = p / SIZE;
                for (int n : new int[]{px > 0 ? p - 1 : -1, px + 1 < SIZE ? p + 1 : -1,
                        pz > 0 ? p - SIZE : -1, pz + 1 < SIZE ? p + SIZE : -1}) {
                    if (n >= 0 && field[n] && !seen[n]) { seen[n] = true; queue.add(n); }
                }
            }
            if (area <= 4) tinyCells += area;
            largest = Math.max(largest, area);
        }
        return new Metrics(snow, edges, components, tinyCells, largest);
    }

    private static FastNoiseLite noise(int seed, int wavelength) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(1f / wavelength);
        return noise;
    }

    private static void writePgm(Path path, boolean[] field) throws Exception {
        byte[] header = ("P5\n" + SIZE + " " + SIZE + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] bytes = new byte[header.length + field.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        for (int i = 0; i < field.length; i++) bytes[header.length + i] = field[i] ? (byte) 255 : 0;
        Files.write(path, bytes);
    }

    private record Metrics(int snow, int edges, int components, int tinyCells, int largest) { }
    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
