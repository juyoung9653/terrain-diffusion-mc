package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Binds the empty-mask optimization to the inspected YUNG's API constructor. */
public final class YungAquiferMaskMixinPlugin implements IMixinConfigPlugin {
    private static final String YUNGS_API_VERSION = "1.21.1-NeoForge-5.1.9";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FMLLoader.getLoadingModList() != null
                && FMLLoader.getLoadingModList().getMods().stream().anyMatch(mod ->
                "yungsapi".equals(mod.getModId()) && YUNGS_API_VERSION.equals(mod.getVersion().toString()));
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
