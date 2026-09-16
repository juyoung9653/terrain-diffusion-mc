import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankShape;

import java.util.Random;

public final class NaturalRiverBankShapeTest {
    public static void main(String[] args) {
        preservesExcludedSamples();
        capsCutAndPreservesFreeboard();
        fadesAcrossWidthAndCorridor();
        distinguishesGentleAndSteepBanks();
        softensOneBlockExcessBank();
        handlesNegativeCoordinatesDeterministically();
        holdsInvariantsAcrossInputs();
        System.out.println("NaturalRiverBankShapeTest: passed");
    }

    private static void preservesExcludedSamples() {
        int original = 91;
        check(target(original, 70, 63, 2.0, 2.0, 0.1) == original, "in-channel sample changed");
        check(target(original, 70, 63, 7.0, 2.0, 0.1) == original, "corridor edge changed");
        check(target(original, 70, 63, 3.0, 4.0, 0.1) == original, "large river changed");
        check(target(original, 65, 63, 3.0, 2.0, 0.1) == original, "low-water river changed");
        check(target(original, 70, 63, 3.0, 2.0, -0.01) == original, "negative slope changed");
        check(target(original, 70, 63, Double.NaN, 2.0, 0.1) == original, "NaN distance changed");
        check(target(original, 70, 63, 3.0, Double.POSITIVE_INFINITY, 0.1) == original, "infinite width changed");
        check(target(original, 70, 63, 3.0, 2.0, Double.NaN) == original, "NaN slope changed");
    }

    private static void capsCutAndPreservesFreeboard() {
        int result = target(120, 70, 63, 2.1, 2.0, 0.1);
        check(result >= 71, "target fell below water freeboard");
        check(120 - result <= 3, "cut exceeded three blocks");
        check(target(71, 70, 63, 2.1, 2.0, 0.1) == 71, "freeboard terrain changed");
    }

    private static void fadesAcrossWidthAndCorridor() {
        int narrow = target(100, 70, 63, 3.0, 2.0, 0.1);
        int wide = target(100, 70, 63, 4.9, 3.9, 0.1);
        int near = target(100, 70, 63, 2.1, 2.0, 0.1);
        int edge = target(100, 70, 63, 6.9, 2.0, 0.1);
        check(narrow < wide, "narrow-width fade did not reduce cutting for wider channels");
        check(near < edge, "corridor fade did not reduce cutting at bank edge");
    }

    private static void distinguishesGentleAndSteepBanks() {
        int gentle = target(75, 70, 63, 5.0, 2.0, 0.0);
        int middle = target(75, 70, 63, 5.0, 2.0, 0.10);
        int steep = target(75, 70, 63, 5.0, 2.0, 0.20);
        check(gentle <= middle && middle <= steep, "slope ramp did not respond gradually");
        check(gentle < steep, "steep bank ramp should preserve more height");
        check(target(75, 70, 63, 5.0, 2.0, 0.19) == steep,
                "slope near steep threshold should not retain a hard-switch ledge");
    }

    private static void softensOneBlockExcessBank() {
        check(target(73, 70, 62, 1.75, 1.0, 0.0) == 72,
                "rounded fade should soften a one-block excess bank");
    }

    private static void handlesNegativeCoordinatesDeterministically() {
        double first = NaturalRiverBankShape.exposure(-17, -33);
        check(first == NaturalRiverBankShape.exposure(-17, -33), "negative coordinates are not deterministic");
        check(first >= 0.0 && first <= 1.0, "negative coordinate exposure outside range");
        double left = NaturalRiverBankShape.exposure(-1, -1);
        double right = NaturalRiverBankShape.exposure(0, -1);
        check(Math.abs(left - right) < 0.2, "exposure is not spatially smooth across origin");
    }

    private static void holdsInvariantsAcrossInputs() {
        Random random = new Random(0xB4A9);
        for (int i = 0; i < 20_000; i++) {
            int original = random.nextInt(180) - 30;
            int water = random.nextInt(130) - 30;
            int sea = random.nextInt(100) - 20;
            double distance = random.nextDouble() * 12.0 - 2.0;
            double width = random.nextDouble() * 6.0 - 1.0;
            double slope = random.nextDouble() * 0.8 - 0.2;
            int result = target(original, water, sea, distance, width, slope);
            check(result <= original, "target raised terrain");
            check(original - result <= 3, "random cut exceeded cap");
            if (result != original) {
                check(result >= water + 1, "random target broke freeboard");
                check(Double.isFinite(distance) && Double.isFinite(width) && Double.isFinite(slope)
                        && width > 0.0 && width < 4.0 && slope >= 0.0 && water > sea + 2
                        && distance > width && distance - width < 5.0, "excluded sample changed");
            }
            int x = random.nextInt();
            int z = random.nextInt();
            double exposure = NaturalRiverBankShape.exposure(x, z);
            check(exposure >= 0.0 && exposure <= 1.0, "random exposure outside range");
        }
    }

    private static int target(int original, int water, int sea, double distance, double width, double slope) {
        return NaturalRiverBankShape.targetHeight(original, water, sea, distance, width, slope);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}