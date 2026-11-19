package com.github.xandergos.terraindiffusionmc.world.ecology;

import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;

/** Coordinate-continuous fields shared by ecological decoration features. */
public final class FeatureEcologyMath {
    public enum CoastalSediment { NONE, MUD, SILT, SAND }
    private static final int[] COASTAL_KERNEL = {1, 2, 3, 4, 3, 2, 1};
    private static final int SOIL_PATCH_MODEL_PIXELS = 64;
    private static final float SOIL_MOISTURE_VARIATION = 0.12F;
    public enum SurfaceMaterial { GRASS, COARSE_DIRT, SAND, PODZOL, GRAVEL, STONE }
    public enum GroundcoverKind {
        NONE, GRASS, TALL_GRASS, FERN, TALL_FERN, BERRY_BUSH, AZALEA,
        FLOWER, DEAD_BUSH, CACTUS, BAMBOO, MOSS, MUSHROOM
    }
    private static final GroundcoverKind[] GROUNDCOVER_KINDS = GroundcoverKind.values();

    /** Relative candidate weights, not final block coverage; substrate/survival are checked by the feature. */
    public record GroundcoverWeights(float grass, float tallGrass, float fern, float tallFern,
                                     float berryBush, float azalea, float flower, float deadBush,
                                     float cactus, float bamboo, float moss, float mushroom) {
        public float total() {
            return grass + tallGrass + fern + tallFern + berryBush + azalea + flower
                    + deadBush + cactus + bamboo + moss + mushroom;
        }

        public float weight(GroundcoverKind kind) {
            return switch (kind) {
                case GRASS -> grass;
                case TALL_GRASS -> tallGrass;
                case FERN -> fern;
                case TALL_FERN -> tallFern;
                case BERRY_BUSH -> berryBush;
                case AZALEA -> azalea;
                case FLOWER -> flower;
                case DEAD_BUSH -> deadBush;
                case CACTUS -> cactus;
                case BAMBOO -> bamboo;
                case MOSS -> moss;
                case MUSHROOM -> mushroom;
                case NONE -> 0;
            };
        }

        public GroundcoverKind choose(float unitRoll) {
            float total = total();
            if (!(total > 0)) return GroundcoverKind.NONE;
            float roll = clamp01(unitRoll) * total;
            GroundcoverKind last = GroundcoverKind.NONE;
            for (GroundcoverKind kind : GROUNDCOVER_KINDS) {
                float weight = weight(kind);
                if (weight <= 0) continue;
                last = kind;
                if (roll < weight) return kind;
                roll -= weight;
            }
            return last;
        }
    }
    public record SpeciesWeights(float spruce, float pine, float oak, float birch, float acacia, float jungle) {
        public float total() {
            return spruce + pine + oak + birch + acacia + jungle;
        }
    }

    private FeatureEcologyMath() {
    }

    public static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /** Smooth value noise whose cells are deliberately much larger than biome quart samples. */
    public static float broadPatch(long seed, int blockX, int blockZ, int cellSize) {
        int cellX = Math.floorDiv(blockX, cellSize);
        int cellZ = Math.floorDiv(blockZ, cellSize);
        float localX = Math.floorMod(blockX, cellSize) / (float)cellSize;
        float localZ = Math.floorMod(blockZ, cellSize) / (float)cellSize;
        float sx = smooth(localX);
        float sz = smooth(localZ);
        float nw = hash01(seed, cellX, cellZ);
        float ne = hash01(seed, cellX + 1, cellZ);
        float sw = hash01(seed, cellX, cellZ + 1);
        float se = hash01(seed, cellX + 1, cellZ + 1);
        return lerp(lerp(nw, ne, sx), lerp(sw, se, sx), sz);
    }

    /**
     * Legacy soil field retained for offline before/after comparisons, in the source's
     * 0..2 domain. Production material selection uses surfaceMaterial instead.
     */
    public static float surfaceMoisture(float moisture, long seed, int blockX, int blockZ, int scale) {
        if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
        int cellSize = Math.multiplyExact(SOIL_PATCH_MODEL_PIXELS, scale);
        float patch = broadPatch(seed, blockX, blockZ, cellSize);
        return Math.max(0.0F, Math.min(2.0F, moisture + (patch - 0.5F) * SOIL_MOISTURE_VARIATION));
    }

    /** Compatibility overload for callers that explicitly use the historic scale-two layout. */
    public static float surfaceMoisture(float moisture, long seed, int blockX, int blockZ) {
        return surfaceMoisture(moisture, seed, blockX, blockZ, 2);
    }

    /**
     * Shared by world generation and the offline atlas. Dry grassland retains a grass
     * matrix; exposed soil occurs in connected patches rather than covering the entire
     * climate band. Labels are deliberately not an input, so woodland boundaries do
     * not introduce material seams.
     */
    public static SurfaceMaterial surfaceMaterial(float temperature, float moisture, float slope,
                                                   float treeCover, long seed, int blockX, int blockZ, int scale) {
        return surfaceMaterial(temperature, moisture, slope, treeCover, 0, seed, blockX, blockZ, scale);
    }

    /** Physical elevation is the cached TD height in metres, before Minecraft height conversion. */
    public static SurfaceMaterial surfaceMaterial(float temperature, float moisture, float slope,
                                                   float treeCover, float elevation, long seed,
                                                   int blockX, int blockZ, int scale) {
        // Fine dither only moves the existing material field by up to three blocks.
        // Keep the actual column's elevation/climate: no neighbouring terrain tile is fetched.
        int dx = (int) Math.floorMod(decorationSeed(seed, blockX, blockZ, 0x4544474558L), 7L) - 3;
        int dz = (int) Math.floorMod(decorationSeed(seed, blockX, blockZ, 0x454447455AL), 7L) - 3;
        return surfaceMaterialAt(temperature, moisture, slope, treeCover, elevation, seed,
                blockX + dx, blockZ + dz, scale);
    }

    static SurfaceMaterial surfaceMaterialAt(float temperature, float moisture, float slope,
                                              float treeCover, float elevation, long seed,
                                              int blockX, int blockZ, int scale) {
        float soil = soilMosaic(seed, blockX, blockZ, elevation, slope, scale);
        float cold = EcologyClassifier.coldState(temperature);
        float exposed = cold * (1 - smoothRange(.08F, .5F, treeCover));
        float rock = EcologyClassifier.alpineExposure(temperature, slope, slope);
        if (soil < .85F * rock) return SurfaceMaterial.STONE;
        if (soil < .30F * exposed) return SurfaceMaterial.GRAVEL;
        if (soil < .65F * exposed) return SurfaceMaterial.COARSE_DIRT;
        float aridity = (1.0F - smoothRange(0.30F, 0.60F, moisture))
                * smoothRange(8.0F, 18.0F, temperature);
        if (aridity > 0.0F) {
            float sandCoverage = smoothRange(10.0F, 14.0F, temperature)
                    * (1.0F - smoothRange(0.14F, 0.38F, moisture));
            if (sandCoverage >= 1.0F || soil < sandCoverage) return SurfaceMaterial.SAND;
            if (soil < sandCoverage + 0.15F * aridity) return SurfaceMaterial.COARSE_DIRT;
        }
        float coniferFloor = (1.0F - smoothRange(5.0F, 12.0F, temperature))
                * smoothRange(0.70F, 1.15F, moisture) * smoothRange(0.25F, 0.70F, treeCover)
                * (1.0F - smoothRange(0.25F, 0.65F, slope));
        if (coniferFloor > 0) {
            if (soil < 0.48F * coniferFloor) return SurfaceMaterial.PODZOL;
        }
        // Small exposed woodland-floor patches taper with canopy, humidity and temperature.
        float woodlandFloor = smoothRange(7, 14, temperature) * (1 - smoothRange(22, 28, temperature))
                * smoothRange(.55F, .95F, moisture) * (1 - smoothRange(1.3F, 1.8F, moisture))
                * smoothRange(.35F, .80F, treeCover) * (1 - smoothRange(.35F, .75F, slope));
        if (woodlandFloor > 0 && detailPatch(seed ^ 0x4C4954544552L, blockX, blockZ, scale)
                < .16F * woodlandFloor) return SurfaceMaterial.COARSE_DIRT;
        return SurfaceMaterial.GRASS;
    }

    /**
     * Continuous soil lenses follow relief: equal-height neighbours share the contour
     * component while slopes drain toward exposed substrate. Slowly varying horizontal
     * drift breaks contour rings. The residual texture never uses independent block rolls.
     */
    private static float soilMosaic(long seed, int x, int z, float elevation, float slope, int scale) {
        float broad = landscapePatch(seed ^ 0x534F494CL, x, z, scale);
        double drift = x / (double) scale / 8 + z / (double) scale / 11;
        float contour = continuousPatch(seed ^ 0x52454C494546L,
                elevation / 3.0 + (broad - .5) * 28, drift, 24);
        float detail = continuousPatch(seed ^ 0x534F494C444554L, x, z, 12 * Math.min(scale, 2));
        return clamp01(.5F + (broad - .5F) * .70F + (contour - .5F) * .80F
                + (detail - .5F) * .12F - .08F * smoothRange(.15F, 1.0F, slope));
    }

    /** Sandy shore climates keep their mineral apron, including marginal desert grasses. */
    public static boolean dryMineralShore(float temperature, float moisture) {
        return temperature > 10 && moisture < .38F;
    }

    /** Riparian moisture is local, small, and fades in a shared continuous field. */
    public static SurfaceMaterial bankSurfaceMaterial(float temperature, float moisture, float slope,
                                                       float treeCover, float elevation, float waterDistance,
                                                       long seed, int x, int z, int scale) {
        float wetness = 1 - smoothRange(0, 4, waterDistance);
        float retention = .35F + .65F * broadPatch(seed ^ 0x42414E4BL, x, z, 24);
        float bankMoisture = Math.min(2, moisture + .10F * wetness * retention);
        return surfaceMaterial(temperature, bankMoisture, slope, treeCover, elevation, seed, x, z, scale);
    }

    /**
     * Fraction of an exposed natural-soil column that may retain mud.  The inputs are
     * continuous habitat weights rather than biome labels, so crossing a quart
     * biome boundary cannot make a soil seam.  Callers still have to reject water,
     * shore minerals, structures, and unsuitable existing substrate.
     */
    public static float wetlandMudChance(float reedMarsh, float freshwaterSwamp,
                                         float mangrove, float riparian,
                                         float temperature, float slope) {
        float warmEnough = smoothRange(-1.0F, 5.0F, temperature);
        float stableGround = 1.0F - smoothRange(.10F, .38F, slope);
        float habitat = .32F * clamp01(reedMarsh)
                + .62F * clamp01(freshwaterSwamp)
                + .78F * clamp01(mangrove)
                + .28F * clamp01(riparian);
        return clamp01(habitat * warmEnough * stableGround);
    }

    /**
     * Identifies a saturated wetland floor from the same continuous ecology that
     * selected the biome.  This deliberately has no extra coordinate-noise field:
     * soil boundaries must follow the wetland's hydrology, not a decorative
     * stencil laid across dry hummocks.
     */
    public static boolean wetlandSedimentCore(float freshwaterSwamp, float mangrove,
                                               float wetness, float slope) {
        float habitat = Math.max(clamp01(freshwaterSwamp), clamp01(mangrove));
        float saturated = habitat * smoothRange(.50F, .82F, wetness)
                * (1.0F - smoothRange(.20F, .48F, slope));
        return saturated >= .50F;
    }

    /**
     * Resolves the visible bed at an existing coastal contact.  The broad field
     * still owns deposition and preserves its cores.  Only the sand/mud handoff
     * gets a deterministic, connected threshold.  Its two broad fields taper a
     * sheltered tongue into the sand; there is deliberately no per-block roll,
     * because that makes diagonal checks and isolated inversions at a flat bed.
     */
    public static CoastalSediment ditheredCoastalSediment(float weightedMangroveSupport,
                                                           float peakMangroveSupport,
                                                           int waterDepth, float concavity,
                                                           float broadDeposit,
                                                           long seed, int blockX, int blockZ) {
        float deposition = coastalDeposition(weightedMangroveSupport, peakMangroveSupport,
                waterDepth, concavity, broadDeposit);
        if (deposition < 0) return CoastalSediment.NONE;
        float contact = 1F - smoothRange(.09F, .40F, Math.abs(deposition - .5F));
        CoastalSediment base;
        if (!(contact > 0F)) {
            // Preserve ordinary mineral cores, but keep a high-supply mud core
            // eligible for a localized fine deposit.
            base = deposition < .5F ? CoastalSediment.SAND : CoastalSediment.MUD;
        } else {
            float noise = coastalDitherNoise(seed, blockX, blockZ);
            // The contact is allowed to wander, but not far enough to turn a calm
            // shallow into long, saw-toothed fingers.
            // A modest coherent offset makes a 3--5-block mineral handoff
            // without turning one contour into saw-toothed fingers.
            float threshold = .5F + (noise - .5F) * .12F * contact;
            base = deposition < threshold ? CoastalSediment.SAND : CoastalSediment.MUD;
        }
        // Fine silt is a localized deposit inside a sheltered muddy lobe, never
        // a mandatory third stripe.  A 13-block coherent grain field breaks the
        // lobe into muddy inclusions and broad patches rather than a uniform
        // plate.  Its sparse sand-side reach is limited to the local handoff,
        // so the open sand remains continuous.
        if (broadDeposit < .74F) return base;
        float siltLobe = broadPatch(seed ^ 0x53494C54504F434BL, blockX, blockZ, 72);
        if (siltLobe < .80F) return base;
        float grain = broadPatch(seed ^ 0x475241494E53494CL, blockX, blockZ, 13);
        if (base == CoastalSediment.MUD && grain >= .50F)
            return CoastalSediment.SILT;
        if (base == CoastalSediment.SAND && Math.abs(deposition - .5F) <= .055F
                && grain >= .78F)
            return CoastalSediment.SILT;
        return base;
    }

    private static float coastalDeposition(float weightedMangroveSupport, float peakMangroveSupport,
                                            int waterDepth, float concavity, float broadDeposit) {
        float affinity = coastalMangroveAffinity(weightedMangroveSupport, peakMangroveSupport,
                waterDepth, concavity);
        if (affinity <= .04F) return -1F;
        // Shoals do not switch from pure sand to pure mud at one contour. A
        // broad supply field shifts the whole depositional transition into
        // tongues and recesses; it is not independent per-block dither.
        float middle = smoothRange(.12F, .88F, affinity)
                * (1F - smoothRange(.12F, .88F, affinity));
        return clamp01(affinity + (clamp01(broadDeposit) - .5F)
                * (.18F + .72F * middle));
    }

    private static float coastalDitherNoise(long seed, int blockX, int blockZ) {
        float small = broadPatch(seed ^ 0x434F415354534D4CL, blockX, blockZ, 13);
        float medium = broadPatch(seed ^ 0x434F4153544D4544L, blockX, blockZ, 37);
        // Both inputs are smooth, so a material contact can make tapering tongues
        // and pockets but never a corner-touching checkerboard.
        return clamp01(.64F * small + .36F * medium);
    }

    // Sample the material field a few blocks away. Nearby columns tend to choose
    // similar offsets, but the per-column part breaks up the remaining hard line.
    // The offset is bounded so neither uniform bed can get speckles in its core.
    static int contactOffset(long axisSeed, int x, int z) {
        float patch = broadPatch(axisSeed, x, z, 3);
        float grain = hash01(axisSeed ^ 0x5041525449434c45L, x, z);
        return Math.round(10F * (.70F * patch + .30F * grain - .5F));
    }

    static CoastalSediment mixSandContact(CoastalSediment here, CoastalSediment nearby) {
        if (here == CoastalSediment.NONE || nearby == CoastalSediment.NONE) return here;
        return (here == CoastalSediment.SAND) != (nearby == CoastalSediment.SAND) ? nearby : here;
    }

    /** Shared, continuous shelter/sediment suitability for a shallow mangrove coast. */
    public static float coastalMangroveAffinity(float weightedMangroveSupport,
                                                 float peakMangroveSupport,
                                                 int waterDepth, float concavity) {
        float connected = smoothRange(.03F, .45F, peakMangroveSupport);
        float habitat = smoothRange(.12F, .62F, weightedMangroveSupport);
        float shallow = 1F - smoothRange(1F, 4F, waterDepth);
        float shelteredLow = smoothRange(-1.5F, 1.5F, concavity);
        return clamp01(connected * (.54F * habitat + .30F * shallow + .16F * shelteredLow));
    }

    /** Surface relation to an exclusive shaped water level. */
    public static PlannedWetlandMargin plannedWetlandMargin(int groundY, int exclusiveWaterLevel) {
        if (exclusiveWaterLevel <= 0) return PlannedWetlandMargin.NONE;
        int raisedAboveWetFloor = groundY - (exclusiveWaterLevel - 1);
        if (raisedAboveWetFloor == 0) return PlannedWetlandMargin.MUD;
        return PlannedWetlandMargin.NONE;
    }

    /** Reduces grass-like cover beside the shaped water table without changing dry land. */
    public static float plannedWetlandGroundcoverFactor(int groundY, int exclusiveWaterLevel) {
        if (exclusiveWaterLevel <= 0) return 1F;
        int raisedAboveWetFloor = groundY - (exclusiveWaterLevel - 1);
        if (raisedAboveWetFloor <= 0) return 0F;
        return 1F;
    }

    /**
     * Acceptance multiplier for the small set of native grass/fern random
     * patches restored in wetland and riparian ecology biomes.  The field is
     * continuous across chunk boundaries and deliberately leaves a low, nonzero
     * background: openings are caused by sparse patch attempts, not a stencil.
     */
    public static float nativeWetlandGroundcoverFactor(boolean fern, boolean overheadCanopy,
                                                       long seed, int blockX, int blockZ) {
        float patch = .20F + .55F * broadPatch(seed ^ 0x47524E5041544348L,
                blockX, blockZ, 24);
        if (fern) return patch * (overheadCanopy ? .85F : .65F);
        return patch * (overheadCanopy ? .30F : 1F);
    }

    /** Fixed 13-block climate-field kernel; values are row-major 7 by 7 samples. */
    public static float coastalMangroveSupport(float[] mangroveSamples) {
        if (mangroveSamples == null || mangroveSamples.length != 49)
            throw new IllegalArgumentException("coastal mangrove kernel requires 49 samples");
        float total = 0, weighted = 0;
        for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) {
            float weight = COASTAL_KERNEL[x] * COASTAL_KERNEL[z];
            total += weight;
            weighted += clamp01(mangroveSamples[z * 7 + x]) * weight;
        }
        return weighted / total;
    }

    /** Dry woodland cannot use saturated habitat or an observed mud root. */
    public static boolean allowsDryTree(boolean saturatedWetland, boolean mudSupport) {
        return !saturatedWetland && !mudSupport;
    }

    public enum PlannedWetlandMargin { NONE, MUD }

    /** Wet-bank plant density; it does not imply that a water-dependent plant may survive. */
    public static float wetlandBankPlantChance(float reedMarsh, float freshwaterSwamp,
                                               float mangrove, float riparian,
                                               float temperature, float slope) {
        float growth = smoothRange(-3.0F, 7.0F, temperature);
        float stableGround = 1.0F - smoothRange(.12F, .45F, slope);
        float habitat = .70F * clamp01(reedMarsh) + .52F * clamp01(freshwaterSwamp)
                + .26F * clamp01(mangrove) + .38F * clamp01(riparian);
        return clamp01(.30F * habitat * growth * stableGround);
    }

    /** Overlapping plant communities. No biome ID, chunk coordinate or hard climate selector is used. */
    public static float coldGroundcoverAcceptance(float temperature) {
        return smoothRange(-12, -4, temperature) * (1 - .85F * EcologyClassifier.coldState(temperature));
    }

    public static GroundcoverWeights groundcoverWeights(float temperature, float moisture,
                                                        float treeCover, float slope) {
        float cover = clamp01(treeCover);
        float cold = EcologyClassifier.coldState(temperature);
        float growth = smoothRange(-12, 4, temperature) * (1 - .75F * cold)
                * (1 - smoothRange(.45F, .90F, slope));
        float hydrated = smoothRange(.14F, .72F, moisture);
        float wet = smoothRange(.55F, 1.20F, moisture);
        float dry = 1 - smoothRange(.15F, .55F, moisture);
        float grass = growth * hydrated * (1 - .65F * cover);
        float fern = growth * wet * (.25F + .75F * cover);
        float warm = smoothRange(5, 18, temperature);
        return new GroundcoverWeights(
                grass,
                .35F * grass * smoothRange(0, 18, temperature) * (1 - .7F * cover),
                .65F * fern * (1 - cold),
                .30F * fern * smoothRange(4, 18, temperature),
                .18F * growth * wet * (1 - smoothRange(12, 20, temperature)) * (.3F + .7F * cover),
                .12F * growth * wet * smoothRange(5, 14, temperature)
                        * (1 - smoothRange(22, 28, temperature)) * smoothRange(.15F, .65F, cover),
                .20F * growth * hydrated * smoothRange(-1, 10, temperature)
                        * (1 - smoothRange(25, 34, temperature)) * (1 - .75F * cover),
                .65F * growth * dry * warm,
                .10F * growth * (1 - smoothRange(.15F, .32F, moisture)) * smoothRange(15, 25, temperature),
                .15F * growth * smoothRange(20, 27, temperature) * smoothRange(.75F, 1.4F, moisture)
                        * smoothRange(.20F, .60F, cover),
                .25F * growth * wet * cover,
                .07F * growth * wet * cover);
    }

    /** Chance per column; foliage survival and occupied cells can only reduce the resulting density. */
    public static float groundcoverChance(float temperature, float moisture, float treeCover, float slope,
                                         long seed, int blockX, int blockZ, int scale) {
        float growth = smoothRange(-12, 4, temperature)
                * (1 - .75F * EcologyClassifier.coldState(temperature));
        float hydrated = smoothRange(.14F, .72F, moisture);
        float openness = 1 - clamp01(treeCover);
        float live = .45F * growth * hydrated * (.35F + .65F * openness);
        float dryPlants = .035F * smoothRange(5, 18, temperature) * (1 - hydrated);
        float patch = .35F * landscapePatch(seed ^ 0x47524F554E44L, blockX, blockZ, scale)
                + .65F * detailPatch(seed ^ 0x504C414E5453L, blockX, blockZ, scale);
        float shelter = 1 - smoothRange(.25F, .85F, slope);
        return Math.min(.55F, (live + dryPlants) * (.45F + 1.1F * patch) * shelter);
    }

    /** Independent random stream for each column, so feature traversal never introduces a chunk seam. */
    public static long decorationSeed(long seed, int x, int z, long salt) {
        long value = (seed ^ salt) + 0x9E3779B97F4A7C15L * x + 0xC2B2AE3D27D4EB4FL * z;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * One stable decoration decision per world column. Repeated native patch
     * attempts at the same position must not compound a soil acceptance rate.
     */
    public static boolean coordinateChance(long seed, int x, int z, long salt, float chance) {
        if (!(chance > 0.0F)) return false;
        if (chance >= 1.0F) return true;
        float roll = (decorationSeed(seed, x, z, salt) >>> 40) * 0x1.0p-24F;
        return roll < chance;
    }

    /** Per-attempt probability, not achieved canopy coverage; tree survival is checked separately. */
    public static float treePlacementChance(float treeCover, long seed, int blockX, int blockZ, int scale) {
        float cover = clamp01(treeCover);
        float grove = landscapePatch(seed ^ 0x47524F56454CL, blockX, blockZ, scale);
        float clustered = lerp(0.08F, 1.65F, smoothRange(0.28F, 0.72F, grove));
        // Dense forests keep a high background probability; sparse woodland has groves and openings.
        float forest = smoothRange(0.45F, 0.85F, cover);
        float multiplier = lerp(clustered, lerp(0.75F, 1.15F, grove), forest);
        return 0.5F * clamp01(cover * multiplier);
    }

    /**
     * A humid, low-slope tropical coastal fringe can support a closed broadleaf
     * canopy. This deliberately excludes both dry inland woodland and exposed
     * beach columns; the caller also verifies connected dry ground.
     */
    public static boolean coastalTropicalCanopy(float temperature, float moisture, float slope,
                                                float marineInfluence) {
        return temperature >= 23.0F && moisture >= .72F && slope <= .34F
                && marineInfluence >= .32F;
    }

    private static float landscapePatch(long seed, int x, int z, int scale) {
        if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
        double modelX = x / (double) scale, modelZ = z / (double) scale;
        double dx = (continuousPatch(seed ^ 0x58L, modelX, modelZ, 256) - 0.5) * 128;
        double dz = (continuousPatch(seed ^ 0x5AL, modelX, modelZ, 256) - 0.5) * 128;
        return continuousPatch(seed, modelX + dx, modelZ + dz, 128);
    }

    private static float detailPatch(long seed, int x, int z, int scale) {
        double modelX = x / (double) scale, modelZ = z / (double) scale;
        return continuousPatch(seed, modelX, modelZ, 32);
    }

    private static float continuousPatch(long seed, double x, double z, int cell) {
        int cx = (int) Math.floor(x / cell), cz = (int) Math.floor(z / cell);
        float sx = smooth((float) (x / cell - cx));
        float sz = smooth((float) (z / cell - cz));
        return lerp(lerp(hash01(seed, cx, cz), hash01(seed, cx + 1, cz), sx),
                lerp(hash01(seed, cx, cz + 1), hash01(seed, cx + 1, cz + 1), sx), sz);
    }

    /**
     * Climate-compatible tree weights. Smooth eligibility curves retain gradual ecotones while
     * excluding tropical broadleaf species from freezing climates and conifers from hot lowlands.
     */
    public static SpeciesWeights speciesWeights(float temperature, float moisture) {
        float conifer = 1.0F - smoothRange(14.0F, 22.0F, temperature);
        float temperateBroadleaf = smoothRange(-2.0F, 8.0F, temperature)
                * (1.0F - smoothRange(20.0F, 28.0F, temperature));
        float warmDry = smoothRange(17.0F, 24.0F, temperature)
                * (1.0F - smoothRange(0.50F, 1.05F, moisture));
        float warmWet = smoothRange(20.0F, 25.0F, temperature)
                * smoothRange(0.75F, 1.35F, moisture);
        float dry = 1.0F - smoothRange(0.50F, 1.05F, moisture);
        float wet = smoothRange(0.75F, 1.35F, moisture);
        return new SpeciesWeights(
                1.25F * conifer * (0.40F + 0.60F * wet),
                conifer * (0.60F + 0.40F * dry),
                0.90F * temperateBroadleaf,
                0.55F * temperateBroadleaf * (0.35F + 0.65F * wet),
                1.40F * warmDry,
                1.60F * warmWet
        );
    }

    private static float smooth(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    private static float smoothRange(float low, float high, float value) {
        return smooth(clamp01((value - low) / (high - low)));
    }

    private static float lerp(float a, float b, float amount) {
        return a + (b - a) * amount;
    }

    private static float hash01(long seed, int x, int z) {
        long value = seed + 0x9E3779B97F4A7C15L * x + 0xC2B2AE3D27D4EB4FL * z;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (float)((value >>> 40) & 0xFFFFFFL) / 16777215.0F;
    }
}
