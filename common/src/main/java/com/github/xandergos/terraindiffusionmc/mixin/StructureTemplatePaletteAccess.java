package com.github.xandergos.terraindiffusionmc.mixin;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
@Mixin(StructureTemplate.class)
public interface StructureTemplatePaletteAccess {
    @Accessor("palettes") List<StructureTemplate.Palette> terrainDiffusion$palettes();
}
