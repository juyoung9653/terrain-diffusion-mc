package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;

/** Thread-confined scope propagates through native random_patch's inline simple_block feature. */
public final class GreeneriesNativePatchScope {
    private static final Set<String> PATCHES = Set.of("patch_blue_grass", "patch_blue_grass_small",
            "patch_red_fescue", "patch_red_fescue_small", "patch_common_bent_grass",
            "patch_common_bent_grass_small", "patch_royal_fern");
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private GreeneriesNativePatchScope() { }

    public static boolean isNativePatch(ResourceLocation id) {
        return id.getNamespace().equals("greeneries") && PATCHES.contains(id.getPath());
    }

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static boolean place(BooleanSupplier placement) {
        boolean previous = active();
        ACTIVE.set(true);
        try {
            return placement.getAsBoolean();
        } finally {
            if (previous) ACTIVE.set(true);
            else ACTIVE.remove();
        }
    }
}
