package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;

/**
 * Integrates with the All The Fish datapack without running a second fishing
 * lottery. The datapack decides the pool, habitat, time, weather and catch
 * weights. This listener enriches only a fish item carrying a known datapack
 * item_model with XZH specimen metadata and collection tracking.
 */
public final class FishFishingListener implements Listener {
    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;
    private final FishCollectionStore collectionStore;
    private final FishGenerator generator = new FishGenerator();

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

        ItemStack caughtStack = caughtEntity.getItemStack();
        ItemMeta caughtMeta = caughtStack.getItemMeta();
        if (caughtMeta == null || caughtMeta.getItemModel() == null) return;

        // The datapack marks custom species with barely_default:fish_<species>.
        // Vanilla cod, salmon, tropical fish and pufferfish have no matching model
        // in our registry and are deliberately left untouched.
        FishDefinition definition = registry.getByModel(caughtMeta.getItemModel().toString());
        if (definition == null) return;

        FishInstance specimen = generator.generate(definition);
        caughtEntity.remove();

        ItemStack specimenItem = itemFactory.create(definition, specimen);
        Map<Integer, ItemStack> leftovers = event.getPlayer().getInventory().addItem(specimenItem);
        leftovers.values().forEach(item ->
                event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item));

        collectionStore.recordCatch(event.getPlayer(), definition, specimen);

        String size = specimen.sizeCategory().charAt(0)
                + specimen.sizeCategory().substring(1).toLowerCase(Locale.ROOT);
        event.getPlayer().sendMessage(ChatColor.AQUA + "You caught a " + size + " "
                + definition.displayName() + ChatColor.GRAY + " ("
                + String.format(Locale.ROOT, "%.2f kg", specimen.weightKg()) + ")");
    }
}
