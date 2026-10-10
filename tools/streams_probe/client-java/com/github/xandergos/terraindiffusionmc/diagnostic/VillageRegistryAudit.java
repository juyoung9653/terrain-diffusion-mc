package com.github.xandergos.terraindiffusionmc.diagnostic;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Opt-in, read-only view of the effective village registries after datapack loading. */
@EventBusSubscriber(modid = "terrain_diffusion_mc", bus = EventBusSubscriber.Bus.GAME)
public final class VillageRegistryAudit {
    private static final Logger LOG = LoggerFactory.getLogger("VillageRegistryAudit");
    private static final String[] TYPES = {"desert", "plains", "savanna", "snowy", "taiga"};

    private VillageRegistryAudit() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("td.villageRegistryAudit")) return;
        try {
            Registry<Biome> biomes = event.getServer().registryAccess().registryOrThrow(Registries.BIOME);
            Registry<Structure> structures = event.getServer().registryAccess().registryOrThrow(Registries.STRUCTURE);
            LOG.info("[TD-VILLAGE-AUDIT] dimension={}",
                    event.getServer().overworld().dimension().location());
            for (String type : TYPES) {
                String path = "village_" + type;
                ResourceLocation tagId = ResourceLocation.fromNamespaceAndPath("minecraft", "has_structure/" + path);
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
                List<String> members = biomes.getTag(tag)
                        .map(holders -> holders.stream().map(VillageRegistryAudit::id).sorted().toList())
                        .orElse(List.of());
                ResourceLocation structureId = ResourceLocation.fromNamespaceAndPath("minecraft", path);
                Structure structure = structures.get(structureId);
                List<String> structureTdBiomes = structure == null ? List.of() : structure.biomes().stream()
                        .map(VillageRegistryAudit::id)
                        .filter(id -> id.startsWith("terrain-diffusion-mc:"))
                        .sorted().toList();
                ResourceLocation tagFile = ResourceLocation.fromNamespaceAndPath("minecraft",
                        "tags/worldgen/biome/has_structure/" + path + ".json");
                List<String> packs = event.getServer().getResourceManager().getResourceStack(tagFile).stream()
                        .map(resource -> resource.sourcePackId()).toList();
                LOG.info("[TD-VILLAGE-AUDIT] {} tagPresent={} members={} structurePresent={} structureTdBiomes={} tagPackStack={}",
                        path, biomes.getTag(tag).isPresent(), members, structure != null, structureTdBiomes, packs);
            }
            TagKey<Structure> villageTag = TagKey.create(Registries.STRUCTURE,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "village"));
            List<String> villageStructures = structures.getTag(villageTag)
                    .map(holders -> holders.stream().map(VillageRegistryAudit::id).sorted().toList())
                    .orElse(List.of());
            LOG.info("[TD-VILLAGE-AUDIT] #minecraft:village structures={}", villageStructures);
        } catch (Exception failure) {
            LOG.error("[TD-VILLAGE-AUDIT] registry inspection failed", failure);
        }
    }

    private static <T> String id(Holder<T> holder) {
        return holder.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
    }
}
