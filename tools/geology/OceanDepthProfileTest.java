import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.OceanDepthProfile;

public final class OceanDepthProfileTest {
    public static void main(String[] args) {
        int[] floors = {-400, -736, -1072, -1408, -1744, -1968};
        for (int scale = 1; scale <= 6; scale++) {
            int previous = 63;
            for (int meters = -1; meters >= Short.MIN_VALUE; meters--) {
                short raw = (short) meters;
                int legacy = HeightConverter.convertToMinecraftHeight(raw, scale);
                int boundary = HeightConverter.convertToMinecraftHeight(raw, scale, 2);
                require(HeightConverter.convertToMinecraftHeight(raw, scale, 1) == legacy, "legacy identity");
                require(boundary <= previous && previous - boundary <= 1, "continuous monotone ocean slope");
                require(boundary > floors[scale - 1] + 96, "rock remains below deep ocean");
                if (meters >= -200) require(boundary == legacy, "coastal shallows unchanged");
                previous = boundary;
            }
            for (int meters = 0; meters <= Short.MAX_VALUE; meters++) {
                require(HeightConverter.convertToMinecraftHeight((short) meters, scale, 2)
                        == HeightConverter.convertToMinecraftHeight((short) meters, scale), "land unchanged");
            }
            require(OceanDepthProfile.oceanBoundary((short) -4000, scale) == 63 - 120 * scale, "abyssal scale");
            require(OceanDepthProfile.oceanBoundary((short) -10000, scale) == 63 - 180 * scale, "deep basin scale");
            require(OceanDepthProfile.oceanBoundary(Short.MIN_VALUE, scale) == 63 - 220 * scale, "depth limit");
        }
        try {
            HeightConverter.convertToMinecraftHeight((short) -100, 2, 3);
            throw new AssertionError("unknown profile accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("OceanDepthProfileTest: PASS (all short elevations, scales 1..6; legacy/land/shore identity, monotonicity, bedrock clearance)");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
