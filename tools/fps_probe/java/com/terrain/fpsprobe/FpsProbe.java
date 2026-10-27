package com.terrain.fpsprobe;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(FpsProbe.MODID)
public final class FpsProbe {
    public static final String MODID = "td_fps_probe";
    public FpsProbe() { if (FMLEnvironment.dist == Dist.CLIENT) FpsProbeClient.install(); }
}
