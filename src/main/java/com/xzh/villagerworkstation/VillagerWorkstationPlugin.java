package com.xzh.villagerworkstation;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Compatibility shell retained so existing deployments can remove the plugin cleanly.
 * Villager professions and custom fishing have both been removed; this plugin has no runtime features.
 */
public final class VillagerWorkstationPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().warning("This plugin no longer provides features. Remove VillagerWorkstation.jar from the server.");
    }
}
