package com.github.xandergos.terraindiffusionmc.diagnostic;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.LoggerFactory;

/** Opt-in real transformed palette roundtrip; no production world changes. */
@EventBusSubscriber(modid = "terrain_diffusion_mc", value = Dist.CLIENT)
public final class PaletteWireAudit {
    private static boolean done;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (done || !Boolean.getBoolean("td.paletteWireAudit") || Minecraft.getInstance().screen == null) return;
        done = true;
        try {
            int cases = 0;
            var decoded = create();
            for (int count : new int[]{1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257}) {
                var original = create();
                for (int index = 0; index < 4096; index++) set(original, index, Block.BLOCK_STATE_REGISTRY.byId(index % count));
                roundtrip(original, decoded, count, "grown"); cases++;
                for (int index = 0; index < 4096; index++) set(original, index, Blocks.AIR.defaultBlockState());
                roundtrip(original, decoded, count, "collapsed"); cases++;
                for (int index = 0; index < 4096; index++) set(original, index, Block.BLOCK_STATE_REGISTRY.byId(index % 3));
                roundtrip(original, decoded, count, "reused"); cases++;
            }
            int sectionCases = roundtripSections();
            LoggerFactory.getLogger("PaletteWireAudit").info(
                    "[TD-PALETTE-AUDIT] PASS cases={} sectionCases={} exact4096=true exact64=true receiverReuse=true exactBoundaries=true",
                    cases, sectionCases);
        } catch (Throwable error) {
            LoggerFactory.getLogger("PaletteWireAudit").error("[TD-PALETTE-AUDIT] FAIL", error);
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private static PalettedContainer<BlockState> create() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    private static void set(PalettedContainer<BlockState> container, int index, BlockState state) {
        container.set(index & 15, index >> 8, (index >> 4) & 15, state);
    }

    private static void roundtrip(PalettedContainer<BlockState> original, PalettedContainer<BlockState> decoded,
                                  int count, String stage) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(buffer);
            int bytes = buffer.readableBytes();
            LoggerFactory.getLogger("PaletteWireAudit").info("[TD-PALETTE-CASE] count={} stage={} bits={} bytes={}",
                    count, stage, buffer.getUnsignedByte(0), bytes);
            decoded.read(buffer);
            if (buffer.isReadable() || bytes != original.getSerializedSize()) throw new AssertionError("wire length mismatch");
            for (int i = 0; i < 4096; i++)
                if (original.get(i & 15, i >> 8, (i >> 4) & 15) != decoded.get(i & 15, i >> 8, (i >> 4) & 15))
                    throw new AssertionError("state mismatch count=" + count + " stage=" + stage + " index=" + i);
        } finally {
            buffer.release();
        }
    }

    private static int roundtripSections() {
        Registry<Biome> registry = biomeRegistry();
        int[] stateCounts = {1, 2, 3, 16, 17, 33, 65};
        int[] biomeCounts = {1, 2, 3, 4, 8, 9, 17};
        LevelChunkSection[] receivers = new LevelChunkSection[stateCounts.length];
        for (int i = 0; i < receivers.length; i++) receivers[i] = new LevelChunkSection(registry);
        int cases = 0;
        for (int stage = 0; stage < 3; stage++) {
            LevelChunkSection[] originals = new LevelChunkSection[stateCounts.length];
            int[] starts = new int[stateCounts.length], ends = new int[stateCounts.length];
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                // Each payload contains seven complete consecutive sections, just
                // like the chunk packet. Reuse the same receivers for every stage.
                for (int i = 0; i < originals.length; i++) {
                    int variant = stage == 2 ? stateCounts.length - 1 - i : i;
                    int states = stage == 1 ? 1 : stateCounts[variant];
                    int biomes = stage == 1 ? 1 : biomeCounts[variant];
                    var stateData = create();
                    var biomeData = new PalettedContainer<Holder<Biome>>(registry.asHolderIdMap(),
                            registry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
                    for (int index = 0; index < 4096; index++)
                        set(stateData, index, Block.BLOCK_STATE_REGISTRY.byId((index % states) + stage));
                    for (int index = 0; index < 64; index++)
                        biomeData.set(index & 3, index >> 4, (index >> 2) & 3,
                                registry.getHolder(((index % biomes) + stage) % registry.size()).orElseThrow());
                    originals[i] = new LevelChunkSection(stateData, biomeData);
                    starts[i] = buffer.writerIndex();
                    originals[i].write(buffer);
                    ends[i] = buffer.writerIndex();
                    if (ends[i] - starts[i] != originals[i].getSerializedSize())
                        throw new AssertionError("section serialized size stage=" + stage + " section=" + i);
                    int stateOffset = starts[i] + Short.BYTES;
                    int biomeOffset = stateOffset + stateData.getSerializedSize();
                    LoggerFactory.getLogger("PaletteWireAudit").info(
                            "[TD-SECTION-WIRE] stage={} section={} states={} biomes={} start={} stateOffset={} stateBits={} biomeOffset={} biomeBits={} end={}",
                            stage, i, states, biomes, starts[i], stateOffset, buffer.getUnsignedByte(stateOffset),
                            biomeOffset, buffer.getUnsignedByte(biomeOffset), ends[i]);
                }
                for (int i = 0; i < originals.length; i++) {
                    if (buffer.readerIndex() != starts[i]) throw new AssertionError("section start misaligned");
                    try {
                        receivers[i].read(buffer);
                    } catch (RuntimeException error) {
                        throw new AssertionError("section decode stage=" + stage + " section=" + i
                                + " start=" + starts[i] + " failedOffset=" + buffer.readerIndex()
                                + " end=" + ends[i], error);
                    }
                    if (buffer.readerIndex() != ends[i])
                        throw new AssertionError("section end misaligned stage=" + stage + " section=" + i
                                + " expected=" + ends[i] + " actual=" + buffer.readerIndex());
                    for (int index = 0; index < 4096; index++) {
                        int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
                        if (originals[i].getBlockState(x, y, z) != receivers[i].getBlockState(x, y, z))
                            throw new AssertionError("section state mismatch stage=" + stage + " section=" + i + " index=" + index);
                    }
                    for (int index = 0; index < 64; index++) {
                        int x = index & 3, y = index >> 4, z = (index >> 2) & 3;
                        if (originals[i].getNoiseBiome(x, y, z) != receivers[i].getNoiseBiome(x, y, z))
                            throw new AssertionError("section biome mismatch stage=" + stage + " section=" + i + " index=" + index);
                    }
                    cases++;
                }
                if (buffer.isReadable()) throw new AssertionError("trailing section bytes");
            } finally {
                buffer.release();
            }
        }
        return cases;
    }

    private static Registry<Biome> biomeRegistry() {
        // A private fixture registry makes this usable on the title screen and
        // exercises biome palette global-mode transitions without loading a world.
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        for (int i = 0; i < 17; i++) {
            ResourceKey<Biome> key = i == 0 ? Biomes.PLAINS : ResourceKey.create(Registries.BIOME,
                    ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "palette_fixture/" + i));
            Biome biome = new Biome.BiomeBuilder().hasPrecipitation(false).temperature(.5F).downfall(0)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).skyColor(0)
                            .waterColor(0).waterFogColor(0).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
            registry.register(key, biome, RegistrationInfo.BUILT_IN);
        }
        return registry.freeze();
    }
}
