package com.github.xandergos.terraindiffusionmc.mixin.memory;

import com.github.xandergos.terraindiffusionmc.compat.memory.SimpleBlockPhysicsPlayerFilter;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Filters players before Simple Block Physics reads their positions in this level. */
@Mixin(targets = "io.github.ferrinember.simpleblockphysics.utils.TickHandler", remap = false)
public abstract class SimpleBlockPhysicsDimensionMixin {
    @Redirect(
            method = "onLevelTick(Lnet/neoforged/neoforge/event/tick/LevelTickEvent$Pre;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"),
            require = 1,
            allow = 1)
    private static List<ServerPlayer> terrainDiffusion$playersInTickLevel(
            PlayerList playerList, LevelTickEvent.Pre event) {
        return SimpleBlockPhysicsPlayerFilter.inLevel(playerList.getPlayers(), event.getLevel());
    }
}
