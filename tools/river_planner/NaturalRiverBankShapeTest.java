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
        softensMeasuredWideSlope();
        preservesWideBankExclusionsAndContainment();
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

    private static void softensMeasuredWideSlope() {
        // Same-seed native bank audit: Y66 water, outer grass Y68/69 at these edge distances.
        check(wide(68, 66, 63, 4.65 + 3.9, 4.65, 0, -841, -1028) == 67,
                "observed inner wide slope did not soften");
        check(wide(69, 66, 63, 4.65 + 4.8, 4.65, 0, -841, -1029) == 68,
                "observed outer wide slope did not soften");
        check(wide(69, 66, 63, 4.65 + 5.8, 4.65, 0, -841, -1030) == 69,
                "cut did not taper into original outer terrain");
        check(wide(67, 66, 63, 4.65 + 2, 4.65, 0, -841, -1026) == 67,
                "existing water freeboard was cut");
        check(wide(69, 69, 63, 8.5, 4.65, 0, -841, -1028) == 69,
                "higher adjacent channel was not respected");
        check(wide(68, 66, 63, 8.5, 4.65, .4, -841, -1028) == 68,
                "steep channel margin was flattened");
    }

    private static void preservesWideBankExclusionsAndContainment() {
        check(wide(90, 66, 63, 4, 3.99, 0, 0, 0) == 90, "narrow tributary changed");
        check(wide(90, 65, 63, 6, 4, 0, 0, 0) == 90, "shore changed");
        check(wide(90, 66, 63, 4, 4, 0, 0, 0) == 90, "channel changed");
        check(wide(90, 66, 63, 5, 4, Double.NaN, 0, 0) == 90, "invalid slope changed");
        check(wide(90, 66, 63, Double.NaN, 4, 0, 0, 0) == 90, "invalid distance changed");
        check(wide(90, 66, 63, 5, Double.POSITIVE_INFINITY, 0, 0, 0) == 90, "invalid width changed");
        Random random = new Random(0xB4A97);
        int changed = 0;
        for (int i = 0; i < 20_000; i++) {
            double width = random.nextDouble() * 20;
            double edge = random.nextDouble() * 12 - 1;
            int water = random.nextInt(200) - 20;
            int original = water + random.nextInt(16) - 4;
            double slope = random.nextDouble() * .5;
            int result = wide(original, water, 63, width + edge, width, slope,
                    random.nextInt(10000) - 5000, random.nextInt(10000) - 5000);
            check(result <= original && original - result <= 2, "wide cut escaped its bound");
            if (result != original) {
                changed++;
                check(result >= water + 1, "wide cut compromised water containment");
                check(width >= 4 && water > 65 && edge > .5 && edge < NaturalRiverBankShape.wideCorridor(width),
                        "wide cut changed an excluded bank");
            }
        }
        check(changed > 100, "wide profile failed to exercise cuts");
    }

    private static int wide(int original, int water, int sea, double distance, double width, double slope, int x, int z) {
        return NaturalRiverBankShape.wideTargetHeight(original, water, sea, distance, width, slope, x, z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
