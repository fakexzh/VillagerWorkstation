package com.xzh.villagerworkstation.fishing;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/** Persistent per-player catch history used by the Economy Fish Collection Library. */
public final class FishCollectionStore {
    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public FishCollectionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "fish-collections.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void recordCatch(Player player, FishDefinition definition, FishInstance fish) {
        String playerPath = "players." + player.getUniqueId();
        String speciesPath = playerPath + ".species." + definition.id();
        int previousCount = data.getInt(speciesPath + ".count", 0);
        data.set(playerPath + ".total-catches", data.getInt(playerPath + ".total-catches", 0) + 1);
        if (previousCount == 0) {
            data.set(playerPath + ".unique-species", data.getInt(playerPath + ".unique-species", 0) + 1);
            data.set(speciesPath + ".first-caught-at", fish.caughtAtEpochMillis());
        }
        data.set(speciesPath + ".count", previousCount + 1);
        data.set(speciesPath + ".last-caught-at", fish.caughtAtEpochMillis());
        data.set(speciesPath + ".display-name", definition.displayName());
        data.set(speciesPath + ".rarity", fish.rarity().name());
        if (fish.weightKg() > data.getDouble(speciesPath + ".best-weight-kg", 0.0)) {
            data.set(speciesPath + ".best-weight-kg", fish.weightKg());
            data.set(speciesPath + ".best-length-cm", fish.lengthCm());
            data.set(speciesPath + ".best-height-cm", fish.heightCm());
            data.set(speciesPath + ".best-width-cm", fish.widthCm());
            data.set(speciesPath + ".best-size-category", fish.sizeCategory());
            data.set(speciesPath + ".best-specimen-id", fish.fishId().toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save fish collection data: " + exception.getMessage());
        }
    }
}
