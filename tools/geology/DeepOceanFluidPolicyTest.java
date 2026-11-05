import com.github.xandergos.terraindiffusionmc.world.DeepOceanFluidPolicy;

public final class DeepOceanFluidPolicyTest {
    public static void main(String[] args) {
        check(DeepOceanFluidPolicy.useLava(-1968, -1968, 63), "floor is lava");
        check(DeepOceanFluidPolicy.useLava(-1961, -1968, 63), "eighth floor block is lava");
        check(!DeepOceanFluidPolicy.useLava(-1960, -1968, 63), "deep ocean above floor is water");
        check(!DeepOceanFluidPolicy.useLava(-201, -1968, 63), "deep ocean bed is water");
        check(!DeepOceanFluidPolicy.useLava(62, -1968, 63), "sea-level water remains water");
        check(DeepOceanFluidPolicy.useLava(-736, -736, 63), "scale-two floor is lava");
        check(!DeepOceanFluidPolicy.useLava(-728, -736, 63), "scale-two cutoff is relative to actual floor");
        check(!DeepOceanFluidPolicy.useLava(-201, -736, 63), "scale-two deep ocean bed is water");
        check(DeepOceanFluidPolicy.useLava(-64, -64, 63), "relative floor works at another minimum");
        check(!DeepOceanFluidPolicy.useLava(-56, -64, 63), "relative cutoff is strict");
        System.out.println("DeepOceanFluidPolicyTest: OK");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}