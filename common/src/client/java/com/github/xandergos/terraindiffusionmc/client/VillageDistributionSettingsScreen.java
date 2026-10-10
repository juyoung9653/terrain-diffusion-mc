package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared village distribution; server settings take effect on the next world load. */
public final class VillageDistributionSettingsScreen extends Screen {
    private final Screen parent;
    private boolean enabled;
    private int algorithmVersion = 2;
    private String distance = "500", share = "70", status = "";
    private boolean loadFailed;
    private EditBox distanceField, shareField;

    public VillageDistributionSettingsScreen(Screen parent) {
        super(Component.literal("Village distribution"));
        this.parent = parent;
        try {
            var settings = StructurePlacementConfig.readDocument().villageDistribution;
            enabled = settings.enabled;
            algorithmVersion = settings.algorithmVersion;
            distance = Integer.toString(settings.targetMeanNearestNeighborBlocks);
            share = Integer.toString(settings.basicSharePercent);
        } catch (Exception error) {
            status = "Cannot load config: " + error.getMessage();
            loadFailed = true;
        }
    }

    @Override protected void init() {
        int center = width / 2;
        addRenderableWidget(Button.builder(Component.literal(enabled ? "Unified villages: ON" : "Unified villages: OFF"), button -> {
            enabled = !enabled;
            button.setMessage(Component.literal(enabled ? "Unified villages: ON" : "Unified villages: OFF"));
        }).bounds(center - 110, 48, 220, 20).build());
        distanceField = new EditBox(font, center + 70, 82, 78, 20, Component.literal("Mean nearest center distance"));
        distanceField.setMaxLength(4);
        distanceField.setValue(distance);
        distanceField.setResponder(value -> distance = value);
        addRenderableWidget(distanceField);
        shareField = new EditBox(font, center + 70, 110, 78, 20, Component.literal("Basic villages percent"));
        shareField.setMaxLength(3);
        shareField.setValue(share);
        shareField.setResponder(value -> share = value);
        addRenderableWidget(shareField);
        addRenderableWidget(Button.builder(placementLabel(), button -> {
            algorithmVersion = algorithmVersion == 2 ? 1 : 2;
            button.setMessage(placementLabel());
        }).bounds(center - 110, 198, 220, 20).build());
        Button save = Button.builder(Component.literal("Save defaults"), button -> save())
                .bounds(center - 100, height - 32, 95, 20).build();
        save.active = !loadFailed;
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(center + 5, height - 32, 95, 20).build());
    }

    private void save() {
        try {
            var document = StructurePlacementConfig.readDocument();
            var settings = document.villageDistribution;
            settings.enabled = enabled;
            settings.targetMeanNearestNeighborBlocks = Integer.parseInt(distance.trim());
            settings.basicSharePercent = Integer.parseInt(share.trim());
            settings.algorithmVersion = algorithmVersion;
            StructurePlacementConfig.save(document);
            onClose();
        } catch (Exception error) { status = "Could not save: " + error.getMessage(); }
    }

    private Component placementLabel() {
        return Component.literal(algorithmVersion == 2 ? "Village placement: improved" : "Village placement: legacy");
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        graphics.drawCenteredString(font, title, center, 12, 0xffffff);
        graphics.drawCenteredString(font, "Applies to new chunks after world reload.", center, 28, 0xaaaaaa);
        graphics.drawString(font, "Mean center distance (256-4096)", center - 149, 88, 0xffffff);
        graphics.drawString(font, "Basic villages % (0-100)", center - 149, 116, 0xffffff);
        graphics.drawCenteredString(font, "Basic: original vanilla + Missing Villages", center, 148, 0xaaaaaa);
        graphics.drawCenteredString(font, "Other: Epic, Towns and Towers, and other villages", center, 161, 0xaaaaaa);
        graphics.drawCenteredString(font, "Terrain and biome availability affect actual results.", center, 178, 0xaaaaaa);
        if (!status.isEmpty()) graphics.drawCenteredString(font,
                font.plainSubstrByWidth(status, Math.max(50, width - 16)), center, height - 10, 0xff5555);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
}
