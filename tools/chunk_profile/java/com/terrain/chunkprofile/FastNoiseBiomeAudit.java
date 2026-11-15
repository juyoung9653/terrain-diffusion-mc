package com.terrain.chunkprofile;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Opt-in regression receipt for zfastnoise 1.0.13's biome fast path.
 *
 * <p>The audit invokes the installed implementation through reflection.  It
 * deliberately has no production dependency on zfastnoise and therefore fails
 * clearly when the selected instance does not contain that exact API.  The
 * compatibility mixin must route palette sizes above eight through vanilla's
 * {@link LevelChunkSection#fillBiomesFromNoise}; otherwise the 9+ cases below
 * produce the same invalid local-palette packet that disconnected the client.</p>
 */
public final class FastNoiseBiomeAudit {
    private static final int[] CARDINALITIES = {1, 2, 3, 4, 7, 8, 9, 10, 16, 17, 32, 33, 64,
            33, 32, 17, 16, 10, 9, 8, 7, 4, 3, 2, 1};
    private static final int BASE_X = -20, BASE_Y = -3, BASE_Z = 11;

    private FastNoiseBiomeAudit() { }

    /** Runs the installed zfastnoise fast path against each protocol boundary. */
    public static Map<String, Object> run(ServerLevel level) {
        Registry<Biome> registry = fixtureRegistry();
        Method populate = populateMethod();
        int sections = 0;
        LevelChunkSection receiver = new LevelChunkSection(registry);
        LevelChunkSection source = new LevelChunkSection(registry);
        for (int cardinality : CARDINALITIES) {
            Holder<Biome>[] expected = expected(registry, cardinality);
            int[] calls = {0};
            BiomeResolver resolver = (x, y, z, sampler) -> {
                int index = sampleIndex(x, y, z);
                calls[0]++;
                return expected[(index * 37 + cardinality) % cardinality];
            };
            invoke(populate, source, resolver);
            if (calls[0] != 64) throw new IllegalStateException("zfastnoise resolver calls=" + calls[0]
                    + " cardinality=" + cardinality);
            for (int index = 0; index < 64; index++) {
                int x = index & 3, y = index >> 4, z = (index >> 2) & 3;
                Holder<Biome> actual = source.getNoiseBiome(x, y, z);
                Holder<Biome> want = expected[(index * 37 + cardinality) % cardinality];
                if (actual != want) throw new IllegalStateException("zfastnoise biome mismatch cardinality="
                        + cardinality + " index=" + index);
            }
            if (cardinality(source) != cardinality) throw new IllegalStateException("Wrong generated cardinality " + cardinality);
            roundTrip(source, receiver, cardinality);
            sections++;
        }
        return Map.of("status", "passed", "jarApi", "FastBiomeGen.populateBiomes",
                "cardinalities", Arrays.stream(CARDINALITIES).boxed().toList(), "sections", sections,
                "resolverCallsPerSection", 64, "negativeOrigin", "-20,-3,11", "receiverReuse", true,
                "packetRoundtrip", true);
    }

    /** Serializes real generated chunk sections using their actual biome registry. */
    public static Map<String, Object> auditChunk(ServerLevel level, LevelChunk chunk) {
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LevelChunkSection[] sections = chunk.getSections();
            int[] starts = new int[sections.length];
            int[] ends = new int[sections.length];
            int maxBiomes = 0;
            for (int i = 0; i < sections.length; i++) {
                starts[i] = buffer.writerIndex();
                sections[i].write(buffer);
                ends[i] = buffer.writerIndex();
                if (ends[i] - starts[i] != sections[i].getSerializedSize())
                    throw new IllegalStateException("chunk section serialized size at " + i);
                maxBiomes = Math.max(maxBiomes, cardinality(sections[i]));
            }
            for (int i = 0; i < sections.length; i++) {
                if (buffer.readerIndex() != starts[i]) throw new IllegalStateException("chunk section start " + i);
                LevelChunkSection decoded = new LevelChunkSection(registry);
                decoded.read(buffer);
                if (buffer.readerIndex() != ends[i]) throw new IllegalStateException("chunk section end " + i);
                verifySection(sections[i], decoded, "chunk section=" + i);
            }
            if (buffer.isReadable()) throw new IllegalStateException("chunk packet trailing bytes");
            return Map.of("status", "passed", "chunk", List.of(chunk.getPos().x, chunk.getPos().z),
                    "sections", sections.length, "maxBiomeCardinality", maxBiomes, "packetRoundtrip", true,
                    "biomeFingerprint", biomeFingerprint(chunk), "blockFingerprint", blockFingerprint(chunk));
        } finally {
            buffer.release();
        }
    }

    private static void invoke(Method populate, LevelChunkSection section, BiomeResolver resolver) {
        try {
            // Fast Noise mutates this scratch palette; never alias the resolver's expected holders.
            populate.invoke(null, section, resolver, null, BASE_X, BASE_Y, BASE_Z, new Holder<?>[64], new byte[64]);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access zfastnoise biome fast path", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("zfastnoise biome fast path failed", cause);
        }
    }

    private static Method populateMethod() {
        try {
            Class<?> fastBiomeGen = Class.forName("org.codeberg.zenxarch.fastnoise.noise.FastBiomeGen");
            return fastBiomeGen.getMethod("populateBiomes", LevelChunkSection.class, BiomeResolver.class,
                    Climate.Sampler.class, int.class, int.class, int.class, Holder[].class, byte[].class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Missing zfastnoise 1.0.13 FastBiomeGen API", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] expected(Registry<Biome> registry, int cardinality) {
        Holder<Biome>[] result = new Holder[cardinality];
        for (int i = 0; i < cardinality; i++) result[i] = registry.getHolder(i).orElseThrow();
        return result;
    }

    private static void roundTrip(LevelChunkSection source, LevelChunkSection receiver, int cardinality) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            source.write(buffer);
            int bytes = buffer.readableBytes();
            if (bytes != source.getSerializedSize()) throw new IllegalStateException("section size cardinality=" + cardinality);
            receiver.read(buffer);
            if (buffer.isReadable()) throw new IllegalStateException("section trailing bytes cardinality=" + cardinality);
            verifySection(source, receiver, "cardinality=" + cardinality);
        } finally {
            buffer.release();
        }
    }

    private static void verifySection(LevelChunkSection source, LevelChunkSection decoded, String detail) {
        for (int i = 0; i < 64; i++) {
            int x = i & 3, y = i >> 4, z = (i >> 2) & 3;
            if (source.getNoiseBiome(x, y, z) != decoded.getNoiseBiome(x, y, z))
                throw new IllegalStateException("biome roundtrip " + detail + " index=" + i);
        }
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
            if (source.getBlockState(x, y, z) != decoded.getBlockState(x, y, z))
                throw new IllegalStateException("state roundtrip " + detail + " index=" + i);
        }
    }

    private static int cardinality(LevelChunkSection section) {
        Set<Holder<Biome>> values = new LinkedHashSet<>();
        for (int i = 0; i < 64; i++) values.add(section.getNoiseBiome(i & 3, i >> 4, (i >> 2) & 3));
        return values.size();
    }

    /** Stable across reloads: section Y followed by its 64 ordered biome resource keys. */
    public static String biomeFingerprint(ChunkAccess chunk) {
        MessageDigest digest = digest();
        LevelChunkSection[] sections = chunk.getSections();
        for (int section = 0; section < sections.length; section++) {
            update(digest, "sectionY=" + (chunk.getMinSection() + section) + "\n");
            for (int index = 0; index < 64; index++) {
                Holder<Biome> holder = sections[section].getNoiseBiome(index & 3, index >> 4, (index >> 2) & 3);
                update(digest, holder.unwrapKey().orElseThrow(() ->
                        new IllegalStateException("unkeyed biome in chunk " + chunk.getPos())).location().toString());
                update(digest, "\n");
            }
        }
        return hex(digest.digest());
    }

    /** Informational only: block ticks can change this after a reload. */
    private static String blockFingerprint(ChunkAccess chunk) {
        MessageDigest digest = digest();
        LevelChunkSection[] sections = chunk.getSections();
        for (int section = 0; section < sections.length; section++) {
            update(digest, "sectionY=" + (chunk.getMinSection() + section) + "\n");
            for (int index = 0; index < 4096; index++) {
                int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
                update(digest, sections[section].getBlockState(x, y, z).toString());
                update(digest, "\n");
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Missing SHA-256", impossible); }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static int sampleIndex(int x, int y, int z) {
        int dx = x - BASE_X, dy = y - BASE_Y, dz = z - BASE_Z;
        if (dx < 0 || dx >= 4 || dy < 0 || dy >= 4 || dz < 0 || dz >= 4)
            throw new IllegalStateException("unexpected resolver coordinate " + x + "," + y + "," + z);
        return (dy << 4 | dz << 2 | dx);
    }

    private static Registry<Biome> fixtureRegistry() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        for (int i = 0; i < 64; i++) {
            ResourceKey<Biome> key = i == 0 ? Biomes.PLAINS : ResourceKey.create(Registries.BIOME,
                    ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "fastnoise_fixture/" + i));
            Biome biome = new Biome.BiomeBuilder().hasPrecipitation(false).temperature(.5F).downfall(0)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).skyColor(0)
                            .waterColor(0).waterFogColor(0).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
            registry.register(key, biome, RegistrationInfo.BUILT_IN);
        }
        return registry.freeze();
    }
}



