package com.terrain.chunkreadprobe;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Class transformation itself is opt-in; injected handlers also enforce exact C2ME support. */
public final class C2meReadMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return !ProbeConfiguration.outputPath().isEmpty() && (target.equals("com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync")
                || target.equals("com.ishland.c2me.notickvd.common.NoTickSystem")
                || target.equals("net.minecraft.server.level.ServerChunkCache")
                || target.equals("net.minecraft.server.level.ChunkMap")
                || target.equals("com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem"));
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
