package com.xzh.villagerworkstation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerWorkstationPlugin extends JavaPlugin implements Listener {
    private final Map<String, Workstation> workstations = new HashMap<>();
    private final Map<String, ChestLocation> chestCache = new HashMap<>();
    private final Map<String, String> workstationStatuses = new HashMap<>();
    private final Map<String, ProductionWindow> productionWindows = new HashMap<>();
    private static final long PRODUCTION_WINDOW_MILLIS = 60L * 60L * 1000L;
    private static final int MAX_FISH_PER_HOUR = 64;
    private static final int MAX_ITEMS_PER_CROP_PER_HOUR = 64;
    private File productionFile;
    private YamlConfiguration productionData;
    private BukkitTask workerTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadWorkstations();
        loadProductionWindows();
        Bukkit.getPluginManager().registerEvents(this, this);
        startWorkerTask();
        getLogger().info("Enabled with " + workstations.size() + " registered farmer/fisherman workstation(s).");
    }

    @Override
    public void onDisable() {
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }
        saveWorkstations();
        saveProductionWindows();
    }

    private void startWorkerTask() {
        if (workerTask != null) workerTask.cancel();
        long interval = Math.max(20L, getConfig().getLong("scan-interval-ticks", 40L));
        workerTask = Bukkit.getScheduler().runTaskTimer(this, this::runWorkCycle, interval, interval);
    }

    /**
     * All world access happens on the server thread. The job loop never force-loads chunks,
     * assigns a villager at most once per cycle and recalls workers to their station during daytime.
     */
    private void runWorkCycle() {
        updateProfessionLabels();
        if (workstations.isEmpty()) return;

        int workRadius = Math.max(1, Math.min(64, getConfig().getInt("work-radius", 64)));
        int chestRadius = Math.max(1, Math.min(16, getConfig().getInt("chest-radius", 8)));

        for (Workstation workstation : List.copyOf(workstations.values())) {
            World world = Bukkit.getWorld(workstation.worldId());
            if (world == null || !world.isChunkLoaded(workstation.x() >> 4, workstation.z() >> 4)) continue;

            Block stationBlock = world.getBlockAt(workstation.x(), workstation.y(), workstation.z());
            Villager.Profession stationProfession = professionForStation(stationBlock.getType());
            if (stationProfession != Villager.Profession.FARMER
                    && stationProfession != Villager.Profession.FISHERMAN) continue;

            // Keep vanilla villager movement: production is simulated without pulling workers back.
            long time = world.getTime();
            boolean night = time >= 12542L && time < 23460L;
            if (night) continue;

            Collection<Entity> nearbyEntities = world.getNearbyEntities(
                    stationBlock.getLocation().add(0.5, 0.5, 0.5),
                    workRadius, 8, workRadius,
                    entity -> entity instanceof Villager villager
                            && !villager.isDead()
                            && villager.getProfession() == stationProfession
            );

            Villager worker = nearbyEntities.stream()
                    .map(Villager.class::cast)
                    .min(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(stationBlock.getLocation())))
                    .orElse(null);
            if (worker == null) {
                reportStatus(workstation, "No matching " + stationProfession.name().toLowerCase(java.util.Locale.ROOT)
                        + " villager within the " + workRadius + " block work area.");
                continue;
            }

            Block chestBlock = findCopperChest(workstation, world, stationBlock.getLocation(), chestRadius);
            if (chestBlock == null || !(chestBlock.getState() instanceof Container container)) {
                reportStatus(workstation, "No supported copper chest with an inventory found within "
                        + chestRadius + " blocks; worker is waiting.");
                continue;
            }
            Inventory inventory = container.getInventory();

            if (stationProfession == Villager.Profession.FISHERMAN) {
                if (!getConfig().getBoolean("fisherman-fishing-enabled", true)) {
                    reportStatus(workstation, "Fisherman production is disabled in config.");
                    continue;
                }
                if (!hasWaterNearby(world, stationBlock.getLocation(), workRadius)) {
                    reportStatus(workstation, "No water found within the fisherman's work radius.");
                    continue;
                }
                ProductionWindow production = productionWindow(workstation);
                if (production.fishCount >= maxFishPerHour()) {
                    reportStatus(workstation, "Fisherman reached the limit of 64 mixed fish per real-world hour.");
                    continue;
                }
                if (catchOneFish(workstation, inventory)) {
                    reportStatus(workstation, "Fisherman active; catch stored directly in copper chest.");
                } else {
                    reportStatus(workstation, "Copper chest has no room for a fish catch.");
                }
                continue;
            }
            if (!getConfig().getBoolean("farmer-enabled", true)) {
                reportStatus(workstation, "Farmer harvesting is disabled in config.");
                continue;
            }

            boolean chestFull = false;
            int minX = stationBlock.getX() - workRadius;
            int maxX = stationBlock.getX() + workRadius;
            int minY = stationBlock.getY() - 3;
            int maxY = stationBlock.getY() + 3;
            int minZ = stationBlock.getZ() - workRadius;
            int maxZ = stationBlock.getZ() + workRadius;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                        Block crop = world.getBlockAt(x, y, z);
                        if (!isSupportedCrop(crop)) continue;
                        if (!(crop.getBlockData() instanceof Ageable ageable)
                                || ageable.getAge() < ageable.getMaximumAge()) continue;

                        Material seed = seedFor(crop.getType());
                        Material replantedCrop = cropForSeed(seed);
                        if (seed == null || replantedCrop == null) continue;

                        Material cropOutput = cropOutputFor(crop.getType());
                        if (cropOutput == null) continue;
                        ProductionWindow production = productionWindow(workstation);
                        int remainingCropItems = maxItemsPerCropPerHour()
                                - production.cropCounts.getOrDefault(cropOutput, 0);
                        if (remainingCropItems <= 0) continue;

                        List<ItemStack> drops = new ArrayList<>();
                        for (ItemStack drop : crop.getDrops()) {
                            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                                drops.add(drop.clone());
                            }
                        }

                        // Limit the main crop item to 64 per crop type per real-world hour.
                        // If a randomized drop would exceed the remaining allowance, leave the crop
                        // untouched and try it again later rather than discarding items or exceeding quota.
                        int primaryCropAmount = drops.stream()
                                .filter(drop -> drop.getType() == cropOutput)
                                .mapToInt(ItemStack::getAmount).sum();
                        if (primaryCropAmount <= 0 || primaryCropAmount > remainingCropItems) continue;

                        if (!canFit(inventory, drops)) {
                            chestFull = true;
                            continue;
                        }

                        // Replant immediately; no crop-drop item entities are spawned.
                        crop.setType(replantedCrop, false);
                        for (ItemStack drop : drops) {
                            Map<Integer, ItemStack> leftovers = inventory.addItem(drop);
                            if (!leftovers.isEmpty()) {
                                // Capacity was simulated immediately before this insertion. If a
                                // custom inventory behaves unexpectedly, preserve leftovers safely.
                                leftovers.values().forEach(item ->
                                        world.dropItemNaturally(chestBlock.getLocation().add(0.5, 1.0, 0.5), item));
                            }
                        }
                        production.cropCounts.merge(cropOutput, primaryCropAmount, Integer::sum);
                        saveProductionWindows();
                    }
                }
            }
            reportStatus(workstation, chestFull
                    ? "Copper chest has no room for all drops; remaining mature crops were left unharvested."
                    : "Farmer active; mature crops are harvested and all drops go directly into the copper chest.");
        }
    }

    /**
     * Show profession labels only for employed villagers. Unemployed villagers and nitwits
     * are never assigned a label by this plugin. Existing custom names are preserved.
     */
    private void updateProfessionLabels() {
        NamespacedKey labelKey = new NamespacedKey(this, "profession-label");
        NamespacedKey labelTextKey = new NamespacedKey(this, "profession-label-text");
        double visibleDistanceSquared = 16.0 * 16.0;
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                var data = villager.getPersistentDataContainer();
                boolean managedLabel = data.has(labelKey, PersistentDataType.BYTE);
                String previousLabel = data.get(labelTextKey, PersistentDataType.STRING);

                // If a player renamed a villager, stop managing that name and preserve it.
                if (managedLabel && previousLabel != null
                        && !previousLabel.equals(villager.getCustomName())) {
                    data.remove(labelKey);
                    data.remove(labelTextKey);
                    managedLabel = false;
                }

                Villager.Profession profession = villager.getProfession();
                boolean hasProfession = profession != Villager.Profession.NONE
                        && profession != Villager.Profession.NITWIT;

                if (!hasProfession && managedLabel) {
                    if (previousLabel == null || previousLabel.equals(villager.getCustomName())) {
                        villager.setCustomName(null);
                    }
                    data.remove(labelKey);
                    data.remove(labelTextKey);
                } else if (hasProfession && (managedLabel || villager.getCustomName() == null)) {
                    String label = profession.name().toLowerCase(java.util.Locale.ROOT);
                    label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                    villager.setCustomName(label);
                    data.set(labelKey, PersistentDataType.BYTE, (byte) 1);
                    data.set(labelTextKey, PersistentDataType.STRING, label);
                }

                boolean playerNearby = Bukkit.getOnlinePlayers().stream()
                        .filter(player -> player.getWorld().equals(world))
                        .anyMatch(player -> player.getLocation().distanceSquared(villager.getLocation())
                                <= visibleDistanceSquared);
                // Preserve player-given names; only control whether an existing name is visible.
                villager.setCustomNameVisible(villager.getCustomName() != null && playerNearby);
            }
        }
    }

    private Block findCopperChest(Workstation workstation, World world, Location center, int radius) {
        ChestLocation cached = chestCache.get(workstation.key());
        if (cached != null && cached.worldId().equals(world.getUID())
                && world.isChunkLoaded(cached.x() >> 4, cached.z() >> 4)) {
            Block cachedBlock = world.getBlockAt(cached.x(), cached.y(), cached.z());
            if (cachedBlock.getType().name().endsWith("COPPER_CHEST")
                    && cachedBlock.getState() instanceof Container
                    && Math.abs(cached.x() - center.getBlockX()) <= radius
                    && Math.abs(cached.y() - center.getBlockY()) <= radius
                    && Math.abs(cached.z() - center.getBlockZ()) <= radius) return cachedBlock;
            chestCache.remove(workstation.key());
        }
        Block nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
            for (int y = center.getBlockY() - radius; y <= center.getBlockY() + radius; y++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().name().endsWith("COPPER_CHEST")
                            || !(block.getState() instanceof Container)) continue;
                    double distance = block.getLocation().distanceSquared(center);
                    if (distance < nearestDistance) {
                        nearest = block;
                        nearestDistance = distance;
                    }
                }
            }
        }
        if (nearest != null) chestCache.put(workstation.key(),
                new ChestLocation(world.getUID(), nearest.getX(), nearest.getY(), nearest.getZ()));
        return nearest;
    }

    private boolean hasWaterNearby(World world, Location center, int radius) {
        for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
            for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = center.getBlockY() - 3; y <= center.getBlockY() + 3; y++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.WATER) return true;
                }
            }
        }
        return false;
    }

    private boolean catchOneFish(Workstation workstation, Inventory inventory) {
        ProductionWindow production = productionWindow(workstation);
        if (production.fishCount >= maxFishPerHour()) return false;
        int roll = ThreadLocalRandom.current().nextInt(100);
        // Common fish dominate; tropical fish and pufferfish are intentionally rare.
        Material fish = roll < 60 ? Material.COD
                : roll < 90 ? Material.SALMON
                : roll < 96 ? Material.TROPICAL_FISH
                : Material.PUFFERFISH;
        ItemStack catchItem = new ItemStack(fish, 1);
        if (!canFit(inventory, List.of(catchItem))) return false;
        if (!inventory.addItem(catchItem).isEmpty()) return false;
        production.fishCount++;
        saveProductionWindows();
        return true;
    }

    private Material cropOutputFor(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            default -> null;
        };
    }

    private int maxFishPerHour() {
        return Math.max(1, Math.min(MAX_FISH_PER_HOUR,
                getConfig().getInt("fisherman-max-fish-per-hour", MAX_FISH_PER_HOUR)));
    }

    private int maxItemsPerCropPerHour() {
        return Math.max(1, Math.min(MAX_ITEMS_PER_CROP_PER_HOUR,
                getConfig().getInt("farmer-max-items-per-crop-per-hour", MAX_ITEMS_PER_CROP_PER_HOUR)));
    }

    private ProductionWindow productionWindow(Workstation workstation) {
        long now = System.currentTimeMillis();
        ProductionWindow window = productionWindows.get(workstation.key());
        if (window == null || now < window.windowStart || now - window.windowStart >= PRODUCTION_WINDOW_MILLIS) {
            window = new ProductionWindow(now);
            productionWindows.put(workstation.key(), window);
        }
        return window;
    }

    private void loadProductionWindows() {
        productionFile = new File(getDataFolder(), "production-limits.yml");
        productionData = YamlConfiguration.loadConfiguration(productionFile);
        productionWindows.clear();
        long now = System.currentTimeMillis();
        var section = productionData.getConfigurationSection("workstations");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            var saved = section.getConfigurationSection(key);
            if (saved == null) continue;
            long start = saved.getLong("window-start", 0L);
            if (start <= 0L || now < start || now - start >= PRODUCTION_WINDOW_MILLIS) continue;
            ProductionWindow window = new ProductionWindow(start);
            window.fishCount = Math.max(0, Math.min(maxFishPerHour(), saved.getInt("fish-count", 0)));
            var crops = saved.getConfigurationSection("crop-counts");
            if (crops != null) {
                for (String name : crops.getKeys(false)) {
                    try {
                        Material material = Material.valueOf(name);
                        if (material == Material.WHEAT || material == Material.CARROT
                                || material == Material.POTATO || material == Material.BEETROOT) {
                            window.cropCounts.put(material, Math.max(0, Math.min(maxItemsPerCropPerHour(), crops.getInt(name, 0))));
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            productionWindows.put(key, window);
        }
    }

    private void saveProductionWindows() {
        if (productionFile == null || productionData == null) return;
        productionData.set("workstations", null);
        for (Map.Entry<String, ProductionWindow> entry : productionWindows.entrySet()) {
            String base = "workstations." + entry.getKey();
            ProductionWindow window = entry.getValue();
            productionData.set(base + ".window-start", window.windowStart);
            productionData.set(base + ".fish-count", window.fishCount);
            for (Map.Entry<Material, Integer> crop : window.cropCounts.entrySet()) {
                productionData.set(base + ".crop-counts." + crop.getKey().name(), crop.getValue());
            }
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            productionData.save(productionFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save production limits: " + exception.getMessage());
        }
    }

    private Villager.Profession professionForStation(Material material) {
        return switch (material) {
            case COMPOSTER -> Villager.Profession.FARMER;
            case BARREL -> Villager.Profession.FISHERMAN;
            default -> null;
        };
    }

    private void reportStatus(Workstation workstation, String status) {
        String previous = workstationStatuses.put(workstation.key(), status);
        if (!status.equals(previous)) {
            getLogger().info("Workstation (" + workstation.x() + ", " + workstation.y() + ", "
                    + workstation.z() + "): " + status);
        }
    }

    /**
     * Prevent registered farmer workstations from feeding items into composters through
     * vanilla villager AI. The composter remains the farmer's workstation/job site.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCompostItem(EntityCompostItemEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || villager.getProfession() != Villager.Profession.FARMER) return;
        Block composter = event.getBlock();
        if (composter.getType() != Material.COMPOSTER) return;
        Workstation workstation = new Workstation(composter.getWorld().getUID(),
                composter.getX(), composter.getY(), composter.getZ());
        if (workstations.containsKey(workstation.key())) {
            event.setCancelled(true);
        }
    }

    private boolean isSupportedCrop(Block block) {
        return switch (block.getType()) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS -> true;
            default -> false;
        };
    }

    private Material seedFor(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            default -> null;
        };
    }

    private Material cropForSeed(Material seed) {
        if (seed == null) return null;
        return switch (seed) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            default -> null;
        };
    }

    private boolean canFit(Inventory inventory, List<ItemStack> additions) {
        ItemStack[] simulated = new ItemStack[inventory.getSize()];
        for (int i = 0; i < simulated.length; i++) {
            ItemStack existing = inventory.getItem(i);
            simulated[i] = existing == null || existing.getType() == Material.AIR ? null : existing.clone();
        }

        for (ItemStack addition : additions) {
            int remaining = addition.getAmount();

            for (ItemStack existing : simulated) {
                if (existing == null || !existing.isSimilar(addition)
                        || existing.getAmount() >= existing.getMaxStackSize()) continue;
                int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                remaining -= moved;
                if (remaining == 0) break;
            }

            while (remaining > 0) {
                int emptySlot = -1;
                for (int i = 0; i < simulated.length; i++) {
                    if (simulated[i] == null) {
                        emptySlot = i;
                        break;
                    }
                }
                if (emptySlot < 0) return false;

                ItemStack placed = addition.clone();
                int moved = Math.min(remaining, placed.getMaxStackSize());
                placed.setAmount(moved);
                simulated[emptySlot] = placed;
                remaining -= moved;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorkstationBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Workstation workstation = new Workstation(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (workstations.remove(workstation.key()) != null) {
            chestCache.remove(workstation.key());
            workstationStatuses.remove(workstation.key());
            saveWorkstations();
            event.getPlayer().sendMessage(color("&eVillager workstation unregistered."));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean quickWorkCommand = command.getName().equalsIgnoreCase("work");
        if (!quickWorkCommand && !sender.hasPermission("villagerworkstation.admin")) {
            sender.sendMessage(color("&cYou don't have permission to manage workstations."));
            return true;
        }

        if (quickWorkCommand) args = new String[]{"set"};
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&6VillagerWorkstation &7- commands"));
            sender.sendMessage(color("&e/work &7- Look at a composter or barrel to register a job."));
            sender.sendMessage(color("&e/vw set &7- Register a farmer composter or fisherman barrel."));
            sender.sendMessage(color("&e/vw remove &7- Unregister the block you're looking at."));
            sender.sendMessage(color("&e/vw list &7- List registered workstations."));
            sender.sendMessage(color("&e/vw reload &7- Reload config and workstation records."));
            return true;
        }

        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(color("&cThis command must be run by a player."));
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (workstations.isEmpty()) {
                player.sendMessage(color("&7No workstations registered."));
                return true;
            }
            player.sendMessage(color("&6Registered workstations: &f" + workstations.size()));
            for (Workstation workstation : workstations.values()) {
                World world = Bukkit.getWorld(workstation.worldId());
                player.sendMessage(color("&e" + (world == null ? workstation.worldId() : world.getName())
                        + " &7(" + workstation.x() + ", " + workstation.y() + ", " + workstation.z() + ")"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadWorkstations();
            startWorkerTask();
            player.sendMessage(color("&aVillagerWorkstation config reloaded."));
            return true;
        }

        Block target = player.getTargetBlockExact(6);
        if (args[0].equalsIgnoreCase("set")) {
            if (target == null || professionForStation(target.getType()) == null) {
                player.sendMessage(color("&cLook at a composter (farmer) or barrel (fisherman) within 6 blocks."));
                return true;
            }
            Location location = target.getLocation();
            Workstation workstation = new Workstation(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (workstations.containsKey(workstation.key())) {
                player.sendMessage(color("&eThat workstation is already registered."));
                player.sendMessage(color("&bKeep a matching villager within " + getConfig().getInt("work-radius", 64)
                        + " blocks and place a copper chest within " + getConfig().getInt("chest-radius", 8) + " blocks."));
                return true;
            }
            workstations.put(workstation.key(), workstation);
            chestCache.remove(workstation.key());
            workstationStatuses.remove(workstation.key());
            saveWorkstations();
            if (target.getType() == Material.COMPOSTER) {
                player.sendMessage(color("&a&lFarmer job registered!"));
                player.sendMessage(color("&b1. Keep a farmer villager within " + getConfig().getInt("work-radius", 64) + " blocks."));
                player.sendMessage(color("&b2. Place a copper chest within " + getConfig().getInt("chest-radius", 8) + " blocks of the composter."));
                player.sendMessage(color("&7Mature wheat, carrots, potatoes and beetroot will be harvested and stored automatically."));
            } else {
                player.sendMessage(color("&a&lFisherman job registered!"));
                player.sendMessage(color("&b1. Keep a fisherman villager within " + getConfig().getInt("work-radius", 64) + " blocks."));
                player.sendMessage(color("&b2. Place a copper chest within " + getConfig().getInt("chest-radius", 8) + " blocks of the barrel."));
                player.sendMessage(color("&b3. Keep water within the " + getConfig().getInt("work-radius", 64) + " block work area."));
                player.sendMessage(color("&7Fish will be added automatically while the workstation is loaded and it is daytime."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            if (target == null) {
                player.sendMessage(color("&cLook at the registered farmer composter or fisherman barrel first."));
                return true;
            }
            Location location = target.getLocation();
            Workstation workstation = new Workstation(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (workstations.remove(workstation.key()) != null) {
                chestCache.remove(workstation.key());
                workstationStatuses.remove(workstation.key());
                saveWorkstations();
                player.sendMessage(color("&aWorkstation registration removed. Place it again and run &e/work &ato register a new job."));
            } else {
                player.sendMessage(color("&cThat block is not a registered workstation."));
            }
            return true;
        }

        player.sendMessage(color("&cUnknown subcommand. Use /vw help."));
        return true;
    }

    private void loadWorkstations() {
        workstations.clear();
        File file = new File(getDataFolder(), "workstations.yml");
        if (!file.isFile()) return;

        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            String worldText = yaml.getString(key + ".world");
            if (worldText == null) continue;
            try {
                Workstation workstation = new Workstation(UUID.fromString(worldText),
                        yaml.getInt(key + ".x"), yaml.getInt(key + ".y"), yaml.getInt(key + ".z"));
                workstations.put(workstation.key(), workstation);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Skipping invalid workstation entry: " + key);
            }
        }
    }

    private void saveWorkstations() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data directory.");
            return;
        }

        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (Workstation workstation : workstations.values()) {
            yaml.set(workstation.key() + ".world", workstation.worldId().toString());
            yaml.set(workstation.key() + ".x", workstation.x());
            yaml.set(workstation.key() + ".y", workstation.y());
            yaml.set(workstation.key() + ".z", workstation.z());
        }

        try {
            yaml.save(new File(getDataFolder(), "workstations.yml"));
        } catch (IOException exception) {
            getLogger().severe("Could not save workstations: " + exception.getMessage());
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static final class ProductionWindow {
        private final long windowStart;
        private int fishCount;
        private final Map<Material, Integer> cropCounts = new HashMap<>();
        private ProductionWindow(long windowStart) { this.windowStart = windowStart; }
    }

    private record ChestLocation(UUID worldId, int x, int y, int z) { }

    private record Workstation(UUID worldId, int x, int y, int z) {
        private String key() {
            return worldId + "_" + x + "_" + y + "_" + z;
        }
    }
}
