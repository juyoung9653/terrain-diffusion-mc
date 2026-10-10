package com.github.xandergos.terraindiffusionmc.world.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Audits the actual rule, not a resource-pack-overridable processor-list name. */
public final class MossProcessorGeometry {
    private static final JsonElement EXPECTED=JsonParser.parseString("""
            {"processor_type":"minecraft:rule","rules":[{
              "input_predicate":{"block":"minecraft:cobblestone","predicate_type":"minecraft:random_block_match"},
              "location_predicate":{"predicate_type":"minecraft:always_true"},
              "output_state":{"Name":"minecraft:mossy_cobblestone"}}]}
            """);
    private static final JsonElement ALWAYS=JsonParser.parseString("{\"predicate_type\":\"minecraft:always_true\"}");
    private static final JsonElement PASSTHROUGH=JsonParser.parseString("{\"type\":\"minecraft:passthrough\"}");
    private MossProcessorGeometry() {}

    public static boolean preservesFootprint(JsonElement encoded) {
        try {
            JsonObject copy=encoded.deepCopy().getAsJsonObject();
            var rules=copy.getAsJsonArray("rules");
            if(rules.size()!=1) return false;
            var rule=rules.get(0).getAsJsonObject();
            var input=rule.getAsJsonObject("input_predicate");
            var probability=input.getAsJsonPrimitive("probability");
            if(!probability.isNumber()) return false;
            double value=probability.getAsDouble();
            if(!Double.isFinite(value) || value<0 || value>1) return false;
            input.remove("probability");
            if(ALWAYS.equals(rule.get("position_predicate"))) rule.remove("position_predicate");
            if(PASSTHROUGH.equals(rule.get("block_entity_modifier"))) rule.remove("block_entity_modifier");
            return EXPECTED.equals(copy);
        } catch(RuntimeException malformed) {
            return false;
        }
    }
}
