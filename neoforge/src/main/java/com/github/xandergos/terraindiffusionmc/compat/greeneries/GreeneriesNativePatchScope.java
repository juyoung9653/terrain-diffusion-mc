package com.github.xandergos.terraindiffusionmc.compat.greeneries;

import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** Thread-confined scope propagates through native random_patch's inline simple_block feature. */
public final class GreeneriesNativePatchScope {
    private static final Set<String> PATCHES = Set.of("patch_blue_grass",
            "patch_red_fescue", "patch_red_fescue_small", "patch_common_bent_grass",
            "patch_common_bent_grass_small", "patch_royal_fern");
    // single_piece_of_grass is the bonemeal provider, not a natural generation pass.
    private static final Set<String> OVERRIDES = Set.of("patch_grass", "patch_grass_jungle",
            "patch_taiga_grass", "patch_tall_grass", "patch_large_fern");
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private GreeneriesNativePatchScope() { }

    public static boolean isNativePatch(ResourceLocation id) {
        return (id.getNamespace().equals("greeneries") && PATCHES.contains(id.getPath()))
                || (id.getNamespace().equals("minecraft") && OVERRIDES.contains(id.getPath())
                    && ModList.get().isLoaded("greeneries"));
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
