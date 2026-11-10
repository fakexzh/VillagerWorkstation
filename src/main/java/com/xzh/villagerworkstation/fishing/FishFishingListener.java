package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.Location;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Adds XZH specimens without replacing vanilla water- or lava-fishing behavior. */
public final class FishFishingListener implements Listener {
    private static final double NETHER_CATCH_RADIUS_SQUARED = 64.0;
    /** Fixed share of eligible fish catches reserved for XZH custom specimens. */
    private static final double CUSTOM_FISH_CHANCE_PERCENT = 5.0;
    /** Keep a cast eligible briefly after Paper invalidates/retracts its hook. */
    private static final long RETRACTED_HOOK_GRACE_TICKS = 100L;

    private final JavaPlugin plugin;
    private final FishRegistry registry;
    private final FishItemFactory itemFactory;
    private final FishCollectionManager collectionManager;
    private final FishGenerator generator = new FishGenerator();
    private final NamespacedKey specimenIdKey;

    /**
     * A cast is keyed by its actual hook UUID. Last location is refreshed while
     * the hook is valid, then retained for a short grace period after retraction.
     */
    private final Map<UUID, HookTrack> activeNetherHooks = new HashMap<>();
    private long serverTick;
    private final BukkitTask hookTrackingTask;

    public FishFishingListener(JavaPlugin plugin, FishRegistry registry, FishItemFactory itemFactory,
                               FishCollectionManager collectionManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemFactory = itemFactory;
        this.collectionManager = collectionManager;
        this.specimenIdKey = new NamespacedKey(plugin, "fish_uuid");
        this.hookTrackingTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshHookTracks, 1L, 1L);
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

            FishHook hook = event.getHook();
            activeNetherHooks.put(hook.getUniqueId(), new HookTrack(
                    player.getUniqueId(), player.getWorld().getUID(), hook.getLocation().clone(),
                    serverTick, Long.MAX_VALUE));
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

        Map<String, Integer> pool = FishHabitatPools.forBiome(
                event.getHook().getLocation().getBlock().getBiome());
        FishDefinition definition = rollSpeciesByRarity(pool);
        if (definition == null) return; // No XZH species rolled: preserve the vanilla catch.
        replaceWithSpecimen(player, caughtEntity, definition);
    }

    /**
     * The datapack creates Nether-lava catches as item entities. Match the item
     * against a recently tracked hook cast by a player using the required rod.
     * The hook's last known position remains available briefly after retraction.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLavaFishSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        if (entity.getWorld().getEnvironment() != World.Environment.NETHER
                || !plugin.getConfig().getBoolean("custom-fishing.enabled", true)) return;
        // A tagged specimen being dropped is not a new fishing catch.
        if (hasSpecimenMetadata(entity.getItemStack())) return;

        // Only process actual catch items associated with a tracked Nether cast.
        // This avoids changing custom fish players simply drop in the Nether.
        HookMatch match = findNetherAngler(entity);
        if (match == null) return;

        Player owner = plugin.getServer().getPlayer(match.track.playerId);
        if (owner == null || !owner.isOnline()) {
            activeNetherHooks.remove(match.hookId);
            return;
        }

        ItemStack caughtStack = entity.getItemStack();
        Material caughtType = caughtStack.getType();
        FishDefinition definition;
        boolean datapackFishItem = isDatapackFishItem(caughtStack);
        String datapackFishId = datapackFishItem ? getDatapackFishId(caughtStack) : null;

        // Datapack fish without a recognized physical-size suffix are not valid
        // XZH specimens. Remove them before they can fall through as plain COD
        // or SALMON and receive the vanilla shop price.
        if (datapackFishItem && datapackFishId == null) {
            entity.remove();
            activeNetherHooks.remove(match.hookId);
            return;
        }

        // Nether catches must belong to the Nether habitat pool. This also
        // prevents sized Overworld-only fish models from leaking into the Nether.
        if (datapackFishItem && !FishHabitatPools.NETHER_LAVA.containsKey(datapackFishId)) {
            entity.remove();
            activeNetherHooks.remove(match.hookId);
            return;
        }

        // Nether fishing deliberately excludes tropical fish. If the datapack's
        // vanilla loot produces one, normalize it to the Nether vanilla pool.
        if (!datapackFishItem && caughtType == Material.TROPICAL_FISH) {
            caughtStack = new ItemStack(chooseVanillaFish());
            entity.setItemStack(caughtStack);
            caughtType = caughtStack.getType();
        }

        if (datapackFishItem || isVanillaFish(caughtType)) {
            // A sized datapack fish is only a catch signal. The plugin creates
            // the final PDC-backed specimen, which the Fish Shop prices correctly.
            definition = rollSpeciesByRarity(FishHabitatPools.NETHER_LAVA);
            if (definition == null) {
                if (datapackFishItem) entity.setItemStack(new ItemStack(chooseVanillaFish()));
                activeNetherHooks.remove(match.hookId);
                return; // Preserve/normalize the vanilla catch on the 95% side.
            }
        } else {
            // Do not let unrelated item entities from the datapack become catches.
            activeNetherHooks.remove(match.hookId);
            return;
        }

        if (definition == null) {
            activeNetherHooks.remove(match.hookId);
            return;
        }

        activeNetherHooks.remove(match.hookId);
        FishInstance specimen = generator.generate(definition);
        entity.setItemStack(itemFactory.create(definition, specimen));
        collectionManager.recordCatch(owner.getUniqueId(), definition, specimen);
        sendCatchMessage(owner, definition, specimen);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeNetherHooks.entrySet().removeIf(entry -> entry.getValue().playerId.equals(playerId));
    }

    private void refreshHookTracks() {
        serverTick++;
        Iterator<Map.Entry<UUID, HookTrack>> iterator = activeNetherHooks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, HookTrack> entry = iterator.next();
            HookTrack track = entry.getValue();
            Player player = plugin.getServer().getPlayer(track.playerId);
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            FishHook hook = player.getFishHook();
            if (hook != null && hook.isValid() && hook.getUniqueId().equals(entry.getKey())
                    && hook.getWorld().getUID().equals(track.worldId)) {
                track.lastLocation = hook.getLocation().clone();
                track.lastSeenTick = serverTick;
                track.expiresAtTick = Long.MAX_VALUE;
            } else if (track.expiresAtTick == Long.MAX_VALUE) {
                track.expiresAtTick = track.lastSeenTick + RETRACTED_HOOK_GRACE_TICKS;
            }

            if (track.expiresAtTick != Long.MAX_VALUE && serverTick > track.expiresAtTick) {
                iterator.remove();
            }
        }
    }

    private HookMatch findNetherAngler(Item item) {
        HookMatch nearestByPlayer = null;
        double nearestPlayerDistance = 16.0; // Catch datapacks often spawn the item beside the angler.
        HookMatch nearestByHook = null;
        double nearestHookDistance = NETHER_CATCH_RADIUS_SQUARED;
        Location itemLocation = item.getLocation();

        for (Map.Entry<UUID, HookTrack> entry : activeNetherHooks.entrySet()) {
            HookTrack track = entry.getValue();
            if (!track.worldId.equals(item.getWorld().getUID())) continue;
            if (serverTick > track.expiresAtTick) continue;

            Player owner = plugin.getServer().getPlayer(track.playerId);
            if (owner == null || !owner.isOnline()
                    || !owner.getWorld().getUID().equals(track.worldId)) continue;

            double playerDistance = owner.getLocation().distanceSquared(itemLocation);
            if (playerDistance <= nearestPlayerDistance) {
                nearestPlayerDistance = playerDistance;
                nearestByPlayer = new HookMatch(entry.getKey(), track);
            }

            double hookDistance = track.lastLocation.distanceSquared(itemLocation);
            if (hookDistance <= nearestHookDistance) {
                nearestHookDistance = hookDistance;
                nearestByHook = new HookMatch(entry.getKey(), track);
            }
        }
        // Prefer an item spawned at the angler; otherwise use the tracked bobber.
        return nearestByPlayer != null ? nearestByPlayer : nearestByHook;
    }

    private boolean isDatapackFishItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        NamespacedKey model = stack.getItemMeta().getItemModel();
        return model != null && model.getNamespace().equals("barely_default")
                && model.getKey().startsWith("fish_");
    }

    private String getDatapackFishId(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey model = meta == null ? null : meta.getItemModel();
        if (model == null || !model.getNamespace().equals("barely_default")) return null;

        String modelName = model.getKey();
        if (!modelName.startsWith("fish_")) return null;

        String fishId = modelName.substring("fish_".length());
        // Only the four physical-size variants are valid Fish Shop specimens.
        boolean hasSize = false;
        for (String size : new String[]{"_small", "_medium", "_large", "_giant"}) {
            if (fishId.endsWith(size)) {
                fishId = fishId.substring(0, fishId.length() - size.length());
                hasSize = true;
                break;
            }
        }
        if (!hasSize || registry.get(fishId) == null) return null;
        return FishHabitatPools.NETHER_LAVA.containsKey(fishId) ? fishId : null;
    }

    private boolean hasSpecimenMetadata(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer()
                .has(specimenIdKey, org.bukkit.persistence.PersistentDataType.STRING);
    }

    /**
     * First reserves exactly 5% of eligible fish catches for XZH specimens.
     * The remaining 95% stays vanilla. Inside that fixed custom pool, species
     * are selected by habitat weight multiplied by rarity weight. No fishing
     * enchantment is read here, so Luck of the Sea cannot alter this split.
     */
    private FishDefinition rollSpeciesByRarity(Map<String, Integer> pool) {
        if (pool == null || pool.isEmpty()) return null;

        // This independent gate is the complete vanilla/custom probability split.
        if (ThreadLocalRandom.current().nextDouble(100.0) >= CUSTOM_FISH_CHANCE_PERCENT) {
            return null;
        }

        double totalWeight = 0.0;
        for (Map.Entry<String, Integer> entry : pool.entrySet()) {
            FishDefinition candidate = registry.get(entry.getKey());
            int habitatWeight = Math.max(0, entry.getValue());
            if (candidate == null || habitatWeight == 0) continue;
            totalWeight += habitatWeight * candidate.rarity().catchWeight();
        }
        if (totalWeight <= 0.0) return null;

        double speciesRoll = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (Map.Entry<String, Integer> entry : pool.entrySet()) {
            FishDefinition candidate = registry.get(entry.getKey());
            int habitatWeight = Math.max(0, entry.getValue());
            if (candidate == null || habitatWeight == 0) continue;
            speciesRoll -= habitatWeight * candidate.rarity().catchWeight();
            if (speciesRoll < 0.0) return candidate;
        }
        return null;
    }

    private boolean isVanillaFish(Material material) {
        return material == Material.COD || material == Material.SALMON
                || material == Material.PUFFERFISH;
    }

    /** Nether vanilla pool: cod, salmon and pufferfish only; tropical fish are excluded. */
    private Material chooseVanillaFish() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 60) return Material.COD;
        if (roll < 90) return Material.SALMON;
        return Material.PUFFERFISH;
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

    private static final class HookTrack {
        private final UUID playerId;
        private final UUID worldId;
        private Location lastLocation;
        private long lastSeenTick;
        private long expiresAtTick;

        private HookTrack(UUID playerId, UUID worldId, Location lastLocation,
                          long lastSeenTick, long expiresAtTick) {
            this.playerId = playerId;
            this.worldId = worldId;
            this.lastLocation = lastLocation;
            this.lastSeenTick = lastSeenTick;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private record HookMatch(UUID hookId, HookTrack track) {}
}
