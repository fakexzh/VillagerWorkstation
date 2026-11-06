package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Adds XZH specimens without replacing vanilla water-fishing behavior. */
public final class FishFishingListener implements Listener {
    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;
    private final FishCollectionManager collectionManager;
    private final FishGenerator generator = new FishGenerator();

    public FishFishingListener(JavaPlugin plugin, FishRegistry registry, FishItemFactory itemFactory,
                               FishCollectionManager collectionManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemFactory = itemFactory;
        this.collectionManager = collectionManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (!plugin.getConfig().getBoolean("custom-fishing.enabled", true)) return;

        if (event.getState() == PlayerFishEvent.State.FISHING
                && event.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER
                && !isNetheriteRod(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            event.getHook().remove();
            event.getPlayer().sendMessage(ChatColor.RED + "Only the Netherite Fishing Rod works in the Nether.");
            return;
        }

        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caughtEntity)) return;

        Material caughtType = caughtEntity.getItemStack().getType();
        if (caughtType != Material.COD && caughtType != Material.SALMON
                && caughtType != Material.TROPICAL_FISH && caughtType != Material.PUFFERFISH) return;
        if (event.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER) return;

        int chance = Math.max(0, Math.min(100,
                plugin.getConfig().getInt("custom-fishing.chance-percent", 20)));
        if (ThreadLocalRandom.current().nextInt(100) >= chance) return;

        Map<String, Integer> pool = FishHabitatPools.forBiome(event.getHook().getLocation().getBlock().getBiome());
        FishDefinition definition = chooseSpecies(pool);
        if (definition == null) return;
        replaceWithSpecimen(event.getPlayer(), caughtEntity, definition);
    }

    /**
     * The AllTheFish datapack runs Nether lava fishing through loot-spawn functions,
     * not PlayerFishEvent. Convert only the two XZH Nether-exclusive item models here.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLavaFishSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != World.Environment.NETHER
                || !plugin.getConfig().getBoolean("custom-fishing.enabled", true)) return;

        ItemMeta meta = entity.getItemStack().getItemMeta();
        NamespacedKey model = meta == null ? null : meta.getItemModel();
        if (model == null || !model.getNamespace().equals("barely_default")) return;
        String modelName = model.getKey();
        if (!modelName.startsWith("fish_")) return;
        String fishId = modelName.substring("fish_".length());
        if (!fishId.equals("netherwrasse") && !fishId.equals("volcanic_bass")) return;

        Player owner = entity.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(entity.getLocation()) <= 100.0)
                .filter(player -> isNetheriteRod(player.getInventory().getItemInMainHand()))
                .min((a, b) -> Double.compare(a.getLocation().distanceSquared(entity.getLocation()),
                        b.getLocation().distanceSquared(entity.getLocation())))
                .orElse(null);
        if (owner == null) return;

        int chance = Math.max(0, Math.min(100,
                plugin.getConfig().getInt("custom-fishing.chance-percent", 20)));
        if (ThreadLocalRandom.current().nextInt(100) >= chance) {
            event.setCancelled(true);
            return;
        }

        FishDefinition definition = registry.get(fishId);
        if (definition == null) {
            event.setCancelled(true);
            plugin.getLogger().warning("Datapack spawned an unregistered XZH fish: " + fishId);
            return;
        }

        FishInstance specimen = generator.generate(definition);
        entity.setItemStack(itemFactory.create(definition, specimen));
        collectionManager.recordCatch(owner.getUniqueId(), definition, specimen);
        sendCatchMessage(owner, definition, specimen);
    }

    private FishDefinition chooseSpecies(Map<String, Integer> pool) {
        int total = pool.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, Integer> entry : pool.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) return registry.get(entry.getKey());
        }
        return null;
    }

    private void replaceWithSpecimen(Player player, Item caughtEntity, FishDefinition definition) {
        caughtEntity.remove();
        FishInstance specimen = generator.generate(definition);
        ItemStack customFish = itemFactory.create(definition, specimen);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(customFish);
        leftovers.values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
        collectionManager.recordCatch(player.getUniqueId(), definition, specimen);
        sendCatchMessage(player, definition, specimen);
    }

    private void sendCatchMessage(Player player, FishDefinition definition, FishInstance specimen) {
        String size = specimen.sizeCategory().charAt(0)
                + specimen.sizeCategory().substring(1).toLowerCase(java.util.Locale.ROOT);
        player.sendMessage(ChatColor.AQUA + "You caught a " + size + " "
                + definition.displayName() + ChatColor.GRAY + " ("
                + String.format(java.util.Locale.ROOT, "%.2f kg", specimen.weightKg()) + ")");
    }

    private boolean isNetheriteRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemName()
                && "Netherite Fishing Rod".equalsIgnoreCase(meta.getItemName());
    }
}
