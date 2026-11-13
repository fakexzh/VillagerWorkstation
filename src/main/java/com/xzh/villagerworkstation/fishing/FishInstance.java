package com.xzh.villagerworkstation.fishing;

import java.util.UUID;

/** Server-generated identity and measured attributes for one individual fish. */
public record FishInstance(
        UUID fishId,
        String speciesId,
        FishRarity rarity,
        double weightKg,
        double lengthCm,
        double heightCm,
        double widthCm,
        String sizeCategory,
        long caughtAtEpochMillis
) { }
