package com.github.xandergos.terraindiffusionmc.compat.continuity;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Optional, client-only bridge for the audited NeoContinuity method layout. */
public final class CattailContinuityMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FMLLoader.getDist() != Dist.CLIENT || FMLLoader.getLoadingModList() == null) return false;
        return FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "continuity".equals(mod.getModId()))
                .anyMatch(mod -> CattailDepthPolicy.supportsRuntime(true, mod.getVersion().toString()));
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
