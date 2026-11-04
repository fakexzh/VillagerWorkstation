package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps vanilla fishing as the default outcome and replaces only successful
 * custom-fish rolls that match the catch location and its habitat constraints.
 */
public final class FishFishingListener implements Listener {
    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;
    private final FishCollectionStore collectionStore;

    public FishFishingListener(JavaPlugin plugin, FishRegistry registry, FishItemFactory itemFactory) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemFactory = itemFactory;
        this.collectionStore = new FishCollectionStore(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !plugin.getConfig().getBoolean("custom-fishing.enabled", true)
                || !(event.getCaught() instanceof Item caughtEntity)) return;

        double customChance = plugin.getConfig().getDouble("custom-fishing.custom-catch-chance", 25.0);
        customChance = Math.max(0.0, Math.min(100.0, customChance));
        if (ThreadLocalRandom.current().nextDouble(100.0) >= customChance) return;

        CatchHabitat habitat = resolveHabitat(event);
        if (habitat == null) return;

        List<FishDefinition> eligible = registry.all().stream()
                .filter(definition -> isEligible(definition, habitat))
                .toList();
        if (eligible.isEmpty()) return; // No configured species here: preserve the vanilla catch.

        FishDefinition definition = chooseSpecies(eligible);
        FishInstance specimen = new FishGenerator().generate(definition);

        caughtEntity.remove();
        ItemStack customFish = itemFactory.create(definition, specimen);
        Map<Integer, ItemStack> leftovers = event.getPlayer().getInventory().addItem(customFish);
        leftovers.values().forEach(item ->
                event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item));
        collectionStore.recordCatch(event.getPlayer(), definition, specimen);

        String size = specimen.sizeCategory().charAt(0)
                + specimen.sizeCategory().substring(1).toLowerCase(Locale.ROOT);
        event.getPlayer().sendMessage(ChatColor.AQUA + "You caught a " + size + " "
                + definition.displayName() + ChatColor.GRAY + " ("
                + String.format(Locale.ROOT, "%.2f kg", specimen.weightKg()) + ")");
    }

    private CatchHabitat resolveHabitat(PlayerFishEvent event) {
        Block water = event.getHook().getLocation().getBlock();
        if (water.getType() != Material.WATER && water.getRelative(BlockFace.DOWN).getType() == Material.WATER)
            water = water.getRelative(BlockFace.DOWN);

        int depth = 0;
        if (water.getType() == Material.WATER) {
            Block cursor = water;
            while (depth < 64 && cursor.getType() == Material.WATER) {
                depth++;
                cursor = cursor.getRelative(BlockFace.DOWN);
            }
        }

        Biome biome = water.getBiome();
        String biomeName = biome.name().toUpperCase(Locale.ROOT);
        String waterType = biomeName.contains("OCEAN") ? "OCEAN"
                : biomeName.contains("RIVER") ? "RIVER" : "INLAND";
        String dimension = switch (event.getPlayer().getWorld().getEnvironment()) {
            case NORMAL -> "OVERWORLD";
            case NETHER -> "NETHER";
            case THE_END -> "END";
            default -> "OTHER";
        };
        return new CatchHabitat(biomeName, waterType, dimension, depth);
    }

    private boolean isEligible(FishDefinition fish, CatchHabitat habitat) {
        if (fish.biomes().isEmpty()) return false;
        boolean biomeMatches = fish.biomes().contains("ANY") || fish.biomes().contains(habitat.biome());
        boolean waterMatches = fish.waterTypes().isEmpty()
                || fish.waterTypes().contains("ANY") || fish.waterTypes().contains(habitat.waterType());
        boolean dimensionMatches = fish.dimensions().contains("ANY")
                || fish.dimensions().contains(habitat.dimension());
        return biomeMatches && waterMatches && dimensionMatches
                && habitat.depth() >= fish.minWaterDepth()
                && habitat.depth() <= fish.maxWaterDepth();
    }

    private FishDefinition chooseSpecies(List<FishDefinition> fish) {
        double total = fish.stream().mapToDouble(definition -> rarityWeight(definition.rarity())).sum();
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (FishDefinition definition : fish) {
            roll -= rarityWeight(definition.rarity());
            if (roll <= 0.0) return definition;
        }
        return fish.get(fish.size() - 1);
    }

    private double rarityWeight(FishRarity rarity) {
        return switch (rarity) {
            case COMMON -> 1.0;
            case UNCOMMON -> 0.55;
            case RARE -> 0.25;
            case EPIC -> 0.10;
            case LEGENDARY -> 0.04;
            case MYTHICAL -> 0.015;
        };
    }

    private record CatchHabitat(String biome, String waterType, String dimension, int depth) { }
}
