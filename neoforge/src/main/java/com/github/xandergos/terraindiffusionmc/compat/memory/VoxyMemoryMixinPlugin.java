package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies only to Voxy versions whose cache injection points have been inspected. */
public final class VoxyMemoryMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> VOXY_VERSIONS = Set.of("0.2.12-alpha", "1.1.0");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FMLLoader.getLoadingModList() != null
                && FMLLoader.getLoadingModList().getMods().stream().anyMatch(mod ->
                "voxy".equals(mod.getModId()) && VOXY_VERSIONS.contains(mod.getVersion().toString()))
                && VoxyMemoryBudget.hasConfiguredReduction();
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
