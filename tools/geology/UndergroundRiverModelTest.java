import com.github.xandergos.terraindiffusionmc.world.geology.GeologyModel;
import com.github.xandergos.terraindiffusionmc.world.geology.UndergroundRiverModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
/** Dependency-free contract and export harness for UndergroundRiverModel. */
public final class UndergroundRiverModelTest {
    static final long SEED = 6187795302574383407L;

    public static void main(String[] args) throws Exception {
        verifyDeterminismAndNegativeCoordinates();
        verifyLegacyVersionOneSnapshots();
        verifyGeometryAndHydraulics();
        verifyPhysicalScaleContract();
        verifyAllProfileBounds();
        verifyDischargeAndMultiSeedRaster();
        if (args.length == 2 && args[0].equals("--export")) export(Path.of(args[1]));
        System.out.println("UndergroundRiverModelTest: PASS");
    }

    static void verifyDeterminismAndNegativeCoordinates() {
        var model = new UndergroundRiverModel(SEED, 2);
        Map<String, String> first = new LinkedHashMap<>();
        for (int x : new int[]{ -640, -321, -1, 0, 319, 641 }) for (int z : new int[]{ -641, -17, 0, 233, 719 }) first.put(x + "," + z, snapshot(model, x, z));
        var keys = new ArrayList<>(first.keySet());
        Collections.reverse(keys);
        for (String key : keys) {
            var point = key.split(",");
            check(first.get(key).equals(snapshot(model, Integer.parseInt(point[0]), Integer.parseInt(point[1]))), "order-dependent column " + key);
        }
        check(model.reaches(-1, 0).equals(model.reaches(-1, 0)), "reach cache changed immutable geometry");
    }

    static void verifyGeometryAndHydraulics() {
        var model = new UndergroundRiverModel(SEED, 2);
        int reaches = 0, lakes = 0, junctions = 0, wet = 0, banks = 0, descending = 0;
        for (int cx = -2; cx <= 1; cx++) for (int cz = -1; cz <= 2; cz++) {
            var list = model.reaches(cx, cz);
            Map<UndergroundRiverModel.Point, Integer> degree = new HashMap<>();
            for (var r : list) {
                reaches++;
                if (r.lake()) {
                    lakes++;
                    check(r.from().equals(r.to()), "terminal lake must be closed");
                    assertLakeStill(model, r, 2);
                } else {
                    check(r.from().y() >= r.to().y(), "uphill reach");
                    if (r.from().y() > r.to().y()) descending++;
                    degree.merge(r.from(), 1, Integer::sum);
                    degree.merge(r.to(), 1, Integer::sum);
                }
            }
            for (int d : degree.values()) if (d >= 3) junctions++;
        }
        List<int[]> wetSamples = new ArrayList<>();
        for (int x = -900; x <= 900; x += 6) for (int z = -900; z <= 900; z += 6) {
            var c = model.column(x, z);
            if (c == null) continue;
            check(c.fits(-736, 320), "raw floor/roof exceeds bounds");
            check(c.liningBottom() < c.bed() && c.bed() < c.roof(), "invalid lining/bed/roof");
            if (c.wet()) {
                wet++;
                if (wetSamples.size() < 36) wetSamples.add(new int[]{
                    x, z
                }
                );
                check(c.bed() < c.water() && c.water() < c.roof(), "flood escaped roof");
                assertRasterFlow(model, x, z, c);
                assertLateralContainment(model, x, z, c);
            } else banks++;
        }
        check(wetSamples.size() == 36, "insufficient concrete wet samples");
        verifyWetCacheOrder(wetSamples);
        check(reaches > 0 && lakes > 0 && junctions > 0 && descending > 0, "missing connected descending branch/lake geometry");
        check(wet > 0 && banks > 0, "raster lacks wet channels or dry banks");
    }

    static void assertLakeStill(UndergroundRiverModel model, UndergroundRiverModel.Reach r, int scale) {
        var c = model.column((int) Math.round(r.from().x() * scale), (int) Math.round(r.from().z() * scale));
        check(c != null && c.lake() && c.wet(), "lake centre is not wet lake");
        check(Math.hypot(c.flowX(), c.flowZ()) < 1e-9, "terminal lake has current");
    }

    static void assertLateralContainment(UndergroundRiverModel m, int x, int z, UndergroundRiverModel.Column c) {
        for (int[] d : new int[][]{
            {
                1, 0
            }
            , {
                -1, 0
            }
            , {
                0, 1
            }
            , {
                0, -1
            }
        }
        ) {
            var n = m.column(x + d[0], z + d[1]);
            if (n == null || n.wet()) continue;
            check(n.bed() >= c.water(), "wet edge opens into dry air at " + x + "," + z);
        }
    }

    static void assertRasterFlow(UndergroundRiverModel m, int x, int z, UndergroundRiverModel.Column c) {
        if (c.lake()) return;
        int currentX = x, currentZ = z;
        var current = c;
        for (int step = 1; step <= 3; step++) {
            double speed = Math.hypot(current.flowX(), current.flowZ());
            check(speed > 1e-9, "nonlake wet raster cell lost flow at " + currentX + "," + currentZ);
            int nx = (int) Math.round(currentX + current.flowX() / speed);
            int nz = (int) Math.round(currentZ + current.flowZ() / speed);
            var n = m.column(nx, nz);
            check(n != null && n.wet(), "chosen flow leaves wet channel from " + currentX + "," + currentZ
                    + " step=" + step + " -> " + nx + "," + nz + " next=" + n);
            check(n.water() <= current.water(), "chosen flow raises water head from " + currentX + "," + currentZ
                    + " water=" + current.water() + " to " + nx + "," + nz + " water=" + n.water());
            currentX = nx;
            currentZ = nz;
            current = n;
            if (current.lake()) break;
        }
    }

    static void verifyWetCacheOrder(List<int[]> samples){
        var first=new UndergroundRiverModel(SEED,2);
        Map<String,String> before=new LinkedHashMap<>();
        for(var p:samples)before.put(p[0]+","+p[1],snapshot(first,p[0],p[1]));
        for(int cx=-40;cx<0;cx++)for(int cz=10;cz<30;cz++)first.reaches(cx,cz);
        var keys=new ArrayList<>(before.keySet());
        Collections.reverse(keys);
        for(String k:keys){
            var a=k.split(",");
            check(before.get(k).equals(snapshot(first,Integer.parseInt(a[0]),Integer.parseInt(a[1]))),"wet sample changed after cache eviction "+k);
        }
    }

    static void verifyLegacyVersionOneSnapshots() {
        var legacy = new UndergroundRiverModel(6761977799736804665L, 2, 1);
        assertSnapshot(legacy, -1016, 969, -251, -254, -241, -270, false);
        assertSnapshot(legacy, -1040, 989, -238, -241, -228, -257, false);
    }

    static void assertSnapshot(UndergroundRiverModel model, int x, int z, int bed, int water,
                               int roof, int liningBottom, boolean wet) {
        var column = model.column(x, z);
        check(column != null, "missing version-one regression column at " + x + "," + z);
        check(column.bed() == bed && column.water() == water && column.roof() == roof
                        && column.liningBottom() == liningBottom && column.wet() == wet,
                "version-one regression changed at " + x + "," + z + ": " + column);
    }

    static void verifyPhysicalScaleContract() {
        var scaleTwo = new UndergroundRiverModel(SEED, 2);
        var scaleSix = new UndergroundRiverModel(SEED, 6);
        for (int cellX = -2; cellX <= 1; cellX++) {
            for (int cellZ = -1; cellZ <= 1; cellZ++) {
                check(scaleTwo.reaches(cellX, cellZ).equals(scaleSix.reaches(cellX, cellZ)),
                        "TD route geometry moved with scale");
            }
        }
        int compared = 0, maxHeadroom = 0, maxV2Width = 0, maxLegacyWidth = 0, flat = 0;
        var legacySix = new UndergroundRiverModel(SEED, 6, 1);
        for (int cellX = -2; cellX <= 1; cellX++) for (int cellZ = -1; cellZ <= 2; cellZ++) {
            for (var reach : scaleTwo.reaches(cellX, cellZ)) {
                if (reach.lake()) continue;
                if (Math.abs(reach.from().y() - reach.to().y()) < 1e-8) flat++;
                for (double t : new double[]{.3, .7}) {
                    double tdX = reach.from().x() + (reach.to().x() - reach.from().x()) * t;
                    double tdZ = reach.from().z() + (reach.to().z() - reach.from().z()) * t;
                    int x2 = (int) Math.round(tdX * 2), z2 = (int) Math.round(tdZ * 2);
                    int x6 = (int) Math.round(tdX * 6), z6 = (int) Math.round(tdZ * 6);
                    var two = scaleTwo.column(x2, z2);
                    var six = scaleSix.column(x6, z6);
                    if (two == null || six == null || !two.wet() || !six.wet()) continue;
                    compared++;
                    int twoHeadroom = two.roof() - two.water(), sixHeadroom = six.roof() - six.water();
                    maxHeadroom = Math.max(maxHeadroom, Math.max(twoHeadroom, sixHeadroom));
                    check(Math.abs(twoHeadroom - sixHeadroom) <= 3,
                            "cross-section height scales with world depth");
                    int twoWidth = wetWidth(scaleTwo, x2, z2, reach);
                    int sixWidth = wetWidth(scaleSix, x6, z6, reach);
                    maxV2Width = Math.max(maxV2Width, sixWidth);
                    maxLegacyWidth = Math.max(maxLegacyWidth, wetWidth(legacySix, x6, z6, reach));
                    check(sixWidth <= twoWidth + 8,
                            "scale-six physical width is not mild: " + twoWidth + " -> " + sixWidth
                                    + " at " + x6 + "," + z6 + " q=" + reach.discharge());
                }
            }
        }
        check(compared > 12, "insufficient scale cross-sections");
        check(maxHeadroom <= 12, "v2 cave headroom exceeds physical cap: " + maxHeadroom);
        check(maxV2Width <= 28, "v2 wet width is physically excessive: " + maxV2Width);
        check(maxLegacyWidth >= maxV2Width + 8,
                "v2 scale-six widths are not materially smaller than legacy: v2=" + maxV2Width
                        + " legacy=" + maxLegacyWidth);
        check(flat == 0, "v2 route profile retained forced level-step segments");
    }

    static int wetWidth(UndergroundRiverModel model, int x, int z, UndergroundRiverModel.Reach reach) {
        double dx = reach.to().x() - reach.from().x(), dz = reach.to().z() - reach.from().z();
        double length = Math.max(1e-9, Math.hypot(dx, dz));
        double nx = -dz / length, nz = dx / length;
        var center = model.column(x, z);
        if (center == null || !center.wet()) return 0;
        // Measure this contiguous channel, not a second bend crossed by the
        // infinite section line 20--30 blocks away. Count rounded blocks once.
        Set<Long> water = new HashSet<>();
        water.add(((long) x << 32) ^ (z & 0xffffffffL));
        for (int sign : new int[]{-1, 1}) for (int offset = 1; offset <= 32; offset++) {
            int sx = (int) Math.round(x + nx * offset * sign);
            int sz = (int) Math.round(z + nz * offset * sign);
            var column = model.column(sx, sz);
            if (column == null || !column.wet()) break;
            water.add(((long) sx << 32) ^ (sz & 0xffffffffL));
        }
        return water.size();
    }

    static void verifyAllProfileBounds() {
        int[] floors = {
            -400, -736, -1072, -1408, -1744, -1968
        }
        ;
        for (int scale = 1; scale <= 6; scale++) {
            var model = new UndergroundRiverModel(SEED, scale);
            int floor = floors[scale - 1], surface = 160 * scale;
            for (int x = -700 * scale; x <= 300 * scale; x += 11 * scale)                 for (int z = 40 * scale; z <= 700 * scale; z += 13 * scale) {
                var column = model.column(x, z);
                if (column != null) check(column.fits(floor, surface),                             "profile " + scale + " exceeds raw floor/roof");
            }
        }
    }

    static void verifyDischargeAndMultiSeedRaster() {
        Set<Integer> sourceCounts = new HashSet<>();
        for (int cellX = -4; cellX <= 3; cellX++) for (int cellZ = -2; cellZ <= 4; cellZ++) {
            var reaches = new UndergroundRiverModel(SEED, 2).reaches(cellX, cellZ);
            Map<UndergroundRiverModel.Point, List<UndergroundRiverModel.Reach>> incoming = new HashMap<>();
            Map<UndergroundRiverModel.Point, List<UndergroundRiverModel.Reach>> outgoing = new HashMap<>();
            for (var reach : reaches) {
                check(reach.discharge() > 0, "non-positive discharge");
                if (!reach.lake()) {
                    incoming.computeIfAbsent(reach.to(), ignored -> new ArrayList<>()).add(reach);
                    outgoing.computeIfAbsent(reach.from(), ignored -> new ArrayList<>()).add(reach);
                }
            }
            for (var point : incoming.keySet()) {
                var in = incoming.get(point);
                var out = outgoing.get(point);
                if (in.size() > 1 && out != null && !out.isEmpty()) {
                    sourceCounts.add(in.size());
                    double sum = in.stream().mapToDouble(UndergroundRiverModel.Reach::discharge).sum();
                    check(Math.abs(out.getFirst().discharge() - sum) < 1e-8, "junction discharge is not conserved");
                }
            }
        }
        check(sourceCounts.size() >= 2, "source count does not vary across cells");
        double globalMinimum = Double.POSITIVE_INFINITY, globalMaximum = 0;
        for (long seed : new long[]{
            0L, 91357L, -71421L
        }
        ) {
            var model = new UndergroundRiverModel(seed, 2);
            double minimum = Double.POSITIVE_INFINITY, maximum = 0;
            for (int x = -700; x <= 700; x += 19) for (int z = -500; z <= 500; z += 23) {
                var c = model.column(x, z);
                if (c == null || !c.wet()) continue;
                assertLateralContainment(model, x, z, c);
                if (!c.lake()) {
                    double speed = Math.hypot(c.flowX(), c.flowZ());
                    check(speed > 0, "nonlake multi-seed water lost current");
                    minimum = Math.min(minimum, speed);
                    maximum = Math.max(maximum, speed);
                    globalMinimum = Math.min(globalMinimum, speed);
                    globalMaximum = Math.max(globalMaximum, speed);
                }
            }
            check(maximum > 0 && minimum < Double.POSITIVE_INFINITY,
                    "no nonlake current samples for seed " + seed);
        }
        check(globalMaximum > globalMinimum + .02, "current speed lacks multi-seed variety");
    }

    static String snapshot(UndergroundRiverModel model, int x, int z) {
        var column = model.column(x, z);
        return column == null ? "null" : column.toString();
    }

    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    static void export(Path out)throws IOException{
        Files.createDirectories(out);
        Fixture f=fixture(2);
        write(out,f,2);
        write(out,fixture(6),6);
        writeCatchment(out);
    }

    static void writeCatchment(Path out)throws IOException{
        var model=new UndergroundRiverModel(SEED,1);
        StringBuilder csv=new StringBuilder("cell_x,cell_z,from_x,from_y,from_z,to_x,to_y,to_z,width_from,width_to,lake,discharge\n");
        for(int cellX=-2;cellX<=0;cellX++) for(int cellZ=-1;cellZ<=1;cellZ++) for(var reach:model.reaches(cellX,cellZ))
            csv.append(cellX).append(',').append(cellZ).append(',').append(reach.from().x()).append(',').append(reach.from().y()).append(',').append(reach.from().z()).append(',').append(reach.to().x()).append(',').append(reach.to().y()).append(',').append(reach.to().z()).append(',').append(reach.widthFrom()).append(',').append(reach.widthTo()).append(',').append(reach.lake()).append(',').append(reach.discharge()).append('\n');
        Files.writeString(out.resolve("river-catchment-td.csv"),csv.toString());
    }
    record Fixture(int cellX,int cellZ,int centerX,int centerZ,List<UndergroundRiverModel.Reach> profile){
    }

    static Fixture fixture(int scale){
        var m=new UndergroundRiverModel(SEED,scale);
        // Illustrative wet window inside benchmark basin (-2,0). Native audits
        // use their separately exported fixed chunk bounds, not this moving window.
        int cx=-2,cz=0;
        var reaches=m.reaches(cx,cz);
        for(var reach:reaches) if(!reach.lake()) for(double t:new double[]{.25,.5,.75}){
            int x=(int)Math.round((reach.from().x()+(reach.to().x()-reach.from().x())*t)*scale);
            int z=(int)Math.round((reach.from().z()+(reach.to().z()-reach.from().z())*t)*scale);
            var column=m.column(x,z);
            if(column!=null&&column.wet())return new Fixture(cx,cz,x,z,profile(reaches));
        }
        throw new AssertionError("no wet benchmark-cell fixture at TD cell (-2,0)");
    }

    static List<UndergroundRiverModel.Reach> profile(List<UndergroundRiverModel.Reach> all){
        var start=all.stream().filter(r->!r.lake()).max(Comparator.comparingDouble(r->r.from().y())).orElseThrow();
        var out=new ArrayList<UndergroundRiverModel.Reach>();
        var point=start.from();
        while(true){
            UndergroundRiverModel.Reach next=null;
            for(var r:all)if(!r.lake()&&r.from().equals(point)&&(next==null||r.to().y()<next.to().y()))next=r;
            if(next==null)break;
            out.add(next);
            point=next.to();
        }
        return List.copyOf(out);
    }

    static void write(Path out,Fixture f,int scale)throws IOException{
        var m=new UndergroundRiverModel(SEED,scale);
        int chunkX=Math.floorDiv(f.centerX,16),chunkZ=Math.floorDiv(f.centerZ,16);
        StringBuilder plan=new StringBuilder(),columns=new StringBuilder("x,z,bed,water,roof,wet,lake\n");
        for(int z=f.centerZ-64;z<f.centerZ+64;z++){
            for(int x=f.centerX-64;x<f.centerX+64;x++){
                var c=m.column(x,z);
                plan.append(c==null?'0':c.wet()?'2':'1');
                if(c!=null)columns.append(x).append(',').append(z).append(',').append(c.bed()).append(',').append(c.water()).append(',').append(c.roof()).append(',').append(c.wet()).append(',').append(c.lake()).append('\n');
            }
            plan.append('\n');
        }
        Files.writeString(out.resolve("river-plan-s"+scale+".txt"),plan.toString());
        Files.writeString(out.resolve("river-columns-s"+scale+".csv"),columns.toString());
        StringBuilder profile=new StringBuilder("x,y,z\n");
        for(var r:f.profile)profile.append(r.from().x()*scale).append(',').append(63+r.from().y()*scale).append(',').append(r.from().z()*scale).append('\n');
        var tail=f.profile.getLast().to();
        profile.append(tail.x()*scale).append(',').append(63+tail.y()*scale).append(',').append(tail.z()*scale).append('\n');
        Files.writeString(out.resolve("river-profile-s"+scale+".csv"),profile.toString());
        Files.writeString(out.resolve("river-fixture-s"+scale+".json"),"{\"seed\":"+SEED+",\"scale\":"+scale+",\"normalizedCell\":["+f.cellX+","+f.cellZ+"],\"centerChunk\":["+chunkX+","+chunkZ+"],\"radiusChunks\":4,\"centerBlock\":["+f.centerX+","+f.centerZ+"]}\n");
    }
}
