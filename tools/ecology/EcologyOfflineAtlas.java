import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone sampler for synthetic ecology diagnostics. It does not load a world,
 * run ONNX inference, or observe successful feature placements.
 */
public final class EcologyOfflineAtlas {
    private static final String[] MATERIALS = {"GRASS", "COARSE_DIRT", "SAND", "PODZOL"};

    private EcologyOfflineAtlas() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("usage: EcologyOfflineAtlas OUTPUT_DIR [SIZE] [MODEL_STEP]");
        }
        Path output = Path.of(args[0]);
        int size = args.length >= 2 ? Integer.parseInt(args[1]) : 128;
        int modelStep = args.length >= 3 ? Integer.parseInt(args[2]) : 16;
        if (size < 8 || modelStep < 1) throw new IllegalArgumentException("size >= 8 and model step >= 1 required");
        Files.createDirectories(output);
        long[] seeds = {91357L, 0L, -71421L};
        int[] scales = {2, 5};
        writeCellsAndSummary(output, size, modelStep, seeds, scales);
        writeClimateGrid(output);
        writeManifest(output, size, modelStep, seeds, scales);
        System.out.printf(Locale.ROOT, "EcologyOfflineAtlas: PASS profiles=%d seeds=%d scales=%d cells=%d output=%s%n",
                EcologyClassifier.BIOMES.size(), seeds.length, scales.length,
                EcologyClassifier.BIOMES.size() * seeds.length * scales.length * size * size, output);
    }

    private static void writeCellsAndSummary(Path output, int size, int modelStep,
                                             long[] seeds, int[] scales) throws IOException {
        try (BufferedWriter cells = Files.newBufferedWriter(output.resolve("cells.csv"), StandardCharsets.UTF_8);
             BufferedWriter summary = Files.newBufferedWriter(output.resolve("summary.csv"), StandardCharsets.UTF_8)) {
            cells.write("profile,seed,scale,col,row,model_x,model_z,block_x,block_z,slope,biome_id,tree_cover,temperature,moisture,current_surface,legacy_td_surface,tree_probability\n");
            summary.write("profile,seed,scale,biome_id,current_grass_fraction,current_coarse_dirt_fraction,current_sand_fraction,current_podzol_fraction,legacy_grass_fraction,legacy_coarse_dirt_fraction,legacy_sand_fraction,mean_tree_probability,min_tree_probability,max_tree_probability,current_neighbor_transition_rate,legacy_neighbor_transition_rate,current_patch_count,current_largest_patch_fraction,tree_above_mean_patch_count,tree_above_mean_largest_fraction\n");
            for (String profile : EcologyClassifier.BIOMES) {
                for (long seed : seeds) {
                    for (int scale : scales) sample(profile, seed, scale, size, modelStep, cells, summary);
                }
            }
        }
    }

    private static void sample(String profile, long seed, int scale, int size, int modelStep,
                               BufferedWriter cells, BufferedWriter summary) throws IOException {
        FeatureEcologyMath.SurfaceMaterial[][] current = new FeatureEcologyMath.SurfaceMaterial[size][size];
        FeatureEcologyMath.SurfaceMaterial[][] legacy = new FeatureEcologyMath.SurfaceMaterial[size][size];
        float[][] tree = new float[size][size];
        Map<FeatureEcologyMath.SurfaceMaterial, Integer> currentCounts = counts();
        Map<FeatureEcologyMath.SurfaceMaterial, Integer> legacyCounts = counts();
        int biomeId = -1;
        double treeSum = 0;
        float treeMin = 1, treeMax = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int modelX = (col - size / 2) * modelStep;
                int modelZ = (row - size / 2) * modelStep;
                int blockX = Math.multiplyExact(modelX, scale);
                int blockZ = Math.multiplyExact(modelZ, scale);
                float slope = syntheticSlope(modelX, modelZ);
                // Model-space coordinates keep the synthetic controls identical across terrain scales.
                EcologyClassifier.Sample sample = EcologyShowcaseProfile.sample(profile, modelX, modelZ, slope);
                biomeId = sample.biomeId();
                FeatureEcologyMath.SurfaceMaterial material = FeatureEcologyMath.surfaceMaterial(
                        sample.temperature(), sample.moisture(), sample.slope(), sample.treeCover(),
                        seed, blockX, blockZ, scale);
                FeatureEcologyMath.SurfaceMaterial old = legacySurface(
                        FeatureEcologyMath.surfaceMoisture(sample.moisture(), seed, blockX, blockZ, scale));
                float chance = FeatureEcologyMath.treePlacementChance(sample.treeCover(), seed, blockX, blockZ, scale);
                current[row][col] = material;
                legacy[row][col] = old;
                tree[row][col] = chance;
                currentCounts.put(material, currentCounts.get(material) + 1);
                legacyCounts.put(old, legacyCounts.get(old) + 1);
                treeSum += chance;
                treeMin = Math.min(treeMin, chance);
                treeMax = Math.max(treeMax, chance);
                cells.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%d,%d,%d,%d,%d,%.7f,%d,%.7f,%.7f,%.7f,%s,%s,%.7f%n",
                        profile, seed, scale, col, row, modelX, modelZ, blockX, blockZ, slope,
                        sample.biomeId(), sample.treeCover(), sample.temperature(), sample.moisture(),
                        material, old, chance));
            }
        }
        int total = size * size;
        double mean = treeSum / total;
        PatchStats currentPatches = materialPatches(current);
        PatchStats treePatches = booleanPatches(tree, (float) mean);
        summary.write(String.format(Locale.ROOT,
                "%s,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%.9f,%d,%.9f%n",
                profile, seed, scale, biomeId,
                fraction(currentCounts, FeatureEcologyMath.SurfaceMaterial.GRASS, total),
                fraction(currentCounts, FeatureEcologyMath.SurfaceMaterial.COARSE_DIRT, total),
                fraction(currentCounts, FeatureEcologyMath.SurfaceMaterial.SAND, total),
                fraction(currentCounts, FeatureEcologyMath.SurfaceMaterial.PODZOL, total),
                fraction(legacyCounts, FeatureEcologyMath.SurfaceMaterial.GRASS, total),
                fraction(legacyCounts, FeatureEcologyMath.SurfaceMaterial.COARSE_DIRT, total),
                fraction(legacyCounts, FeatureEcologyMath.SurfaceMaterial.SAND, total),
                mean, treeMin, treeMax, transitions(current), transitions(legacy),
                currentPatches.count, currentPatches.largest / (double) total,
                treePatches.count, treePatches.largest / (double) total));
    }

    private static float syntheticSlope(int x, int z) {
        return 0.08F + 0.34F * (float) (0.5 + 0.5 * Math.sin(x / 290.0) * Math.cos(z / 370.0));
    }

    /** Historical TD-only rule; it intentionally excludes later mod interactions such as IW. */
    private static FeatureEcologyMath.SurfaceMaterial legacySurface(float moisture) {
        if (moisture < 0.22F) return FeatureEcologyMath.SurfaceMaterial.SAND;
        if (moisture < 0.40F) return FeatureEcologyMath.SurfaceMaterial.COARSE_DIRT;
        return FeatureEcologyMath.SurfaceMaterial.GRASS;
    }

    private static Map<FeatureEcologyMath.SurfaceMaterial, Integer> counts() {
        Map<FeatureEcologyMath.SurfaceMaterial, Integer> result =
                new EnumMap<>(FeatureEcologyMath.SurfaceMaterial.class);
        for (FeatureEcologyMath.SurfaceMaterial material : FeatureEcologyMath.SurfaceMaterial.values()) result.put(material, 0);
        return result;
    }

    private static double fraction(Map<FeatureEcologyMath.SurfaceMaterial, Integer> counts,
                                   FeatureEcologyMath.SurfaceMaterial material, int total) {
        return counts.get(material) / (double) total;
    }

    private static double transitions(FeatureEcologyMath.SurfaceMaterial[][] grid) {
        int changed = 0, pairs = 0;
        for (int row = 0; row < grid.length; row++) {
            for (int col = 0; col < grid[row].length; col++) {
                if (col + 1 < grid[row].length) {
                    pairs++;
                    if (grid[row][col] != grid[row][col + 1]) changed++;
                }
                if (row + 1 < grid.length) {
                    pairs++;
                    if (grid[row][col] != grid[row + 1][col]) changed++;
                }
            }
        }
        return changed / (double) pairs;
    }

    private static PatchStats materialPatches(FeatureEcologyMath.SurfaceMaterial[][] grid) {
        boolean[][] seen = new boolean[grid.length][grid[0].length];
        int count = 0, largest = 0;
        for (int row = 0; row < grid.length; row++) for (int col = 0; col < grid[row].length; col++) {
            if (!seen[row][col]) {
                count++;
                largest = Math.max(largest, floodMaterial(grid, seen, row, col));
            }
        }
        return new PatchStats(count, largest);
    }

    private static int floodMaterial(FeatureEcologyMath.SurfaceMaterial[][] grid, boolean[][] seen,
                                     int startRow, int startCol) {
        FeatureEcologyMath.SurfaceMaterial target = grid[startRow][startCol];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startRow * grid[0].length + startCol);
        seen[startRow][startCol] = true;
        int size = 0;
        while (!queue.isEmpty()) {
            int value = queue.removeFirst(), row = value / grid[0].length, col = value % grid[0].length;
            size++;
            for (int[] d : DIRECTIONS) {
                int nr = row + d[0], nc = col + d[1];
                if (nr >= 0 && nr < grid.length && nc >= 0 && nc < grid[0].length
                        && !seen[nr][nc] && grid[nr][nc] == target) {
                    seen[nr][nc] = true;
                    queue.add(nr * grid[0].length + nc);
                }
            }
        }
        return size;
    }

    private static PatchStats booleanPatches(float[][] grid, float threshold) {
        boolean[][] seen = new boolean[grid.length][grid[0].length];
        int count = 0, largest = 0;
        for (int row = 0; row < grid.length; row++) for (int col = 0; col < grid[row].length; col++) {
            if (!seen[row][col] && grid[row][col] > threshold) {
                count++;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(row * grid[0].length + col);
                seen[row][col] = true;
                int patch = 0;
                while (!queue.isEmpty()) {
                    int value = queue.removeFirst(), r = value / grid[0].length, c = value % grid[0].length;
                    patch++;
                    for (int[] d : DIRECTIONS) {
                        int nr = r + d[0], nc = c + d[1];
                        if (nr >= 0 && nr < grid.length && nc >= 0 && nc < grid[0].length
                                && !seen[nr][nc] && grid[nr][nc] > threshold) {
                            seen[nr][nc] = true;
                            queue.add(nr * grid[0].length + nc);
                        }
                    }
                }
                largest = Math.max(largest, patch);
            }
        }
        return new PatchStats(count, largest);
    }

    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private record PatchStats(int count, int largest) { }

    private static void writeClimateGrid(Path output) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("climate-grid.csv"), StandardCharsets.UTF_8)) {
            writer.write("temperature,moisture,biome_id,biome_label,tree_cover\n");
            for (int ti = 0; ti <= 21; ti++) {
                float temperature = -10 + ti * 2;
                for (int mi = 0; mi <= 18; mi++) {
                    float moisture = mi / 10.0F;
                    float rainfall = (500 + 20 * Math.max(0, temperature)) * moisture;
                    EcologyClassifier.Sample sample = EcologyClassifier.select(
                            ti * 97, mi * 101, 100, 0.1F, false,
                            temperature, rainfall, 100).sample();
                    String label = sample.biomeId() > 0 ? EcologyClassifier.BIOMES.get(sample.biomeId() - 1) : "terrain";
                    writer.write(String.format(Locale.ROOT, "%.1f,%.1f,%d,%s,%.7f%n",
                            temperature, moisture, sample.biomeId(), label, sample.treeCover()));
                }
            }
        }
    }

    private static void writeManifest(Path output, int size, int modelStep, long[] seeds, int[] scales) throws IOException {
        String json = "{\n"
                + "  \"title\": \"Synthetic offline ecology diagnostic\",\n"
                + "  \"provenance\": \"Production EcologyClassifier, EcologyShowcaseProfile, and FeatureEcologyMath APIs\",\n"
                + "  \"limitations\": [\"Not ONNX generated terrain\", \"Not sampled from saved chunks\", \"Tree values are per-attempt probabilities, not actual placements\", \"Legacy TD-only surface rule excludes Immersive Weathering and other mod interactions\"],\n"
                + "  \"grid_size\": " + size + ",\n"
                + "  \"model_step\": " + modelStep + ",\n"
                + "  \"seeds\": [91357, 0, -71421],\n"
                + "  \"scales\": [2, 5],\n"
                + "  \"materials\": [\"" + String.join("\", \"", MATERIALS) + "\"]\n"
                + "}\n";
        Files.writeString(output.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }
}
