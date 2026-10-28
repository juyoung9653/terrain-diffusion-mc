package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Snapshot;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.*;
import java.util.*;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.structures.*;
import com.github.xandergos.terraindiffusionmc.mixin.StructurePlacementAccess;

/** Bounded, chunk-free preflight arbitration; never requests neighboring STARTS. */
public final class StructureReservations {
    // Audited installed implementations retain exactly one seed-only grid origin per cell.
    // Their biome/ring/exclusion predicates only remove origins; those predicates remain
    // in vanilla createStructures, never in this terrain-free reservation enumeration.
    private static final Set<String> VERIFIED_SPREAD_SUBCLASSES=Set.of(
            "com.github.L_Ender.cataclysm.world.structures.placements.CataclysmRandomSpread",
            "com.yungnickyoung.minecraft.betterdeserttemples.world.placement.BetterDesertTemplePlacement",
            "com.yungnickyoung.minecraft.betterjungletemples.world.placement.BetterJungleTemplePlacement",
            "com.yungnickyoung.minecraft.betterstrongholds.world.placement.BetterStrongholdsPlacement");
    // Reviewed native assembly paths use only seeded local builders, templates and generator
    // height/biome queries. Stronghold is excluded because its piece selection mutates statics.
    private static final Set<Class<?>> PREFLIGHT_STRUCTURES=Set.of(JigsawStructure.class,
            BuriedTreasureStructure.class,DesertPyramidStructure.class,IglooStructure.class,
            JungleTempleStructure.class,OceanRuinStructure.class,ShipwreckStructure.class,
            SwampHutStructure.class,RuinedPortalStructure.class,OceanMonumentStructure.class,
            MineshaftStructure.class,WoodlandMansionStructure.class);
    // Audited installed YUNG API 5.1.9 / Better Mineshafts 5.1.1. Root and assembly
    // bytecode guards prevent silently extending this audit to another implementation.
    private static final Map<String,Map<String,String>> MODDED_PREFLIGHT=Map.of(
            "com.yungnickyoung.minecraft.yungsapi.world.structure.YungJigsawStructure",Map.of(
                    "com/yungnickyoung/minecraft/yungsapi/world/structure/YungJigsawStructure.class","debd4d27d15afdb45dbacdf001529766bb63298a75610e83b1ec303c02f1cccd",
                    "com/yungnickyoung/minecraft/yungsapi/world/structure/jigsaw/JigsawManager.class","76073a9f443070a47a98c23401c82afb9dae3e1f43b4e96ef80c3f2e8226a8fd",
                    "com/yungnickyoung/minecraft/yungsapi/world/structure/jigsaw/assembler/JigsawStructureAssembler.class","3ec95fd31842f25683e28666ca898f4e5943436dbe24f08fa8ed7a3dc6519814"),
            "com.yungnickyoung.minecraft.bettermineshafts.world.BetterMineshaftStructure",Map.of(
                    "com/yungnickyoung/minecraft/bettermineshafts/world/BetterMineshaftStructure.class","dcd0e012a4a0abf8e9809411cf18dd6170dcc73975bdca6d9c81ace1193dab05",
                    "com/yungnickyoung/minecraft/bettermineshafts/world/generator/BetterMineshaftGenerator.class","75fc2ffa030910e142eabdab2e1cdc22f8736eed3cf63f8126ee14d31abac7f6"));
    private final Map<Class<?>,Boolean> verifiedModdedClasses=new java.util.concurrent.ConcurrentHashMap<>();
    public record Descriptor(Structure structure, int attempt, PlacementGeometry.Rect bounds,
            PlacementGeometry.Rect influence, List<String> pieces, String terrainFingerprint, int gap, boolean surface, boolean conservative) {}
    public record Reservation(PlacementGeometry.Candidate candidate, Descriptor descriptor) {}
    @FunctionalInterface public interface Preflight {
        Descriptor assemble(Structure structure, ChunkPos owner);
        default Descriptor assemble(Structure structure, ChunkPos owner, StructureHeightCache heights) {
            return assemble(structure,owner);
        }
    }
    private record Key(String set, int x, int z) {}
    private final PlacementGeometry.PreflightCache<Key,Optional<Descriptor>> preflights=new PlacementGeometry.PreflightCache<>(8192);
    private record VillageChoice(ChunkPos owner,Descriptor descriptor) {}
    private final PlacementGeometry.PreflightCache<Key,Optional<VillageChoice>> villageCells=new PlacementGeometry.PreflightCache<>(2048);
    private final PlacementGeometry.AcceptedIndex<Key,Descriptor> accepted=new PlacementGeometry.AcceptedIndex<>();
    private record SetPlan(String id, StructureSet set, int radius, int gap, double multiplier) {}
    private final ChunkGeneratorStructureState state;
    private final List<SetPlan> sets;
    private final Set<String> preflightFailures=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Registry<Structure> registry;
    private final Snapshot config;

    public StructureReservations(ChunkGeneratorStructureState state, Registry<Structure> registry, Snapshot config) {
        this.state = state;
        this.registry=registry;
        this.config=config;
        List<SetPlan> plans = new ArrayList<>();
        for (Holder<StructureSet> holder : state.possibleStructureSets()) {
            String id = holder.unwrapKey().map(k -> k.location().toString())
                    .orElseGet(() -> UnifiedVillageSets.syntheticId(holder.value()));
            if (id == null) { LogUtils.getLogger().warn("TD structure reservation rejects unregistered structure set"); continue; }
            StructurePlacement placement = holder.value().placement();
            boolean supportedSpread=placement instanceof RandomSpreadStructurePlacement
                    && (placement.getClass()==RandomSpreadStructurePlacement.class
                    || placement.getClass()==UnifiedVillagePlacement.class || VERIFIED_SPREAD_SUBCLASSES.contains(placement.getClass().getName()));
            if (!supportedSpread && placement.getClass() != ConcentricRingsStructurePlacement.class) {
                LogUtils.getLogger().warn("TD structure reservation rejects unsupported placement {} for {}", placement.getClass().getName(), id);
                continue;
            }
            int radius = 0, gap = 100;
            double multiplier = 1;
            for (StructureSet.StructureSelectionEntry entry : holder.value().structures()) {
                var policy = config.resolve(String.valueOf(registry.getKey(entry.structure().value())));
                radius = Math.max(radius, policy.reservationRadiusBlocks());
                gap = Math.max(gap, policy.minGapBlocks());
                multiplier = Math.max(multiplier, policy.spacingMultiplier());
            }
            plans.add(new SetPlan(id, holder.value(), radius, gap, multiplier));
        }
        plans.sort(Comparator.comparing(SetPlan::id));
        sets = List.copyOf(plans);
    }

    private PlacementGeometry.Candidate candidate(SetPlan set, ChunkPos pos) {
        return new PlacementGeometry.Candidate(set.id, pos.x, pos.z, set.radius, set.gap,
                PlacementGeometry.priority(state.getLevelSeed(), set.id, pos.x, pos.z));
    }

    /** Final actual-start reservation; this planner belongs to exactly one structure state/dimension. */
    public boolean accept(ChunkPos owner,Descriptor descriptor) {
        return accepted.accept(new Key(String.valueOf(registry.getKey(descriptor.structure())),owner.x,owner.z),
                descriptor,descriptor.bounds(),descriptor.influence(),descriptor.gap());
    }

    /** Exact vanilla weighted draw/removal order, including fallback after local policy failure. */
    private Descriptor preflight(SetPlan set, ChunkPos owner, Preflight assembly) {
        return preflights.get(new Key(set.id,owner.x,owner.z),() -> {
            try {
                var placement=set.set.placement();
                if (placement instanceof UnifiedVillagePlacement villages) {
                    VillageChoice choice=villageChoice(set,villages,owner,assembly);
                    return choice!=null && choice.owner().equals(owner) ? Optional.of(choice.descriptor()) : Optional.empty();
                }
                // The custom placement grid origins are audited, but their full biome/ring searches
                // are deliberately not invoked from speculative worldgen. Preserve a conservative
                // envelope for these and for unaudited structure implementations.
                if (!auditedPlacement(placement,Collections.newSetFromMap(new IdentityHashMap<>())))
                    return densityEligible(set,owner) ? Optional.of(conservative(set,owner)) : Optional.empty();
                if (!placement.isStructureChunk(state,owner.x,owner.z)) return Optional.empty();
                List<StructureSet.StructureSelectionEntry> entries=new ArrayList<>(set.set.structures());
                WorldgenRandom random=new WorldgenRandom(new LegacyRandomSource(0L));
                random.setLargeFeatureSeed(state.getLevelSeed(),owner.x,owner.z);
                int weight=entries.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
                while (!entries.isEmpty()) {
                    int index=0;
                    if (entries.size()>1) {
                        int draw=random.nextInt(weight);
                        for (;index<entries.size()-1;index++) {
                            draw-=entries.get(index).weight();
                            if (draw<0) break;
                        }
                    } else if (set.set.structures().size()>1) {
                        // Vanilla still consumes the weighted draw for the final fallback.
                        random.nextInt(weight);
                    }
                    var entry=entries.remove(index);
                    weight-=entry.weight();
                    Structure selected=entry.structure().value();
                    if (!auditedStructure(selected))
                        return densityEligible(set,owner) ? Optional.of(conservative(set,owner)) : Optional.empty();
                    Descriptor result=assembly.assemble(selected,owner);
                    if (result!=null) {
                        double multiplier=config.resolve(String.valueOf(registry.getKey(selected))).spacingMultiplier();
                        return PlacementGeometry.densityFraction(state.getLevelSeed(),set.id,owner.x,owner.z)
                                < 1.0/(multiplier*multiplier) ? Optional.of(result) : Optional.empty();
                    }
                }
                return Optional.empty();
            } catch (RuntimeException failure) {
                // Streams' trial pass uses this as asynchronous control flow. Its outer
                // ChunkStepDefer awaits the river plates and retries STRUCTURE_STARTS.
                // Caching an empty/conservative result here permanently loses candidates.
                if (StructurePreflightControl.isDeferred(failure)) throw failure;
                // Speculative failures cannot crash an unrelated owner. The actual native
                // assembly below arbitration still propagates its own failure normally.
                // The maximum set density/gap and envelope are deliberately conservative.
                if (preflightFailures.add(set.id)) LogUtils.getLogger().warn(
                        "TD speculative structure preflight failed for {}; retaining conservative reservation",set.id,failure);
                if (set.set.placement() instanceof UnifiedVillagePlacement) return Optional.empty();
                return densityEligible(set,owner) ? Optional.of(conservative(set,owner)) : Optional.empty();
            }
        }).orElse(null);
    }

    /** Group quotas apply to viable local choices, never to a weighted native fallback. */
    private VillageChoice villageChoice(SetPlan set,UnifiedVillagePlacement placement,ChunkPos owner,Preflight assembly) {
        int cellX=placement.cellX(owner),cellZ=placement.cellZ(owner);
        return villageCells.get(new Key(set.id,cellX,cellZ),() -> {
            // Only this synchronous choice shares raw heights. Cached descriptors and
            // concurrent/deferred attempts never retain or share this mutable cache.
            var heights=new StructureHeightCache();
            try {
                var basic=firstVillage(set,placement,owner,VillageDistributionGeometry.Group.BASIC,assembly,heights);
                var distinctive=firstVillage(set,placement,owner,VillageDistributionGeometry.Group.DISTINCTIVE,assembly,heights);
                String availability=basic!=null && distinctive!=null ? "both groups eligible"
                        : basic!=null ? "basic only eligible" : distinctive!=null ? "distinctive only eligible" : "no group eligible";
                StructurePlacementRuntime.count(VillageDistributionGeometry.SET_ID,availability);
                var chosen=VillageDistributionGeometry.choose(state.getLevelSeed(),cellX,cellZ,
                        placement.settings().basicSharePercent(),basic!=null,distinctive!=null);
                if (chosen==null) return Optional.empty();
                if (basic==null || distinctive==null) StructurePlacementRuntime.count(VillageDistributionGeometry.SET_ID,"availability fallback");
                StructurePlacementRuntime.count(VillageDistributionGeometry.SET_ID,"selected "+chosen.name().toLowerCase(java.util.Locale.ROOT));
                return Optional.of(chosen==VillageDistributionGeometry.Group.BASIC ? basic : distinctive);
            } finally {
                StructurePlacementRuntime.recordHeightCache(heights.snapshot());
            }
        }).orElse(null);
    }

    private VillageChoice firstVillage(SetPlan set,UnifiedVillagePlacement placement,ChunkPos owner,
            VillageDistributionGeometry.Group group,Preflight assembly,StructureHeightCache heights) {
        List<StructureSet.StructureSelectionEntry> members=set.set.structures().stream().filter(entry ->
                VillageDistributionGeometry.group(String.valueOf(registry.getKey(entry.structure().value())),placement.epicLoaded())==group).toList();
        if (members.isEmpty()) return null;
        for (ChunkPos hop:placement.candidates(state.getLevelSeed(),owner)) {
            var ordered=new ArrayList<>(members);
            ordered.sort(Comparator.comparingLong(entry -> PlacementGeometry.priority(state.getLevelSeed(),
                    String.valueOf(registry.getKey(entry.structure().value())),hop.x,hop.z)));
            for (var entry:ordered) {
                Structure structure=entry.structure().value();
                if (!auditedStructure(structure)) continue;
                String id=String.valueOf(registry.getKey(structure));
                if (!VillageDistributionGeometry.densityEligible(state.getLevelSeed(),id,hop.x,hop.z,
                        config.resolve(id).spacingMultiplier())) {
                    StructurePlacementRuntime.count(VillageDistributionGeometry.SET_ID,"member density rejected");
                    continue;
                }
                StructurePlacementRuntime.count(VillageDistributionGeometry.SET_ID,"member preflights");
                Descriptor descriptor=assembly.assemble(structure,hop,heights);
                if (descriptor!=null) return new VillageChoice(hop,descriptor);
            }
        }
        return null;
    }

    private boolean auditedStructure(Structure structure) {
        Class<?> type=structure.getClass();
        if (PREFLIGHT_STRUCTURES.contains(type)) return true;
        var expected=MODDED_PREFLIGHT.get(type.getName());
        if (expected==null) return false;
        return verifiedModdedClasses.computeIfAbsent(type,key -> {
            boolean verified=true;
            try {
                for (var entry:expected.entrySet()) {
                    try (var input=key.getResourceAsStream("/"+entry.getKey())) {
                        if (input==null || !HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(input.readAllBytes())).equals(entry.getValue())) { verified=false; break; }
                    }
                }
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException unavailable) { verified=false; }
            if (!verified) LogUtils.getLogger().warn("TD structure preflight audit does not match {}; retaining conservative reservation",key.getName());
            return verified;
        });
    }

    private boolean auditedPlacement(StructurePlacement placement,Set<StructurePlacement> seen) {
        if (placement.getClass()!=RandomSpreadStructurePlacement.class
                && placement.getClass()!=ConcentricRingsStructurePlacement.class) return false;
        if (!seen.add(placement)) return false;
        var exclusion=((StructurePlacementAccess)(Object)placement).terrainDiffusion$exclusionZone();
        return exclusion.isEmpty() || auditedPlacement(exclusion.get().otherSet().value().placement(),seen);
    }

    private boolean densityEligible(SetPlan set,ChunkPos owner) {
        return PlacementGeometry.densityFraction(state.getLevelSeed(),set.id,owner.x,owner.z)
                < 1.0/(set.multiplier*set.multiplier);
    }
    private Descriptor conservative(SetPlan set,ChunkPos owner) {
        var bounds=candidate(set,owner).envelope();
        var dependency=new PlacementGeometry.Rect(owner.getMinBlockX()-128,owner.getMinBlockZ()-128,
                owner.getMaxBlockX()+128,owner.getMaxBlockZ()+128);
        return new Descriptor(null,-1,bounds,bounds.union(dependency),List.of(),"",set.gap,false,true);
    }
    private PlacementGeometry.Viable viable(SetPlan set,ChunkPos owner,Descriptor descriptor) {
        var raw=candidate(set,owner);
        var actual=new PlacementGeometry.Candidate(raw.id(),raw.chunkX(),raw.chunkZ(),raw.radius(),descriptor.gap(),raw.priority(),
                !descriptor.conservative() && descriptor.surface() ? 0 : 1);
        return new PlacementGeometry.Viable(actual,descriptor.bounds(),descriptor.influence());
    }

    public Reservation winner(Structure structure,ChunkPos owner,StructureSet generatingSet,Preflight assembly) {
        for (SetPlan own:sets) {
            if (own.set!=generatingSet || !rawOrigin(own.set.placement(),owner)) continue;
            Descriptor selected=preflight(own,owner,assembly);
            // Never let native fallback replace a selected entry rejected by arbitration.
            if (selected==null || (!selected.conservative() && selected.structure()!=structure)) return null;
            var candidate=viable(own,owner,selected);
            for (SetPlan other:sets) {
                int range=(Math.max(own.radius,136)+Math.max(other.radius,136)+Math.max(own.gap,other.gap)+31)/16;
                for (ChunkPos origin:origins(other,owner,range)) {
                    var rival=candidate(other,origin);
                    // Optimistic surface tier is essential: a raw seed rank cannot prune a
                    // viable surface candidate that outranks an underground/conservative one.
                    var optimistic=rival.withTier(0);
                    if (optimistic.comparePriority(candidate.candidate())>=0) continue;
                    var possible=new PlacementGeometry.Viable(optimistic,rival.envelope(),conservative(other,origin).influence());
                    if (!possible.defeats(candidate)) continue;
                    Descriptor descriptor=preflight(other,origin,assembly);
                    if (descriptor!=null && viable(other,origin,descriptor).defeats(candidate)) return null;
                }
            }
            return new Reservation(candidate.candidate(),selected);
        }
        return null;
    }

    public PlacementGeometry.Candidate winner(Structure structure, ChunkPos pos) {
        return winner(structure,pos,null);
    }
    public PlacementGeometry.Candidate winner(Structure structure, ChunkPos pos, StructureSet generatingSet) {
        for (SetPlan own : sets) {
            if (generatingSet!=null && generatingSet!=own.set) continue;
            if (own.set.structures().stream().noneMatch(e -> e.structure().value() == structure)
                    || !rawOrigin(own.set.placement(),pos)) continue;
            var candidate = candidate(own, pos);
            // Legacy terrain-free preview only; real STARTS use the descriptor overload above.
            double fraction = PlacementGeometry.densityFraction(state.getLevelSeed(),own.id,pos.x,pos.z);
            if (fraction >= 1.0 / (own.multiplier * own.multiplier)) continue;
            boolean defeated = false;
            for (SetPlan other : sets) {
                int range = (own.radius + other.radius + Math.max(own.gap, other.gap) + 31) / 16;
                for (ChunkPos origin : origins(other, pos, range)) {
                    if (densityEligible(other,origin) && candidate(other, origin).defeats(candidate)) { defeated = true; break; }
                }
                if (defeated) break;
            }
            if (!defeated) return candidate;
        }
        return null;
    }

    private boolean rawOrigin(StructurePlacement placement,ChunkPos pos) {
        if (!placement.applyAdditionalChunkRestrictions(pos.x,pos.z,state.getLevelSeed())) return false;
        if (placement instanceof RandomSpreadStructurePlacement spread) {
            return spread.getPotentialStructureChunk(state.getLevelSeed(),pos.x,pos.z).equals(pos);
        }
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            List<ChunkPos> positions=state.getRingPositionsFor(rings);
            return positions!=null && positions.contains(pos);
        }
        return false;
    }

    /** Bounded, terrain-free audit/preview candidates; weighted biome selection still happens at STARTS. */
    public List<PlacementGeometry.Candidate> nearbyWinners(ChunkPos center, int chunkRadius) {
        if (chunkRadius<0 || chunkRadius>256) throw new IllegalArgumentException("Audit radius must be 0..256 chunks");
        Map<String,PlacementGeometry.Candidate> result=new TreeMap<>();
        for (SetPlan set:sets) for (ChunkPos origin:origins(set,center,chunkRadius)) {
            for (var entry:set.set.structures()) {
                var winner=winner(entry.structure().value(),origin);
                if (winner!=null) { result.put(winner.id()+":"+winner.chunkX()+":"+winner.chunkZ(),winner); break; }
            }
        }
        return List.copyOf(result.values());
    }

    private List<ChunkPos> origins(SetPlan plan, ChunkPos center, int range) {
        StructurePlacement placement = plan.set.placement();
        List<ChunkPos> result = new ArrayList<>();
        if (placement instanceof RandomSpreadStructurePlacement spread) {
            int spacing = spread.spacing();
            for (int x = Math.floorDiv(center.x - range, spacing); x <= Math.floorDiv(center.x + range, spacing); x++) {
                for (int z = Math.floorDiv(center.z - range, spacing); z <= Math.floorDiv(center.z + range, spacing); z++) {
                    ChunkPos pos = spread.getPotentialStructureChunk(state.getLevelSeed(), x * spacing, z * spacing);
                    if (Math.abs((long)pos.x - center.x) <= range && Math.abs((long)pos.z - center.z) <= range
                            && placement.applyAdditionalChunkRestrictions(pos.x,pos.z,state.getLevelSeed())) result.add(pos);
                }
            }
        } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
            List<ChunkPos> positions = state.getRingPositionsFor(rings);
            if (positions != null) for (ChunkPos pos : positions) {
                if (Math.abs((long)pos.x - center.x) <= range && Math.abs((long)pos.z - center.z) <= range
                        && placement.applyAdditionalChunkRestrictions(pos.x,pos.z,state.getLevelSeed())) result.add(pos);
            }
        }
        return result;
    }
}
