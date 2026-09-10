package com.github.xandergos.terraindiffusionmc.compat.streams;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the private-method mixin only to the bytecode layout audited for Streams 2.13.1. */
public final class StreamsStructureShieldMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN = "com.github.xandergos.terraindiffusionmc.mixin.StreamsStructureShieldMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !MIXIN.equals(mixinClassName) || hasAuditedStreamsVersion();
    }

    private static boolean hasAuditedStreamsVersion() {
        return FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "streamsreflowing".equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch("2.13.1"::equals);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
