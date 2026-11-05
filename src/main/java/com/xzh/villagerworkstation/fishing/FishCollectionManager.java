package com.xzh.villagerworkstation.fishing;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Persists genuine player catches in the file consumed by XZHEconomySystem's Fish Collection. */
public final class FishCollectionManager {
    private final JavaPlugin plugin;
    private final File file;

    public FishCollectionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "fish-collections.yml");
    }

    public synchronized void recordCatch(UUID playerId, FishDefinition fish, FishInstance specimen) {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create fish collection data directory.");
            return;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        String player = "players." + playerId;
        String species = player + ".species." + fish.id();
        int previousCount = data.getInt(species + ".count", 0);
        data.set(player + ".total-catches", data.getInt(player + ".total-catches", 0) + 1);
        data.set(species + ".count", previousCount + 1);
        if (previousCount == 0) {
            data.set(player + ".unique-species", data.getInt(player + ".unique-species", 0) + 1);
        }

        double previousBestWeight = data.getDouble(species + ".best-weight-kg", 0.0);
        if (specimen.weightKg() > previousBestWeight) {
            data.set(species + ".best-weight-kg", specimen.weightKg());
            data.set(species + ".best-size-category", specimen.sizeCategory());
            data.set(species + ".best-length-cm", specimen.lengthCm());
            data.set(species + ".best-height-cm", specimen.heightCm());
            data.set(species + ".best-width-cm", specimen.widthCm());
            data.set(species + ".best-rarity", specimen.rarity().name());
            data.set(species + ".best-specimen-id", specimen.fishId().toString());
            data.set(species + ".best-caught-at", specimen.caughtAtEpochMillis());
        }

        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save fish collection for " + playerId + ": " + exception.getMessage());
        }
    }
}
