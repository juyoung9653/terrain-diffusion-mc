package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persisted per-world settings for terrain diffusion.
 *
 * <p>This is stored in the world save via Minecraft's saved data storage.
 */
public final class WorldScaleSettingsState extends SavedData {

    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE)
                    .forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_scale", false)
                    .forGetter(WorldScaleSettingsState::hasExplicitScale),
            Codec.BOOL.optionalFieldOf("fast_ground_sampling", false)
                    .forGetter(WorldScaleSettingsState::isFastGroundSampling)
    ).apply(instance, WorldScaleSettingsState::new));

    private int scale;
    private boolean explicitScale;
    private final boolean fastGroundSampling;

    /**
     * Creates a default state for worlds that do not yet have saved terrain diffusion settings.
     */
    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale, boolean fastGroundSampling) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
        this.fastGroundSampling = fastGroundSampling;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, true);
    }

    /**
     * Type descriptor used by the saved data storage.
     */
    public static final SavedData.Factory<WorldScaleSettingsState> TYPE =
            new SavedData.Factory<>(
                    WorldScaleSettingsState::createDefault,
                    WorldScaleSettingsState::fromNbt,
                    null
            );

    // Type descriptor helper
    public static WorldScaleSettingsState fromNbt(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        // A damaged optional inference field must not reset an otherwise valid world scale.
        if (nbt.contains("fast_ground_sampling") && !nbt.contains("fast_ground_sampling", 99)) {
            nbt = nbt.copy();
            nbt.remove("fast_ground_sampling");
        }
        return CODEC.parse(NbtOps.INSTANCE, nbt)
                .result()
                .orElseGet(() -> new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, false));
    }

    // Type descriptor helper
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .result()
                .ifPresent(encoded -> nbt.merge((CompoundTag) encoded));
        return nbt;
    }

    /**
     * Returns the currently persisted world scale.
     */
    public int getScale() {
        return scale;
    }

    /**
     * Returns whether this world has an explicitly chosen scale.
     */
    public boolean hasExplicitScale() {
        return explicitScale;
    }

    public boolean isFastGroundSampling() {
        return fastGroundSampling;
    }

    /**
     * Applies a new persisted world scale and marks the state dirty.
     */
    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
