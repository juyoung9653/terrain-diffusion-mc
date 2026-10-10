package com.github.xandergos.terraindiffusionmc.diagnostic;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;

/** Deliberately malformed client-only section, restricted to the copied replay fixture. */
@EventBusSubscriber(modid = "terrain_diffusion_mc", value = Dist.CLIENT)
public final class ChunkFailureCaptureAudit {
    private static boolean done;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (done || !Boolean.getBoolean("td.chunkFailureCaptureAudit") || mc.level == null || mc.player == null
                || mc.screen != null || mc.getConnection() == null) return;
        done = true;
        try {
            var game = mc.gameDirectory.toPath().toRealPath();
            if (!"disconnect-repair".equals(game.getParent().getParent().getFileName().toString())
                    || !Files.isRegularFile(game.resolveSibling("fixture.json")))
                throw new IllegalStateException("Not the isolated replay fixture");
            byte[] invalid = new byte[21];
            invalid[2] = 1; // Invalid block palette bit width, selects a 16-slot LinearPalette under Lithium.
            invalid[3] = 17;
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.level.registryAccess());
            ClientboundLevelChunkPacketData packet;
            try {
                wire.writeNbt(new CompoundTag());
                wire.writeByteArray(invalid);
                wire.writeVarInt(0); // no block entities
                packet = new ClientboundLevelChunkPacketData(wire, mc.player.chunkPosition().x, mc.player.chunkPosition().z);
            } finally { wire.release(); }
            var method = ClientPacketListener.class.getDeclaredMethod("updateLevelChunk", int.class, int.class,
                    ClientboundLevelChunkPacketData.class);
            method.setAccessible(true);
            boolean originalPreserved = false;
            try {
                method.invoke(mc.getConnection(), mc.player.chunkPosition().x, mc.player.chunkPosition().z, packet);
            } catch (InvocationTargetException failure) {
                originalPreserved = failure.getCause() instanceof ArrayIndexOutOfBoundsException;
            }
            if (!originalPreserved) throw new AssertionError("Original decode failure changed or missing");
            if (!Arrays.equals(invalid, Files.readAllBytes(game.resolve("debug/td-chunk-packet/latest.bin"))))
                throw new AssertionError("Captured bytes differ");
            LoggerFactory.getLogger("ChunkFailureCaptureAudit").info("[TD-CHUNK-CAPTURE-AUDIT] PASS exactBytes=true originalException=true");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("ChunkFailureCaptureAudit").error("[TD-CHUNK-CAPTURE-AUDIT] FAIL", failure);
        } finally { mc.stop(); }
    }
}
