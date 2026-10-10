package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.xandergos.terraindiffusionmc.world.FastLocateSearch.*;

/** Standalone deterministic tests: no Minecraft registry, server, GPU or native runtime. */
public final class FastLocateSearchTest {
    public static void main(String[] args) throws Exception {
        coordinates(); nativeRegions(); mixedPlacementCoverage(); rankingAndVerification(); cancellationAndDeadline(); limitsAndFailure();
        System.out.println("PASS FastLocateSearchTest: deterministic hints, native region coverage, exact-only results, diverse fallback, negative coordinates, budgets, stale-result cancellation");
    }

    static void coordinates() {
        require(new Point(-1, -257).quart().equals(new Point(-4, -260)), "negative quart rounding");
        require(new Point(-1, -257).tile(256).equals(new Point(-256, -512)), "negative tile rounding");
        Point origin = new Point(-17, 33);
        require(patches(origin, MAX_RADIUS).size() <= PATCH_LIMIT, "bounded coarse batch count");
        require(patches(origin, MAX_RADIUS).containsAll(patches(origin, 2048)), "larger radius preserves nearby coverage");
        require(patches(origin, MAX_RADIUS).contains(new Point(origin.x() + MAX_RADIUS, origin.z())), "outer radius sampled");
        require(patches(origin, DEFAULT_RADIUS).size() == PATCH_LIMIT, "use spare coarse budget to fill angular gaps");
        require(patches(origin, MAX_RADIUS).contains(new Point(origin.x() + MAX_RADIUS, origin.z() + MAX_RADIUS / 2)),
                "outer coverage includes off-axis locations");
        require(patches(origin, MAX_RADIUS).size() == new java.util.HashSet<>(patches(origin, MAX_RADIUS)).size(), "no duplicate patches");
        int remaining = TILE_LIMIT;
        int patchCount = patches(origin, MAX_RADIUS).size();
        for (int first = 0; first < patchCount; first += PATCH_BATCH) {
            int allowance = batchAllowance(remaining, patchCount - first);
            require(allowance > 0 && allowance <= remaining, "far batches retain verification budget");
            remaining -= allowance;
        }
        require(remaining == 0, "all available verification budget may be used");
        require(patches(origin, 16).getFirst().equals(origin), "nearby patch first");
        for (Point point : patches(origin, MAX_RADIUS)) require(inside(point, origin, MAX_RADIUS), "patch radius");
        require(!inside(new Point(-34, 33), origin, 16), "radius rejection");
        require(new Point(-30_000_000, -30_000_000).distanceSquared(new Point(30_000_000, 30_000_000))
                == 7_200_000_000_000_000L, "distance overflow");
    }

    static void nativeRegions() throws Exception {
        Point origin = new Point(1024, 1024);
        var regions = placementRegions(origin, 512, origin, DEFAULT_RADIUS, 34);
        require(regions.size() == 9 && regions.contains(new Point(0, 0)),
                "native regions fill gaps between nine point probes");
        require(regions.equals(placementRegions(origin, 512, origin, DEFAULT_RADIUS, 34)), "stable region order");
        Point missedTarget = new Point(528, 528);
        List<Candidate> regionCandidates = regions.stream().map(region -> {
            Point potentialStart = new Point(region.x() * 34 * 16 + 528, region.z() * 34 * 16 + 528);
            return new Candidate(potentialStart, potentialStart.equals(missedTarget) ? 1 : 0, 0);
        }).toList();
        require(missedTarget.equals(findVerified(ranked(regionCandidates, origin, 16), new Budget(() -> 0, 60),
                candidate -> candidate.point().equals(missedTarget) ? missedTarget : null)),
                "native region plan crosses ranking boundary and reaches exact verifier for a formerly missed start");
        var negative = placementRegions(new Point(-1025, -1025), 512, new Point(-1025, -1025), DEFAULT_RADIUS, 34);
        require(negative.size() == 9 && negative.contains(new Point(-1, -1)), "negative placement region boundary");
        var bounded = placementRegions(origin, 512, origin, MAX_RADIUS, 1);
        require(bounded.size() == REGIONS_PER_PATCH, "dense custom placements cannot grow plan without bound");
        require(new java.util.HashSet<>(bounded).size() == bounded.size(), "unique region coordinates");
        var small = placementRegions(new Point(-1, -1), 512, new Point(-1, -1), 16, 34);
        require(new java.util.HashSet<>(small).equals(java.util.Set.of(new Point(-1, -1), new Point(-1, 0),
                new Point(0, -1), new Point(0, 0))), "patch clipped to small search boundary");

        // Five village variants share a placement. Count the actual planner operations,
        // not timings or inferred chunk-generation savings from a synthetic world.
        var oldRegions = new java.util.HashSet<Point>();
        var newRegions = new java.util.HashSet<Point>();
        int oldCalls = 0;
        for (Point patch : patches(origin, DEFAULT_RADIUS)) {
            for (int dz : new int[]{-256, 0, 256}) for (int dx : new int[]{-256, 0, 256}) {
                Point probe = new Point(patch.x() + dx, patch.z() + dz).quart();
                if (!inside(probe, origin, DEFAULT_RADIUS)) continue;
                oldCalls += 5;
                oldRegions.add(new Point(Math.floorDiv(probe.x(), 34 * 16), Math.floorDiv(probe.z(), 34 * 16)));
            }
            newRegions.addAll(placementRegions(patch, 512, origin, DEFAULT_RADIUS, 34));
        }
        require(newRegions.containsAll(oldRegions), "native enumeration retains all old probed regions");
        require(newRegions.size() > oldRegions.size(), "native enumeration increases spatial coverage");
        require(newRegions.size() < oldCalls && newRegions.size() < REGION_LIMIT, "grouped region work reduces native calls");
        System.out.println("  village planning fixture (same " + PATCH_LIMIT + " patches, five shared variants): calls "
                + oldCalls + " -> " + newRegions.size() + "; distinct regions " + oldRegions.size() + " -> " + newRegions.size());
    }

    static void mixedPlacementCoverage() {
        Point origin = new Point(-1025, -1025);
        for (int radius : new int[]{DEFAULT_RADIUS, MAX_RADIUS}) {
            for (int[] spacings : new int[][]{{1, 34}, {34, 1}}) {
                var patches = patches(origin, radius);
                var seen = List.of(new java.util.HashSet<Point>(), new java.util.HashSet<Point>());
                Budget budget = new Budget(() -> 0, 60);
                int[] outerPatches = new int[2];
                for (int patchIndex = 0; patchIndex < patches.size(); patchIndex++) {
                    Point patch = patches.get(patchIndex);
                    int patchEnd = budget.regions() + regionAllowance(REGION_LIMIT - budget.regions(), patches.size() - patchIndex);
                    for (int group = 0; group < spacings.length; group++) {
                        int allowance = regionAllowance(patchEnd - budget.regions(), spacings.length - group);
                        int admitted = 0;
                        for (Point region : placementRegions(patch, 512, origin, radius, spacings[group])) {
                            if (admitted >= allowance) break;
                            if (!seen.get(group).add(region)) continue;
                            require(budget.takeRegion(), "mixed placements respect total planning limit");
                            admitted++;
                        }
                        if (patchIndex >= patches.size() - PATCH_BATCH) {
                            require(admitted > 0, "every outer patch retains dense and village placement coverage");
                            outerPatches[group]++;
                        }
                    }
                    require(budget.regions() <= patchEnd, "one patch cannot consume future patches' reservation");
                }
                require(budget.regions() <= REGION_LIMIT, "mixed planning remains bounded");
                System.out.println("  mixed spacing " + spacings[0] + "/" + spacings[1] + ", radius=" + radius
                        + ": regions=" + budget.regions() + "/" + REGION_LIMIT + ", outer patches="
                        + outerPatches[0] + "/" + outerPatches[1]);
            }
        }
        require(regionAllowance(0, 2) == 0, "exhausted planning allocation cannot admit work");
    }

    static void rankingAndVerification() throws Exception {
        List<Candidate> input = new ArrayList<>();
        Point origin = new Point(0, 0), target = new Point(1024, 1024);
        for (int z = -10; z <= 10; z++) for (int x = -10; x <= 10; x++) {
            Point p = new Point(x * 256, z * 256);
            int hint = p.equals(target) ? 2 : z == -10 && x >= -10 && x <= -8 ? 3 : 0;
            input.add(new Candidate(p, hint, Math.floorMod(x + z * 5, 25)));
        }
        List<Candidate> order = ranked(input, origin, 256);
        require(order.equals(ranked(input, origin, 256)), "deterministic order");
        require(order.size() == input.size(), "hints do not reject any candidate");
        Budget fast = new Budget(() -> 0, 60);
        Point result = findVerified(order, fast, candidate -> candidate.point().equals(target) ? target : null);
        require(target.equals(result), "only exact positive accepted, false positive hints rejected");
        List<Candidate> baseline = new ArrayList<>(input);
        baseline.sort(Comparator.comparingLong(c -> c.point().distanceSquared(origin)));
        int fullTiles = 0;
        for (Candidate candidate : baseline) { fullTiles++; if (candidate.point().equals(target)) break; }
        require(fast.tiles() < fullTiles, "coarse-to-fine loads fewer exact tiles in controlled fixture");
        System.out.println("  controlled cold fixture: coarse-ranked exact tiles=" + fast.tiles() + ", distance traversal=" + fullTiles);

        List<Candidate> zeros = input.stream().map(c -> new Candidate(c.point(), 0, c.sector())).toList();
        List<Candidate> fallback = ranked(zeros, origin, 256);
        require(fallback.size() == zeros.size(), "no useful hint still retains candidates");
        require(fallback.subList(0, TILE_LIMIT).stream().map(Candidate::sector).distinct().count() >= 16,
                "bounded fallback spans distinct sectors");
        require(ranked(List.of(new Candidate(new Point(-1, -1), 0, 0),
                new Candidate(new Point(-4, -4), 1, 1)), origin, 256).size() == 1, "deduplicate negative tiles");
    }

    static void cancellationAndDeadline() throws Exception {
        var candidate = List.of(new Candidate(new Point(0, 0), 1, 0));
        Budget cancelled = new Budget(() -> 0, 60);
        int[] calls = {0};
        Object stale = findVerified(candidate, cancelled, c -> {
            calls[0]++; cancelled.cancel(); return "native call returned after cancellation";
        });
        require(stale == null && calls[0] == 1, "running verifier drains but cannot publish stale success");
        require(!cancelled.takeTile() && !cancelled.takeStart() && !cancelled.takePatch() && !cancelled.takeRegion(), "cancel blocks new admission");
        require(!Thread.currentThread().isInterrupted(), "cooperative cancel never interrupts native thread");

        AtomicLong clock = new AtomicLong();
        Budget deadline = new Budget(clock::get, 60);
        require(findVerified(candidate, deadline, c -> { clock.set(60); return "late success"; }) == null,
                "deadline suppresses success from late verification");
        require(!deadline.takeStart() && !deadline.takePatch() && !deadline.takeRegion(), "deadline blocks new work");
        Budget fresh = new Budget(() -> 0, 60);
        require(fresh.live(), "cancelled old generation cannot affect independent new job");
    }

    static void limitsAndFailure() throws Exception {
        Budget budget = new Budget(() -> 0, 60);
        for (int n = 0; n < TILE_LIMIT; n++) require(budget.takeTile(), "tile admission");
        require(!budget.takeTile() && budget.tiles() == TILE_LIMIT, "terrain cap");
        for (int n = 0; n < START_LIMIT; n++) require(budget.takeStart(), "start admission");
        require(!budget.takeStart() && budget.starts() == START_LIMIT, "start cap");
        for (int n = 0; n < PATCH_LIMIT; n++) require(budget.takePatch(), "coarse admission");
        require(!budget.takePatch(), "coarse cap");
        for (int n = 0; n < REGION_LIMIT; n++) require(budget.takeRegion(), "placement region admission");
        require(!budget.takeRegion() && budget.regions() == REGION_LIMIT, "placement region cap");
        var candidate = List.of(new Candidate(new Point(0, 0), 999, 0));
        require(findVerified(candidate, new Budget(() -> 0, 60), c -> null) == null,
                "very strong approximate hint never certifies a hit");
        try {
            findVerified(candidate, new Budget(() -> 0, 60), c -> { throw new IllegalStateException("tile unavailable"); });
            throw new AssertionError("failed exact fetch must not become success/absence");
        } catch (IllegalStateException expected) { require(expected.getMessage().equals("tile unavailable"), "failure preserved"); }
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
