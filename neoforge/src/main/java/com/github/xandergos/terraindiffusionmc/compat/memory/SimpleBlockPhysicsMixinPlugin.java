package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies only to the audited Simple Block Physics level-tick implementation. */
public final class SimpleBlockPhysicsMixinPlugin implements IMixinConfigPlugin {
    private static final String SIMPLE_BLOCK_PHYSICS_VERSION = "1.21.1-neoforge-1.2.0";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FMLLoader.getLoadingModList() != null
                && FMLLoader.getLoadingModList().getMods().stream().anyMatch(mod ->
                "simpleblockphysics".equals(mod.getModId())
                        && SIMPLE_BLOCK_PHYSICS_VERSION.equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
