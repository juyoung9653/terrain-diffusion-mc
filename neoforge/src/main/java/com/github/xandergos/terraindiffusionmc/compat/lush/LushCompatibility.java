package com.github.xandergos.terraindiffusionmc.compat.lush;

import net.neoforged.fml.loading.FMLLoader;

/** Runtime gate for the audited Lush vegetation implementation. */
public final class LushCompatibility {
    // The jar filename carries the Minecraft suffix; NeoForge exposes the declared version.
    public static final String AUDITED_LUSH_VERSION = "2.2";

    private LushCompatibility() { }

    public static boolean supportsRuntime(boolean greeneriesLoaded, String lushVersion) {
        return greeneriesLoaded && AUDITED_LUSH_VERSION.equals(lushVersion);
    }

    public static boolean auditedLushLoaded() {
        if (FMLLoader.getLoadingModList() == null) return false;
        return FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "lush".equals(mod.getModId()))
                .anyMatch(mod -> AUDITED_LUSH_VERSION.equals(mod.getVersion().toString()));
    }

    public static boolean adapterAvailable() {
        if (!auditedLushLoaded()) return false;
        boolean greeneriesLoaded = FMLLoader.getLoadingModList().getMods().stream()
                .anyMatch(mod -> "greeneries".equals(mod.getModId()));
        return supportsRuntime(greeneriesLoaded, AUDITED_LUSH_VERSION);
    }
}
