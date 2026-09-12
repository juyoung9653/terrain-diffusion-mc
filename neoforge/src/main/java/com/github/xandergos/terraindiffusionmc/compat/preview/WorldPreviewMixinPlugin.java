package com.github.xandergos.terraindiffusionmc.compat.preview;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Restricts the reflection-backed bridge to the World Preview layout it was audited against. */
public final class WorldPreviewMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> MIXINS = Set.of(
            "com.github.xandergos.terraindiffusionmc.mixin.WorldPreviewWorkManagerMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.WorldPreviewWorkBatchMixin");

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !MIXINS.contains(mixinClassName) || FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "world_preview".equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch("1.3.1"::equals);
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
