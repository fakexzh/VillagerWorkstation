package com.xzh.villagerworkstation.fishing;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Generates individual specimens with a triangular weight distribution around typical size. */
public final class FishGenerator {
    public FishInstance generate(FishDefinition definition) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double weight = triangular(random, definition.minWeightKg(),
                definition.maxWeightKg(), definition.typicalWeightKg());
        double normalized = (weight - definition.minWeightKg())
                / Math.max(0.000001, definition.maxWeightKg() - definition.minWeightKg());
        double length = interpolate(definition.minLengthCm(), definition.maxLengthCm(), normalized)
                * random.nextDouble(0.96, 1.041);
        double height = interpolate(definition.minHeightCm(), definition.maxHeightCm(), normalized)
                * random.nextDouble(0.96, 1.041);
        double width = interpolate(definition.minWidthCm(), definition.maxWidthCm(), normalized)
                * random.nextDouble(0.96, 1.041);
        double sizePosition = (weight - definition.minWeightKg())
                / Math.max(0.000001, definition.maxWeightKg() - definition.minWeightKg());
        String category = sizePosition < (1.0 / 3.0) ? "SMALL"
                : sizePosition < (2.0 / 3.0) ? "MEDIUM" : "LARGE";
        return new FishInstance(UUID.randomUUID(), definition.id(), definition.rarity(),
                round(weight), round(length), round(height), round(width), category,
                System.currentTimeMillis());
    }

    private double triangular(ThreadLocalRandom random, double min, double max, double mode) {
        double u = random.nextDouble();
        double split = (mode - min) / (max - min);
        return u < split
                ? min + Math.sqrt(u * (max - min) * (mode - min))
                : max - Math.sqrt((1.0 - u) * (max - min) * (max - mode));
    }

    private double interpolate(double min, double max, double ratio) {
        return min + (max - min) * Math.max(0.0, Math.min(1.0, ratio));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
