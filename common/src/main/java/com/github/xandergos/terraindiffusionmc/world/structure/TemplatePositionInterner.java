package com.github.xandergos.terraindiffusionmc.world.structure;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.BlockPos;

/**
 * Shares the small, immutable local coordinates repeated by loaded structure template palettes.
 *
 * <p>Template NBT positions are local to the template.  The vanilla loader creates one
 * {@link BlockPos} for every entry in every palette, even though palette alternatives normally
 * use the same local coordinates.  This cache is deliberately bounded to the normal local
 * template volume; unusual coordinates retain a private immutable position instead.</p>
 */
public final class TemplatePositionInterner {
    private static final int XZ_SIZE = 128;
    private static final int Y_SIZE = 64;
    private static final int SLOT_COUNT = XZ_SIZE * Y_SIZE * XZ_SIZE;
    private static final AtomicReferenceArray<BlockPos> POSITIONS = new AtomicReferenceArray<>(SLOT_COUNT);

    private TemplatePositionInterner() {
    }

    /**
     * Returns a shared immutable coordinate for the normal local-template range.
     * Mutable positions are copied before publication so later mutation cannot alter a template.
     */
    public static BlockPos intern(BlockPos position) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        if (x < 0 || x >= XZ_SIZE || y < 0 || y >= Y_SIZE || z < 0 || z >= XZ_SIZE) {
            return immutable(position, x, y, z);
        }

        int index = (y * XZ_SIZE + z) * XZ_SIZE + x;
        BlockPos cached = POSITIONS.get(index);
        if (cached != null) {
            return cached;
        }

        BlockPos immutable = immutable(position, x, y, z);
        if (POSITIONS.compareAndSet(index, null, immutable)) {
            return immutable;
        }
        return POSITIONS.get(index);
    }

    private static BlockPos immutable(BlockPos position, int x, int y, int z) {
        return position instanceof BlockPos.MutableBlockPos ? new BlockPos(x, y, z) : position;
    }
}
