package com.github.xandergos.terraindiffusionmc.world.structure;

import com.github.xandergos.terraindiffusionmc.mixin.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;

/** Entity/jigsaw marker templates are components, but are not buildings or foundations. */
public final class StructurePieceOccupancy {
    private record Templates(ComponentGeometry.Footprint legacy,ComponentGeometry.Footprint single,
                             Map<Integer,Optional<List<PlacementGeometry.Rect>>> groundMasks) {}
    private static final Map<StructureTemplate,Templates> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>,Boolean> AUDITED=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList,Boolean>
            PROCESSORS=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final String VERSIONED="com.finndog.moogs_structures.world.structures.pieces.VersionAwareSinglePoolElement";
    // Moog's Structure Lib 3.4.2 resolves the inherited template once; its extra processor changes entities only.
    private static final Map<String,String> VERSIONED_BYTES=Map.of(
            "com/finndog/moogs_structures/world/structures/pieces/VersionAwareSinglePoolElement.class","d0d4fda99e1dede2996587300edca03849d66be6bdb2ff1667cf1575b4c4cca3",
            "com/finndog/moogs_structures/world/processors/HangingEntityAnchorProcessor.class","2b84dcdd9152371ac6a1d8e0bb9929c6f028ac6006b0c196a52d046fbdb3ee97",
            "com/finndog/moogs_structures/world/processors/StructureEntityProcessor.class","7090d063c537ac20c013f19664155f3c7f5266e0a15bdd9a0e4dc4a3cfdd4cd8");
    private StructurePieceOccupancy() {}
    public static void clear() { CACHE.clear(); AUDITED.clear(); PROCESSORS.clear(); }
    public static boolean hasBlocks(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pool)) return true;
        return hasBlocks(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates(),false);
    }
    /** Paths still enter grade validation; features and AIR-only entity markers never request a pad. */
    public static boolean requiresTerrainSupport(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pool)) return true;
        return hasBlocks(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates(),true);
    }
    public static boolean buriedWellBottom(StructurePiece piece,List<StructurePiece> pieces) {
        if(piece.getClass()!=PoolElementStructurePiece.class) return false;
        var pool=(PoolElementStructurePiece)piece;
        if(pool.getElement().getClass()!=LegacySinglePoolElement.class
                || pool.getElement().getProjection()!=StructureTemplatePool.Projection.RIGID) return false;
        var access=(SinglePoolTemplateAccess)pool.getElement();
        String id=access.terrainDiffusion$templateIdentity().left().map(Object::toString).orElse("");
        if(!id.equals("minecraft:village/common/well_bottom")
                && !id.equals("terrain-diffusion-mc:basic_village/common/well_bottom")) return false;
        if(templateData(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates())==null) return false;
        var box=piece.getBoundingBox();
        for(var parent:pieces) {
            if(parent==piece || parent.getClass()!=PoolElementStructurePiece.class) continue;
            var element=((PoolElementStructurePiece)parent).getElement();
            if(element.getClass()!=LegacySinglePoolElement.class
                    || element.getProjection()!=StructureTemplatePool.Projection.RIGID) continue;
            String parentId=((SinglePoolTemplateAccess)element).terrainDiffusion$templateIdentity().left()
                    .map(Object::toString).orElse("");
            if(!parentId.startsWith("minecraft:village/") && !parentId.startsWith("terrain-diffusion-mc:basic_village/")) continue;
            if(ComponentGeometry.buriedWell(id,StructureTerrain.rect(box),box.minY(),box.maxY(),
                    StructureTerrain.rect(parent.getBoundingBox()),parent.getBoundingBox().minY())) {
                StructurePlacementRuntime.count("component geometry","buried well surface pad omitted");
                return true;
            }
        }
        return false;
    }
    /** Policy-two contact footprint. Null requests the original conservative whole-box pad. */
    public static List<PlacementGeometry.Rect> groundMask(PoolElementStructurePiece pool) {
        if (pool.getClass()!=PoolElementStructurePiece.class
                || pool.getElement().getProjection()!=StructureTemplatePool.Projection.RIGID) return null;
        int band=pool.getGroundLevelDelta();
        if (band<0 || band>16) return null;
        var data=templateData(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates());
        if (data==null) return null;
        List<PlacementGeometry.Rect> local;
        synchronized (data.groundMasks()) {
            local=data.groundMasks().computeIfAbsent(band,key -> Optional.ofNullable(
                    ComponentGeometry.groundMask(data.legacy().solid(),key))).orElse(null);
        }
        if (local==null) return null;
        int rotation=switch(pool.getRotation()) { case NONE -> 0; case CLOCKWISE_90 -> 1; case CLOCKWISE_180 -> 2; case COUNTERCLOCKWISE_90 -> 3; };
        return local.stream().map(rect -> ComponentGeometry.transform(rect,rotation,pool.getPosition().getX(),pool.getPosition().getZ())).toList();
    }
    /** Reproduce policy-one support exactly when rebuilding a previously accepted start's plan. */
    public static boolean hasLegacyTerrainSupport(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pool)) return true;
        return legacySupport(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates());
    }
    private static boolean legacySupport(StructurePoolElement element,StructureTemplateManager manager) {
        if (element==EmptyPoolElement.INSTANCE) return false;
        if (element.getClass()==ListPoolElement.class) {
            for (var child:((ListPoolElementsAccess)element).terrainDiffusion$elements()) if (legacySupport(child,manager)) return true;
            return false;
        }
        if (element.getClass()!=LegacySinglePoolElement.class) return true;
        // Saved policy-one plans used whole boxes for every processed template.
        if (!((SinglePoolTemplateAccess)element).terrainDiffusion$processors().value().list().isEmpty()) return true;
        var footprint=template(element,manager);
        return footprint==null || footprint.needsSupport();
    }
    /** Native terrain-matching paths deliberately share attachment envelopes with buildings. */
    public static boolean conflicts(StructurePiece first,StructurePiece second,boolean envelopesConflict) {
        if (!envelopesConflict) return false;
        if (nativePath(first) || nativePath(second) || nativeFeature(first) || nativeFeature(second)) return false;
        var a=occupied(first);
        var b=occupied(second);
        if (a==null || b==null) return true;
        return a.conflicts(b);
    }
    private static boolean nativeFeature(StructurePiece piece) {
        return piece.getClass()==PoolElementStructurePiece.class
                && ((PoolElementStructurePiece)piece).getElement().getClass()==FeaturePoolElement.class;
    }
    private static boolean nativePath(StructurePiece piece) {
        if (piece.getClass()!=PoolElementStructurePiece.class) return false;
        var element=((PoolElementStructurePiece)piece).getElement();
        if ((element.getClass()!=LegacySinglePoolElement.class && element.getClass()!=SinglePoolElement.class)
                || element.getProjection()!=StructureTemplatePool.Projection.TERRAIN_MATCHING) return false;
        var access=(SinglePoolTemplateAccess)element;
        if (!access.terrainDiffusion$templateIdentity().left().map(id -> ComponentGeometry.villagePath(id.toString())).orElse(false)) return false;
        // Native rule and rot processors alter states/remove blocks; custom position transforms stay conservative.
        return access.terrainDiffusion$processors().value().list().stream().allMatch(processor ->
                processor.getClass()==net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor.class
                        || processor.getClass()==net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor.class);
    }
    private static ComponentGeometry.Footprint occupied(StructurePiece piece) {
        if (piece.getClass()!=PoolElementStructurePiece.class) return null;
        var pool=(PoolElementStructurePiece)piece;
        if (pool.getElement().getProjection()!=StructureTemplatePool.Projection.RIGID) return null;
        var local=template(pool.getElement(),((PoolElementPieceAccess)pool).terrainDiffusion$templates());
        if (local==null) return null;
        int rotation=switch(pool.getRotation()) { case NONE -> 0; case CLOCKWISE_90 -> 1; case CLOCKWISE_180 -> 2; case COUNTERCLOCKWISE_90 -> 3; };
        return local.at(rotation,pool.getPosition().getX(),pool.getPosition().getY(),pool.getPosition().getZ());
    }
    private static boolean hasBlocks(StructurePoolElement element,StructureTemplateManager manager,boolean support) {
        // FeaturePoolElement's 1x1 box is a feature origin, not a building/foundation envelope.
        if (element==EmptyPoolElement.INSTANCE || element.getClass()==FeaturePoolElement.class) return false;
        if (element.getClass()==ListPoolElement.class) {
            for (var child:((ListPoolElementsAccess)element).terrainDiffusion$elements()) if (hasBlocks(child,manager,support)) return true;
            return false;
        }
        var footprint=template(element,manager);
        return footprint==null || (support ? footprint.needsSupport() : footprint.hasWrites());
    }
    private static ComponentGeometry.Footprint template(StructurePoolElement element,StructureTemplateManager manager) {
        Templates result=templateData(element,manager);
        return result==null ? null : element.getClass()==LegacySinglePoolElement.class ? result.legacy() : result.single();
    }
    private static Templates templateData(StructurePoolElement element,StructureTemplateManager manager) {
        if (!auditedSingle(element.getClass())) return null;
        var single=(SinglePoolTemplateAccess)element;
        // Unknown transforms may move blocks or turn AIR into solids. Retain conservative envelopes.
        if (!preservesFootprint(single.terrainDiffusion$processors().value())) return null;
        StructureTemplate template=single.terrainDiffusion$template(manager);
        Templates result;
        synchronized (CACHE) {
            result=CACHE.computeIfAbsent(template,key -> {
                var legacySolid=new HashSet<PlacementGeometry.Voxel>();
                var singleSolid=new HashSet<PlacementGeometry.Voxel>();
                var singleAir=new HashSet<PlacementGeometry.Voxel>();
                // Union all palettes. AIR in a Single (including MMV's wrapper) can destroy a wall.
                for (var palette:((StructureTemplatePaletteAccess)key).terrainDiffusion$palettes()) for (var block:palette.blocks()) {
                    String state=BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString();
                    String replacement=block.nbt()==null ? null : block.nbt().getString("final_state");
                    var voxel=new PlacementGeometry.Voxel(block.pos().getX(),block.pos().getY(),block.pos().getZ());
                    if (ComponentGeometry.write(state,replacement,true)==ComponentGeometry.Write.SOLID) legacySolid.add(voxel);
                    switch (ComponentGeometry.write(state,replacement,false)) {
                        case SOLID -> singleSolid.add(voxel);
                        case AIR -> singleAir.add(voxel);
                        default -> { }
                    }
                }
                return new Templates(new ComponentGeometry.Footprint(legacySolid,Set.of()),new ComponentGeometry.Footprint(singleSolid,singleAir),new HashMap<>());
            });
        }
        return result;
    }
    private static boolean preservesFootprint(net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList list) {
        if(list.list().isEmpty()) return true;
        synchronized(PROCESSORS) {
            return PROCESSORS.computeIfAbsent(list,key -> {
                if(key.list().size()!=1 || key.list().getFirst().getClass()!=
                        net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor.class) return false;
                boolean accepted=net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.SINGLE_CODEC
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE,key.list().getFirst()).result()
                        .map(MossProcessorGeometry::preservesFootprint).orElse(false);
                if(accepted) StructurePlacementRuntime.count("component geometry","moss-preserving processor list audited");
                return accepted;
            });
        }
    }
    private static boolean auditedSingle(Class<?> type) {
        if (type==LegacySinglePoolElement.class || type==SinglePoolElement.class) return true;
        if (!type.getName().equals(VERSIONED)) return false;
        return AUDITED.computeIfAbsent(type,key -> {
            try {
                for (var entry:VERSIONED_BYTES.entrySet()) try (var input=key.getResourceAsStream("/"+entry.getKey())) {
                    if (input==null || !HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(input.readAllBytes())).equals(entry.getValue())) return false;
                }
                return true;
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException unavailable) { return false; }
        });
    }
}
