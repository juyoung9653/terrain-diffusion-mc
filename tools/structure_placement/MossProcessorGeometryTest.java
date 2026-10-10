import com.github.xandergos.terraindiffusionmc.world.structure.MossProcessorGeometry;
import com.google.gson.*;
import java.nio.file.*;

public final class MossProcessorGeometryTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        for(String path:args) {
            var original=JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject()
                    .getAsJsonArray("processors").get(0).getAsJsonObject();
            String before=original.toString();
            check(MossProcessorGeometry.preservesFootprint(original),"original moss rule rejected");
            check(before.equals(original.toString()),"audit mutated codec data");
            var rule=original.getAsJsonArray("rules").get(0).getAsJsonObject();
            var defaults=original.deepCopy();
            var defaultRule=defaults.getAsJsonArray("rules").get(0).getAsJsonObject();
            defaultRule.add("position_predicate",JsonParser.parseString("{\"predicate_type\":\"minecraft:always_true\"}"));
            defaultRule.add("block_entity_modifier",JsonParser.parseString("{\"type\":\"minecraft:passthrough\"}"));
            check(MossProcessorGeometry.preservesFootprint(defaults),"explicit native defaults rejected");
            for(String replacement:new String[]{"minecraft:air","minecraft:jigsaw","other:cobblestone"}) {
                var changed=original.deepCopy();
                changed.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("input_predicate").addProperty("block",replacement);
                check(!MossProcessorGeometry.preservesFootprint(changed),"foreign input admitted");
            }
            var air=original.deepCopy();
            air.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("output_state").addProperty("Name","minecraft:air");
            check(!MossProcessorGeometry.preservesFootprint(air),"destructive output admitted");
            var extra=original.deepCopy(); extra.getAsJsonArray("rules").add(rule.deepCopy());
            check(!MossProcessorGeometry.preservesFootprint(extra),"unaudited extra rule admitted");
            var modifier=original.deepCopy();
            modifier.getAsJsonArray("rules").get(0).getAsJsonObject().add("block_entity_modifier",JsonParser.parseString("{\"type\":\"minecraft:append_static\",\"data\":{}}"));
            check(!MossProcessorGeometry.preservesFootprint(modifier),"NBT modifier admitted");
            for(JsonElement value:new JsonElement[]{JsonNull.INSTANCE,new JsonPrimitive("0.2"),new JsonPrimitive(-1),new JsonPrimitive(2)}) {
                var bad=original.deepCopy();
                bad.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("input_predicate").add("probability",value);
                check(!MossProcessorGeometry.preservesFootprint(bad),"invalid probability admitted");
            }
        }
        check(!MossProcessorGeometry.preservesFootprint(JsonNull.INSTANCE),"null admitted");
        check(!MossProcessorGeometry.preservesFootprint(new JsonObject()),"missing fields admitted");
        System.out.println("MossProcessorGeometryTest: "+checks+" assertions passed against original Minecraft processor resources");
    }
}
