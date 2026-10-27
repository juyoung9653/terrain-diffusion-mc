package com.terrain.chunkreadprobe.mixin;

import com.terrain.chunkreadprobe.ChunkReadCounter;
import com.terrain.chunkreadprobe.ProbeConfiguration;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.reflect.Method;
import java.nio.file.Path;

@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync", remap = false)
abstract class ReadFromDiskAsyncMixin {
    private static volatile boolean initialized;
    private static volatile boolean enabled;
    @Inject(method = "invokeAsyncLoad(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;)Lio/reactivex/rxjava3/core/Single;", at = @At("HEAD"), require = 1)
    private void td$observe(@Coerce Object context, CallbackInfoReturnable<?> cir) {
        if (!init()) return;
        try {
            Object holder = context.getClass().getMethod("holder").invoke(context);
            Object pos = holder.getClass().getMethod("getKey").invoke(holder);
            int x = pos.getClass().getField("x").getInt(pos), z = pos.getClass().getField("z").getInt(pos);
            Object tacs = context.getClass().getMethod("tacs").invoke(context);
            Method world = tacs.getClass().getMethod("getWorld"); Object level = world.invoke(tacs);
            Object dimension = level.getClass().getMethod("dimension").invoke(level);
            ChunkReadCounter.observed(String.valueOf(dimension), x, z);
        } catch (ReflectiveOperationException | SecurityException error) { ChunkReadCounter.reflectionFailure(error); }
    }
    private static boolean init() {
        if (initialized) return enabled;
        synchronized (ReadFromDiskAsyncMixin.class) {
            if (initialized) return enabled;
            String file = ProbeConfiguration.outputPath();
            enabled = !file.isEmpty() && ProbeConfiguration.supportedC2me();
            if (enabled) ChunkReadCounter.configure(Path.of(file)); initialized = true; return enabled;
        }
    }
}
