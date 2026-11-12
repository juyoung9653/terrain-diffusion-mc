import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Document;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Override;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StructureConfigTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(Document d) {
        try { StructurePlacementConfig.validate(d); throw new AssertionError("invalid config accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "config-test-");
        PlatformPaths.configure(root.resolve("config"), root);
        Document d = new Document();
        d.global.minGapBlocks = 120;
        Override mod = new Override(); mod.minGapBlocks = 180; mod.maxCutBlocks = 1;
        d.namespaces.put("test", mod);
        Override structure = new Override(); structure.minGapBlocks = 240;
        d.structures.put("test:village", structure);
        var snapshot = StructurePlacementConfig.validate(d);
        for (String id : new String[]{"minecraft:ruined_portal", "minecraft:ruined_portal_desert",
                "minecraft:ruined_portal_jungle", "minecraft:ruined_portal_mountain",
                "minecraft:ruined_portal_nether", "minecraft:ruined_portal_ocean",
                "minecraft:ruined_portal_swamp"})
            check(snapshot.resolve(id).spacingMultiplier() == 2.0,
                    "every vanilla ruined portal defaults to double spacing: " + id);
        check(snapshot.resolve("minecraft:village_plains").spacingMultiplier() == 1.0,
                "ruined-portal default leaves other structures unchanged");
        Override portalOverride = new Override(); portalOverride.spacingMultiplier = 1.0;
        d.structures.put("minecraft:ruined_portal", portalOverride);
        check(StructurePlacementConfig.validate(d).resolve("minecraft:ruined_portal").spacingMultiplier() == 1.0,
                "explicit ruined-portal setting overrides the default");
        d.structures.remove("minecraft:ruined_portal");
        Override portalPartial = new Override(); portalPartial.surfaceMode = StructurePlacementConfig.SurfaceMode.PRESERVE;
        d.structures.put("minecraft:ruined_portal", portalPartial);
        var partialPortal = StructurePlacementConfig.validate(d).resolve("minecraft:ruined_portal");
        check(partialPortal.spacingMultiplier() == 2.0 && partialPortal.surfaceMode() == StructurePlacementConfig.SurfaceMode.PRESERVE,
                "partial ruined-portal override retains its setting and inherits double spacing");
        d.structures.remove("minecraft:ruined_portal");
        check(!snapshot.villages().enabled() && snapshot.villages().targetMeanNearestNeighborBlocks()==500
                && snapshot.villages().basicSharePercent()==70, "legacy config leaves unified villages disabled");
        d.villageDistribution.enabled=true;
        d.villageDistribution.basicSharePercent=101; invalid(d); d.villageDistribution.basicSharePercent=70;
        d.villageDistribution.targetMeanNearestNeighborBlocks=255; invalid(d); d.villageDistribution.targetMeanNearestNeighborBlocks=500;
        check(snapshot.villages().algorithmVersion()==2,"new defaults use expanded candidate search");
        d.villageDistribution.algorithmVersion=1;
        check(StructurePlacementConfig.validate(d).villages().algorithmVersion()==1,"legacy algorithm remains supported");
        d.villageDistribution.algorithmVersion=3; invalid(d); d.villageDistribution.algorithmVersion=2;
        check(!snapshot.villages().enabled(), "village snapshot detached from UI draft");
        check(snapshot.resolve("other:house").minGapBlocks() == 120, "global inheritance");
        check(snapshot.resolve("test:house").minGapBlocks() == 180, "mod inheritance");
        check(snapshot.resolve("test:village").minGapBlocks() == 240, "structure override");
        check(snapshot.resolve("test:village").maxCutBlocks() == 1, "partial override inheritance");
        mod.minGapBlocks = 500;
        check(snapshot.resolve("test:house").minGapBlocks() == 180, "snapshot detached from UI draft");
        snapshot.document().global.minGapBlocks = 700;
        check(snapshot.global().minGapBlocks() == 120, "document copy detached");
        d.global.minGapBlocks = 99; invalid(d); d.global.minGapBlocks = 120;
        d.global.spacingMultiplier = Double.NaN; invalid(d); d.global.spacingMultiplier = 1.4;
        d.structures.put("bad", structure); invalid(d); d.structures.remove("bad");
        d.structures.put("minecraft:ruined_portal", null); invalid(d); d.structures.remove("minecraft:ruined_portal");
        d.global.maxCandidateOffsetBlocks = 8; invalid(d); d.global.maxCandidateOffsetBlocks = 7;
        d.global.maxCandidateAttempts = 10; invalid(d); d.global.maxCandidateAttempts = 9;
        StructurePlacementConfig.save(d);
        check(StructurePlacementConfig.readDocument().global.spacingMultiplier == 1.4, "disk roundtrip");
        check(StructurePlacementConfig.readDocument().structures.get("minecraft:ruined_portal").spacingMultiplier == 2.0,
                "older config is upgraded with the ruined-portal distance default");
        Object registry = new Object(); Path world = root.resolve("world");
        StructurePlacementConfig.bindWorld(registry, world);
        String originalPin = Files.readString(world.resolve("data/td-structure-placement.json"));
        check(StructurePlacementConfig.snapshotFor(registry).global().minGapBlocks() == 120, "initial binding");
        d.global.minGapBlocks = 300; StructurePlacementConfig.save(d);
        check(StructurePlacementConfig.snapshotFor(registry).global().minGapBlocks() == 120, "live world pinned");
        StructurePlacementConfig.unbindWorld(registry);
        Object restartedRegistry = new Object();
        StructurePlacementConfig.bindWorld(restartedRegistry, world);
        check(StructurePlacementConfig.snapshotFor(restartedRegistry).global().minGapBlocks() == 300, "restart refreshes distance");
        var terrainBefore = StructurePlacementConfig.snapshotFor(restartedRegistry).resolve("new_mod:house");
        d.global.maxCutBlocks = 7;
        d.global.spacingMultiplier = 2.0;
        d.villageDistribution.targetMeanNearestNeighborBlocks=640;
        d.villageDistribution.basicSharePercent=80;
        var newNamespace = new StructurePlacementConfig.Override(); newNamespace.minGapBlocks = 400; newNamespace.maxFillBlocks = 8;
        d.namespaces.put("new_mod",newNamespace);
        var newId = new StructurePlacementConfig.Override();newId.minGapBlocks = 500;newId.maxCutBlocks = 8;
        d.structures.put("new_mod:house",newId);
        StructurePlacementConfig.save(d);
        Object updatedRegistry = new Object(); StructurePlacementConfig.bindWorld(updatedRegistry, world);
        var updated = StructurePlacementConfig.snapshotFor(updatedRegistry);
        check(updated.resolve("new_mod:other").minGapBlocks() == 400 && updated.resolve("new_mod:house").minGapBlocks() == 500, "new scope distance overrides");
        check(updated.global().spacingMultiplier() == 2.0, "density updates on reload");
        check(updated.villages().enabled() && updated.villages().targetMeanNearestNeighborBlocks()==640
                && updated.villages().basicSharePercent()==80, "village distribution refreshes on reload without changing terrain pin");
        check(updated.resolve("new_mod:house").maxCutBlocks() == terrainBefore.maxCutBlocks()
                && updated.resolve("new_mod:other").maxFillBlocks() == terrainBefore.maxFillBlocks(), "geometry remains pinned for new scopes");
        check(StructurePlacementConfig.validate(updated.document()).resolve("new_mod:house").equals(updated.resolve("new_mod:house")), "effective snapshot document is consistent");
        d.namespaces.remove("new_mod"); d.structures.remove("new_mod:house");
        d.namespaces.clear(); d.structures.clear(); StructurePlacementConfig.save(d);
        Object removedRegistry = new Object();StructurePlacementConfig.bindWorld(removedRegistry,world);
        check(StructurePlacementConfig.snapshotFor(removedRegistry).resolve("new_mod:house").minGapBlocks() == 300, "removed current scope inherits global distance");
        check(StructurePlacementConfig.snapshotFor(removedRegistry).resolve("test:village").minGapBlocks() == 300
                && StructurePlacementConfig.snapshotFor(removedRegistry).resolve("test:village").maxCutBlocks() == 1,
                "removed pinned scope retains terrain but inherits current global distance");
        check(originalPin.equals(Files.readString(world.resolve("data/td-structure-placement.json"))), "distance refresh never rewrites geometry pin");
        String frozen = Files.readString(world.resolve("data/td-structure-placement.json"));
        check(frozen.contains("maxFillBlocks") && frozen.contains("surfaceMode"), "world snapshot stores resolved defaults");
        Object nextRegistry = new Object(); StructurePlacementConfig.bindWorld(nextRegistry, root.resolve("next-world"));
        check(StructurePlacementConfig.snapshotFor(nextRegistry).global().minGapBlocks() == 300, "new world uses new defaults");
        for (boolean pinned : new boolean[]{false,true}) for (boolean current : new boolean[]{false,true}) {
            Document matrix = new Document(); matrix.enabled = pinned; matrix.villageDistribution.enabled = true;
            StructurePlacementConfig.save(matrix);
            Path matrixWorld = root.resolve("matrix-"+pinned+"-"+current);
            Object initial = new Object(); StructurePlacementConfig.bindWorld(initial,matrixWorld);
            StructurePlacementConfig.unbindWorld(initial);
            matrix.enabled = current; StructurePlacementConfig.save(matrix);
            Object rebound = new Object(); StructurePlacementConfig.bindWorld(rebound,matrixWorld);
            var matched = StructurePlacementConfig.snapshotMatching(candidate -> candidate == rebound);
            check(matched != null && matched.enabled() == pinned, "placement rewrite uses pinned enablement");
            check(matched.villages().enabled(), "village setting survives pinned enabled matrix");
            StructurePlacementConfig.unbindWorld(rebound);
            try {
                StructurePlacementConfig.snapshotMatching(candidate -> candidate == rebound);
                throw new AssertionError("unbound registry enabled synthetic placement");
            } catch (IllegalStateException expected) { }
        }
        Files.writeString(world.resolve("data/td-structure-placement.json"), "{broken");
        try { StructurePlacementConfig.bindWorld(new Object(), world); throw new AssertionError("corrupt world policy ignored"); }
        catch (IllegalStateException expected) { }
        for (String invalidJson : new String[]{"{\"global\":{\"surfaceMode\":\"unknown\"}}", "{\"global\":{\"minGapBlocks\":100.5}}", "{\"global\":{\"minDistance\":100}}",
                "{\"villageDistribution\":{\"enabled\":\"true\"}}", "{\"villageDistribution\":{\"basicSharePercent\":70.5}}",
                "{\"villageDistribution\":{\"targetMeanNearestNeighborBlocks\":null}}", "{\"villageDistribution\":{\"spacing\":500}}"}) {
            Files.writeString(StructurePlacementConfig.configPath(), invalidJson);
            try { StructurePlacementConfig.readDocument(); throw new AssertionError("Bad config silently accepted: " + invalidJson); }
            catch (java.io.IOException expected) { }
        }
        System.out.println("PASS: config inheritance, validation, immutability, atomic persistence and world pinning");
    }
}
