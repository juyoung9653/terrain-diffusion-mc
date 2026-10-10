package com.github.xandergos.terraindiffusionmc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Failure-only evidence capture. Never accepts, repairs or skips malformed chunks. */
@Mixin(ClientPacketListener.class)
public abstract class ChunkPacketFailureCaptureMixin {
    @Unique private static final AtomicBoolean terrainDiffusion$capturedChunkFailure = new AtomicBoolean();

    @WrapMethod(method = "updateLevelChunk")
    private void terrainDiffusion$captureChunkFailure(int x, int z, ClientboundLevelChunkPacketData packet,
                                                      Operation<Void> original) {
        try {
            original.call(x, z, packet);
        } catch (RuntimeException failure) {
            if (terrainDiffusion$capturedChunkFailure.compareAndSet(false, true)) {
                try {
                    Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/td-chunk-packet");
                    Files.createDirectories(directory);
                    // getReadBuffer creates a fresh wrapper, independent of the failed reader index.
                    FriendlyByteBuf buffer = packet.getReadBuffer();
                    try {
                        int length = buffer.readableBytes();
                        if (length > 2 * 1024 * 1024) throw new IllegalArgumentException("Oversized chunk evidence: " + length);
                        byte[] bytes = new byte[length];
                        buffer.getBytes(buffer.readerIndex(), bytes);
                        Files.write(directory.resolve("latest.bin"), bytes);
                        Files.writeString(directory.resolve("latest.txt"), "chunkX=" + x + "\nchunkZ=" + z
                                + "\nbytes=" + length + "\nexception=" + failure.getClass().getName() + "\n",
                                StandardCharsets.UTF_8);
                        LoggerFactory.getLogger("TerrainDiffusion/ChunkPacket").error(
                                "Chunk decode failed at [{}, {}]. Saved {} bytes to {}. Original failure follows.",
                                x, z, length, directory, failure);
                    } finally {
                        buffer.release();
                    }
                } catch (Exception captureFailure) {
                    // Diagnostics must never replace the original packet failure.
                    failure.addSuppressed(captureFailure);
                }
            }
            throw failure;
        }
    }
}
