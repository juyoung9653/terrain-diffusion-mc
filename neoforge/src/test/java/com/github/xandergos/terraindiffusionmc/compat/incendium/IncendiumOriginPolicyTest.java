package com.github.xandergos.terraindiffusionmc.compat.incendium;

import java.util.ArrayList;
import java.util.List;

public final class IncendiumOriginPolicyTest {
    public static void main(String[] args) {
        require(IncendiumOriginPolicy.supports(IncendiumOriginPolicy.LOAD_COMMANDS, IncendiumOriginPolicy.HELPER_COMMANDS), "audited bodies");
        var changed = new ArrayList<>(IncendiumOriginPolicy.LOAD_COMMANDS);
        changed.set(0, "forceload add 16 0");
        require(!IncendiumOriginPolicy.supports(changed, IncendiumOriginPolicy.HELPER_COMMANDS), "other coordinates must fall back");
        require(!IncendiumOriginPolicy.supports(IncendiumOriginPolicy.LOAD_COMMANDS, List.of()), "missing helper must fall back");
        changed = new ArrayList<>(IncendiumOriginPolicy.HELPER_COMMANDS);
        changed.add("say changed");
        require(!IncendiumOriginPolicy.supports(IncendiumOriginPolicy.LOAD_COMMANDS, changed), "modified helper must fall back");
        var state = new IncendiumOriginPolicy.State();
        require(state.suppressHelper(), "helper blocked while origin pending");
        require(!state.beginApply(false, false), "no generation");
        require(!state.beginApply(true, false), "persisted entities must load before singleton check");
        require(!state.beginApply(false, true), "entity readiness alone insufficient");
        require(state.beginApply(true, true), "both ready");
        require(!state.suppressHelper(), "original helper must execute during apply");
        require(!state.beginApply(true, true), "apply once");
        state.complete();
        require(state.phase() == IncendiumOriginPolicy.State.Phase.READY, "ready");
        require(!state.beginApply(true, true), "do not repeat on reload");
        state = new IncendiumOriginPolicy.State();
        state.stop();
        require(!state.beginApply(true, true), "late completion after stop ignored");
        state.fallback();
        require(state.phase() == IncendiumOriginPolicy.State.Phase.STOPPED, "fallback cannot revive stopped server");
        state = new IncendiumOriginPolicy.State();
        state.fallback();
        require(!state.suppressHelper() && !state.beginApply(true, true), "unsupported pack follows vanilla");
        System.out.println("IncendiumOriginPolicyTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
