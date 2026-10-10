import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier.Sample;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.EcologyShowcaseProfile;
import com.github.xandergos.terraindiffusionmc.pipeline.ShoreClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.world.ecology.FeatureEcologyMath.SurfaceMaterial;
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverBankSurface;

public final class NaturalRiverBankSurfaceTest {
    public static void main(String[] args) {
        Sample desert = new Sample(11, 0, 28, .10F, .03F);
        Sample temperate = new Sample(1, .8F, 13, 1.25F, .05F);
        Sample boreal = new Sample(12, .9F, -3, 1.25F, .05F);
        Sample glacier = new Sample(19, 0, -12, .35F, .7F);
        Sample retained = new Sample(0, 0, -3, .3F, .05F);
        short gravel = (short) (TerralithBiomeClassifier.biomeNames().indexOf("terralith:gravel_beach") + 1);
        SurfaceMaterial[][] forward = new SurfaceMaterial[128][128];
        int podzol = 0, coldMineral = 0, grass = 0;
        for (int x = -64; x < 64; x++) for (int z = -64; z < 64; z++) {
            for (float distance : new float[]{0, 2, 5}) {
                SurfaceMaterial dry = material(desert, (short) 0, distance, x, z);
                require(dry == SurfaceMaterial.SAND || dry == SurfaceMaterial.COARSE_DIRT,
                        "arid river recap became grass: " + dry);
                require(material(desert, gravel, distance, x, z) == SurfaceMaterial.GRAVEL,
                        "arid gravel beach changed to sand or grass");
                require(material(retained, ShoreClassifier.SNOWY_BEACH, distance, x, z) == SurfaceMaterial.SAND,
                        "snowy beach acquired river grass");
                require(material(temperate, ShoreClassifier.STONY_SHORE, distance, x, z) == SurfaceMaterial.STONE,
                        "rock shore acquired soil");
                require(material(retained, (short) 0, distance, x, z) == null,
                        "unclassified retained terrain invented land soil");
            }
            SurfaceMaterial normal = material(temperate, (short) 0, 2, x, z);
            forward[x + 64][z + 64] = normal;
            if (normal == SurfaceMaterial.GRASS) grass++;
            if (material(boreal, (short) 0, 2, x, z) == SurfaceMaterial.PODZOL) podzol++;
            if (material(glacier, (short) 0, 2, x, z) == SurfaceMaterial.STONE) coldMineral++;
        }
        // Reversing visit order has no effect, including negative chunk edges.
        for (int z = 63; z >= -64; z--) for (int x = 63; x >= -64; x--)
            require(forward[x + 64][z + 64] == material(temperate, (short) 0, 2, x, z), "order-dependent soil");
        require(grass > 8000, "ordinary temperate grass was removed");
        require(podzol > 1000, "cold forest floor never reaches a bank");
        require(coldMineral > 4000, "glacial bank lost its mineral exposure");
        allEcologyClimates();
        climateDomainAndShoreOverrides(gravel);
        System.out.println("NaturalRiverBankSurfaceTest: passed desert, gravel, snowy/rock shore, retained terrain; "
                + "temperate grass=" + grass + " boreal podzol=" + podzol + " glacial stone=" + coldMineral);
    }

    private static void allEcologyClimates() {
        for (String biome : EcologyClassifier.BIOMES) {
            int count = 0, sand = 0, grass = 0, mineral = 0;
            for (int scale : new int[]{1, 6}) for (float slope : new float[]{0, .4F, .9F})
                for (float water : new float[]{0, 2, 6}) for (int x = -64; x <= 64; x += 8)
                    for (int z = -64; z <= 64; z += 8) {
                        Sample sample = EcologyShowcaseProfile.sample(biome, x, z, slope);
                        SurfaceMaterial result = NaturalRiverBankSurface.material(sample, (short) 0,
                                150, water, -5095275822693182495L, x, z, scale);
                        require(result != null, "land climate left native recap unresolved: " + biome);
                        if (result == SurfaceMaterial.SAND) sand++;
                        if (result == SurfaceMaterial.GRASS) grass++;
                        if (result == SurfaceMaterial.STONE || result == SurfaceMaterial.GRAVEL) mineral++;
                        // Renaming an ecosystem must not change its physical soil response.
                        Sample renamed = new Sample(sample.biomeId() % EcologyClassifier.BIOMES.size() + 1,
                                sample.treeCover(), sample.temperature(), sample.moisture(), sample.slope());
                        require(result == NaturalRiverBankSurface.material(renamed, (short) 0,
                                150, water, -5095275822693182495L, x, z, scale),
                                "bank soil follows label instead of climate: " + biome);
                        if (sample.temperature() <= 5 || sample.moisture() >= .6F)
                            require(result != SurfaceMaterial.SAND, "cold/humid land acquired desert sand: " + biome);
                        if (sample.temperature() >= 18)
                            require(result != SurfaceMaterial.PODZOL, "warm bank acquired cold forest floor: " + biome);
                        count++;
                    }
            if (biome.equals("desert")) require(grass == 0 && sand > count / 2, "desert recap is not mineral");
            if (biome.equals("tropical_forest")) require(grass > count / 2, "tropical soil was stripped");
            // Flat glacial ground may retain coarse soil; steep samples must expose
            // minerals, and the overall bank must not become a temperate grass strip.
            if (biome.equals("glacial_highlands")) require(grass < count / 20 && mineral > count / 4,
                    "glacial substrate became a green bank");
            System.out.println("  " + biome + ": samples=" + count + " sand=" + sand
                    + " grass=" + grass + " stone/gravel=" + mineral);
        }
    }

    private static void climateDomainAndShoreOverrides(short gravel) {
        int samples = 0;
        short ocean = (short) (TerralithBiomeClassifier.biomeNames().indexOf("terralith:deep_warm_ocean") + 1);
        require(ocean > 0 && gravel > 0, "required terrain registry entries absent");
        for (float temperature : new float[]{-20, -12, -6, -3, 0, 5, 10, 18, 23, 28, 40})
            for (float moisture : new float[]{0, .1F, .3F, .38F, .6F, 1, 1.5F, 2})
                for (float slope : new float[]{0, .2F, .8F}) for (float water : new float[]{0, 2, 6})
                    for (int x : new int[]{-17, -16, -1, 0, 15, 16, 2240}) {
                        Sample land = new Sample(1, .5F, temperature, moisture, slope);
                        Sample shore = new Sample(0, 0, temperature, moisture, slope);
                        int z = x - 12;
                        SurfaceMaterial result = material(land, (short) 0, water, x, z);
                        require(result != null, "finite climate produced no soil");
                        if (temperature <= 5 || moisture >= .6F)
                            require(result != SurfaceMaterial.SAND, "climate-domain sand leak");
                        require(material(shore, gravel, water, x, z) == SurfaceMaterial.GRAVEL,
                                "gravel shore changed with temperature or moisture");
                        require(material(shore, ShoreClassifier.STONY_SHORE, water, x, z) == SurfaceMaterial.STONE,
                                "rock shore changed with temperature or moisture");
                        require(material(shore, ShoreClassifier.SNOWY_BEACH, water, x, z) == SurfaceMaterial.SAND,
                                "snowy beach soil replaced its mineral substrate");
                        require(material(shore, ocean, water, x, z) == null,
                                "retained ocean acquired land soil");
                        SurfaceMaterial beach = material(shore, ShoreClassifier.BEACH, water, x, z);
                        require(beach != null, "beach climate was discarded");
                        if (temperature >= 18 && moisture <= .3F)
                            require(beach == SurfaceMaterial.SAND, "dry sandy coast acquired grass");
                        samples++;
                    }
        System.out.println("  Climate domain and all shore overrides: " + samples + " samples passed");
    }

    private static SurfaceMaterial material(Sample sample, short terrain, float waterDistance, int x, int z) {
        return NaturalRiverBankSurface.material(sample, terrain, 150, waterDistance, -5095275822693182495L, x, z, 1);
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
