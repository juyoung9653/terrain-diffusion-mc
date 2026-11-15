import com.github.xandergos.terraindiffusionmc.world.structure.LushCaveRuinsLegacyData;
import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LushCaveRuinsLegacyDataTest {
    static final String REGISTRY="minecraft:worldgen/configured_feature", ID="keeb_struc_lush:column_ruins_feature";
    static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JsonElement adapt(JsonElement value){return LushCaveRuinsLegacyData.adapt(REGISTRY,ID,value);}
    static JsonObject nested(JsonElement root){return root.getAsJsonObject().getAsJsonObject("config")
            .getAsJsonArray("features").get(0).getAsJsonObject().getAsJsonObject("feature").getAsJsonObject("feature");}
    static JsonElement expected(JsonElement original){
        JsonElement copy=original.deepCopy(); JsonObject source=nested(original), target=nested(copy);
        JsonObject config=new JsonObject(); config.add("state_provider",source.getAsJsonObject("config").get("trunk_provider").deepCopy());
        target.addProperty("type","minecraft:block_pile"); target.add("config",config); return copy;
    }
    static boolean fallen(JsonElement value){
        if(value.isJsonObject()){for(var entry:value.getAsJsonObject().entrySet()){
            if("type".equals(entry.getKey())&&entry.getValue().isJsonPrimitive()&&"minecraft:fallen_tree".equals(entry.getValue().getAsString()))return true;
            if(fallen(entry.getValue()))return true;
        }} else if(value.isJsonArray()) for(var child:value.getAsJsonArray()) if(fallen(child))return true;
        return false;
    }
    static JsonElement loot(String id, JsonElement value){return LushCaveRuinsLegacyData.adaptLoot(id,value);}
    static JsonElement structureSet(JsonElement value){return LushCaveRuinsLegacyData.adapt("minecraft:worldgen/structure_set","keeb_struc_lush:lush_ruins",value);}
    static int ownerBlock(int region,int offset,int spacing){return (region*spacing+offset)*16;}
    static boolean contains(JsonElement value,String name){
        if(value.isJsonObject()){for(var entry:value.getAsJsonObject().entrySet()){
            if("name".equals(entry.getKey())&&name.equals(entry.getValue().getAsString()))return true;
            if(contains(entry.getValue(),name))return true;
        }} else if(value.isJsonArray())for(var child:value.getAsJsonArray())if(contains(child,name))return true; return false;
    }
    public static void main(String[] args)throws Exception{
        JsonElement simple=JsonParser.parseString("""
          {"type":"minecraft:random_selector","config":{"features":[
            {"chance":0.35,"feature":{"feature":{"type":"minecraft:fallen_tree","config":{"trunk_provider":{"type":"minecraft:weighted_state_provider","entries":[{"weight":2,"data":{"Name":"minecraft:bricks"}}]},"log_length":5,"stump_decorators":[],"log_decorators":[]}}}},
            {"chance":0.2,"feature":{"feature":{"type":"minecraft:block_pile","config":{"state_provider":{"Name":"minecraft:granite"}}}}}
          ],"default":{"feature":{"type":"minecraft:block_column","config":{"layers":[{"provider":{"Name":"minecraft:terracotta"}}]}}}}}""");
        String before=simple.toString(); JsonElement expected=expected(simple); JsonElement converted=adapt(simple);
        ok(converted!=simple,"affected value deep-copied");
        ok(before.equals(simple.toString()),"original remains byte-for-byte JSON equal");
        ok(expected.equals(converted),"only exact nested branch changed");
        JsonObject first=nested(converted);
        ok("minecraft:block_pile".equals(first.get("type").getAsString()),"fallen tree converted");
        ok(first.getAsJsonObject("config").has("state_provider")&&!first.getAsJsonObject("config").has("log_length"),"provider copied without tree-only fields");
        ok(first.getAsJsonObject("config").get("state_provider").equals(nested(simple).getAsJsonObject("config").get("trunk_provider")),"provider copied exactly");
        ok(converted.getAsJsonObject().getAsJsonObject("config").getAsJsonArray("features").get(0).getAsJsonObject().get("chance").getAsDouble()==.35,"selector chance retained");
        ok(converted.toString().contains("minecraft:granite")&&converted.toString().contains("minecraft:terracotta"),"other variants and palette retained");
        ok(!fallen(converted),"repaired JSON contains no fallen tree");
        ok(adapt(converted)==converted,"idempotent after conversion");
        ok(LushCaveRuinsLegacyData.adapt("other",ID,simple)==simple&&LushCaveRuinsLegacyData.adapt(REGISTRY,"other",simple)==simple,"only exact target");
        JsonElement decorated=JsonParser.parseString("{\"type\":\"minecraft:fallen_tree\",\"config\":{\"trunk_provider\":{},\"stump_decorators\":[{}],\"log_decorators\":[]}}");
        ok(adapt(decorated)==decorated,"nonempty decorators never dropped");
        JsonElement set=JsonParser.parseString("{\"structures\":[{\"structure\":\"keeb_struc_lush:lush_cave_ruins\",\"weight\":1}],\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":14,\"separation\":8,\"salt\":12345,\"frequency\":0.7,\"exclusion_zone\":{\"other_set\":\"minecraft:trial_chambers\",\"chunk_count\":8}}}");
        String setBefore=set.toString(); JsonElement setExpected=set.deepCopy(); JsonObject expectedPlacement=setExpected.getAsJsonObject().getAsJsonObject("placement"); expectedPlacement.addProperty("spacing",69); expectedPlacement.addProperty("separation",63);
        JsonElement spaced=structureSet(set);
        ok(spaced!=set&&setBefore.equals(set.toString())&&setExpected.equals(spaced),"exact structure-set JSON preserves all other fields");
        JsonObject placement=spaced.getAsJsonObject().getAsJsonObject("placement");
        ok(placement.get("spacing").getAsInt()==69&&placement.get("separation").getAsInt()==63,"new spacing and separation exact");
        ok(placement.get("salt").getAsInt()==12345&&placement.has("exclusion_zone"),"salt and exclusion preserved");
        for(int ox=0;ox<6;ox++)for(int oz=0;oz<6;oz++)for(int nx=0;nx<6;nx++)for(int nz=0;nz<6;nz++){
            for(int rx:new int[]{-1,0})for(int rz:new int[]{-1,0}){
                int hereX=ownerBlock(rx,ox,69), hereZ=ownerBlock(rz,oz,69);
                for(int[] neighbor:new int[][]{{rx+1,rz},{rx,rz+1},{rx+1,rz+1}}){
                    int dx=Math.abs(ownerBlock(neighbor[0],nx,69)-hereX), dz=Math.abs(ownerBlock(neighbor[1],nz,69)-hereZ);
                    ok(Math.max(dx,dz)>=1024,"adjacent-grid owner references separated at offsets "+ox+","+oz+" / "+nx+","+nz);
                }
            }
        }
        ok(ownerBlock(1,0,14)-ownerBlock(0,5,14)<1024,"old 14/8 layout would fail 1024-block bound");
        JsonElement concentric=JsonParser.parseString("{\"placement\":{\"type\":\"minecraft:concentric_rings\",\"spacing\":14,\"separation\":8}}");
        JsonElement stricter=JsonParser.parseString("{\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":100,\"separation\":63}}");
        ok(LushCaveRuinsLegacyData.adapt("other","keeb_struc_lush:lush_ruins",set)==set&&LushCaveRuinsLegacyData.adapt("minecraft:worldgen/structure_set","other",set)==set,"only exact structure set/registry");
        ok(structureSet(concentric)==concentric&&structureSet(stricter)==stricter&&structureSet(spaced)==spaced,"concentric, stricter and repaired sets retain identity");
        JsonElement pot=JsonParser.parseString("{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:resin_clump\",\"weight\":3,\"functions\":[{\"function\":\"minecraft:set_count\",\"count\":2}]},{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\",\"weight\":1}]}]}");
        String potBefore=pot.toString(); JsonElement potExpected=pot.deepCopy(); potExpected.getAsJsonObject().getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("name","minecraft:clay_ball"); JsonElement potFixed=loot("keeb_struc_lush:lush_cave_ruins_pot",pot);
        ok(potFixed!=pot&&potBefore.equals(pot.toString())&&potFixed.toString().contains("minecraft:clay_ball"),"pot exact replacement preserves original");
        ok(potExpected.equals(potFixed),"pot exact expected JSON");
        ok(potFixed.toString().contains("\"weight\":3")&&potFixed.toString().contains("set_count"),"pot weight/count/functions preserved");
        ok(loot("keeb_struc_lush:lush_cave_ruins_pot",potFixed)==potFixed&&loot("other",pot)==pot,"loot idempotent and exact table only");
        JsonElement common=JsonParser.parseString("{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:brown_bundle\",\"weight\":2},{\"type\":\"minecraft:item\",\"name\":\"minecraft:bundle\",\"weight\":1}]}]}");
        JsonElement commonExpected=common.deepCopy(); commonExpected.getAsJsonObject().getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("name","minecraft:bundle"); JsonElement commonFixed=loot("keeb_struc_lush:lush_cave_ruins_common",common);
        ok(!contains(commonFixed,"minecraft:brown_bundle")&&contains(commonFixed,"minecraft:bundle"),"common bundle fallback exact");
        ok(commonExpected.equals(commonFixed),"common exact expected JSON");
        if(args.length==4){
            JsonElement installed=JsonParser.parseString(Files.readString(Path.of(args[0]))); String installedBefore=installed.toString(); JsonElement installedExpected=expected(installed);
            JsonElement fixed=adapt(installed);
            ok(fixed!=installed&&fixed.toString().contains("minecraft:block_pile"),"installed LCR fallen tree repaired");
            ok(installedBefore.equals(installed.toString())&&installedExpected.equals(fixed),"installed JSON changed only exact branch");
            ok(!fallen(fixed),"installed repaired JSON contains no fallen tree");
            ok(fixed.toString().contains("minecraft:brick_slab")&&fixed.toString().contains("minecraft:blue_terracotta"),"installed palette preserved");
            JsonElement installedPot=JsonParser.parseString(Files.readString(Path.of(args[1]))), installedCommon=JsonParser.parseString(Files.readString(Path.of(args[2])));
            JsonElement fixedPot=loot("keeb_struc_lush:lush_cave_ruins_pot",installedPot), fixedCommon=loot("keeb_struc_lush:lush_cave_ruins_common",installedCommon);
            ok(fixedPot!=installedPot&&!contains(fixedPot,"minecraft:resin_clump")&&contains(fixedPot,"minecraft:clay_ball"),"installed pot repaired");
            ok(fixedCommon!=installedCommon&&!contains(fixedCommon,"minecraft:brown_bundle")&&!contains(fixedCommon,"minecraft:light_gray_bundle")&&!contains(fixedCommon,"minecraft:gray_bundle")&&!contains(fixedCommon,"minecraft:green_bundle"),"installed common bundles repaired");
            ok(loot("keeb_struc_lush:lush_cave_ruins_pot",fixedPot)==fixedPot&&loot("keeb_struc_lush:lush_cave_ruins_common",fixedCommon)==fixedCommon,"installed loot idempotent");
            JsonElement installedSet=JsonParser.parseString(Files.readString(Path.of(args[3]))); JsonElement installedSetExpected=installedSet.deepCopy(); installedSetExpected.getAsJsonObject().getAsJsonObject("placement").addProperty("spacing",69); installedSetExpected.getAsJsonObject().getAsJsonObject("placement").addProperty("separation",63); JsonElement installedSetFixed=structureSet(installedSet);
            JsonObject installedPlacement=installedSetFixed.getAsJsonObject().getAsJsonObject("placement");
            ok(installedSetFixed!=installedSet&&installedSetExpected.equals(installedSetFixed)&&installedPlacement.get("spacing").getAsInt()==69&&installedPlacement.get("separation").getAsInt()==63,"installed structure set spacing repaired without other changes");
            ok(installedPlacement.get("salt").equals(installedSet.getAsJsonObject().getAsJsonObject("placement").get("salt"))&&installedPlacement.get("exclusion_zone").equals(installedSet.getAsJsonObject().getAsJsonObject("placement").get("exclusion_zone")),"installed salt/exclusion preserved");
        }
        System.out.println("LushCaveRuinsLegacyDataTest: PASS exact legacy fallback and 1024-block LCR spacing");
    }
}
