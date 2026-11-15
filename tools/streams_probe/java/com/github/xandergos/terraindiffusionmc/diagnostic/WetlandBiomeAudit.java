package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.world.ecology.NaturalEcology;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded physical ecology sample. Sites are FULL chunks supplied as X,Z pairs. */
public final class WetlandBiomeAudit {
    private static final Map<Integer, String> LABELS = Map.of(20, "reed_marsh", 21, "freshwater_swamp",
            22, "mangrove_swamp", 23, "riparian_forest");
    private WetlandBiomeAudit() { }

    public static JsonObject run(ServerLevel level) {
        JsonObject result = new JsonObject(); result.addProperty("event", "wetland_biome_audit");
        try {
            var source = level.getChunkSource().getGenerator().getBiomeSource();
            boolean showcase = com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources
                    .find(source) != null && !com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSources
                    .find(source).ecologyShowcase().isEmpty();
            result.addProperty("controlledShowcase", showcase);
            JsonArray registered = new JsonArray();
            for (var holder : source.possibleBiomes()) {
                String id = holder.unwrapKey().map(key -> key.location().toString()).orElse("unkeyed");
                if (id.startsWith("terrain-diffusion-mc:ecology/")) registered.add(id);
            }
            result.add("ecologyBiomesInSource", registered);
            for (String label : LABELS.values()) {
                String id = "terrain-diffusion-mc:ecology/" + label;
                boolean present = source.possibleBiomes().stream().anyMatch(holder -> holder.unwrapKey()
                        .map(key -> id.equals(key.location().toString())).orElse(false));
                require(present, "new ecology biome absent from TD biome source: " + id);
            }
            String sites = System.getProperty("td.streamsProbe.wetlandSites", "").trim();
            boolean autoSelected = sites.isEmpty();
            if (autoSelected) {
                Discovery discovery = discoverSites(level);
                sites = discovery.sites();
                result.addProperty("rawFieldSearchExtentBlocks", 512);
                result.addProperty("rawFieldSearchSamples", discovery.samples());
                result.addProperty("rawFieldWetlandCandidates", discovery.wetlandCandidates());
                result.add("rawFieldLabels", discovery.labels());
            }
            result.addProperty("siteSelection", autoSelected ? "bounded_raw_field_ranked_wetland_chunks" : "operator_coordinates");
            JsonArray samples = new JsonArray(); Map<String, Integer> labels = new LinkedHashMap<>();
            int materials = 0, logs = 0, plants = 0, mud = 0, supports = 0, transitions = 0;
            Map<String, Integer> topMaterials = new LinkedHashMap<>();
            for (String raw : sites.split(";")) {
                if (!raw.matches("-?\\d+,-?\\d+")) throw new IllegalArgumentException("wetland site must be chunkX,chunkZ");
                String[] pair = raw.split(",", 2); LevelChunk chunk = level.getChunk(Integer.parseInt(pair[0]), Integer.parseInt(pair[1]));
                EcologyClassifier.Sample previous = null;
                for (int dz = 0; dz < 16; dz += 4) for (int dx = 0; dx < 16; dx += 4) {
                    int x = chunk.getPos().getMinBlockX() + dx, z = chunk.getPos().getMinBlockZ() + dz;
                    NaturalEcology.SurfaceSample surface = NaturalEcology.surfaceSample(level.getChunkSource().getGenerator(), x, z);
                    if (surface == null) continue;
                    EcologyClassifier.Sample sample = surface.climate();
                    String label = LABELS.getOrDefault(sample.biomeId(), "other"); labels.merge(label, 1, Integer::sum);
                    if (previous != null && (Math.abs(sample.wetness() - previous.wetness()) > .06F
                            || Math.abs(sample.marineInfluence() - previous.marineInfluence()) > .06F)) transitions++;
                    previous = sample;
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    var state = chunk.getBlockState(new BlockPos(x, top, z));
                    String topMaterial = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!state.isAir()) { materials++; topMaterials.merge(topMaterial, 1, Integer::sum); }
                    int ground = level.getChunkSource().getGenerator().getBaseHeight(x, z,
                            net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level,
                            level.getChunkSource().randomState()) - 1;
                    for (int y = Math.max(level.getMinBuildHeight(), ground);
                         y <= Math.min(top, level.getMaxBuildHeight() - 1); y++) {
                        var column = chunk.getBlockState(new BlockPos(x, y, z));
                        String path = BuiltInRegistries.BLOCK.getKey(column.getBlock()).getPath();
                        if (column.is(BlockTags.LOGS)) logs++;
                        if (plant(path)) plants++;
                        if (path.contains("mud")) mud++;
                        if (support(path)) supports++;
                    }
                    JsonObject row = new JsonObject(); row.addProperty("x", x); row.addProperty("z", z);
                    row.addProperty("biomeId", sample.biomeId()); row.addProperty("label", label);
                    row.addProperty("wetness", sample.wetness()); row.addProperty("marineInfluence", sample.marineInfluence());
                    row.addProperty("topMaterial", topMaterial); samples.add(row);
                }
            }
            result.add("samples", samples); JsonObject counts = new JsonObject(); labels.forEach(counts::addProperty); result.add("labels", counts);
            JsonObject materialCounts = new JsonObject(); topMaterials.forEach(materialCounts::addProperty); result.add("topMaterialCounts", materialCounts);
            result.addProperty("fullChunkSurfaceMaterials", materials); result.addProperty("treeLogBlocks", logs);
            result.addProperty("waterPlantBlocks", plants); result.addProperty("mudBlocks", mud);
            result.addProperty("naturalSupportBlocks", supports); result.addProperty("transitionSamples", transitions);
            String required = System.getProperty("td.streamsProbe.wetlandRequiredLabels", "").trim();
            if (!required.isEmpty()) for (String label : required.split(","))
                require(labels.getOrDefault(label.trim(), 0) > 0, "required wetland label not observed: " + label.trim());
            result.addProperty("requiredLabels", required);
            result.addProperty("evidenceLevel", showcase ? "controlled_showcase_full_chunks" : "natural_full_chunks");
            require(samples.size() > 0, "no NaturalEcology surface samples"); result.addProperty("status", "passed");
        } catch (RuntimeException failure) { result.addProperty("status", "failed"); result.addProperty("error", failure.toString()); }
        return result;
    }
    private static boolean plant(String path) {
        return path.equals("lily_pad") || path.equals("sugar_cane") || path.equals("seagrass")
                || path.equals("tall_seagrass");
    }
    private static boolean support(String path) {
        return path.equals("dirt") || path.equals("grass_block") || path.equals("rooted_dirt")
                || path.equals("mud") || path.equals("clay") || path.equals("sand");
    }
    /** Raw-field sampling has no chunk FULL requests; only the retained sites are generated afterwards. */
    private static Discovery discoverSites(ServerLevel level) {
        int centerX = level.getSharedSpawnPos().getX(), centerZ = level.getSharedSpawnPos().getZ();
        var generator = level.getChunkSource().getGenerator();
        List<Site> ranked = new ArrayList<>();
        Map<String, Integer> labels = new LinkedHashMap<>();
        int samples = 0;
        for (int dz = -512; dz <= 512; dz += 32) for (int dx = -512; dx <= 512; dx += 32) {
            EcologyClassifier.Sample sample = NaturalEcology.sample(generator, centerX + dx, centerZ + dz);
            samples++;
            if (sample == null) continue;
            String label = LABELS.getOrDefault(sample.biomeId(), "other");
            labels.merge(label, 1, Integer::sum);
            if (LABELS.containsKey(sample.biomeId())) ranked.add(new Site((centerX + dx) >> 4,
                    (centerZ + dz) >> 4, sample.wetness(), sample.marineInfluence(), label));
        }
        List<Site> selected = new ArrayList<>();
        ranked.stream().max(Comparator.comparing(Site::wetness)).ifPresent(selected::add);
        ranked.stream().max(Comparator.comparing(Site::marine)).ifPresent(selected::add);
        for (String label : List.of("freshwater_swamp", "riparian_forest")) ranked.stream()
                .filter(site -> site.label.equals(label)).max(Comparator.comparing(Site::wetness)).ifPresent(selected::add);
        List<Site> unique = selected.stream().collect(java.util.stream.Collectors.toMap(
                site -> site.x + "," + site.z, site -> site, (left, right) -> left, LinkedHashMap::new))
                .values().stream().limit(4).toList();
        if (unique.isEmpty()) unique = ranked.stream().sorted(Comparator.comparing(Site::wetness).reversed()).limit(2).toList();
        require(!unique.isEmpty(), "bounded raw-field search found no wetland label; coverage is explicit, not fabricated");
        JsonArray found = new JsonArray(); labels.forEach((label, count) -> { JsonObject row = new JsonObject(); row.addProperty("label", label); row.addProperty("samples", count); found.add(row); });
        return new Discovery(unique.stream().map(site -> site.x + "," + site.z).reduce((a, b) -> a + ";" + b).orElseThrow(),
                samples, ranked.size(), found);
    }
    private record Site(int x, int z, float wetness, float marine, String label) { }
    private record Discovery(String sites, int samples, int wetlandCandidates, JsonArray labels) { }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
