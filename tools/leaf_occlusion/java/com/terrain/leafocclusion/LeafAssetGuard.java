package com.terrain.leafocclusion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/** Reload-time byte guard: a resource-pack change fails closed before chunk workers see it. */
final class LeafAssetGuard implements PreparableReloadListener {
    private static volatile boolean enabled;
    private static volatile String status = "not_reloaded";
    static boolean isEnabled() { return enabled; }
    static String status() { return status; }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                          ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                                          Executor prepareExecutor, Executor applyExecutor) {
        enabled = false;
        return CompletableFuture.supplyAsync(() -> validate(resources), prepareExecutor)
            .thenCompose(barrier::wait)
            .thenAcceptAsync(result -> { enabled = result; if (result) status = "active"; else if (status.equals("validating")) status = "validation_failed"; LeafOcclusion.onGuardReload(enabled, status); }, applyExecutor);
    }

    private static boolean validate(ResourceManager resources) {
        status = "validating";
        try {
            Map<ResourceLocation, String> expected = manifest();
            if (expected.isEmpty()) { status = "empty_manifest"; return false; }
            var actualCtm = resources.listResources("optifine/ctm", id -> id.getPath().endsWith(".properties")).keySet();
            var expectedCtm = new HashSet<ResourceLocation>();
            for (ResourceLocation id : expected.keySet()) if (id.getPath().startsWith("optifine/ctm/") && id.getPath().endsWith(".properties")) expectedCtm.add(id);
            if (!actualCtm.equals(expectedCtm)) {
                var missing = new HashSet<>(expectedCtm); missing.removeAll(actualCtm);
                var extra = new HashSet<>(actualCtm); extra.removeAll(expectedCtm);
                status = "ctm_keyset_mismatch:missing=" + preview(missing) + ";extra=" + preview(extra);
                return false;
            }
            for (Map.Entry<ResourceLocation, String> entry : expected.entrySet()) {
                Resource resource = resources.getResourceOrThrow(entry.getKey());
                try (InputStream stream = resource.open()) {
                    if (!entry.getValue().equals(hex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())))) { status = "asset_hash_mismatch:" + entry.getKey(); return false; }
                }
            }
            return true;
        } catch (Throwable ignored) { status = "resource_error:" + ignored.getClass().getSimpleName(); return false; }
    }

    private static Map<ResourceLocation, String> manifest() throws IOException {
        Map<ResourceLocation, String> result = new LinkedHashMap<>();
        try (InputStream input = LeafAssetGuard.class.getClassLoader().getResourceAsStream("assets/td_leaf_occlusion/leaf-assets.sha256")) {
            if (input == null) return result;
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null;) {
                    String[] part = line.split("\\t", 2);
                    if (part.length != 2 || !part[0].startsWith("assets/")) return Map.of();
                    String[] location = part[0].substring("assets/".length()).split("/", 2);
                    if (location.length != 2) return Map.of();
                    result.put(ResourceLocation.fromNamespaceAndPath(location[0], location[1]), part[1]);
                }
            }
        }
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static String preview(java.util.Set<ResourceLocation> ids) {
        return ids.stream().limit(3).map(ResourceLocation::toString).reduce((a, b) -> a + "," + b).orElse("none");
    }
}
