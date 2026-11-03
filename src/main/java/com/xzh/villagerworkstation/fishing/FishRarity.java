package com.xzh.villagerworkstation.fishing;

/** Ordered fish rarity tiers used for display and valuation. */
public enum FishRarity {
    COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHICAL;

    public static FishRarity parse(String value) {
        return FishRarity.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
