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
import org.bukkit.event.entity.EntitySpawnEvent;
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
import java.util.concurrent.ThreadLocalRandom;

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
        updateLoadedVillagerNames();
        Bukkit.getScheduler().runTaskTimer(this, this::updateLoadedVillagerNames, 100L, 100L);
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

    /**
     * Keep profession labels visible above every loaded villager. This intentionally uses
     * profession names as requested, replacing any existing custom name on villagers.
     */
    private void updateLoadedVillagerNames() {
        if (!getConfig().getBoolean("profession-names.enabled", true)) return;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Villager villager && !villager.isDead()) {
                    applyProfessionName(villager);
                }
            }
        }
    }

    private void applyProfessionName(Villager villager) {
        if (!getConfig().getBoolean("profession-names.enabled", true)) return;
        String name = stationProfessionName(villager.getProfession());
        if (!name.isEmpty()) {
            name = java.util.Arrays.stream(name.split(" "))
                    .filter(part -> !part.isEmpty())
                    .map(part -> part.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + part.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
        }
        if (!name.equals(villager.getCustomName()) || !villager.isCustomNameVisible()) {
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVillagerSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            applyProfessionName(villager);
        }
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
        if (workstations.isEmpty()) return;

        int workRadius = Math.max(1, Math.min(16, getConfig().getInt("work-radius", 8)));
        int chestRadius = Math.max(1, Math.min(16, getConfig().getInt("chest-radius", 8)));
        Set<UUID> assignedVillagers = new HashSet<>();

        for (Workstation workstation : List.copyOf(workstations.values())) {
            World world = Bukkit.getWorld(workstation.worldId());
            if (world == null || !world.isChunkLoaded(workstation.x() >> 4, workstation.z() >> 4)) continue;

            Block stationBlock = world.getBlockAt(workstation.x(), workstation.y(), workstation.z());
            Villager.Profession stationProfession = professionForStation(stationBlock.getType());
            if (stationProfession == null) continue;

            // Keep the vanilla night/sleep routine, but actively recall matching workers during daytime.
            long time = world.getTime();
            boolean night = time >= 12542L && time < 23460L;
            if (night) continue;

            int returnRadius = Math.max(workRadius, Math.min(64, getConfig().getInt("return-to-work-radius", 32)));
            Collection<Entity> nearbyEntities = world.getNearbyEntities(
                    stationBlock.getLocation().add(0.5, 0.5, 0.5),
                    returnRadius, returnRadius, returnRadius,
                    entity -> entity instanceof Villager villager
                            && !villager.isDead()
                            && villager.getProfession() == stationProfession
                            && !assignedVillagers.contains(villager.getUniqueId())
            );

            Villager worker = nearbyEntities.stream()
                    .map(Villager.class::cast)
                    .min(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(stationBlock.getLocation())))
                    .orElse(null);
            if (worker == null) continue;
            assignedVillagers.add(worker.getUniqueId());

            Location stationCenter = stationBlock.getLocation().add(0.5, 1.0, 0.5);
            if (worker.getLocation().distanceSquared(stationCenter) > 9.0) {
                worker.getPathfinder().moveTo(stationCenter, 1.0);
                continue;
            }

            Block chestBlock = findCopperChest(workstation, world, stationBlock.getLocation(), chestRadius);
            if (chestBlock == null || !(chestBlock.getState() instanceof Container container)) continue;
            Inventory inventory = container.getInventory();

            if (stationProfession != Villager.Profession.FARMER) {
                if (stationProfession == Villager.Profession.FISHERMAN
                        && getConfig().getBoolean("fisherman-fishing-enabled", true)
                        && hasWaterNearby(world, stationBlock.getLocation(), workRadius)) {
                    catchOneFish(inventory);
                }
                if (getConfig().getBoolean("profession-drop-collection-enabled", true)) {
                    collectProfessionDrops(world, stationBlock.getLocation(), inventory, stationProfession, workRadius);
                }
                continue;
            }
            if (!getConfig().getBoolean("farmer-enabled", true)) continue;

            int harvested = 0;
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

    private void catchOneFish(Inventory inventory) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        Material fish = roll < 60 ? Material.COD
                : roll < 85 ? Material.SALMON
                : roll < 87 ? Material.TROPICAL_FISH
                : Material.PUFFERFISH;
        ItemStack catchItem = new ItemStack(fish, 1);
        if (canFit(inventory, List.of(catchItem))) inventory.addItem(catchItem);
    }

    private Villager.Profession professionForStation(Material material) {
        return switch (material) {
            case COMPOSTER -> Villager.Profession.FARMER;
            case BARREL -> Villager.Profession.FISHERMAN;
            case LOOM -> Villager.Profession.SHEPHERD;
            case FLETCHING_TABLE -> Villager.Profession.FLETCHER;
            case LECTERN -> Villager.Profession.LIBRARIAN;
            case CARTOGRAPHY_TABLE -> Villager.Profession.CARTOGRAPHER;
            case BREWING_STAND -> Villager.Profession.CLERIC;
            case BLAST_FURNACE -> Villager.Profession.ARMORER;
            case SMOKER -> Villager.Profession.BUTCHER;
            case CAULDRON -> Villager.Profession.LEATHERWORKER;
            case STONECUTTER -> Villager.Profession.MASON;
            case SMITHING_TABLE -> Villager.Profession.TOOLSMITH;
            case GRINDSTONE -> Villager.Profession.WEAPONSMITH;
            default -> null;
        };
    }

    private String stationProfessionName(Villager.Profession profession) {
        return profession.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** Collects nearby dropped items associated with the matching profession without breaking blocks or harming entities. */
    private void collectProfessionDrops(World world, Location center, Inventory inventory,
                                       Villager.Profession profession, int radius) {
        int limit = Math.max(1, getConfig().getInt("max-items-per-cycle", 8));
        int movedStacks = 0;
        for (Entity entity : world.getNearbyEntities(center, Math.min(radius, 6), Math.min(radius, 4),
                Math.min(radius, 6), e -> e instanceof org.bukkit.entity.Item)) {
            if (movedStacks >= limit) break;
            org.bukkit.entity.Item dropped = (org.bukkit.entity.Item) entity;
            ItemStack stack = dropped.getItemStack();
            if (!matchesProfessionItem(profession, stack.getType())) continue;
            if (!canFit(inventory, List.of(stack))) continue;
            Map<Integer, ItemStack> leftovers = inventory.addItem(stack.clone());
            if (leftovers.isEmpty()) {
                dropped.remove();
                movedStacks++;
            }
        }
    }

    private boolean matchesProfessionItem(Villager.Profession profession, Material material) {
        String n = material.name();
        if (profession.equals(Villager.Profession.FISHERMAN)) return n.contains("FISH") || n.equals("FISHING_ROD");
        if (profession.equals(Villager.Profession.SHEPHERD)) return n.endsWith("_WOOL") || n.equals("SHEARS") || n.endsWith("_CARPET");
        if (profession.equals(Villager.Profession.FLETCHER)) return n.equals("ARROW") || n.equals("SPECTRAL_ARROW") || n.equals("FEATHER") || n.equals("FLINT") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("STICK");
        if (profession.equals(Villager.Profession.LIBRARIAN)) return n.contains("BOOK") || n.equals("PAPER") || n.equals("INK_SAC");
        if (profession.equals(Villager.Profession.CARTOGRAPHER)) return n.equals("MAP") || n.equals("FILLED_MAP") || n.equals("PAPER") || n.equals("COMPASS") || n.equals("GLASS_PANE");
        if (profession.equals(Villager.Profession.CLERIC)) return n.equals("ROTTEN_FLESH") || n.equals("GOLD_INGOT") || n.equals("GOLD_NUGGET") || n.equals("REDSTONE") || n.equals("GLOWSTONE_DUST") || n.equals("LAPIS_LAZULI") || n.equals("GLASS_BOTTLE") || n.contains("POTION");
        if (profession.equals(Villager.Profession.ARMORER)) return n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS") || n.contains("SHIELD") || n.equals("IRON_INGOT") || n.equals("IRON_NUGGET") || n.equals("CHAIN");
        if (profession.equals(Villager.Profession.BUTCHER)) return n.contains("BEEF") || n.contains("PORKCHOP") || n.contains("CHICKEN") || n.contains("MUTTON") || n.contains("RABBIT") || n.contains("COD") || n.contains("SALMON");
        if (profession.equals(Villager.Profession.LEATHERWORKER)) return n.equals("LEATHER") || n.startsWith("LEATHER_") || n.equals("RABBIT_HIDE") || n.equals("RABBIT_FOOT");
        if (profession.equals(Villager.Profession.MASON)) return n.contains("STONE") || n.contains("BRICK") || n.contains("TERRACOTTA") || n.contains("QUARTZ") || n.equals("CLAY_BALL");
        if (profession.equals(Villager.Profession.TOOLSMITH)) return n.contains("PICKAXE") || n.contains("SHOVEL") || n.contains("HOE") || n.equals("IRON_INGOT") || n.equals("IRON_NUGGET") || n.equals("DIAMOND");
        if (profession.equals(Villager.Profession.WEAPONSMITH)) return n.contains("SWORD") || n.contains("AXE") || n.equals("IRON_INGOT") || n.equals("IRON_NUGGET") || n.equals("DIAMOND");
        return false;
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
            if (target == null || professionForStation(target.getType()) == null) {
                player.sendMessage(color("&cLook at a supported villager workstation within 6 blocks."));
                return true;
            }
            Location location = target.getLocation();
            Workstation workstation = new Workstation(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            workstations.put(workstation.key(), workstation);
            saveWorkstations();
            player.sendMessage(color("&a" + stationProfessionName(professionForStation(target.getType()))
                    + " workstation registered. Place a copper chest within "
                    + getConfig().getInt("chest-radius", 8) + " blocks and bring the matching villager nearby."));
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
