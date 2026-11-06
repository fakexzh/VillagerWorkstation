package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Adds XZH specimens without replacing vanilla water- or lava-fishing behavior. */
public final class FishFishingListener implements Listener {
    private static final double NETHER_CATCH_RADIUS_SQUARED = 64.0;

    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;
    private final FishCollectionManager collectionManager;
    private final FishGenerator generator = new FishGenerator();

    /** Associates a live Nether fishing hook with the player who actually cast it. */
    private final Map<UUID, UUID> activeNetherHooks = new HashMap<>();

    public FishFishingListener(JavaPlugin plugin, FishRegistry registry, FishItemFactory itemFactory,
                               FishCollectionManager collectionManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemFactory = itemFactory;
        this.collectionManager = collectionManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();

        if (event.getState() == PlayerFishEvent.State.FISHING
                && player.getWorld().getEnvironment() == World.Environment.NETHER) {
            if (!isNetheriteRod(player.getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                event.getHook().remove();
                activeNetherHooks.remove(event.getHook().getUniqueId());
                player.sendMessage(ChatColor.RED + "Only the Netherite Fishing Rod works in the Nether.");
                return;
            }

            activeNetherHooks.put(event.getHook().getUniqueId(), player.getUniqueId());
            return;
        }

        if (!plugin.getConfig().getBoolean("custom-fishing.enabled", true)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caughtEntity)) return;

        Material caughtType = caughtEntity.getItemStack().getType();
        if (!isVanillaFish(caughtType)) return;

        // The Overworld conversion path remains unchanged. Nether lava catches are
        // handled by ItemSpawnEvent because the datapack creates those item entities.
        if (player.getWorld().getEnvironment() == World.Environment.NETHER) return;

        if (!passesCustomFishRoll()) return;

        Map<String, Integer> pool = FishHabitatPools.forBiome(
                event.getHook().getLocation().getBlock().getBiome());
        FishDefinition definition = chooseSpecies(pool);
        if (definition == null) return;
        replaceWithSpecimen(player, caughtEntity, definition);
    }

    /**
     * The AllTheFish datapack creates Nether-lava catches as item entities rather
     * than normal PlayerFishEvent catches. Match the spawned item to a live hook
     * that was cast by a player with the required rod.
     *
     * Vanilla fish stay vanilla when the custom-fish roll fails. When the
     * datapack supplies a custom Nether-fish model, a failed conversion becomes
     * a normal vanilla fish using the Overworld catch distribution. If no owner
     * can be established, the spawned item is left intact.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLavaFishSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != World.Environment.NETHER
                || !plugin.getConfig().getBoolean("custom-fishing.enabled", true)) return;

        Player owner = findNetherAngler(entity);
        if (owner == null) return;

        ItemStack caughtStack = entity.getItemStack();
        Material caughtType = caughtStack.getType();
        FishDefinition definition;

        if (isVanillaFish(caughtType)) {
            // The same vanilla fish pool remains available in lava. Only the
            // configured percentage is converted into an XZH Nether species.
            if (!passesCustomFishRoll()) return;
            definition = chooseSpecies(FishHabitatPools.NETHER_LAVA);
        } else {
            // The datapack's custom Nether fish have their own item models.
            String fishId = getDatapackFishId(caughtStack);
            if (fishId == null) return;

            definition = registry.get(fishId);
            if (definition == null) {
                plugin.getLogger().warning("Datapack spawned an unregistered XZH fish: " + fishId);
                return;
            }
            if (!passesCustomFishRoll()) {
                // Match Overworld fishing odds: the non-converted catch is a
                // normal vanilla fish, not a deleted or raw datapack-only item.
                entity.setItemStack(new ItemStack(chooseVanillaFish()));
                return;
            }
        }

        if (definition == null) return;

        FishInstance specimen = generator.generate(definition);
        entity.setItemStack(itemFactory.create(definition, specimen));
        collectionManager.recordCatch(owner.getUniqueId(), definition, specimen);
        sendCatchMessage(owner, definition, specimen);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeNetherHooks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }

    private Player findNetherAngler(Item item) {
        Player nearest = null;
        double nearestDistance = NETHER_CATCH_RADIUS_SQUARED;

        for (Player player : item.getWorld().getPlayers()) {
            FishHook hook = player.getFishHook();
            if (hook == null || !hook.isValid()) continue;
            if (!player.getUniqueId().equals(activeNetherHooks.get(hook.getUniqueId()))) continue;

            double distance = hook.getLocation().distanceSquared(item.getLocation());
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        // Remove stale entries after checking the live hooks.
        activeNetherHooks.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getValue());
            if (player == null) return true;
            FishHook hook = player.getFishHook();
            return hook == null || !hook.isValid()
                    || !hook.getUniqueId().equals(entry.getKey());
        });

        return nearest;
    }

    private String getDatapackFishId(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey model = meta == null ? null : meta.getItemModel();
        if (model == null || !model.getNamespace().equals("barely_default")) return null;

        String modelName = model.getKey();
        if (!modelName.startsWith("fish_")) return null;

        String fishId = modelName.substring("fish_".length());
        return fishId.equals("netherwrasse") || fishId.equals("volcanic_bass")
                ? fishId : null;
    }

    private boolean passesCustomFishRoll() {
        int chance = Math.max(0, Math.min(100,
                plugin.getConfig().getInt("custom-fishing.chance-percent", 20)));
        return ThreadLocalRandom.current().nextInt(100) < chance;
    }

    private boolean isVanillaFish(Material material) {
        return material == Material.COD || material == Material.SALMON
                || material == Material.TROPICAL_FISH || material == Material.PUFFERFISH;
    }

    /** Uses the same vanilla catch distribution documented for Overworld fishing. */
    private Material chooseVanillaFish() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 60) return Material.COD;
        if (roll < 90) return Material.SALMON;
        if (roll < 96) return Material.TROPICAL_FISH;
        return Material.PUFFERFISH;
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
        if (meta == null) return false;

        return matchesRodName(meta.hasItemName() ? meta.getItemName() : null)
                || matchesRodName(meta.hasDisplayName() ? meta.getDisplayName() : null);
    }

    private boolean matchesRodName(String name) {
        if (name == null) return false;
        String plainName = ChatColor.stripColor(name);
        return plainName != null
                && plainName.trim().equalsIgnoreCase("Netherite Fishing Rod");
    }
}
