package com.github.xandergos.terraindiffusionmc.compat.incendium;

import java.util.List;

/** Exact executable bodies audited in Incendium 5.4.4; changed packs retain vanilla behavior. */
public final class IncendiumOriginPolicy {
    public static final String LOAD = "incendium:technical/load";
    public static final String HELPER = "incendium:technical/summon_helper";
    public static final List<String> LOAD_COMMANDS = List.of(
            "forceload add 0 0",
            "function incendium:technical/summon_helper",
            "scoreboard objectives add in.eid dummy",
            "execute unless score $current.id in.eid matches 1.. run scoreboard players set $current.id in.eid 0",
            "scoreboard players set #-1 in.constants -1",
            "scoreboard players set #2 in.constants 2");
    public static final List<String> HELPER_COMMANDS = List.of(
            "execute store result score $count in.dummy if entity @e[type=marker,tag=in.helper_entity]",
            "execute unless score $count in.dummy matches 1 run kill @e[type=marker,tag=in.helper_entity]",
            "execute unless score $count in.dummy matches 1 in minecraft:overworld run summon marker 0.0 0.0 0.0 {Tags:[\"in.helper_entity\", \"smithed.entity\"]}",
            "execute unless score $count in.dummy matches 1 in minecraft:overworld run tp @e[type=marker,tag=in.helper_entity] 0.0 0.0 0.0");

    private IncendiumOriginPolicy() { }

    public static boolean supports(List<String> load, List<String> helper) {
        return LOAD_COMMANDS.equals(load) && HELPER_COMMANDS.equals(helper);
    }

    /** Server-thread state only. Readiness includes entity storage, not merely terrain generation. */
    public static final class State {
        public enum Phase { PENDING, APPLYING, READY, FALLBACK, STOPPED }
        private Phase phase = Phase.PENDING;

        public Phase phase() { return phase; }
        public boolean suppressHelper() { return phase == Phase.PENDING; }
        public boolean beginApply(boolean fullChunk, boolean entitiesLoaded) {
            if (phase != Phase.PENDING || !fullChunk || !entitiesLoaded) return false;
            phase = Phase.APPLYING;
            return true;
        }
        public void complete() {
            if (phase != Phase.APPLYING) throw new IllegalStateException("Origin is not ready to initialize");
            phase = Phase.READY;
        }
        public void fallback() { if (phase != Phase.STOPPED) phase = Phase.FALLBACK; }
        public void stop() { phase = Phase.STOPPED; }
    }
}
