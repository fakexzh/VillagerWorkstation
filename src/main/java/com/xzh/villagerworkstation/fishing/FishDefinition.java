package com.xzh.villagerworkstation.fishing;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable species-level constraints. Individual catches store their own dimensions and weight. */
public record FishDefinition(
        String id,
        String displayName,
        FishRarity rarity,
        double minWeightKg,
        double maxWeightKg,
        double typicalWeightKg,
        double minLengthCm,
        double maxLengthCm,
        double minHeightCm,
        double maxHeightCm,
        double minWidthCm,
        double maxWidthCm,
        double basePrice,
        List<String> biomes,
        List<String> waterTypes,
        List<String> dimensions,
        int minWaterDepth,
        int maxWaterDepth,
        String model
) {
    public FishDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(rarity, "rarity");
        biomes = normalize(biomes);
        waterTypes = normalize(waterTypes);
        dimensions = normalize(dimensions);
        Objects.requireNonNull(model, "model");
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid fish id: " + id);
        if (minWeightKg <= 0 || maxWeightKg < minWeightKg
                || typicalWeightKg < minWeightKg || typicalWeightKg > maxWeightKg)
            throw new IllegalArgumentException("Invalid weight range for " + id);
        if (minLengthCm <= 0 || maxLengthCm < minLengthCm
                || minHeightCm <= 0 || maxHeightCm < minHeightCm
                || minWidthCm <= 0 || maxWidthCm < minWidthCm)
            throw new IllegalArgumentException("Invalid physical dimensions for " + id);
        if (basePrice < 0 || minWaterDepth < 0 || maxWaterDepth < minWaterDepth)
            throw new IllegalArgumentException("Invalid price or habitat depth for " + id);
    }

    private static List<String> normalize(List<String> values) {
        return values.stream().filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).distinct().toList();
    }
}
