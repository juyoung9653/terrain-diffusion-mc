package com.terrain.chunkprofile;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(ChunkProfile.MODID)
public final class ChunkProfile {
    public static final String MODID = "td_chunk_profile";
    public ChunkProfile() { if (FMLEnvironment.dist == Dist.CLIENT) ChunkProfileClient.install(); }
}
