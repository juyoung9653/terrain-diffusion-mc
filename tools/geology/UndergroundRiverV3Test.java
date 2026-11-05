import com.github.xandergos.terraindiffusionmc.world.geology.GeologyModel;
import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRiverModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure contracts for the new hydro-geologic river profile. */
public final class UndergroundRiverV3Test {
    private static final long[] SEEDS = {0L, 91357L, -71421L, 6187795302574383407L};

    public static void main(String[] args) {
        for (long seed : SEEDS) verify(seed);
        System.out.println("UndergroundRiverV3Test: PASS");
    }

    private static void verify(long seed) {
        var model = new UndergroundRiverModel(seed, 2, 3);
        int empty = 0, nonempty = 0, reaches = 0, lakes = 0, wet = 0, banks = 0;
        for (int cellX = -4; cellX <= 3; cellX++) for (int cellZ = -3; cellZ <= 4; cellZ++) {
            List<UndergroundRiverModel.Reach> first = model.reaches(cellX, cellZ);
            check(first.equals(model.reaches(cellX, cellZ)), "cache changes V3 basin");
            if (first.isEmpty()) { empty++; continue; }
            nonempty++;
            Map<UndergroundRiverModel.Point, Double> incoming = new HashMap<>();
            Map<UndergroundRiverModel.Point, UndergroundRiverModel.Reach> outgoing = new HashMap<>();
            for (var reach : first) {
                reaches++;
                check(reach.discharge() > 0 && reach.widthFrom() > 0 && reach.widthTo() > 0,
                        "invalid V3 reach dimensions");
                double radiusCap = reach.lake() ? 5.6 : 3.8;
                check(reach.widthFrom() <= radiusCap && reach.widthTo() <= radiusCap,
                        "V3 channel or terminal pool exceeds compact radius");
                if (reach.lake()) {
                    lakes++;
                    check(reach.from().equals(reach.to()), "terminal pool must close");
                    continue;
                }
                check(reach.from().y() >= reach.to().y(), "V3 reach flows uphill");
                incoming.merge(reach.to(), reach.discharge(), Double::sum);
                outgoing.put(reach.from(), reach);
            }
            for (var entry : incoming.entrySet()) {
                var downstream = outgoing.get(entry.getKey());
                if (downstream != null)
                    check(Math.abs(downstream.discharge() - entry.getValue()) < 1e-8,
                            "confluence loses or creates discharge");
            }
        }
        check(empty > 0 && nonempty > 0 && reaches > 0 && lakes > 0,
                "V3 lacks regional spring variability");

        // Raster at block resolution. These checks cover the retained bank, the
        // physical section cap, and a one-block current step without world I/O.
        for (int x = -1500; x <= 1500; x += 7) for (int z = -1500; z <= 1500; z += 9) {
            var column = model.column(x, z);
            if (column == null) continue;
            check(column.fits(-736, 320), "V3 column breaches roof/floor guard");
            check(column.liningBottom() < column.bed() && column.bed() < column.roof(),
                    "V3 invalid support geometry");
            if (!column.wet()) { banks++; continue; }
            wet++;
            check(column.bed() < column.water() && column.water() < column.roof(),
                    "V3 water reaches cave roof");
            if (column.lake()) {
                check(Math.hypot(column.flowX(), column.flowZ()) < 1e-9, "pool has current");
                continue;
            }
            double speed = Math.hypot(column.flowX(), column.flowZ());
            check(speed > 1e-9, "wet V3 river lacks current");
            int nx = (int) Math.round(x + column.flowX() / speed);
            int nz = (int) Math.round(z + column.flowZ() / speed);
            var next = model.column(nx, nz);
            check(next != null && next.wet() && next.water() <= column.water(),
                    "V3 current leaves water raster or climbs");
        }
        check(wet > 0 && banks > 0, "V3 has insufficient wet channel/bank samples: wet=" + wet + " banks=" + banks);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
