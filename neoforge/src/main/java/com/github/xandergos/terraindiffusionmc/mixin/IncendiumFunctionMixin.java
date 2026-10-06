package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.compat.incendium.IncendiumOriginCompatibility;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.execution.tasks.CallFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CallFunction.class)
public abstract class IncendiumFunctionMixin<T extends ExecutionCommandSource<T>> {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "execute(Lnet/minecraft/commands/ExecutionCommandSource;Lnet/minecraft/commands/execution/ExecutionContext;Lnet/minecraft/commands/execution/Frame;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/functions/InstantiatedFunction;entries()Ljava/util/List;", ordinal = 0))
    private List<UnboundEntryAction<T>> terrainDiffusion$deferIncendiumOrigin(
            InstantiatedFunction<T> function, T source, ExecutionContext<T> context, Frame frame) {
        if (source instanceof CommandSourceStack stack) {
            return (List) IncendiumOriginCompatibility.entries((InstantiatedFunction) function, stack);
        }
        return function.entries();
    }
}
