package com.github.xandergos.terraindiffusionmc.diagnostic;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Isolated native-server integration audit; never included in release jars. */
public final class FastLocateAudit {
    private static final class Recorder implements CommandSource {
        final List<String> messages = new CopyOnWriteArrayList<>();
        public void sendSystemMessage(Component text) {
            messages.add(text.getString());
            System.out.println("FAST_LOCATE_AUDIT " + text.getString());
        }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static int command(MinecraftServer server, CommandSourceStack source, String command) throws Exception {
        return server.submit(() -> execute(source, command)).get(20, TimeUnit.SECONDS);
    }
    private static int execute(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalStateException(e); }
    }
    private static long awaitIdle(MinecraftServer server, CommandSourceStack source) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(360), maxCallback = 0;
        while (System.nanoTime() < until) {
            Thread.sleep(250);
            long start = System.nanoTime();
            int active = command(server, source, "td-locate status");
            maxCallback = Math.max(maxCallback, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            if (active == 0) return maxCallback;
        }
        throw new IllegalStateException("Fast locate failed to drain within 360 seconds");
    }
    private static BlockPos found(Recorder recorder) {
        var regex = Pattern.compile("\\[td-locate] FOUND .*?\\[(-?\\d+), (-?\\d+), (-?\\d+)]");
        for (String message : recorder.messages) {
            var match = regex.matcher(message);
            if (match.find()) return new BlockPos(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
        }
        throw new IllegalStateException("No verified result: " + recorder.messages.stream().filter(m -> !m.contains("RUNNING")).toList());
    }
    public static String run(MinecraftServer server) throws Exception {
        Recorder recorder = new Recorder();
        var level = server.overworld();
        var source = server.submit(() -> server.createCommandSourceStack().withSource(recorder)
                .withPermission(4).withLevel(level).withPosition(new Vec3(-2179, 128, 3517))).get();
        String biome = "#terrain-diffusion-mc:ecology";
        require(command(server, source, "td-locate biome #terrain-diffusion-mc:missing") == 0, "Missing biome tag accepted");
        require(command(server, source, "td-locate structure #terrain-diffusion-mc:missing") == 0, "Missing structure tag accepted");
        for (String input : List.of("td-locate biome #terrain-diffusion-mc:e", "td-locate structure #minecraft:v")) {
            var suggestions = server.submit(() -> {
                var dispatcher = server.getCommands().getDispatcher();
                return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source));
            }).get().get(10, TimeUnit.SECONDS);
            require(suggestions.getList().stream().anyMatch(s -> s.getText().startsWith("#")), "Missing tag suggestions: " + input);
        }
        server.submit(() -> {
            var dispatcher = server.getCommands().getDispatcher();
            var parsed = dispatcher.parse("td-locate biome " + biome + " 131072 1800", source);
            require(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "Expanded limits did not parse");
            return null;
        }).get();
        long started = System.nanoTime();
        require(command(server, source, "td-locate biome " + biome + " 8192 300") == 1, "Biome tag start rejected");
        long admissionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long maxCallback = awaitIdle(server, source);
        long biomeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        BlockPos pos = found(recorder);
        require(server.submit(() -> {
            var generator = level.getChunkSource().getGenerator();
            var actual = generator.getBiomeSource().getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2,
                    level.getChunkSource().randomState().sampler());
            return actual.is(net.minecraft.tags.TagKey.create(Registries.BIOME, ResourceLocation.parse(biome.substring(1))));
        }).get(30, TimeUnit.SECONDS), "Returned biome does not match exact source");
        recorder.messages.clear();
        started = System.nanoTime();
        String structureId = System.getProperty("td.streamsProbe.fastLocateStructure", "#minecraft:village");
        require(command(server, source, "td-locate structure " + structureId + " 8192 300") == 1, "Structure tag start rejected");
        maxCallback = Math.max(maxCallback, awaitIdle(server, source));
        long structureMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        BlockPos structurePos = found(recorder);
        var chunk = level.getChunkSource().getChunkFuture(structurePos.getX() >> 4, structurePos.getZ() >> 4,
                ChunkStatus.STRUCTURE_STARTS, true).get(60, TimeUnit.SECONDS).orElseThrow(() -> new IllegalStateException("Missing verified chunk"));
        require(server.submit(() -> {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var message = recorder.messages.stream().filter(m -> m.startsWith("[td-locate] FOUND")).findFirst().orElseThrow();
            String matchedId = message.substring("[td-locate] FOUND ".length()).split(" ")[0];
            var holder = registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse(matchedId))).orElseThrow();
            var actual = chunk.getStartForStructure(holder.value());
            boolean requested = structureId.startsWith("#")
                    ? holder.is(net.minecraft.tags.TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(structureId.substring(1))))
                    : matchedId.equals(structureId);
            return requested && actual != null && actual.isValid();
        }).get(), "Returned structure has no valid actual start");
        recorder.messages.clear();
        require(command(server, source, "td-locate structure minecraft:stronghold 8192") == 0, "Unsupported placement admitted");
        recorder.messages.clear();
        server.submit(() -> {
            require(execute(source, "td-locate biome " + biome + " 8192") == 1, "Cancellation fixture start");
            require(execute(source, "td-locate biome " + biome) == 0, "Second job admitted");
            require(execute(source, "td-locate cancel") == 1, "Cancel rejected");
            return null;
        }).get();
        awaitIdle(server, source);
        require(recorder.messages.stream().noneMatch(m -> m.startsWith("[td-locate] FOUND")), "Result published after cancellation");
        return "{\"event\":\"fast_locate_audit\",\"status\":\"passed\",\"biomeMs\":" + biomeMs
                + ",\"structureMs\":" + structureMs + ",\"admissionMs\":" + admissionMs
                + ",\"maxServerCallbackMs\":" + maxCallback + ",\"tagSuggestions\":true,\"expandedLimits\":true,\"exactBiome\":true,\"validStructureStart\":true,\"cancelledNoResult\":true}";
    }
}
