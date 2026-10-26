package com.xzh.villagerworkstation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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

public final class VillagerWorkstationPlugin extends JavaPlugin implements Listener {
    private final Map<String, Workstation> workstations = new HashMap<>();
    private final Map<String, ChestLocation> chestCache = new HashMap<>();
    private BukkitTask workerTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadWorkstations();
        Bukkit.getPluginManager().registerEvents(this, this);
        startWorkerTask();
        getLogger().info("Enabled with " + workstations.size() + " registered workstation(s).");
    }

    @Override
    public void onDisable() {
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }
        saveWorkstations();
    }

    private void startWorkerTask() {
        if (workerTask != null) workerTask.cancel();
        long interval = Math.max(20L, getConfig().getLong("scan-interval-ticks", 40L));
        workerTask = Bukkit.getScheduler().runTaskTimer(this, this::runWorkCycle, interval, interval);
    }

    /**
     * All world access happens on the server thread. The job loop never force-loads chunks,
     * limits the work done per station, and assigns a villager at most once per cycle.
     */
    private void runWorkCycle() {
        if (!getConfig().getBoolean("farmer-enabled", true) || workstations.isEmpty()) return;

        int workRadius = Math.max(1, Math.min(16, getConfig().getInt("work-radius", 8)));
        int chestRadius = Math.max(1, Math.min(16, getConfig().getInt("chest-radius", 8)));
        int maxCrops = Math.max(1, Math.min(16, getConfig().getInt("max-crops-per-cycle", 2)));
        Set<UUID> assignedFarmers = new HashSet<>();

        for (Workstation workstation : List.copyOf(workstations.values())) {
            World world = Bukkit.getWorld(workstation.worldId());
            if (world == null || !world.isChunkLoaded(workstation.x() >> 4, workstation.z() >> 4)) continue;

            Block stationBlock = world.getBlockAt(workstation.x(), workstation.y(), workstation.z());
            if (stationBlock.getType() != Material.COMPOSTER) continue;

            Collection<Entity> nearbyEntities = world.getNearbyEntities(
                    stationBlock.getLocation().add(0.5, 0.5, 0.5),
                    workRadius, workRadius, workRadius,
                    entity -> entity instanceof Villager villager
                            && !villager.isDead()
                            && villager.getProfession() == Villager.Profession.FARMER
                            && !assignedFarmers.contains(villager.getUniqueId())
            );

            Villager farmer = nearbyEntities.stream()
                    .map(Villager.class::cast)
                    .min(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(stationBlock.getLocation())))
                    .orElse(null);
            if (farmer == null) continue;
            assignedFarmers.add(farmer.getUniqueId());

            Block chestBlock = findCopperChest(workstation, world, stationBlock.getLocation(), chestRadius);
            if (chestBlock == null || !(chestBlock.getState() instanceof Container container)) continue;
            Inventory inventory = container.getInventory();

            int harvested = 0;
            int minX = stationBlock.getX() - workRadius;
            int maxX = stationBlock.getX() + workRadius;
            int minY = stationBlock.getY() - 3;
            int maxY = stationBlock.getY() + 3;
            int minZ = stationBlock.getZ() - workRadius;
            int maxZ = stationBlock.getZ() + workRadius;

            for (int x = minX; x <= maxX && harvested < maxCrops; x++) {
                for (int y = minY; y <= maxY && harvested < maxCrops; y++) {
                    for (int z = minZ; z <= maxZ && harvested < maxCrops; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                        Block crop = world.getBlockAt(x, y, z);
                        if (!isSupportedCrop(crop)) continue;
                        if (!(crop.getBlockData() instanceof Ageable ageable)
                                || ageable.getAge() < ageable.getMaximumAge()) continue;

                        Material seed = seedFor(crop.getType());
                        Material replantedCrop = cropForSeed(seed);
                        if (seed == null || replantedCrop == null) continue;

                        List<ItemStack> drops = new ArrayList<>();
                        for (ItemStack drop : crop.getDrops()) {
                            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                                drops.add(drop.clone());
                            }
                        }

                        // Only harvest when vanilla drops contain the exact item needed to replant.
                        if (!removeOne(drops, seed)) continue;
                        if (!canFit(inventory, drops)) continue;

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
                        harvested++;
                    }
                }
            }
        }
    }

    private Block findCopperChest(Workstation workstation, World world, Location center, int radius) {
        // Cache the selected chest so every work cycle doesn't rescan thousands of blocks.
        ChestLocation cached = chestCache.get(workstation.key());
        if (cached != null && cached.worldId().equals(world.getUID())
                && world.isChunkLoaded(cached.x() >> 4, cached.z() >> 4)) {
            Block cachedBlock = world.getBlockAt(cached.x(), cached.y(), cached.z());
            if (cachedBlock.getType().name().endsWith("COPPER_CHEST")
                    && cachedBlock.getState() instanceof Container) {
                return cachedBlock;
            }
            chestCache.remove(workstation.key());
        }

        for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
            for (int y = center.getBlockY() - radius; y <= center.getBlockY() + radius; y++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().name().endsWith("COPPER_CHEST")) continue;
                    if (!(block.getState() instanceof Container)) continue;
                    chestCache.put(workstation.key(),
                            new ChestLocation(world.getUID(), block.getX(), block.getY(), block.getZ()));
                    return block;
                }
            }
        }
        return null;
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

    private boolean removeOne(List<ItemStack> drops, Material material) {
        for (int i = 0; i < drops.size(); i++) {
            ItemStack stack = drops.get(i);
            if (stack.getType() != material || stack.getAmount() <= 0) continue;
            stack.setAmount(stack.getAmount() - 1);
            if (stack.getAmount() == 0) drops.remove(i);
            return true;
        }
        return false;
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
            saveWorkstations();
            event.getPlayer().sendMessage(color("&eFarmer workstation unregistered."));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("villagerworkstation.admin")) {
            sender.sendMessage(color("&cYou don't have permission to manage workstations."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&6VillagerWorkstation &7- commands"));
            sender.sendMessage(color("&e/vw set &7- Register the composter you're looking at."));
            sender.sendMessage(color("&e/vw remove &7- Unregister the block you're looking at."));
            sender.sendMessage(color("&e/vw list &7- List registered workstations."));
            sender.sendMessage(color("&e/vw reload &7- Reload config and workstation records."));
            return true;
        }

        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("This command must be run by a player.");
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
            if (target == null || target.getType() != Material.COMPOSTER) {
                player.sendMessage(color("&cLook at a composter within 6 blocks."));
                return true;
            }
            Location location = target.getLocation();
            Workstation workstation = new Workstation(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            workstations.put(workstation.key(), workstation);
            saveWorkstations();
            player.sendMessage(color("&aFarmer workstation registered. Place a copper chest within "
                    + getConfig().getInt("chest-radius", 8) + " blocks and bring a Farmer villager nearby."));
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            if (target == null) {
                player.sendMessage(color("&cLook at the registered composter first."));
                return true;
            }
            Location location = target.getLocation();
            Workstation workstation = new Workstation(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (workstations.remove(workstation.key()) != null) {
                chestCache.remove(workstation.key());
                saveWorkstations();
                player.sendMessage(color("&aFarmer workstation removed."));
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

    private record ChestLocation(UUID worldId, int x, int y, int z) { }

    private record Workstation(UUID worldId, int x, int y, int z) {
        private String key() {
            return worldId + "_" + x + "_" + y + "_" + z;
        }
    }
}
