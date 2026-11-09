package com.xzh.villagerworkstation.fishing;

/** Ordered fish rarity tiers used for display and valuation. */
public enum FishRarity {
    COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHICAL;

    /** Absolute overall chance per eligible fishing catch, in percentage points. */
    public double catchChancePercent() {
        return switch (this) {
            case COMMON -> 10.0;
            case UNCOMMON -> 6.0;
            case RARE -> 3.0;
            case EPIC -> 2.0;
            case LEGENDARY -> 1.0;
            case MYTHICAL -> 0.5;
        };
    }

    public static FishRarity parse(String value) {
        return FishRarity.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
