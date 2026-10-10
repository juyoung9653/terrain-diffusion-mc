package com.github.xandergos.terraindiffusionmc.mixin;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import com.mojang.datafixers.util.Either;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SinglePoolElement.class)
public interface SinglePoolTemplateAccess {
    @Invoker("getTemplate") StructureTemplate terrainDiffusion$template(StructureTemplateManager manager);
    @Accessor("processors") Holder<StructureProcessorList> terrainDiffusion$processors();
    @Accessor("template") Either<ResourceLocation,StructureTemplate> terrainDiffusion$templateIdentity();
}
