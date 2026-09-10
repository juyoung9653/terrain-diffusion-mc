package com.github.xandergos.terraindiffusionmc.compat.streams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure tuple handling for the Streams 2.13.1 structure-shield compatibility hook. */
public final class StructureShieldGeometry {
    public static final int TUPLE_SIZE = 5;

    private StructureShieldGeometry() {
    }

    public record Box(int minX, int minZ, int maxX, int maxZ, int maxY) {
        public int[] paddedTuple(int padding) {
            return new int[] {minX - padding, minZ - padding, maxX + padding, maxZ + padding, maxY};
        }

        public boolean intersects(int minX, int minZ, int maxX, int maxZ) {
            return this.maxX >= minX && this.minX <= maxX && this.maxZ >= minZ && this.minZ <= maxZ;
        }
    }

    /** One original aggregate tuple and the individual structure-piece tuples that replace it. */
    public record Replacement(Box aggregate, List<Box> pieces) {
        public Replacement {
            pieces = List.copyOf(pieces);
        }
    }

    /**
     * Removes precisely one aggregate tuple per replacement and appends its protected pieces.
     * If Streams returned an unexpected tuple array, or an expected aggregate is absent, returns
     * the original array unchanged so the upstream protection remains intact.
     */
    public static int[] replaceAggregates(int[] original, List<Replacement> replacements, int padding) {
        if (original == null || original.length % TUPLE_SIZE != 0 || replacements.isEmpty()) return original;

        Map<Tuple, Integer> required = new HashMap<>();
        List<int[]> pieces = new ArrayList<>();
        for (Replacement replacement : replacements) {
            Tuple aggregate = Tuple.of(replacement.aggregate().paddedTuple(padding));
            required.merge(aggregate, 1, Integer::sum);
            for (Box piece : replacement.pieces()) pieces.add(piece.paddedTuple(padding));
        }

        Map<Tuple, Integer> available = new HashMap<>();
        for (int index = 0; index < original.length; index += TUPLE_SIZE) {
            available.merge(Tuple.at(original, index), 1, Integer::sum);
        }
        for (Map.Entry<Tuple, Integer> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) return original;
        }

        List<int[]> result = new ArrayList<>(original.length / TUPLE_SIZE + pieces.size());
        Map<Tuple, Integer> remaining = new HashMap<>(required);
        for (int index = 0; index < original.length; index += TUPLE_SIZE) {
            Tuple tuple = Tuple.at(original, index);
            int count = remaining.getOrDefault(tuple, 0);
            if (count == 0) {
                result.add(tuple.toArray());
            } else if (count == 1) {
                remaining.remove(tuple);
            } else {
                remaining.put(tuple, count - 1);
            }
        }
        result.addAll(pieces);
        int[] flattened = new int[result.size() * TUPLE_SIZE];
        for (int index = 0; index < result.size(); index++) {
            System.arraycopy(result.get(index), 0, flattened, index * TUPLE_SIZE, TUPLE_SIZE);
        }
        return flattened;
    }

    private record Tuple(int minX, int minZ, int maxX, int maxZ, int maxY) {
        static Tuple at(int[] values, int offset) {
            return new Tuple(values[offset], values[offset + 1], values[offset + 2], values[offset + 3], values[offset + 4]);
        }

        static Tuple of(int[] values) {
            return at(values, 0);
        }

        int[] toArray() {
            return new int[] {minX, minZ, maxX, maxZ, maxY};
        }
    }
}
