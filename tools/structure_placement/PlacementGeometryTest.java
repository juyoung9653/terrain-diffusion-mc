import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Candidate;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Rect;
import java.util.*;
import com.github.xandergos.terraindiffusionmc.world.structure.PlacementGeometry.Viable;

public final class PlacementGeometryTest {
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static Set<Candidate> winners(List<Candidate> raw) {
        Set<Candidate> result=new HashSet<>();
        for (Candidate candidate:raw) if (raw.stream().noneMatch(other -> other.defeats(candidate))) result.add(candidate);
        return result;
    }
    private static Set<PlacementGeometry.Voxel> frozenAt(Set<PlacementGeometry.Voxel> blocks,
                                                          int rotation,int x,int y,int z) {
        var world=new HashSet<PlacementGeometry.Voxel>();
        for (var block:blocks) world.add(block.rotate(rotation).offset(x,y,z));
        return Set.copyOf(world);
    }
    private static void occupiedSetContract() {
        var source=new HashSet<PlacementGeometry.Voxel>();
        source.add(new PlacementGeometry.Voxel(1,2,3));
        source.add(new PlacementGeometry.Voxel(-4,5,-6));
        var occupied=new PlacementGeometry.Occupied(source);
        source.clear();
        check(occupied.blocks().equals(Set.of(new PlacementGeometry.Voxel(1,2,3),
                new PlacementGeometry.Voxel(-4,5,-6))),"occupied defensively copies input set");
        boolean immutable=false;
        try { occupied.blocks().clear(); } catch (UnsupportedOperationException expected) { immutable=true; }
        check(immutable,"occupied blocks reject clear");
        var iterator=occupied.blocks().iterator();
        iterator.next();
        immutable=false;
        try { iterator.remove(); } catch (UnsupportedOperationException expected) { immutable=true; }
        check(immutable,"occupied blocks iterator stays immutable");
        boolean rejectedNull=false;
        try { new PlacementGeometry.Occupied((Set<PlacementGeometry.Voxel>)null); }
        catch (NullPointerException expected) { rejectedNull=true; }
        check(rejectedNull,"occupied rejects null set");
        source.add(null);
        rejectedNull=false;
        try { new PlacementGeometry.Occupied(source); } catch (NullPointerException expected) { rejectedNull=true; }
        check(rejectedNull,"occupied rejects null voxel");

        var dense=new HashSet<PlacementGeometry.Voxel>();
        for (int x=0;x<32;x++) for (int y=0;y<32;y++) for (int z=0;z<32;z++)
            dense.add(new PlacementGeometry.Voxel(x,y,z));
        var actual=new PlacementGeometry.Occupied(dense);
        check(actual.blocks().equals(Set.copyOf(dense)) && actual.blocks().hashCode()==Set.copyOf(dense).hashCode(),
                "dense occupied keeps old set value and hash semantics");
        int[][] offsets={{-1_000_000_000,-64,1_000_000_000},
                {Integer.MAX_VALUE-31,Integer.MIN_VALUE+31,Integer.MIN_VALUE+31}};
        for (int rotation=0;rotation<4;rotation++) for (int[] offset:offsets) {
            var expected=frozenAt(dense,rotation,offset[0],offset[1],offset[2]);
            var transformed=actual.at(rotation,offset[0],offset[1],offset[2]);
            check(transformed.blocks().equals(expected) && transformed.blocks().hashCode()==expected.hashCode(),
                    "dense transform matches frozen Set.copyOf reference for rotations and extreme coordinates");
            var shifted=frozenAt(dense,rotation,offset[0]+32,offset[1],offset[2]);
            boolean reference=expected.stream().anyMatch(shifted::contains);
            check(transformed.overlaps(new PlacementGeometry.Occupied(shifted))==reference,
                    "dense overlap matches frozen reference");
        }
    }
    private static void acceptedStartSafety() {
        Rect left=new Rect(0,0,9,9),right=new Rect(110,0,119,9);
        var index=new PlacementGeometry.AcceptedIndex<String,String>();
        check(index.accept("a","full descriptor",left,left,100),"first actual start commits");
        check(index.accept("a","full descriptor",left,left,100),"identical owner replay is idempotent");
        check(index.size()==1,"owner replay retains one accepted entry");
        check(!index.accept("a","changed pieces",left,left,100),"same geometry with changed full descriptor is rejected");
        check(!index.accept("a","full descriptor",right,right,100),"same owner cannot move its committed bounds");
        check(index.accept("b","b",right,right,100),"exact 100-block gap still accepted");
        check(!index.accept("c","c",new Rect(109,0,118,9),right,100),"99-block gap rejected");
        var dimension=new PlacementGeometry.AcceptedIndex<String,String>();
        check(dimension.accept("a","different dimension",left,left,100),"independent dimension index has no cross-dimension collision");
        for (boolean reverse:List.of(false,true)) {
            var gaps=new PlacementGeometry.AcceptedIndex<String,String>();
            Rect far=new Rect(150,0,159,9);
            check(gaps.accept("first","first",reverse?far:left,reverse?far:left,reverse?200:100),"asymmetric gap first start");
            check(!gaps.accept("second","second",reverse?left:far,reverse?left:far,reverse?100:200),"larger gap enforced in both commit orders");
            var influence=new PlacementGeometry.AcceptedIndex<String,String>();
            Rect longRim=new Rect(0,0,510,9),farRim=new Rect(500,0,700,9),farBounds=new Rect(600,0,609,9);
            check(influence.accept("first","first",reverse?farBounds:left,reverse?farRim:longRim,100),"influence first start");
            check(!influence.accept("second","second",reverse?left:farBounds,reverse?longRim:farRim,100),"overlapping influence rejected symmetrically across spatial cells");
        }
        var cache=new PlacementGeometry.PreflightCache<String,Optional<Rect>>(1);
        cache.get("unstable",Optional::empty);
        var committed=new PlacementGeometry.AcceptedIndex<String,Rect>();
        check(committed.accept("stable",left,left,left,100),"neighbor accepted while unstable candidate was absent");
        cache.get("eviction",() -> Optional.of(right));
        Rect changed=cache.get("unstable",() -> Optional.of(new Rect(20,0,29,9))).orElseThrow();
        check(!committed.accept("unstable",changed,changed,changed,100),"evicted absent candidate becoming viable cannot violate accepted gap");
        check(committed.size()==1,"rejected speculative drift is never retained");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round=0;round<100;round++) {
                var race=new PlacementGeometry.AcceptedIndex<String,String>();
                var ready=new java.util.concurrent.CountDownLatch(2);
                var begin=new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<Boolean>> futures=new ArrayList<>();
                for (String key:List.of("one","two")) futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!begin.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
                    return race.accept(key,key,left,left,100);
                }));
                if (!ready.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("race readiness timed out");
                begin.countDown();
                int successes=0;
                for (var future:futures) if (future.get(10,java.util.concurrent.TimeUnit.SECONDS)) successes++;
                check(successes==1 && race.size()==1,"concurrent conflicting commits have exactly one winner");
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { pool.shutdownNow(); }
        var spatial=new PlacementGeometry.AcceptedIndex<Integer,Viable>();
        List<Viable> brute=new ArrayList<>();
        Random random=new Random(7231);
        for (int i=0;i<2000;i++) {
            int x=random.nextInt(16000)-8000,z=random.nextInt(16000)-8000;
            Rect bounds=new Rect(x,z,x+random.nextInt(90),z+random.nextInt(90));
            Viable entry=new Viable(new Candidate("test:"+i,0,0,0,100+random.nextInt(350),0),bounds,bounds.inflate(random.nextInt(200)));
            boolean expected=brute.stream().noneMatch(other -> bounds.conflicts(other.bounds(),Math.max(entry.candidate().gap(),other.candidate().gap()))
                    || entry.influence().conflicts(other.influence(),0));
            check(spatial.accept(i,entry,bounds,entry.influence(),entry.candidate().gap())==expected,"spatial commit index agrees with brute force including negative cells");
            if (expected) brute.add(entry);
        }
        var oversized=new PlacementGeometry.AcceptedIndex<String,String>();
        Rect huge=new Rect(-40000,-40000,40000,40000);
        check(oversized.accept("huge","huge",left,huge,100),"oversized influence commits without excessive cell enumeration");
        Rect distant=new Rect(30000,30000,30009,30009);
        check(!oversized.accept("small","small",distant,distant,100),"normal query checks oversized accepted influence");
        var reverseOversized=new PlacementGeometry.AcceptedIndex<String,String>();
        check(reverseOversized.accept("small","small",distant,distant,100),"small footprint commits before oversized query");
        check(!reverseOversized.accept("huge","huge",left,huge,100),"oversized query checks every existing accepted entry");
    }
    private static void terrainBlendAndFoundationPolicy() {
        Rect point=new Rect(0,0,0,0);
        int radius=12;
        check(PlacementGeometry.terrainBlendDistance(point,-13,1,radius,4)==Integer.MAX_VALUE,
                "V4 keeps the axis-distance blend exclusion");
        check(PlacementGeometry.terrainBlendDistance(point,-13,1,radius,5)==11,
                "V5 admits the rounded 11-block contour just outside the axis gate");
        check(PlacementGeometry.terrainBlendDistance(point,-14,1,radius,5)==12,
                "V5 admits the rounded radius boundary");
        check(PlacementGeometry.terrainBlendDistance(point,-15,1,radius,5)==Integer.MAX_VALUE,
                "V5 does not extend the early axis window beyond two blocks");
        int repeated=PlacementGeometry.terrainBlendDistance(point,-14,1,radius,5);
        check(repeated==PlacementGeometry.terrainBlendDistance(point,-14,1,radius,5),
                "rounded blend admission is deterministic");

        check(PlacementGeometry.naturalFoundation(0,99,4),"legacy foundation policy remains unchanged");
        check(PlacementGeometry.naturalFoundation(0,0,5),"empty small decoration remains exempt");
        check(PlacementGeometry.naturalFoundation(15,15,5),"small deep decoration remains exempt");
        check(PlacementGeometry.naturalFoundation(16,8,5),"V5 permits half deep foundation columns");
        check(!PlacementGeometry.naturalFoundation(16,9,5),"V5 rejects a majority deep foundation");
        check(!PlacementGeometry.naturalFoundation(16,-1,5),"V5 rejects invalid negative deep count");
        check(!PlacementGeometry.naturalFoundation(16,17,5),"V5 rejects deep count beyond columns");
    }
    public static void main(String[] args) {
        occupiedSetContract();
        terrainBlendAndFoundationPolicy();
        check(PlacementGeometry.layoutSeed(77,"test:a",-8,3,0)==77,"first layout retains native seed");
        Set<Long> layouts=new HashSet<>();
        for (int attempt=1;attempt<9;attempt++) for (String id:List.of("test:a","test:b"))
            for (int x:List.of(-8,8)) {
                long seed=PlacementGeometry.layoutSeed(77,id,x,3,attempt);
                check(seed==PlacementGeometry.layoutSeed(77,id,x,3,attempt),"alternate layout seed repeats");
                check(layouts.add(seed),"alternate layouts separate attempts, IDs and owners");
            }
        Rect terrace = new Rect(-12,-9,12,9);
        for (int x=-32;x<=32;x++) for (int z=-32;z<=32;z++) {
            int distance = terrace.terrainDistance(x,z);
            check((distance==0)==(terrace.distance(x,z)==0),"rounded contours preserve exact foundation");
            double dx=Math.max(0,Math.abs(x)-12), dz=Math.max(0,Math.abs(z)-9);
            check(Math.abs(distance-Math.hypot(dx,dz))<2.3,"contour perturbation stays local");
            check(distance==terrace.terrainDistance(x,z),"world anchored contour is deterministic");
        }
        check(terrace.terrainDistance(22,19)>terrace.distance(22,19),"terrace corners are rounded");
        acceptedStartSafety();
        Rect a=new Rect(0,0,9,9);
        check(a.conflicts(new Rect(109,0,118,9),100),"99 empty columns must fail");
        check(!a.conflicts(new Rect(110,0,119,9),100),"100 empty columns must pass");
        check(a.conflicts(new Rect(9,9,19,19),0),"shared block always overlaps");
        check(!a.conflicts(new Rect(10,0,19,9),0),"adjacent blocks do not overlap when gap disabled");
        check(!a.conflicts(new Rect(70,90,79,99),100),"60/80 diagonal exactly100");
        check(a.conflicts(new Rect(69,90,78,99),100),"59/80 diagonal below100");
        check(new Rect(-100,-100,100,100).contains(a),"contained footprint");
        check(!a.contains(new Rect(-1,0,9,9)),"overflow negative edge");
        check(!a.contains(new Rect(0,0,10,9)),"overflow positive edge");
        Candidate negative=new Candidate("a",-1,-1,7,100,0);
        check(negative.envelope().equals(new Rect(-15,-15,-1,-1)),"negative chunk floor coordinates");
        Candidate strongerGap=new Candidate("a",0,0,0,200,0);
        Candidate weakerGap=new Candidate("b",10,0,0,100,1);
        check(strongerGap.defeats(weakerGap),"pair uses larger minimum gap");
        check(!weakerGap.defeats(strongerGap),"strict priority order");
        Candidate chainA=new Candidate("a",0,0,0,100,0);
        Candidate chainB=new Candidate("b",5,0,0,100,1);
        Candidate chainC=new Candidate("c",10,0,0,100,2);
        check(winners(List.of(chainA,chainB,chainC)).equals(Set.of(chainA)),"losing raw B still blocks C; no order-dependent greedy acceptance");
        var hay=new PlacementGeometry.Occupied(Set.of(new PlacementGeometry.Voxel(0,0,0)));
        var room=new PlacementGeometry.Occupied(Set.of(new PlacementGeometry.Voxel(0,0,0),
                new PlacementGeometry.Voxel(2,0,0),new PlacementGeometry.Voxel(2,2,0)));
        check(hay.at(0,1,1,0).overlaps(room)==false,"decoration inside room AIR is allowed");
        check(hay.at(0,2,2,0).overlaps(room),"physical decoration still conflicts with occupied roof");
        var interlockingRoom=new PlacementGeometry.Occupied(Set.of(new PlacementGeometry.Voxel(1,0,0),
                new PlacementGeometry.Voxel(1,2,0),new PlacementGeometry.Voxel(0,2,0)));
        check(!room.overlaps(interlockingRoom),"multi-level rooms with overlapping AIR envelopes and disjoint occupied blocks are allowed");
        check(room.overlaps(interlockingRoom.at(0,1,0,0)),"actual occupied multi-level room collision remains rejected");
        for (int rotation=0;rotation<4;rotation++) {
            var worldRoom=room.at(rotation,-20,73,17);
            var worldHay=hay.at(0,2,2,0).at(rotation,-20,73,17);
            check(worldRoom.overlaps(worldHay),"occupied collision follows all native rotations and negative translations");
            var emptyHay=hay.at(0,1,1,0).at(rotation,-20,73,17);
            check(!worldRoom.overlaps(emptyHay),"AIR exclusion follows all native rotations");
        }
        check(PlacementGeometry.jigsawWritesBlock("minecraft:hay_block"),"horse marker final hay block remains occupied");
        check(PlacementGeometry.jigsawWritesBlock(null),"missing final state conservatively occupies jigsaw");
        check(PlacementGeometry.jigsawWritesBlock("mod:unknown[property=value]"),"unknown final states remain occupied");
        check(!PlacementGeometry.jigsawWritesBlock("minecraft:air"),"legacy final AIR ignored after replacement processor");
        check(!PlacementGeometry.jigsawWritesBlock("minecraft:structure_void"),"native replacement void writes no block");
        var paletteUnion=new HashSet<PlacementGeometry.Voxel>(hay.blocks());
        paletteUnion.add(new PlacementGeometry.Voxel(3,2,1));
        check(new PlacementGeometry.Occupied(paletteUnion).overlaps(hay.at(0,3,2,1)),"another palette's possible solid stays a blocker");
        Viable actualA=new Viable(new Candidate("mod:a",0,0,128,100,0),new Rect(0,0,9,9),new Rect(-8,-8,17,17));
        Viable actualB=new Viable(new Candidate("mod:b",8,0,128,100,1),new Rect(128,0,137,9),new Rect(120,-8,145,17));
        check(actualA.candidate().defeats(actualB.candidate()),"old max envelopes suppress separated buildings");
        check(!actualA.defeats(actualB),"actual accepted footprints permit distant buildings");
        Viable nearB=new Viable(actualB.candidate(),new Rect(108,0,117,9),new Rect(100,-8,125,17));
        check(actualA.defeats(nearB),"98 empty columns still rejected across namespaces");
        Viable influenceB=new Viable(actualB.candidate(),actualB.bounds(),new Rect(17,0,145,17));
        check(actualA.defeats(influenceB),"terrain influence conflict independent of footprint gap");
        Viable groundPriority=new Viable(new Candidate("mod:village",0,0,128,100,-1L,0),actualA.bounds(),actualA.influence());
        Viable undergroundPriority=new Viable(new Candidate("mod:dungeon",0,0,128,100,0L,1),actualA.bounds(),actualA.influence());
        check(groundPriority.defeats(undergroundPriority),"confirmed surface viability outranks strongest underground seed hash");
        check(!undergroundPriority.defeats(groundPriority),"surface ranking cannot be discarded by raw hash pruning");
        Viable conservativePriority=new Viable(new Candidate("mod:unknown",0,0,128,100,0L),actualA.bounds(),actualA.influence());
        check(!conservativePriority.defeats(groundPriority),"unverified conservative reservation has no surface boost");
        Viable farSurface=new Viable(new Candidate("mod:far_village",8,0,128,100,0L,0),actualB.bounds(),actualB.influence());
        check(!farSurface.defeats(groundPriority),"surface priority never extends the physical conflict range");
        check(groundPriority.candidate().priority()==-1L,"surface tier preserves every original unsigned seed hash bit");
        List<Optional<Viable>> eligibility=List.of(Optional.of(nearB),Optional.empty(),Optional.empty(),Optional.empty());
        check(eligibility.stream().flatMap(Optional::stream).noneMatch(v -> v.defeats(nearB)),
                "absent biome, terrain-invalid and density-removed candidates cannot block");
        var memo=new PlacementGeometry.PreflightCache<Integer,Optional<Viable>>(4);
        List<Optional<Viable>> concurrent=java.util.stream.IntStream.range(0,1000).parallel()
                .mapToObj(i -> memo.get(i%2,() -> i%2==0 ? Optional.of(actualA) : Optional.empty())).toList();
        for (int i=0;i<concurrent.size();i++) check(concurrent.get(i).equals(i%2==0 ? Optional.of(actualA) : Optional.empty()),
                "concurrent descriptor and negative cache results remain immutable and repeatable");
        for (int i=0;i<100;i++) memo.get(i,() -> Optional.of(actualB));
        check(memo.size()==4,"preflight cache evicts to fixed memory bound");
        List<Viable> viableCandidates=new ArrayList<>();
        for (int i=0;i<150;i++) {
            int x=(i%15)*8,z=(i/15)*8;
            Candidate candidate=new Candidate("mod:"+(i%4)+"/"+i,x,z,128,100,PlacementGeometry.priority(53,"set"+i,x,z));
            Rect actual=new Rect(x*16,z*16,x*16+15,z*16+15);
            viableCandidates.add(new Viable(candidate.withTier(i%3==0 ? 0 : 1),actual,actual.inflate(8)));
        }
        Set<Viable> accepted=new HashSet<>();
        for (Viable candidate:viableCandidates)
            if (viableCandidates.stream().noneMatch(other -> other.defeats(candidate))) accepted.add(candidate);
        for (int order=0;order<10;order++) {
            Collections.shuffle(viableCandidates,new Random(order));
            Set<Viable> ordered=new HashSet<>();
            for (Viable candidate:viableCandidates)
                if (viableCandidates.stream().noneMatch(other -> other.defeats(candidate))) ordered.add(candidate);
            check(accepted.equals(ordered),"actual footprint arbitration invariant under arrival order");
        }
        for (Viable left:accepted) for (Viable right:accepted) if (left!=right) {
            check(!left.bounds().conflicts(right.bounds(),100),"accepted actual bounds maintain 100 edge gap");
            check(!left.influence().conflicts(right.influence(),0),"accepted terrain influence disjoint");
        }
        check(PlacementGeometry.boundedEarthwork(70,73,2,3),"fill boundary");
        check(!PlacementGeometry.boundedEarthwork(70,74,2,3),"fill overflow");
        check(PlacementGeometry.boundedEarthwork(70,68,2,3),"cut boundary");
        check(!PlacementGeometry.boundedEarthwork(70,67,2,3),"cut overflow");
        check(PlacementGeometry.supportFloor(107,2)==104,"regression: protect native foundation carved at 104 through 107");
        for (int original=-60;original<320;original+=13) for (int cut=0;cut<=8;cut++) {
            int floor=PlacementGeometry.supportFloor(original,cut);
            check(floor==original-cut-1,"carver floor matches preflight's lowest verified solid block");
            check(floor<original-cut,"maximum cut retains a protected solid block beneath the foundation");
        }
        check(PlacementGeometry.constrainedHeight(80,Math.max(76,77),Math.min(78,79))==78,"overlapping pad cones choose common feasible height");
        check(PlacementGeometry.constrainedHeight(77,77,78)==77,"feasible natural height is preserved");
        boolean incompatible=false;
        try { PlacementGeometry.constrainedHeight(80,79,78); } catch (IllegalArgumentException expectedFailure) { incompatible=true; }
        check(incompatible,"only empty interval intersection rejects");
        check(PlacementGeometry.gradeAllowance(0,3)==0,"foundation grade stays exact");
        check(PlacementGeometry.gradeAllowance(1,3)==1,"adjacent pad may rise one voxel");
        check(PlacementGeometry.gradeAllowance(1,2)==0,"saved policy 2 preserves strict interval");
        check(PlacementGeometry.gradeAllowance(1,1)==0,"saved policy 1 preserves strict interval");
        check(PlacementGeometry.gradeAllowance(2,3)==1,"two columns still allow only one rise");
        check(!PlacementGeometry.slope(0,2,2),"consecutive rising steps remain rejected");
        check(PlacementGeometry.slope(70,71,1),"one step voxel grade");
        check(PlacementGeometry.slope(70,71,2),"1:2 slope");
        check(!PlacementGeometry.slope(70,72,2),"1:1 slope fails");
        check(PlacementGeometry.localOffset(-16,-7,-16)==-13,"negative chunk edge uses distinct opposite half-site");
        check(PlacementGeometry.localOffset(-16,7,-16)==-9,"positive local shift");
        check(PlacementGeometry.localOffset(20,7,0)==20,"foreign origin never shifted or clamped");
        for (int position=-16;position<0;position++) for (int delta=-7;delta<=7;delta++) {
            int shifted=PlacementGeometry.localOffset(position,delta,-16);
            check(shifted>=-16 && shifted<0,"retry retains owning chunk");
            check(Math.abs(shifted-position)<=Math.abs(delta),"retry bounded by maximum offset");
        }
        Random random=new Random(91823);
        List<Candidate> candidates=new ArrayList<>();
        for (int i=0;i<500;i++) {
            int x=random.nextInt(200)-100,z=random.nextInt(200)-100;
            String id="mod"+(i%7)+":set"+i;
            candidates.add(new Candidate(id,x,z,16+random.nextInt(97),100+random.nextInt(150),PlacementGeometry.priority(1234,id,x,z)));
        }
        Set<Candidate> expected=winners(candidates);
        check(!expected.isEmpty(),"some candidate survives");
        for (int i=0;i<20;i++) {
            Collections.shuffle(candidates,random);
            check(winners(candidates).equals(expected),"permutation invariant winners");
        }
        for (Candidate left:expected) for (Candidate right:expected) if (left!=right) {
            check(!left.envelope().conflicts(right.envelope(),Math.max(left.gap(),right.gap())),"all accepted pairs preserve both policies");
        }
        check(PlacementGeometry.priority(42,"a",-3,8)==PlacementGeometry.priority(42,"a",-3,8),"repeatable seed");
        check(PlacementGeometry.priority(42,"a",-3,8)!=PlacementGeometry.priority(42,"b",-3,8),"namespace influences priority");
        int lowPriority=0,thinned=0;
        for (int seed=0;seed<20000;seed++) {
            double rank=(PlacementGeometry.priority(seed,"mod:a",-3,8)>>>11)*0x1.0p-53;
            if (rank<0.05) {
                lowPriority++;
                if (PlacementGeometry.densityFraction(seed,"mod:a",-3,8)<0.5) thinned++;
            }
        }
        check(thinned>lowPriority*0.4 && thinned<lowPriority*0.6,"density thinning remains unbiased among low-priority winners");
        System.out.println("PlacementGeometryTest: "+assertions+" assertions passed; "+expected.size()+" adversarial winners");
    }
}
