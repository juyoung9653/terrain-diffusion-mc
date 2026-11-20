package com.terrain.chunkprofile;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Pure resolver checks: executes BiomeVisualReview's real private resolver through reflection. */
public final class BiomeVisualReviewResolverTest {
    private static final Class<?> REVIEW = BiomeVisualReview.class;
    private static final Class<?> COLUMN = nested("Column"), SUPPORT = nested("LogSupport");
    private static final Class<?> WINDOW = nested("Window"), REQUEST = nested("InspectionRequest"), PLAN = nested("Plan");

    public static void main(String[] args) throws Exception {
        List<Object> windows = List.of(window("w", 0, 31, 0, 31));
        Object requested = column(10, 10, 4, 0, false, false, null); // no tree at request
        Object canopyBranch = column(11, 10, 4, 8, false, false, null); // log floats above terrain
        Object grounded = column(12, 10, 4, 5, false, false, null);
        Object farGrounded = column(15, 10, 4, 5, false, false, null);
        Object root = resolve(request("root", "root", 10, 10), windows,
                List.of(requested, canopyBranch, grounded, farGrounded));
        equal(2, value(root, "candidateCount"), "root candidate count ignores branch");
        equal(12, value(value(root, "resolved"), "x"), "nearest grounded trunk chosen");

        Object absent = resolve(request("absent", "root", 1, 1), windows, List.of(column(1, 1, 4, 0, false, false, null)));
        equal(0, value(absent, "candidateCount"), "absent root has no target");
        equal(1, value(value(absent, "resolved"), "x"), "absent root keeps requested point");
        check(((String) value(absent, "reason")).contains("no-target"), "absent root is explicit");

        Object crest = column(23, 20, 11, 0, false, false, null);
        Object toe = column(20, 20, 7, 0, false, false, null);
        Object bank = resolve(request("bank", "bank", 20, 20), windows,
                List.of(toe, column(21, 20, 8, 0, false, false, null), column(22, 20, 9, 0, false, false, null), crest));
        equal(2, ((List<?>) value(bank, "endpoints")).size(), "bank records crest and toe");

        Object shallow = column(5, 5, 3, 0, true, false, null, 1);
        Object covered = column(6, 5, 3, 0, true, true, "minecraft:muddy_mangrove_roots", 4);
        Object deep = column(7, 5, 3, 0, true, false, null, 2);
        Object outside = column(32, 5, 3, 0, true, false, null, 5);
        Object underwater = resolve(request("water", "underwater", 5, 5), windows, List.of(shallow, covered, deep, outside));
        equal(1, value(underwater, "candidateCount"), "only exposed deep in-window water qualifies");
        equal(7, value(value(underwater, "resolved"), "x"), "deep exposed water selected");

        Object edgeRequest = column(30, 2, 4, 0, false, false, null);
        Object outsideGrounded = column(32, 2, 4, 5, false, false, null);
        Object edge = resolve(request("edge", "root", 30, 2), windows, List.of(edgeRequest, outsideGrounded));
        equal(0, value(edge, "candidateCount"), "outside-window grounded log is not selected");
        check(BiomeVisualReview.underwaterEyes(40, 60, 0).contains(41.2), "deep water includes a near-bed eye");
        check(BiomeVisualReview.underwaterEyes(40, 41, 0).stream().allMatch(y -> y > 41 && y < 42), "shallow candidates remain in water");
        List<Double> bedFirst = BiomeVisualReview.underwaterEyes(40, 60, 0);
        List<Double> waterlineFirst = BiomeVisualReview.waterlineEyes(40, 60, 0);
        equal(bedFirst.get(bedFirst.size() - 1), waterlineFirst.get(0), "waterline camera starts at upper water eye");

        BlockPos rootStart = new BlockPos(0, 5, 0), trunk = new BlockPos(0, 7, 0);
        Set<BlockPos> connected = Set.of(rootStart, new BlockPos(0, 6, 0), trunk);
        Object complete = BiomeVisualReview.boundedRootPath(rootStart, connected::contains, trunk::equals, pos -> true);
        check((Boolean) value(complete, "complete"), "root-to-log path completes");
        equal(3, ((List<?>) value(complete, "path")).size(), "root path retains support-to-trunk blocks");
        Object broken = BiomeVisualReview.boundedRootPath(rootStart, Set.of(rootStart, new BlockPos(0, 6, 0))::contains, trunk::equals, pos -> true);
        check(!(Boolean) value(broken, "complete"), "broken root remains an incomplete candidate");
        equal(2, ((List<?>) value(broken, "path")).size(), "broken root retains its observed root leg");
        Object radius = BiomeVisualReview.boundedRootPath(rootStart, connected::contains, trunk::equals, pos -> pos.getY() <= 6);
        check(!(Boolean) value(radius, "complete"), "bounded root traversal cannot escape its allowed radius");
        check(!BiomeVisualReview.rootGroundContact("not-substrate"), "water, leaves, plants and roots cannot ground a root");
        check(BiomeVisualReview.rootGroundContact("known-natural"), "natural bed grounds a root");
        Set<BlockPos> longRoot = new HashSet<>(); for (int x = 0; x < 2050; x++) longRoot.add(new BlockPos(x, 5, 0));
        Object capped = BiomeVisualReview.boundedRootPath(rootStart, longRoot::contains, new BlockPos(3000, 5, 0)::equals, pos -> true);
        check((Boolean) value(capped, "capped"), "root traversal records its 2048-node bound");
        double[][] perpendicular = BiomeVisualReview.sideDirections(List.of(new Vec3(0, 5, 0), new Vec3(6, 5, 2)));
        check(Math.abs(perpendicular[0][0] * 6 + perpendicular[0][1] * 2) < .0001, "side camera uses geometric perpendicular to non-45-degree path");
        equal(10, perpendicular.length, "side camera retains both perpendicular views and all eight compass fallbacks");
        Vec3 target = new Vec3(.5, .5, .5);
        check(BiomeVisualReview.intendedTargetVisible(new BlockHitResult(new Vec3(.5, .5, 0), Direction.NORTH, BlockPos.ZERO, false), target), "target block face is visible");
        check(!BiomeVisualReview.intendedTargetVisible(new BlockHitResult(new Vec3(.5, .5, 2), Direction.NORTH, new BlockPos(0, 0, 2), false), target), "blocked partial target is visible=false");
        Set<net.minecraft.world.level.ChunkPos> requestTickets = new HashSet<>(BiomeVisualReview.rootRequestTickets(15, -1));
        for (net.minecraft.world.level.ChunkPos chunk : List.of(new net.minecraft.world.level.ChunkPos(3 >> 4, -13 >> 4), new net.minecraft.world.level.ChunkPos((3 >> 4) - 1, (-13 >> 4) - 1), new net.minecraft.world.level.ChunkPos((3 >> 4) + 1, (-13 >> 4) + 1)))
            check(requestTickets.contains(chunk), "5x5 request tickets cover shifted root anchor camera neighborhood");
        Object unscopedPlan = parsePlan(false);
        equal(2, ((List<?>) value(unscopedPlan, "explicit")).size(), "unscoped plan retains all fixed sites");
        equal(2, ((List<?>) value(unscopedPlan, "inspectionRequests")).size(), "unscoped plan retains all inspections");
        Object scopedPlan = parsePlan(true);
        equal(1, ((List<?>) value(scopedPlan, "explicit")).size(), "scoped plan filters uncited fixed site");
        equal(1, ((List<?>) value(scopedPlan, "inspectionRequests")).size(), "scoped plan filters uncited inspection");
        Method choose = REVIEW.getDeclaredMethod("choose", PLAN, List.class);
        choose.setAccessible(true);
        List<Object> interior = List.of(column(1, 1, 4, 0, false, false, null),
                                        column(2, 2, 4, 0, false, false, null));
        Object scopedSelection = choose.invoke(null, scopedPlan, interior);
        equal(1, ((List<?>) value(scopedSelection, "sites")).size(), "only the fixed report needs a standard scene");
        equal(List.of(), value(scopedSelection, "gaps"), "a scoped site need not be near a biome boundary");
        Object survey = choose.invoke(null, unscopedPlan, interior);
        check(!((List<?>) value(survey, "gaps")).isEmpty(), "the full survey still requires a boundary");
        Method skipCapture = REVIEW.getDeclaredMethod("skipCapture", nested("Selection"));
        skipCapture.setAccessible(true);
        check((Boolean) skipCapture.invoke(null, survey), "a census coverage gap skips camera capture");
        check(!(Boolean) skipCapture.invoke(null, scopedSelection),
                "a complete scoped census still captures its required site");
        for (String argument : args) verifyArchivedPlan(argument);
        System.out.println("biome visual resolver: PASS");
    }

    private static Object resolve(Object request, List<Object> windows, List<Object> columns) throws Exception {
        Method method = REVIEW.getDeclaredMethod("resolveInspection", REQUEST, List.class, List.class);
        method.setAccessible(true); return method.invoke(null, request, windows, columns);
    }
    private static Object parsePlan(boolean scoped) throws Exception {
        Map<String, Object> raw = new HashMap<>();
        raw.put("schema", 1); raw.put("seed", 7L); raw.put("scale", 1);
        raw.put("sourceFingerprint", "source"); raw.put("artifactSha256", "artifact");
        raw.put("requiredBiomes", List.of("test:biome"));
        raw.put("windows", List.of(Map.of("id", "w", "minX", 0, "maxX", 15, "minZ", 0, "maxZ", 15)));
        raw.put("regressionSites", List.of(Map.of("id", "pinned", "x", 1, "z", 1)));
        raw.put("explicitSites", List.of(Map.of("id", "uncited", "x", 2, "z", 2)));
        raw.put("inspectionRequests", List.of(Map.of("id", "inspection-pinned", "x", 3, "z", 3, "kind", "root"),
                                               Map.of("id", "inspection-uncited", "x", 4, "z", 4, "kind", "root")));
        if (scoped) raw.put("reviewScope", Map.of("criteria", List.of(Map.of("siteIds", List.of("pinned", "inspection-pinned")))));
        Method parse = PLAN.getDeclaredMethod("parse", Map.class, long.class);
        parse.setAccessible(true); return parse.invoke(null, raw, 7L);
    }
    @SuppressWarnings("unchecked")
    private static void verifyArchivedPlan(String argument) throws Exception {
        String[] pieces = argument.split("=", 2);
        if (pieces.length != 2) throw new IllegalArgumentException("archived plan argument must be PATH=COUNT");
        Map<String, Object> raw = new Gson().fromJson(JsonParser.parseString(Files.readString(Path.of(pieces[0]))), Map.class);
        Method parse = PLAN.getDeclaredMethod("parse", Map.class, long.class);
        parse.setAccessible(true);
        Object plan = parse.invoke(null, raw, ((Number) raw.get("seed")).longValue());
        int expected = Integer.parseInt(pieces[1]);
        int selected = ((List<?>) value(plan, "explicit")).size() + ((List<?>) value(plan, "inspectionRequests")).size();
        equal(expected, selected, "archived scoped plan filters to frozen site IDs: " + pieces[0]);
    }
    private static Object request(String id, String kind, int x, int z) throws Exception {
        return constructor(REQUEST, String.class, int.class, int.class, String.class, String.class)
                .newInstance(id, x, z, kind, "bed");
    }
    private static Object window(String id, int minX, int maxX, int minZ, int maxZ) throws Exception {
        return constructor(WINDOW, String.class, int.class, int.class, int.class, int.class).newInstance(id, minX, maxX, minZ, maxZ);
    }
    private static Object column(int x, int z, int groundY, int logY, boolean exposed, boolean covered, String cover) throws Exception {
        return column(x, z, groundY, logY, exposed, covered, cover, 0);
    }
    private static Object column(int x, int z, int groundY, int logY, boolean exposed, boolean covered, String cover, int waterDepth) throws Exception {
        Object support = constructor(SUPPORT, Integer.class, String.class, String.class, boolean.class)
                .newInstance(logY == 0 ? null : logY, logY == 0 ? null : "minecraft:oak_log", "minecraft:mud", false);
        return constructor(COLUMN, int.class, int.class, String.class, int.class, int.class, int.class, String.class, String.class,
                boolean.class, boolean.class, String.class, SUPPORT, int.class, boolean.class, boolean.class)
                .newInstance(x, z, "test:biome", groundY, groundY, waterDepth, "minecraft:mud", "known-natural", exposed,
                        covered, cover, support, 0, false, false);
    }
    private static Object value(Object value, String name) throws Exception { return value.getClass().getDeclaredMethod(name).invoke(value); }
    private static Constructor<?> constructor(Class<?> type, Class<?>... types) throws Exception { Constructor<?> c = type.getDeclaredConstructor(types); c.setAccessible(true); return c; }
    private static Class<?> nested(String name) { try { return Class.forName(REVIEW.getName() + "$" + name); } catch (ClassNotFoundException e) { throw new AssertionError(e); } }
    private static void equal(Object expected, Object actual, String message) { if (!expected.equals(actual)) throw new AssertionError(message + ": " + actual); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
