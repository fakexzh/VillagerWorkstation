package com.xzh.villagerworkstation.fishing;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Creates uniquely identified fish items. Display text is never used as authoritative data. */
public final class FishItemFactory {
    private final NamespacedKey fishIdKey;
    private final NamespacedKey speciesKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey weightKey;
    private final NamespacedKey lengthKey;
    private final NamespacedKey heightKey;
    private final NamespacedKey widthKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey caughtAtKey;

    public FishItemFactory(JavaPlugin plugin) {
        fishIdKey = new NamespacedKey(plugin, "fish_uuid");
        speciesKey = new NamespacedKey(plugin, "fish_species");
        rarityKey = new NamespacedKey(plugin, "fish_rarity");
        weightKey = new NamespacedKey(plugin, "fish_weight_kg");
        lengthKey = new NamespacedKey(plugin, "fish_length_cm");
        heightKey = new NamespacedKey(plugin, "fish_height_cm");
        widthKey = new NamespacedKey(plugin, "fish_width_cm");
        categoryKey = new NamespacedKey(plugin, "fish_size_category");
        caughtAtKey = new NamespacedKey(plugin, "fish_caught_at");
    }

    public ItemStack create(FishDefinition definition, FishInstance fish) {
        ItemStack item = new ItemStack(Material.COD, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + definition.displayName());
        meta.setLore(List.of(
                ChatColor.GRAY + "Rarity: " + ChatColor.WHITE + fish.rarity().name(),
                ChatColor.GRAY + String.format(java.util.Locale.ROOT, "Weight: %.2f kg", fish.weightKg()),
                ChatColor.GRAY + String.format(java.util.Locale.ROOT, "Length: %.2f cm", fish.lengthCm()),
                ChatColor.GRAY + String.format(java.util.Locale.ROOT, "Height: %.2f cm", fish.heightCm()),
                ChatColor.GRAY + String.format(java.util.Locale.ROOT, "Width: %.2f cm", fish.widthCm()),
                ChatColor.DARK_GRAY + "Specimen #" + fish.fishId().toString().substring(0, 8)
        ));
        var pdc = meta.getPersistentDataContainer();
        pdc.set(fishIdKey, PersistentDataType.STRING, fish.fishId().toString());
        pdc.set(speciesKey, PersistentDataType.STRING, fish.speciesId());
        pdc.set(rarityKey, PersistentDataType.STRING, fish.rarity().name());
        pdc.set(weightKey, PersistentDataType.DOUBLE, fish.weightKg());
        pdc.set(lengthKey, PersistentDataType.DOUBLE, fish.lengthCm());
        pdc.set(heightKey, PersistentDataType.DOUBLE, fish.heightCm());
        pdc.set(widthKey, PersistentDataType.DOUBLE, fish.widthCm());
        pdc.set(categoryKey, PersistentDataType.STRING, fish.sizeCategory());
        pdc.set(caughtAtKey, PersistentDataType.LONG, fish.caughtAtEpochMillis());
        item.setItemMeta(meta);
        return item;
    }
}
