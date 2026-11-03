package com.terrain.photonwavingprobe;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(PhotonWavingProbe.MODID)
public final class PhotonWavingProbe {
    public static final String MODID = "td_photon_waving_probe";
    public PhotonWavingProbe() { if (FMLEnvironment.dist == Dist.CLIENT) PhotonWavingProbeClient.install(); }
}
