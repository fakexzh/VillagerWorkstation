package com.xzh.villagerworkstation;

import com.xzh.villagerworkstation.fishing.FishCollectionManager;
import com.xzh.villagerworkstation.fishing.FishDefinition;
import com.xzh.villagerworkstation.fishing.FishFishingListener;
import com.xzh.villagerworkstation.fishing.FishItemFactory;
import com.xzh.villagerworkstation.fishing.FishRegistry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Retains the independent XZH custom-fishing and specimen-collection feature.
 * All villager professions, jobs, workstation registration and automated
 * farmer/fisherman production have been removed.
 */
public final class VillagerWorkstationPlugin extends JavaPlugin {
    private final FishRegistry fishRegistry = new FishRegistry();
    private FishItemFactory fishItemFactory;
    private FishCollectionManager fishCollectionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int previousChance = getConfig().getInt("custom-fishing.chance-percent", 20);
        if (previousChance == 20 || previousChance == 25) {
            getConfig().set("custom-fishing.chance-percent", 5);
            saveConfig();
        }

        fishRegistry.load(this);
        fishItemFactory = new FishItemFactory(this);
        fishCollectionManager = new FishCollectionManager(this);
        getServer().getPluginManager().registerEvents(
                new FishFishingListener(this, fishRegistry, fishItemFactory, fishCollectionManager), this);
        getLogger().info("Enabled custom fishing and specimen collections; villager jobs are disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&6XZH Fishing &7- commands"));
            sender.sendMessage(color("&e/vw fish list [page] &7- Browse the custom fish catalogue."));
            sender.sendMessage(color("&e/vw reload &7- Reload fishing configuration and catalogue."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("villagerworkstation.admin")) {
                sender.sendMessage(color("&cYou don't have permission to reload this plugin."));
                return true;
            }
            reloadConfig();
            fishRegistry.load(this);
            sender.sendMessage(color("&aFishing configuration and catalogue reloaded."));
            return true;
        }

        if (args[0].equalsIgnoreCase("fish")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("list")) {
                sender.sendMessage(color("&eUsage: /vw fish list [page]"));
                return true;
            }
            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException ignored) {
                    page = 1;
                }
            }
            List<FishDefinition> species = new ArrayList<>(fishRegistry.all());
            int pageSize = 20;
            int pages = Math.max(1, (species.size() + pageSize - 1) / pageSize);
            page = Math.min(page, pages);
            sender.sendMessage(color("&bFish catalogue &7- page &f" + page + "&7/&f" + pages
                    + " &8(" + species.size() + " species)"));
            for (int i = (page - 1) * pageSize; i < Math.min(page * pageSize, species.size()); i++) {
                FishDefinition fish = species.get(i);
                sender.sendMessage(color("&7- &f" + fish.displayName() + " &8[" + fish.rarity().name() + "]"));
            }
            return true;
        }

        sender.sendMessage(color("&cUnknown subcommand. Use /vw help."));
        return true;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
