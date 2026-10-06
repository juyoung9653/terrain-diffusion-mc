package com.github.xandergos.terraindiffusionmc.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.xandergos.terraindiffusionmc.world.FastLocateSearch.*;

/** Standalone deterministic tests: no Minecraft registry, server, GPU or native runtime. */
public final class FastLocateSearchTest {
    public static void main(String[] args) throws Exception {
        coordinates(); rankingAndVerification(); cancellationAndDeadline(); limitsAndFailure();
        System.out.println("PASS FastLocateSearchTest: deterministic hints, exact-only results, diverse fallback, negative coordinates, budgets, stale-result cancellation");
    }

    static void coordinates() {
        require(new Point(-1, -257).quart().equals(new Point(-4, -260)), "negative quart rounding");
        require(new Point(-1, -257).tile(256).equals(new Point(-256, -512)), "negative tile rounding");
        Point origin = new Point(-17, 33);
        require(patches(origin, MAX_RADIUS).size() == 25, "bounded coarse batch count");
        require(patches(origin, 16).getFirst().equals(origin), "nearby patch first");
        for (Point point : patches(origin, MAX_RADIUS)) require(inside(point, origin, MAX_RADIUS), "patch radius");
        require(!inside(new Point(-34, 33), origin, 16), "radius rejection");
        require(new Point(-30_000_000, -30_000_000).distanceSquared(new Point(30_000_000, 30_000_000))
                == 7_200_000_000_000_000L, "distance overflow");
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
        require(!cancelled.takeTile() && !cancelled.takeStart() && !cancelled.takePatch(), "cancel blocks new admission");
        require(!Thread.currentThread().isInterrupted(), "cooperative cancel never interrupts native thread");

        AtomicLong clock = new AtomicLong();
        Budget deadline = new Budget(clock::get, 60);
        require(findVerified(candidate, deadline, c -> { clock.set(60); return "late success"; }) == null,
                "deadline suppresses success from late verification");
        require(!deadline.takeStart() && !deadline.takePatch(), "deadline blocks new work");
        Budget fresh = new Budget(() -> 0, 60);
        require(fresh.live(), "cancelled old generation cannot affect independent new job");
    }

    static void limitsAndFailure() throws Exception {
        Budget budget = new Budget(() -> 0, 60);
        for (int n = 0; n < TILE_LIMIT; n++) require(budget.takeTile(), "tile admission");
        require(!budget.takeTile() && budget.tiles() == TILE_LIMIT, "terrain cap");
        for (int n = 0; n < START_LIMIT; n++) require(budget.takeStart(), "start admission");
        require(!budget.takeStart() && budget.starts() == START_LIMIT, "start cap");
        for (int n = 0; n < 25; n++) require(budget.takePatch(), "coarse admission");
        require(!budget.takePatch(), "coarse cap");
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
