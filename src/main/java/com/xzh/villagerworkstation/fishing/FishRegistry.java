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
    private final Map<String, FishDefinition> definitionsByModel = new LinkedHashMap<>();

    public void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString(
                "custom-fishing.fish-catalogue", "fish.yml"));
        if (!file.exists()) plugin.saveResource("fish.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("fish");
        if (section == null) throw new IllegalStateException("fish.yml is missing the 'fish' section.");

        Map<String, FishDefinition> loaded = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection fish = section.getConfigurationSection(id);
            if (fish == null) continue;
            try {
                ConfigurationSection habitat = fish.getConfigurationSection("habitat");
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
                        habitat == null || !habitat.contains("water-types")
                                ? List.of("ANY") : habitat.getStringList("water-types"),
                        habitat == null || !habitat.contains("dimensions")
                                ? List.of("OVERWORLD") : habitat.getStringList("dimensions"),
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
        definitionsByModel.clear();
        for (FishDefinition definition : definitions.values()) {
            definitionsByModel.put(definition.model().toLowerCase(java.util.Locale.ROOT), definition);
        }
        long mapped = definitions.values().stream().filter(fish -> !fish.biomes().isEmpty()).count();
        plugin.getLogger().info("Loaded " + definitions.size() + " fish species (" + mapped
                + " with biome habitats configured).");
    }

    public FishDefinition get(String id) {
        return definitions.get(id);
    }

    /** Resolves a datapack catch by its minecraft:item_model component. */
    public FishDefinition getByModel(String itemModel) {
        if (itemModel == null || itemModel.isBlank()) return null;
        return definitionsByModel.get(itemModel.toLowerCase(java.util.Locale.ROOT));
    }

    public Collection<FishDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
