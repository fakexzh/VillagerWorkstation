package com.xzh.villagerworkstation.fishing;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates species definitions from plugins/VillagerWorkstation/fish.yml. */
public final class FishRegistry {
    private final Map<String, FishDefinition> definitions = new LinkedHashMap<>();

    public void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "fish.yml");
        if (!file.exists()) plugin.saveResource("fish.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("fish");
        if (section == null) throw new IllegalStateException("fish.yml is missing the 'fish' section.");

        Map<String, FishDefinition> loaded = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection fish = section.getConfigurationSection(id);
            if (fish == null) continue;
            try {
                FishDefinition definition = new FishDefinition(
                        id,
                        fish.getString("display-name", id),
                        FishRarity.parse(fish.getString("rarity", "COMMON")),
                        fish.getDouble("weight.minimum"),
                        fish.getDouble("weight.maximum"),
                        fish.getDouble("weight.typical"),
                        fish.getDouble("dimensions.length.minimum"),
                        fish.getDouble("dimensions.length.maximum"),
                        fish.getDouble("dimensions.height.minimum"),
                        fish.getDouble("dimensions.height.maximum"),
                        fish.getDouble("dimensions.width.minimum"),
                        fish.getDouble("dimensions.width.maximum"),
                        fish.getDouble("value.base-price"),
                        fish.getStringList("habitat.biomes"),
                        fish.getInt("habitat.water-depth.minimum", 0),
                        fish.getInt("habitat.water-depth.maximum", 64),
                        fish.getString("model", "xzh:fishing/fish/" + id)
                );
                loaded.put(id, definition);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid fish definition '" + id + "': "
                        + exception.getMessage(), exception);
            }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("fish.yml contains no fish definitions.");
        definitions.clear();
        definitions.putAll(loaded);
        plugin.getLogger().info("Loaded " + definitions.size() + " fish species.");
    }

    public FishDefinition get(String id) {
        return definitions.get(id);
    }

    public Collection<FishDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
