package com.github.xandergos.terraindiffusionmc.compat.hybridaquatic;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Optional hooks are bound to the inspected HA and Lithostitched bytecode layouts. */
public final class HybridAquaticMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return loaded("hybrid_aquatic", "1.7.4") && loaded("lithostitched", "1.8.1");
    }
    private static boolean loaded(String id, String version) {
        if (FMLLoader.getLoadingModList() == null) return false;
        return FMLLoader.getLoadingModList().getMods().stream().anyMatch(mod ->
                id.equals(mod.getModId()) && version.equals(mod.getVersion().toString()));
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
