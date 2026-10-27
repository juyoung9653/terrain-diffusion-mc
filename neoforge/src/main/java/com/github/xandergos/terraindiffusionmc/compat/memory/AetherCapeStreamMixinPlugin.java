package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.util.List;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies only to the inspected Aether 1.5.10 client cape hook. */
public final class AetherCapeStreamMixinPlugin implements IMixinConfigPlugin {
    private static final String AETHER_VERSION = "1.5.10";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FMLLoader.getDist() == Dist.CLIENT
                && FMLLoader.getLoadingModList() != null
                && FMLLoader.getLoadingModList().getMods().stream().anyMatch(mod ->
                "aether".equals(mod.getModId()) && AETHER_VERSION.equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
