package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.Set;

/** Server-owned rules: terrain is world-pinned; distance/density refresh at world load. */
public final class StructurePlacementConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Object, Snapshot> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    public static final String FILE_NAME = "terrain-diffusion-structures.json";
    public static final String WORLD_FILE_NAME = "td-structure-placement.json";
    /** Vanilla registers one generic and six biome-specific ruined-portal structures. */
    private static final Set<String> RUINED_PORTAL_IDS = Set.of(
            "minecraft:ruined_portal", "minecraft:ruined_portal_desert",
            "minecraft:ruined_portal_jungle", "minecraft:ruined_portal_mountain",
            "minecraft:ruined_portal_nether", "minecraft:ruined_portal_ocean",
            "minecraft:ruined_portal_swamp");
    private static final double RUINED_PORTAL_SPACING_MULTIPLIER = 2.0;

    private StructurePlacementConfig() { }

    public enum SurfaceMode { AUTO, SURFACE, PRESERVE }

    public record VillageDistribution(boolean enabled, int targetMeanNearestNeighborBlocks,
                                      int basicSharePercent, int algorithmVersion) { }

    public static final class VillageSettings {
        public boolean enabled = false;
        public int targetMeanNearestNeighborBlocks = 500;
        public int basicSharePercent = 70;
        public int algorithmVersion = 2;
    }

    public record Policy(int minGapBlocks, int reservationRadiusBlocks, int maxCandidateOffsetBlocks,
                         int maxCutBlocks, int maxFillBlocks, int blendRadiusBlocks,
                         int maxTerrainSamples, int maxCandidateAttempts, double spacingMultiplier,
                         SurfaceMode surfaceMode) { }

    /** Nullable fields deliberately mean inherit, rather than silently restoring defaults. */
    public static final class Override {
        public Integer minGapBlocks;
        public Integer reservationRadiusBlocks;
        public Integer maxCandidateOffsetBlocks;
        public Integer maxCutBlocks;
        public Integer maxFillBlocks;
        public Integer blendRadiusBlocks;
        public Integer maxTerrainSamples;
        public Integer maxCandidateAttempts;
        public Double spacingMultiplier;
        public SurfaceMode surfaceMode;
    }

    public static final class Document {
        public int version = 1;
        public boolean enabled = true;
        public Override global = new Override();
        public Map<String, Override> namespaces = new TreeMap<>();
        public Map<String, Override> structures = new TreeMap<>();
        public VillageSettings villageDistribution = new VillageSettings();
    }

    public static final class Snapshot {
        private final boolean enabled;
        private final Policy global;
        private final Map<String, Policy> namespaces;
        private final Map<String, Policy> structures;
        private final String serialized;
        private final VillageDistribution villages;

        private Snapshot(boolean enabled, Policy global, Map<String, Policy> namespaces,
                         Map<String, Policy> structures, VillageDistribution villages, String serialized) {
            this.enabled = enabled;
            this.global = global;
            this.namespaces = Map.copyOf(namespaces);
            this.structures = Map.copyOf(structures);
            this.serialized = serialized;
            this.villages = villages;
        }

        public boolean enabled() { return enabled; }
        public VillageDistribution villages() { return villages; }
        public Policy global() { return global; }
        public Map<String, Policy> namespaces() { return namespaces; }
        public Map<String, Policy> structures() { return structures; }
        public Policy resolve(String id) {
            Policy exact = structures.get(id);
            if (exact != null) return exact;
            int separator = id.indexOf(':');
            return namespaces.getOrDefault(separator < 0 ? id : id.substring(0, separator), global);
        }
        public Document document() { return JSON.fromJson(serialized, Document.class); }
    }

    public static Policy defaults() {
        return new Policy(100, 112, 7, 3, 4, 12, 65536, 9, 1.0, SurfaceMode.AUTO);
    }

    public static Path configPath() { return PlatformPaths.configDir().resolve(FILE_NAME); }

    public static Document readDocument() throws IOException {
        Path path = configPath();
        if (!Files.exists(path)) return new Document();
        return read(path);
    }

    public static synchronized void save(Document document) throws IOException {
        Snapshot checked = validate(document);
        atomicWrite(configPath(), checked.serialized);
    }

    public static Snapshot snapshot() {
        try {
            return validate(readDocument());
        } catch (IOException | IllegalArgumentException e) {
            // A malformed rule must never silently disable distance protection.
            throw new IllegalStateException("Invalid structure placement config: " + configPath(), e);
        }
    }

    /** Bind before ServerLevels construct their generator structure states. */
    public static synchronized void bindWorld(Object registryIdentity, Path worldRoot) {
        Path path = worldRoot.resolve("data").resolve(WORLD_FILE_NAME);
        try {
            Snapshot current = snapshot();
            Snapshot pinned;
            if (Files.exists(path)) {
                pinned = validate(read(path));
            } else {
                pinned = current;
                atomicWrite(path, JSON.toJson(frozenDocument(pinned)));
                // Readback also ensures the on-disk policy is usable on the next restart.
                pinned = validate(read(path));
            }
            WORLDS.put(registryIdentity, withCurrentDistances(pinned, current));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot pin structure placement rules at " + path, e);
        }
    }

    public static Snapshot snapshotFor(Object registryIdentity) {
        Snapshot result = WORLDS.get(registryIdentity);
        if (result == null) {
            throw new IllegalStateException("TD structure rules were not bound before world generation");
        }
        return result;
    }

    /** Resolves constructor-time state to its already-bound registry without using mutable disk defaults. */
    public static Snapshot snapshotMatching(java.util.function.Predicate<Object> registryMatch) {
        Map<Object,Snapshot> bound;
        synchronized (WORLDS) { bound = Map.copyOf(WORLDS); }
        Snapshot result = null;
        for (var entry : bound.entrySet()) if (registryMatch.test(entry.getKey())) {
            if (result != null && result != entry.getValue())
                throw new IllegalStateException("Ambiguous TD structure registry during generator initialization");
            result = entry.getValue();
        }
        if (result == null) throw new IllegalStateException("TD structure registry was not bound before generator initialization");
        return result;
    }

    public static void unbindWorld(Object registryIdentity) { WORLDS.remove(registryIdentity); }

    private static Snapshot withCurrentDistances(Snapshot pinned, Snapshot current) {
        Document effective = frozenDocument(pinned);
        effective.villageDistribution = current.document().villageDistribution;
        effective.global = distanceOverride(pinned.global, current.global);
        var namespaces = new java.util.TreeSet<>(pinned.namespaces.keySet());
        namespaces.addAll(current.namespaces.keySet());
        for (String namespace : namespaces) effective.namespaces.put(namespace, distanceOverride(
                pinned.namespaces.getOrDefault(namespace, pinned.global),
                current.namespaces.getOrDefault(namespace, current.global)));
        var structures = new java.util.TreeSet<>(pinned.structures.keySet());
        structures.addAll(current.structures.keySet());
        for (String id : structures) effective.structures.put(id, distanceOverride(pinned.resolve(id), current.resolve(id)));
        return validate(effective);
    }

    private static Override distanceOverride(Policy terrain, Policy distance) {
        Override result = explicit(terrain);
        result.minGapBlocks = distance.minGapBlocks;
        result.spacingMultiplier = distance.spacingMultiplier;
        return result;
    }

    private static Document frozenDocument(Snapshot snapshot) {
        Document result = new Document();
        result.enabled = snapshot.enabled;
        result.villageDistribution = snapshot.document().villageDistribution;
        result.global = explicit(snapshot.global);
        snapshot.namespaces.forEach((id, policy) -> result.namespaces.put(id, explicit(policy)));
        snapshot.structures.forEach((id, policy) -> result.structures.put(id, explicit(policy)));
        return result;
    }

    private static Override explicit(Policy p) {
        Override result = new Override();
        result.minGapBlocks = p.minGapBlocks;
        result.reservationRadiusBlocks = p.reservationRadiusBlocks;
        result.maxCandidateOffsetBlocks = p.maxCandidateOffsetBlocks;
        result.maxCutBlocks = p.maxCutBlocks;
        result.maxFillBlocks = p.maxFillBlocks;
        result.blendRadiusBlocks = p.blendRadiusBlocks;
        result.maxTerrainSamples = p.maxTerrainSamples;
        result.maxCandidateAttempts = p.maxCandidateAttempts;
        result.spacingMultiplier = p.spacingMultiplier;
        result.surfaceMode = p.surfaceMode;
        return result;
    }

    public static Snapshot validate(Document document) {
        if (document == null || document.version != 1) throw new IllegalArgumentException("Unsupported structure config version");
        if (document.global == null || document.namespaces == null || document.structures == null)
            throw new IllegalArgumentException("global, namespaces and structures must be objects");
        Document effective = withRuinedPortalDefaults(document);
        Policy global = merge(defaults(), effective.global);
        VillageSettings v = effective.villageDistribution;
        if (v == null) throw new IllegalArgumentException("villageDistribution must be an object");
        range("villageDistribution", "targetMeanNearestNeighborBlocks", v.targetMeanNearestNeighborBlocks, 256, 4096);
        range("villageDistribution", "basicSharePercent", v.basicSharePercent, 0, 100);
        if (v.algorithmVersion < 1 || v.algorithmVersion > 2) throw new IllegalArgumentException("Unsupported village distribution algorithm version");
        VillageDistribution villages = new VillageDistribution(v.enabled, v.targetMeanNearestNeighborBlocks,
                v.basicSharePercent, v.algorithmVersion);
        validatePolicy("global", global);
        Map<String, Policy> namespaces = new TreeMap<>();
        for (var entry : effective.namespaces.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid mod namespace: " + entry.getKey());
            Policy value = merge(global, entry.getValue());
            validatePolicy(entry.getKey(), value);
            namespaces.put(entry.getKey(), value);
        }
        Map<String, Policy> structures = new TreeMap<>();
        for (var entry : effective.structures.entrySet()) {
            String id = entry.getKey();
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid structure ID: " + id);
            Policy parent = namespaces.getOrDefault(id.substring(0, id.indexOf(':')), global);
            Policy value = merge(parent, entry.getValue());
            validatePolicy(id, value);
            structures.put(id, value);
        }
        return new Snapshot(effective.enabled, global, namespaces, structures, villages, JSON.toJson(effective));
    }

    /**
     * Makes the portal-distance default explicit for new and older config files.
     * A user-supplied portal override always wins, including an explicit 1.0.
     */
    private static Document withRuinedPortalDefaults(Document document) {
        for (var entry : document.structures.entrySet())
            if (entry.getValue() == null) throw new IllegalArgumentException("Override must be an object: " + entry.getKey());
        for (var entry : document.namespaces.entrySet())
            if (entry.getValue() == null) throw new IllegalArgumentException("Override must be an object: " + entry.getKey());
        Document result = JSON.fromJson(JSON.toJson(document), Document.class);
        for (String id : RUINED_PORTAL_IDS) {
            Override override = result.structures.get(id);
            if (override == null) {
                override = new Override();
                result.structures.put(id, override);
            }
            if (override.spacingMultiplier == null)
                override.spacingMultiplier = RUINED_PORTAL_SPACING_MULTIPLIER;
        }
        return result;
    }

    private static Policy merge(Policy p, Override o) {
        if (o == null) throw new IllegalArgumentException("Override must be an object");
        return new Policy(value(o.minGapBlocks, p.minGapBlocks), value(o.reservationRadiusBlocks, p.reservationRadiusBlocks),
                value(o.maxCandidateOffsetBlocks, p.maxCandidateOffsetBlocks), value(o.maxCutBlocks, p.maxCutBlocks),
                value(o.maxFillBlocks, p.maxFillBlocks), value(o.blendRadiusBlocks, p.blendRadiusBlocks),
                value(o.maxTerrainSamples, p.maxTerrainSamples), value(o.maxCandidateAttempts, p.maxCandidateAttempts),
                o.spacingMultiplier == null ? p.spacingMultiplier : o.spacingMultiplier,
                o.surfaceMode == null ? p.surfaceMode : o.surfaceMode);
    }

    private static int value(Integer v, int fallback) { return v == null ? fallback : v; }

    private static void validatePolicy(String id, Policy p) {
        range(id, "minGapBlocks", p.minGapBlocks, 100, 1024);
        range(id, "reservationRadiusBlocks", p.reservationRadiusBlocks, 8, 512);
        range(id, "maxCandidateOffsetBlocks", p.maxCandidateOffsetBlocks, 0, 7);
        range(id, "maxCutBlocks", p.maxCutBlocks, 0, 8);
        range(id, "maxFillBlocks", p.maxFillBlocks, 0, 8);
        range(id, "blendRadiusBlocks", p.blendRadiusBlocks, 0, 32);
        range(id, "maxTerrainSamples", p.maxTerrainSamples, 64, 65536);
        range(id, "maxCandidateAttempts", p.maxCandidateAttempts, 1, 9);
        if (!Double.isFinite(p.spacingMultiplier) || p.spacingMultiplier < 1 || p.spacingMultiplier > 8)
            throw new IllegalArgumentException(id + ": spacingMultiplier must be between 1 and 8");
        if (p.surfaceMode == null) throw new IllegalArgumentException(id + ": surfaceMode is required");
    }

    private static void range(String id, String name, int v, int low, int high) {
        if (v < low || v > high) throw new IllegalArgumentException(id + ": " + name + " must be " + low + ".." + high);
    }

    private static Document read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement tree = JsonParser.parseReader(reader);
            if (!tree.isJsonObject()) throw new IllegalArgumentException("Config must be an object");
            JsonObject root = tree.getAsJsonObject();
            keys(root, Set.of("version", "enabled", "global", "namespaces", "structures", "villageDistribution"), "config");
            if (root.has("villageDistribution")) {
                JsonElement element = root.get("villageDistribution");
                if (!element.isJsonObject()) throw new IllegalArgumentException("villageDistribution must be an object");
                JsonObject village = element.getAsJsonObject();
                keys(village, Set.of("enabled", "targetMeanNearestNeighborBlocks", "basicSharePercent", "algorithmVersion"), "villageDistribution");
                for (var entry : village.entrySet()) {
                    var value = entry.getValue();
                    if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid village setting " + entry.getKey());
                    if (entry.getKey().equals("enabled")) {
                        if (!value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("villageDistribution.enabled must be boolean");
                    } else {
                        if (!value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Village settings must be integers");
                        try { value.getAsBigDecimal().intValueExact(); }
                        catch (ArithmeticException error) { throw new IllegalArgumentException("Village settings must be integers", error); }
                    }
                }
            }
            if (root.has("global")) checkOverride(root.get("global"), "global");
            for (String scope : new String[]{"namespaces", "structures"}) {
                if (!root.has(scope)) continue;
                if (!root.get(scope).isJsonObject()) throw new IllegalArgumentException(scope + " must be an object");
                root.getAsJsonObject(scope).entrySet().forEach(e -> checkOverride(e.getValue(), e.getKey()));
            }
            Document result = JSON.fromJson(tree, Document.class);
            validate(result);
            return withRuinedPortalDefaults(result);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid structure placement JSON: " + path + ": " + e.getMessage(), e);
        }
    }

    private static void keys(JsonObject object, Set<String> allowed, String scope) {
        for (String key : object.keySet()) if (!allowed.contains(key))
            throw new IllegalArgumentException(scope + ": unknown setting " + key);
    }

    private static void checkOverride(JsonElement element, String scope) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(scope + " must be an object");
        JsonObject object = element.getAsJsonObject();
        keys(object, Set.of("minGapBlocks", "reservationRadiusBlocks", "maxCandidateOffsetBlocks", "maxCutBlocks",
                "maxFillBlocks", "blendRadiusBlocks", "maxTerrainSamples", "maxCandidateAttempts", "spacingMultiplier", "surfaceMode"), scope);
        for (var entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonNull()) continue;
            if (entry.getKey().equals("surfaceMode")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException(scope + ": surfaceMode must be AUTO, SURFACE or PRESERVE");
                SurfaceMode.valueOf(value.getAsString());
            } else {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException(scope + ": " + entry.getKey() + " must be a number");
                if (!entry.getKey().equals("spacingMultiplier")) {
                    try { value.getAsBigDecimal().intValueExact(); }
                    catch (ArithmeticException e) { throw new IllegalArgumentException(scope + ": " + entry.getKey() + " must be an integer"); }
                }
            }
        }
    }

    private static void atomicWrite(Path path, String text) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "td-structures-", ".tmp");
        try {
            Files.writeString(temp, text + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
