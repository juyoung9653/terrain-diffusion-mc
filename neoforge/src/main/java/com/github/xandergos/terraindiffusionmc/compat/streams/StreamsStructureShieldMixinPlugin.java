package com.github.xandergos.terraindiffusionmc.compat.streams;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies compatibility hooks only to the audited Streams 2.13.1 and 2.13.5 layouts. */
public final class StreamsStructureShieldMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> AUDITED_MIXINS = Set.of(
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsStructureShieldMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsWorldgenPrefetchMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsWorldgenScaleMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsNaturalRiverMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsNaturalNetworkMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsRiverBanksMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsRiverRasterMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsPlayerPrefetchMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsBankMaterialMixin");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !AUDITED_MIXINS.contains(mixinClassName) || hasAuditedStreamsVersion();
    }

    private static boolean hasAuditedStreamsVersion() {
        return FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "streamsreflowing".equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch(StreamsStructureShieldMixinPlugin::isAuditedVersion);
    }

    public static boolean isAuditedVersion(String version) {
        return "2.13.1".equals(version) || "2.13.5".equals(version);
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
