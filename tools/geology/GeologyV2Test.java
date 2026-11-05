import com.github.xandergos.terraindiffusionmc.world.geology.GeologyModel;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Contract checks for the persisted V2 regional geology profile. */
public final class GeologyV2Test {
    private static final long SEED = 91_357L;
    private static final int[] FLOORS = {-400, -736, -1072, -1408, -1744, -1968};

    public static void main(String[] args) throws Exception {
        legacyDigest();
        deterministicAndSeamSafe();
        honorsRawBoundsAndScale();
        hasRegionalRocksAndMixedAquifers();
        rejectsUnknownProfiles();
        System.out.println("GeologyV2Test: PASS");
    }

    /** Compared by the launcher against the pinned pre-V2 fingerprint. */
    private static void legacyDigest() throws Exception {
        GeologyModel legacy = new GeologyModel(SEED, -400, 1);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int x = -384; x <= 384; x += 31) for (int z = -320; z <= 320; z += 29) {
            var column = legacy.column(x, z, 319);
            for (int y = -391; y <= 303; y += 7) {
                digest.update((byte) column.rock(y));
                digest.update((byte) column.material(y));
                digest.update((byte) column.cavity(y));
            }
        }
        System.out.printf("legacy-sha256=%032x%n", new BigInteger(1, digest.digest()));
    }

    private static void deterministicAndSeamSafe() {
        List<String> forward = sample(new GeologyModel(SEED, -400, 1, 2), false);
        List<String> reverse = sample(new GeologyModel(SEED, -400, 1, 2), true);
        require(forward.equals(reverse), "V2 changed with traversal order");
        require(forward.equals(sample(new GeologyModel(SEED, -400, 1, 2), false)), "V2 changed between instances");
        require(!forward.equals(sample(new GeologyModel(SEED + 1, -400, 1, 2), false)), "V2 ignores seed");
    }

    private static List<String> sample(GeologyModel model, boolean reverse) {
        var result = new ArrayList<String>();
        for (int i = reverse ? 48 : -48; reverse ? i >= -48 : i <= 48; i += reverse ? -1 : 1) {
            int x = i * 16 + (i & 3), z = -i * 13 + 7;
            var c = model.column(x, z, 340);
            StringBuilder row = new StringBuilder();
            for (int y = -391; y <= 300; y += 11) row.append((char)c.rock(y)).append((char)c.cavity(y));
            result.add(row.toString());
        }
        if (reverse) java.util.Collections.reverse(result);
        return result;
    }

    private static void honorsRawBoundsAndScale() {
        for (int scale = 1; scale <= 6; scale++) {
            int floor = FLOORS[scale - 1], surface = GeologyModel.SEA_LEVEL + 280 * scale;
            var scaled = new GeologyModel(SEED, floor, scale, 2);
            var one = new GeologyModel(SEED, FLOORS[0], 1, 2);
            for (int bx : new int[] {-177, -31, 43, 191}) for (int bz : new int[] {-91, 17, 149}) {
                var c = scaled.column(bx * scale, bz * scale, surface);
                for (int y = floor - 4; y <= floor + 8; y++) require(c.cavity(y) == 0, "cavity crossed floor");
                for (int y = surface - GeologyModel.MIN_ROOF; y <= surface + 4; y++) require(c.cavity(y) == 0, "cavity crossed roof");
                var base = one.column(bx, bz, GeologyModel.SEA_LEVEL + 280);
                for (int by = -170; by <= 160; by += 13) {
                    int sy = GeologyModel.SEA_LEVEL + by * scale;
                    require(base.rock(GeologyModel.SEA_LEVEL + by) == c.rock(sy), "V2 rock scale mismatch");
                }
            }
        }
    }

    private static void hasRegionalRocksAndMixedAquifers() {
        Set<Integer> rocks = new java.util.HashSet<>();
        int air = 0, water = 0, wetColumns = 0, dryColumns = 0;
        var model = new GeologyModel(SEED, -400, 1, 2);
        for (int x = -2048; x <= 2048; x += 32) for (int z = -2048; z <= 2048; z += 32) {
            var c = model.column(x, z, 450);
            boolean hasWet = false, hasDry = false;
            for (int y = -391; y <= 425; y += 3) {
                rocks.add(c.rock(y));
                if (c.cavity(y) == 1) { air++; hasDry = true; }
                if (c.cavity(y) == 2) { water++; hasWet = true; }
            }
            if (hasWet) wetColumns++;
            if (hasDry) dryColumns++;
        }
        require(rocks.size() == 7, "V2 did not expose every rock family: " + rocks);
        require(air > 0 && water > 0, "V2 lacks dry or wet cave cells");
        require(wetColumns > 0 && dryColumns > wetColumns, "V2 aquifer is absent or floods too broadly");
        System.out.printf("v2-caves air=%d water=%d wetColumns=%d dryColumns=%d sampledColumns=%d%n",
                air, water, wetColumns, dryColumns, 129 * 129);
    }

    private static void rejectsUnknownProfiles() {
        try { new GeologyModel(SEED, -400, 1, 3); throw new AssertionError("accepted unknown profile"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
