package com.github.xandergos.terraindiffusionmc.compat.memory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Keeps Simple Block Physics from probing this level at players in another dimension. */
public final class SimpleBlockPhysicsPlayerFilter {
    private SimpleBlockPhysicsPlayerFilter() { }

    public static List<ServerPlayer> inLevel(List<ServerPlayer> players, Level level) {
        ArrayList<ServerPlayer> matching = null;
        for (int index = 0; index < players.size(); index++) {
            ServerPlayer player = players.get(index);
            if (level.dimension().equals(player.level().dimension())) {
                if (matching != null) {
                    matching.add(player);
                }
            } else if (matching == null) {
                matching = new ArrayList<>(players.size() - 1);
                matching.addAll(players.subList(0, index));
            }
        }
        return matching == null ? players : matching.isEmpty() ? List.of() : matching;
    }
}
