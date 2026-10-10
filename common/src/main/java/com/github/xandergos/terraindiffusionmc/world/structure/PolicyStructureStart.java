package com.github.xandergos.terraindiffusionmc.world.structure;

/** Versioned start metadata; absent on structures generated before this policy. */
public interface PolicyStructureStart {
    int terrainDiffusion$policyVersion();
    void terrainDiffusion$policyVersion(int version);
}
