package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Document;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.Override;
import com.github.xandergos.terraindiffusionmc.config.StructurePlacementConfig.SurfaceMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Edits server defaults; distance changes apply next load, terrain rules stay pinned. */
public final class StructurePlacementSettingsScreen extends Screen {
    private static final String[] FIELD_NAMES = {
            "Edge gap >=100 blocks", "Reservation radius (blocks)",
            "Candidate offset (blocks)", "Maximum cut (blocks)",
            "Maximum fill (blocks)", "Blend radius (blocks)",
            "Terrain samples", "Candidate attempts", "Spacing multiplier", "Surface mode"
    };
    private static final int PAGE_SIZE = 2;

    private final Screen parent;
    private final Map<String, String[]> drafts = new HashMap<>();
    private Document document;
    private Scope scope = Scope.GLOBAL;
    private String selectedId = "";
    private int page;
    private boolean enabled = true;
    private boolean loadFailed;
    private String status = "";
    private EditBox idField;
    private final EditBox[] visibleFields = new EditBox[PAGE_SIZE];

    private enum Scope { GLOBAL, NAMESPACE, STRUCTURE }

    public StructurePlacementSettingsScreen(Screen parent) {
        super(Component.literal("Structure placement"));
        this.parent = parent;
        try {
            document = StructurePlacementConfig.readDocument();
            enabled = document.enabled;
            drafts.put("global", values(document.global));
        } catch (IOException | IllegalArgumentException error) {
            document = new Document();
            loadFailed = true;
            status = "Cannot load config: " + error.getMessage();
        }
    }

    @java.lang.Override
    protected void init() {
        int center = width / 2;
        int row = 51;
        addRenderableWidget(Button.builder(Component.literal("Global"), button -> selectScope(Scope.GLOBAL))
                .bounds(center - 149, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mod namespace"), button -> selectScope(Scope.NAMESPACE))
                .bounds(center - 49, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Structure ID"), button -> selectScope(Scope.STRUCTURE))
                .bounds(center + 51, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal(enabled ? "New worlds: Enabled" : "New worlds: Disabled"), button -> {
                    enabled = !enabled;
                    button.setMessage(Component.literal(enabled ? "New worlds: Enabled" : "New worlds: Disabled"));
                }).bounds(center - 149, 75, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Village distribution"), button -> {
            capturePage();
            if (minecraft != null) minecraft.setScreen(new VillageDistributionSettingsScreen(this));
        }).bounds(center + 4, 75, 145, 20).build());

        if (scope != Scope.GLOBAL) {
            idField = new EditBox(font, center - 149, 99, 174, 20,
                    Component.literal(scope == Scope.NAMESPACE ? "Mod namespace" : "Structure ID"));
            idField.setMaxLength(128);
            idField.setValue(selectedId);
            addRenderableWidget(idField);
            addRenderableWidget(Button.builder(Component.literal("Open ID"), button -> openId())
                    .bounds(center + 29, 99, 65, 20).build());
            addRenderableWidget(Button.builder(Component.literal("<"), button -> cycleId(-1))
                    .bounds(center + 98, 99, 23, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> cycleId(1))
                    .bounds(center + 125, 99, 23, 20).build());
        } else {
            idField = null;
        }

        addRenderableWidget(Button.builder(Component.literal("< Page"), button -> turnPage(-1))
                .bounds(center - 90, 123, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Page >"), button -> turnPage(1))
                .bounds(center + 22, 123, 68, 20).build());

        String[] current = currentValues();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            visibleFields[slot] = null;
            if (index >= FIELD_NAMES.length) break;
            EditBox field = new EditBox(font, center + 55, 155 + slot * 24, 93, 20,
                    Component.literal(FIELD_NAMES[index]));
            field.setMaxLength(32);
            field.setValue(current[index]);
            field.setHint(Component.literal(inheritedValue(index)));
            field.setResponder(value -> {
                if (!loadFailed) status = "";
            });
            visibleFields[slot] = addRenderableWidget(field);
        }

        Button save = Button.builder(Component.literal("Save defaults"), button -> save())
                .bounds(center - 100, height - 32, 95, 20).build();
        save.active = !loadFailed;
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(center + 5, height - 32, 95, 20).build());
    }

    @java.lang.Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xffffff);
        graphics.drawCenteredString(font, "100+ gap between structures; village pieces stay together.",
                width / 2, 24, 0xaaaaaa);
        graphics.drawCenteredString(font, "Gap/density: next load. Terrain: fixed per saved world.",
                width / 2, 36, 0xaaaaaa);
        int center = width / 2;
        String caption = scopeLabel() + "  |  Page " + (page + 1) + "/5  |  Blank = inherit";
        graphics.drawCenteredString(font, font.plainSubstrByWidth(caption, width - 12), center, 145, 0xcccccc);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index >= FIELD_NAMES.length) break;
            graphics.drawString(font, FIELD_NAMES[index], center - 149, 161 + slot * 24, 0xffffff);
        }
        if (!status.isEmpty()) {
            String clipped = font.plainSubstrByWidth(status, Math.max(50, width - 16));
            graphics.drawCenteredString(font, clipped, center, height - 10, 0xff5555);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @java.lang.Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @java.lang.Override
    public void resize(Minecraft minecraft, int width, int height) {
        capturePage();
        super.resize(minecraft, width, height);
    }

    private String inheritedValue(int index) {
        if (scope == Scope.STRUCTURE) {
            String namespace = selectedId.substring(0, selectedId.indexOf(':'));
            String[] parent = drafts.getOrDefault("namespace:" + namespace, values(document.namespaces.get(namespace)));
            if (!parent[index].isBlank()) return parent[index];
        }
        if (scope != Scope.GLOBAL) {
            String[] parent = drafts.getOrDefault("global", values(document.global));
            if (!parent[index].isBlank()) return parent[index];
        }
        var policy = StructurePlacementConfig.defaults();
        return switch (index) {
            case 0 -> Integer.toString(policy.minGapBlocks());
            case 1 -> Integer.toString(policy.reservationRadiusBlocks());
            case 2 -> Integer.toString(policy.maxCandidateOffsetBlocks());
            case 3 -> Integer.toString(policy.maxCutBlocks());
            case 4 -> Integer.toString(policy.maxFillBlocks());
            case 5 -> Integer.toString(policy.blendRadiusBlocks());
            case 6 -> Integer.toString(policy.maxTerrainSamples());
            case 7 -> Integer.toString(policy.maxCandidateAttempts());
            case 8 -> Double.toString(policy.spacingMultiplier());
            default -> policy.surfaceMode().name();
        };
    }

    private void selectScope(Scope next) {
        capturePage();
        scope = next;
        if (next == Scope.NAMESPACE && !selectedId.matches("[a-z0-9_.-]+")) {
            selectedId = firstId(document.namespaces, "minecraft");
        } else if (next == Scope.STRUCTURE && !selectedId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            selectedId = firstId(document.structures, "minecraft:village_plains");
        }
        page = 0;
        rebuild();
    }

    private static String firstId(Map<String, Override> entries, String fallback) {
        return entries.isEmpty() ? fallback : entries.keySet().iterator().next();
    }

    private void openId() {
        String requested = idField.getValue().trim();
        String pattern = scope == Scope.NAMESPACE ? "[a-z0-9_.-]+" : "[a-z0-9_.-]+:[a-z0-9_./-]+";
        if (!requested.matches(pattern)) {
            status = scope == Scope.NAMESPACE ? "Use a lowercase mod namespace." : "Use a lowercase namespace:path ID.";
            return;
        }
        capturePage();
        selectedId = requested;
        page = 0;
        rebuild();
    }

    private void cycleId(int direction) {
        TreeSet<String> ids = new TreeSet<>(scope == Scope.NAMESPACE
                ? document.namespaces.keySet() : document.structures.keySet());
        String prefix = scope == Scope.NAMESPACE ? "namespace:" : "structure:";
        for (String key : drafts.keySet()) if (key.startsWith(prefix)) ids.add(key.substring(prefix.length()));
        if (ids.isEmpty()) return;
        capturePage();
        List<String> sorted = new ArrayList<>(ids);
        int index = sorted.indexOf(selectedId);
        selectedId = sorted.get(index < 0 ? 0 : Math.floorMod(index + direction, sorted.size()));
        page = 0;
        rebuild();
    }

    private void turnPage(int direction) {
        capturePage();
        page = Math.floorMod(page + direction, 5);
        rebuild();
    }

    private void capturePage() {
        String[] values = currentValues();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index < values.length && visibleFields[slot] != null) values[index] = visibleFields[slot].getValue().trim();
        }
    }

    private String[] currentValues() {
        String key = key();
        return drafts.computeIfAbsent(key, ignored -> values(scope == Scope.GLOBAL ? document.global
                : scope == Scope.NAMESPACE ? document.namespaces.get(selectedId) : document.structures.get(selectedId)));
    }

    private String key() {
        return scope == Scope.GLOBAL ? "global" :
                (scope == Scope.NAMESPACE ? "namespace:" : "structure:") + selectedId;
    }

    private String scopeLabel() {
        return scope == Scope.GLOBAL ? "Global" :
                (scope == Scope.NAMESPACE ? "Mod " : "Structure ") + selectedId;
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void save() {
        if (idField != null && !idField.getValue().trim().equals(selectedId)) {
            status = "Press Open ID before saving edits for a different ID.";
            return;
        }
        capturePage();
        try {
            Document proposed = StructurePlacementConfig.readDocument();
            proposed.enabled = enabled;
            for (Map.Entry<String, String[]> entry : drafts.entrySet()) {
                Override value = parse(entry.getKey(), entry.getValue());
                String key = entry.getKey();
                if (key.equals("global")) proposed.global = value;
                else if (key.startsWith("namespace:")) {
                    String id = key.substring("namespace:".length());
                    if (isEmpty(value)) proposed.namespaces.remove(id);
                    else proposed.namespaces.put(id, value);
                } else if (key.startsWith("structure:")) {
                    String id = key.substring("structure:".length());
                    if (isEmpty(value)) proposed.structures.remove(id);
                    else proposed.structures.put(id, value);
                }
            }
            StructurePlacementConfig.validate(proposed);
            StructurePlacementConfig.save(proposed);
            onClose();
        } catch (IOException | IllegalArgumentException error) {
            status = "Could not save: " + error.getMessage();
        }
    }

    private static String[] values(Override value) {
        String[] result = new String[FIELD_NAMES.length];
        if (value == null) value = new Override();
        Object[] fields = {value.minGapBlocks, value.reservationRadiusBlocks, value.maxCandidateOffsetBlocks,
                value.maxCutBlocks, value.maxFillBlocks, value.blendRadiusBlocks, value.maxTerrainSamples,
                value.maxCandidateAttempts, value.spacingMultiplier, value.surfaceMode};
        for (int i = 0; i < fields.length; i++) result[i] = fields[i] == null ? "" : fields[i].toString();
        return result;
    }

    private static Override parse(String scope, String[] values) {
        Override result = new Override();
        result.minGapBlocks = integer(scope, FIELD_NAMES[0], values[0]);
        result.reservationRadiusBlocks = integer(scope, FIELD_NAMES[1], values[1]);
        result.maxCandidateOffsetBlocks = integer(scope, FIELD_NAMES[2], values[2]);
        result.maxCutBlocks = integer(scope, FIELD_NAMES[3], values[3]);
        result.maxFillBlocks = integer(scope, FIELD_NAMES[4], values[4]);
        result.blendRadiusBlocks = integer(scope, FIELD_NAMES[5], values[5]);
        result.maxTerrainSamples = integer(scope, FIELD_NAMES[6], values[6]);
        result.maxCandidateAttempts = integer(scope, FIELD_NAMES[7], values[7]);
        if (!values[8].isBlank()) {
            try { result.spacingMultiplier = Double.valueOf(values[8]); }
            catch (NumberFormatException error) { throw new IllegalArgumentException(scope + ": spacing multiplier must be a number"); }
        }
        if (!values[9].isBlank()) {
            try { result.surfaceMode = SurfaceMode.valueOf(values[9].toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException(scope + ": surface mode must be AUTO, SURFACE or PRESERVE"); }
        }
        return result;
    }

    private static Integer integer(String scope, String name, String text) {
        if (text.isBlank()) return null;
        try { return Integer.valueOf(text); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(scope + ": " + name + " must be an integer"); }
    }

    private static boolean isEmpty(Override value) {
        return value.minGapBlocks == null && value.reservationRadiusBlocks == null
                && value.maxCandidateOffsetBlocks == null && value.maxCutBlocks == null
                && value.maxFillBlocks == null && value.blendRadiusBlocks == null
                && value.maxTerrainSamples == null && value.maxCandidateAttempts == null
                && value.spacingMultiplier == null && value.surfaceMode == null;
    }
}
