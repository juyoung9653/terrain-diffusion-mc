package com.terrain.optimizationbench;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** Client-only stationary render benchmark. Workloads such as terrain generation are intentionally excluded. */
@Mod(OptimizationBench.MODID)
public final class OptimizationBench {
    public static final String MODID = "optimization_bench";

    public OptimizationBench() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BenchmarkClient.install();
        }
    }
}
