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
        verifyGeometryAndHydraulics();
        verifyScaleAnchoring();
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
                    assertDownstreamCurrent(model, r, 2);
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

    static void assertDownstreamCurrent(UndergroundRiverModel model,UndergroundRiverModel.Reach r,int scale){
        for(double t:new double[]{ .2,.5,.8 }){
            double x=(r.from().x()+(r.to().x()-r.from().x())*t)*scale,z=(r.from().z()+(r.to().z()-r.from().z())*t)*scale;
            var c=model.column((int)Math.round(x),(int)Math.round(z));
            check(c!=null&&c.wet(),"reach centre did not rasterize wet: "+r+" t="+t);
            if(c.lake()){
                check(Math.hypot(c.flowX(),c.flowZ())<1e-9,"receiving lake has current");
                continue;
            }
            double dot=c.flowX()*(r.to().x()-r.from().x())+c.flowZ()*(r.to().z()-r.from().z());
            check(Math.hypot(c.flowX(),c.flowZ())>1e-8&&dot>0,"wet current is not downstream: reach="+r+" t="+t+" flow="+c.flowX()+","+c.flowZ()+" dot="+dot+" chosen="+c);
        }
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
        double speed = Math.hypot(c.flowX(), c.flowZ());
        check(speed > 1e-9, "nonlake wet raster cell lost flow at " + x + "," + z);
        for (int step = 1; step <= 3; step++) {
            int nx = (int) Math.round(x + c.flowX() / speed * step), nz = (int) Math.round(z + c.flowZ() / speed * step);
            var n = m.column(nx, nz);
            check(n != null && n.wet(), "chosen flow leaves wet channel from " + x + "," + z + " step=" + step + " -> " + nx + "," + nz + " next=" + n);
            check(n.water() <= c.water(), "chosen flow raises water head from " + x + "," + z + " water=" + c.water() + " to " + nx + "," + nz + " water=" + n.water());
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

    static void verifyScaleAnchoring() {
        var scaleTwo = new UndergroundRiverModel(SEED, 2);
        var scaleSix = new UndergroundRiverModel(SEED, 6);
        for (int cellX = -2; cellX <= 1; cellX++) {
            for (int cellZ = -1; cellZ <= 1; cellZ++) {
                check(scaleTwo.reaches(cellX, cellZ).equals(scaleSix.reaches(cellX, cellZ)),                         "TD reach geometry moved with scale");
            }
        }
        for (int tdX = -400; tdX <= 400; tdX += 80) for (int tdZ = -400; tdZ <= 400; tdZ += 80) {
            var two = scaleTwo.column(tdX * 2, tdZ * 2);
            var six = scaleSix.column(tdX * 6, tdZ * 6);
            check((two == null) == (six == null), "scale changed corridor footprint");
            if (two == null) continue;
            check(two.wet() == six.wet() && two.lake() == six.lake(), "scale changed water class");
            check(Math.abs((two.bed() - 63) / 2.0 - (six.bed() - 63) / 6.0) <= 1.1,                     "sea-level anchored bed mismatch");
        }
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
                }
            }
            check(maximum > minimum + .02, "current speed lacks variety for seed " + seed);
        }
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
        for(int cx=-1;cx>=-1;cx--)for(int cz=0;cz<=0;cz++){
            var r=m.reaches(cx,cz);
            if(!r.isEmpty())return new Fixture(cx,cz,(cx*320+160)*scale,(cz*320+160)*scale,profile(r));
        }
        throw new AssertionError("no warm-quadrant fixture");
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
