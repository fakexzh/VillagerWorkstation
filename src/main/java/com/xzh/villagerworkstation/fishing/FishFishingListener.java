package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Replaces successful vanilla fish catches with individually generated custom specimens. */
public final class FishFishingListener implements Listener {
    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;

    public FishFishingListener(JavaPlugin plugin, FishRegistry registry, FishItemFactory itemFactory) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemFactory = itemFactory;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !plugin.getConfig().getBoolean("custom-fishing.enabled", true)
                || !(event.getCaught() instanceof Item caughtEntity)) return;

        List<FishDefinition> fish = new ArrayList<>(registry.all());
        if (fish.isEmpty()) return;
        FishDefinition definition = chooseSpecies(fish);
        FishInstance specimen = new FishGenerator().generate(definition);

        // Remove the vanilla catch so it cannot be duplicated alongside the custom fish.
        caughtEntity.remove();
        ItemStack customFish = itemFactory.create(definition, specimen);
        Map<Integer, ItemStack> leftovers = event.getPlayer().getInventory().addItem(customFish);
        leftovers.values().forEach(item ->
                event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item));

        String size = specimen.sizeCategory().charAt(0)
                + specimen.sizeCategory().substring(1).toLowerCase(java.util.Locale.ROOT);
        event.getPlayer().sendMessage(ChatColor.AQUA + "You caught a " + size + " "
                + definition.displayName() + ChatColor.GRAY + " ("
                + String.format(java.util.Locale.ROOT, "%.2f kg", specimen.weightKg()) + ")");
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
}
