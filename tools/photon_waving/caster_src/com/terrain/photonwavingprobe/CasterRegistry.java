package com.terrain.photonwavingprobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exports the active Iris material-map IDs and a fail-closed shadow-caster
 * classification. Call only after the selected shader pack has loaded.
 */
public final class CasterRegistry {
    private static final int UNASSIGNED_BASE_ID = 10000;
    private static final int CUBE_OFFSET = 12000;
    private static final int STAIRS_SLABS_OFFSET = 16000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BlockPos ORIGIN = BlockPos.ZERO;

    private CasterRegistry() {}

    public static void export(Path output) throws IOException {
        Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (ids == null) {
            throw new IllegalStateException("Iris block-state IDs are unavailable; call after shader-pack load");
        }

        Export document = new Export();
        document.definition = "cube: outline and collision shapes are each a full unit cube at EmptyBlockGetter/BlockPos.ZERO; leaves and LiquidBlock states are excluded. stairs_slabs: remaining StairBlock or SlabBlock states, including waterlogged solid states.";
        document.contextCaveat = "Shapes are sampled without neighbours at BlockPos.ZERO. Dynamic or neighbour-dependent shapes fail closed; resource-pack models are not collision shapes.";
        document.unassignedBaseId = UNASSIGNED_BASE_ID;
        document.cubeOffset = CUBE_OFFSET;
        document.stairsSlabsOffset = STAIRS_SLABS_OFFSET;
        document.irisMappedStates = ids.size();

        Map<Key, List<StateRecord>> grouped = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation name = BuiltInRegistries.BLOCK.getKey(block);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int original = ids.containsKey(state) ? ids.getInt(state) : -1;
                if (original == UNASSIGNED_BASE_ID) {
                    throw new IllegalStateException("Iris assigned reserved block ID 10000: " + name);
                }
                StateRecord record = classify(name, state, original, document.shapeExceptions);
                document.stateCount++;
                if (record.casterClass == CasterClass.NONE) document.rejectedStates++;
                if (record.casterClass == CasterClass.NONE && original < 0) continue;
                if (record.casterClass != CasterClass.NONE) document.acceptedStates++;
                if (record.casterClass == CasterClass.CUBE) document.cubeStates++;
                else if (record.casterClass == CasterClass.STAIRS_SLABS) document.stairsSlabsStates++;
                grouped.computeIfAbsent(new Key(record.baseId, record.casterClass), ignored -> new ArrayList<>()).add(record);
            }
        }

        for (Map.Entry<Key, List<StateRecord>> entry : grouped.entrySet()) {
            Group group = new Group();
            group.baseId = entry.getKey().baseId;
            group.casterClass = entry.getKey().casterClass.label;
            group.encodedId = encodedId(group.baseId, entry.getKey().casterClass);
            group.selectors = compactSelectors(entry.getValue());
            document.groups.add(group);
        }
        document.assertions = assertions(ids, document.shapeExceptions);
        document.metadataSha256 = sha256(JSON.toJson(document));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JSON.toJson(document) + "\n", StandardCharsets.UTF_8);
    }

    /** Validates that Iris applied the exported complete state mapping before a benchmark starts. */
    public static void verifyActive(Path sourceMetadata, String mode, Path report) throws IOException {
        if (!mode.equals("cube") && !mode.equals("cube-stairs-slabs")) {
            throw new IllegalArgumentException("Unknown caster mode: " + mode);
        }
        Export metadata = JSON.fromJson(Files.readString(sourceMetadata, StandardCharsets.UTF_8), Export.class);
        if (metadata == null || metadata.groups == null) throw new IllegalArgumentException("Invalid caster metadata");
        Map<BlockState, Integer> expected = new HashMap<>();
        for (Group group : metadata.groups) {
            int value = expectedRaw(group, mode);
            for (String selector : group.selectors) {
                for (BlockState state : expandSelector(selector)) {
                    Integer previous = expected.putIfAbsent(state, value);
                    if (previous != null && previous != value) {
                        throw new IllegalStateException("Overlapping metadata selector: " + selector);
                    }
                }
            }
        }
        Object2IntMap<BlockState> actualIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        if (actualIds == null) throw new IllegalStateException("Iris block-state IDs unavailable");
        Verification result = new Verification();
        result.mode = mode;
        result.metadata = sourceMetadata.toString();
        result.expectedStates = expected.size();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int expectedRaw = expected.getOrDefault(state, -1);
                int actualRaw = actualIds.containsKey(state) ? actualIds.getInt(state) : -1;
                result.checkedStates++;
                if (expectedRaw != actualRaw) {
                    result.mismatches++;
                    if (result.examples.size() < 20) {
                        Mismatch mismatch = new Mismatch();
                        mismatch.state = new StateRecord(blockId, state, -1).selector();
                        mismatch.expected = expectedRaw;
                        mismatch.actual = actualRaw;
                        result.examples.add(mismatch);
                    }
                }
            }
        }
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, JSON.toJson(result) + "\n", StandardCharsets.UTF_8);
        if (result.mismatches != 0) {
            throw new IllegalStateException("Caster mapping verification failed: " + result.mismatches + " mismatches; see " + report);
        }
    }

    private static int expectedRaw(Group group, String mode) {
        if ("none".equals(group.casterClass)) return group.baseId;
        boolean enabled = "cube".equals(group.casterClass) || mode.equals("cube-stairs-slabs");
        if (enabled) return group.encodedId;
        return group.baseId == UNASSIGNED_BASE_ID ? -1 : group.baseId;
    }

    private static List<BlockState> expandSelector(String selector) {
        String[] parts = selector.split(":");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid selector: " + selector);
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(parts[0] + ":" + parts[1]));
        List<BlockState> states = new ArrayList<>();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            boolean matches = true;
            for (int index = 2; index < parts.length; index++) {
                String[] predicate = parts[index].split("=", 2);
                if (predicate.length != 2) throw new IllegalArgumentException("Invalid selector predicate: " + selector);
                Property<?> property = state.getBlock().getStateDefinition().getProperty(predicate[0]);
                if (property == null || !valueName(state, property).equals(predicate[1])) { matches = false; break; }
            }
            if (matches) states.add(state);
        }
        if (states.isEmpty()) throw new IllegalStateException("Selector matches no current states: " + selector);
        return states;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(BlockState state, Property property) { return property.getName(state.getValue(property)); }

    private static StateRecord classify(ResourceLocation name, BlockState state, int original,
                                        List<ShapeException> exceptions) {
        StateRecord record = new StateRecord(name, state, original);
        if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LiquidBlock) return record;
        try {
            VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, ORIGIN);
            VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, ORIGIN);
            if (Block.isShapeFullBlock(outline) && Block.isShapeFullBlock(collision)) {
                record.casterClass = CasterClass.CUBE;
            } else if (state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock) {
                record.casterClass = CasterClass.STAIRS_SLABS;
            }
        } catch (RuntimeException error) {
            ShapeException exception = new ShapeException();
            exception.selector = record.selector();
            exception.error = error.getClass().getName();
            exceptions.add(exception);
        }
        return record;
    }

    private static List<String> compactSelectors(List<StateRecord> states) {
        Map<String, List<StateRecord>> byBlock = new TreeMap<>();
        for (StateRecord state : states) byBlock.computeIfAbsent(state.block.toString(), ignored -> new ArrayList<>()).add(state);
        List<String> selectors = new ArrayList<>();
        for (Map.Entry<String, List<StateRecord>> entry : byBlock.entrySet()) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(entry.getKey()));
            if (entry.getValue().size() == block.getStateDefinition().getPossibleStates().size()) {
                selectors.add(entry.getKey());
            } else {
                entry.getValue().stream().map(StateRecord::selector).sorted().forEach(selectors::add);
            }
        }
        return selectors;
    }

    private static List<Assertion> assertions(Object2IntMap<BlockState> ids, List<ShapeException> exceptions) {
        List<Assertion> result = new ArrayList<>();
        expect(result, ids, "minecraft:stone", CasterClass.CUBE, exceptions);
        expect(result, ids, "minecraft:glass", CasterClass.CUBE, exceptions);
        expect(result, ids, "minecraft:oak_leaves", CasterClass.NONE, exceptions);
        expectSlabStates(result, ids, exceptions);
        expect(result, ids, "minecraft:oak_stairs", CasterClass.STAIRS_SLABS, exceptions);
        expect(result, ids, "minecraft:oak_fence", CasterClass.NONE, exceptions);
        expect(result, ids, "minecraft:wheat", CasterClass.NONE, exceptions);
        for (Assertion assertion : result) {
            if (!assertion.passed) throw new IllegalStateException("Caster classification assertion failed: " + assertion.block);
        }
        return result;
    }

    private static void expectSlabStates(List<Assertion> assertions, Object2IntMap<BlockState> ids,
                                         List<ShapeException> exceptions) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:oak_slab"));
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            CasterClass expected = state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                ? CasterClass.CUBE : CasterClass.STAIRS_SLABS;
            CasterClass actual = classify(BuiltInRegistries.BLOCK.getKey(block), state,
                ids.containsKey(state) ? ids.getInt(state) : -1, exceptions).casterClass;
            Assertion assertion = new Assertion();
            assertion.block = new StateRecord(BuiltInRegistries.BLOCK.getKey(block), state, -1).selector();
            assertion.expected = expected.label;
            assertion.actual = actual.label;
            assertion.passed = actual == expected;
            assertions.add(assertion);
        }
    }

    private static void expect(List<Assertion> assertions, Object2IntMap<BlockState> ids, String id,
                               CasterClass expected, List<ShapeException> exceptions) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        CasterClass actual = CasterClass.NONE;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            actual = classify(BuiltInRegistries.BLOCK.getKey(block), state,
                ids.containsKey(state) ? ids.getInt(state) : -1, exceptions).casterClass;
            if (actual != expected) break;
        }
        Assertion assertion = new Assertion();
        assertion.block = id;
        assertion.expected = expected.label;
        assertion.actual = actual.label;
        assertion.passed = actual == expected;
        assertions.add(assertion);
    }

    private static int encodedId(int baseId, CasterClass casterClass) {
        if (casterClass == CasterClass.NONE) return baseId;
        if (baseId < UNASSIGNED_BASE_ID || baseId > 10922) throw new IllegalStateException("Unsupported Iris material ID: " + baseId);
        int encoded = baseId + (casterClass == CasterClass.CUBE ? CUBE_OFFSET : STAIRS_SLABS_OFFSET);
        if (encoded > 32767) throw new IllegalStateException("Encoded ID exceeds Iris/OptiFine signed-short range: " + encoded);
        return encoded;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private enum CasterClass {
        NONE("none"), CUBE("cube"), STAIRS_SLABS("stairs_slabs");
        final String label;
        CasterClass(String label) { this.label = label; }
    }

    private record Key(int baseId, CasterClass casterClass) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int ids = Integer.compare(baseId, other.baseId);
            return ids != 0 ? ids : casterClass.compareTo(other.casterClass);
        }
    }

    private static final class StateRecord {
        final ResourceLocation block;
        final BlockState state;
        final int originalId;
        final int baseId;
        CasterClass casterClass = CasterClass.NONE;
        StateRecord(ResourceLocation block, BlockState state, int originalId) {
            this.block = block;
            this.state = state;
            this.originalId = originalId;
            this.baseId = originalId < 0 ? UNASSIGNED_BASE_ID : originalId;
        }
        String selector() {
            StringBuilder result = new StringBuilder(block.toString());
            for (Property<?> property : state.getProperties().stream().sorted(Comparator.comparing(Property::getName)).toList()) {
                result.append(':').append(property.getName()).append('=').append(valueName(state, property));
            }
            return result.toString();
        }
        
    }

    private static final class Export {
        String definition;
        String contextCaveat;
        int unassignedBaseId;
        int cubeOffset;
        int stairsSlabsOffset;
        int irisMappedStates;
        int stateCount;
        int acceptedStates;
        int rejectedStates;
        int cubeStates;
        int stairsSlabsStates;
        List<ShapeException> shapeExceptions = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        List<Assertion> assertions = new ArrayList<>();
        String metadataSha256;
    }
    private static final class Group { int baseId; String casterClass; int encodedId; List<String> selectors; }
    private static final class ShapeException { String selector; String error; }
    private static final class Assertion { String block; String expected; String actual; boolean passed; }
    private static final class Verification { String mode; String metadata; int expectedStates; int checkedStates; int mismatches; List<Mismatch> examples = new ArrayList<>(); }
    private static final class Mismatch { String state; int expected; int actual; }
}
