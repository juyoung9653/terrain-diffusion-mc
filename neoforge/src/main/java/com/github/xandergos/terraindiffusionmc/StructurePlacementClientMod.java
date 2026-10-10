package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.client.StructurePlacementSettingsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only Mods menu entry. Dedicated servers never load the screen class. */
@Mod(value = TerrainDiffusionMc.FML_MOD_ID, dist = Dist.CLIENT)
public final class StructurePlacementClientMod {
    public StructurePlacementClientMod(ModContainer container) {
        IConfigScreenFactory screenFactory = (mod, parent) -> new StructurePlacementSettingsScreen(parent);
        container.registerExtensionPoint(IConfigScreenFactory.class, screenFactory);
    }
}
