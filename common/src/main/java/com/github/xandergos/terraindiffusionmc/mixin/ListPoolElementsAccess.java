package com.github.xandergos.terraindiffusionmc.mixin;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
@Mixin(ListPoolElement.class)
public interface ListPoolElementsAccess {
    @Accessor("elements") List<StructurePoolElement> terrainDiffusion$elements();
}
