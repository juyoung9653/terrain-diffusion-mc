package com.github.xandergos.terraindiffusionmc.compat.streams;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies compatibility hooks only to audited Streams layouts. */
public final class StreamsStructureShieldMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> AUDITED_MIXINS = Set.of(
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsStructureShieldMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsWorldgenPrefetchMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsWorldgenScaleMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsNaturalRiverMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsNaturalNetworkMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsRiverBanksMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsShallowBanksMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsRiverRasterMixin",
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsPlayerPrefetchMixin");
    private static final String LEGACY_BANK_MATERIAL_MIXIN =
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsBankMaterialMixin";
    private static final String BANK_MATERIAL_2138_MIXIN =
            "com.github.xandergos.terraindiffusionmc.mixin.StreamsBankMaterial2138Mixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.equals("com.github.xandergos.terraindiffusionmc.mixin.SnowUnderTreesFeatureMixin")) {
            return FMLLoader.getLoadingModList().getMods().stream()
                    .anyMatch(mod -> "snowundertrees".equals(mod.getModId())
                            && "1.5.1".equals(mod.getVersion().toString()));
        }
        if (LEGACY_BANK_MATERIAL_MIXIN.equals(mixinClassName)) {
            return hasStreamsVersion(StreamsStructureShieldMixinPlugin::isLegacyBankMaterialVersion);
        }
        if (BANK_MATERIAL_2138_MIXIN.equals(mixinClassName)
                || mixinClassName.equals("com.github.xandergos.terraindiffusionmc.mixin.StreamsSeagrassFeatureMixin")) {
            return hasStreamsVersion(StreamsStructureShieldMixinPlugin::is2138);
        }
        if (mixinClassName.equals("com.github.xandergos.terraindiffusionmc.mixin.StreamsStartupMixin")) {
            return hasStartupAdapter();
        }
        return !AUDITED_MIXINS.contains(mixinClassName) || hasStreamsVersion(StreamsStructureShieldMixinPlugin::isAuditedVersion);
    }

    private static boolean hasStreamsVersion(java.util.function.Predicate<String> versionPredicate) {
        return FMLLoader.getLoadingModList().getMods().stream()
                .filter(mod -> "streamsreflowing".equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch(versionPredicate);
    }

    public static boolean hasStartupAdapter() {
        return hasStreamsVersion(StreamsStructureShieldMixinPlugin::is2138);
    }

    public static boolean isAuditedVersion(String version) {
        return isLegacyBankMaterialVersion(version) || is2138(version);
    }

    private static boolean isLegacyBankMaterialVersion(String version) {
        return "2.13.1".equals(version) || "2.13.5".equals(version);
    }

    private static boolean is2138(String version) {
        return "2.13.8".equals(version);
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
