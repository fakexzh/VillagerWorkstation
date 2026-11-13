package com.xzh.villagerworkstation.fishing;

import org.bukkit.block.Biome;
import java.util.Map;

/** XZH-owned biome pools. Weights are relative only within the active biome pool. */
final class FishHabitatPools {
    private FishHabitatPools() {}

    static Map<String, Integer> forBiome(Biome biome) {
        String name = biome.name();
        if (name.equals("RIVER") || name.equals("FROZEN_RIVER")) return RIVER;
        if (name.equals("SWAMP") || name.equals("MANGROVE_SWAMP")) return SWAMP;
        if (name.equals("WARM_OCEAN") || name.equals("LUKEWARM_OCEAN")
                || name.equals("DEEP_LUKEWARM_OCEAN")) return WARM_OCEAN;
        if (name.equals("COLD_OCEAN") || name.equals("DEEP_COLD_OCEAN")
                || name.equals("FROZEN_OCEAN") || name.equals("DEEP_FROZEN_OCEAN")) return COLD_OCEAN;
        if (name.equals("OCEAN") || name.equals("DEEP_OCEAN")) return DEEP_OCEAN;
        return Map.of();
    }

    static final Map<String, Integer> RIVER = Map.of(
            "catfish", 25, "king_of_the_pond", 25, "princess_fish", 15,
            "arowana", 15, "eel", 10, "sturgeon", 10);
    static final Map<String, Integer> SWAMP = Map.of(
            "alpha_spiderfish", 35, "mystery_fish", 25, "saddled_bichir", 25, "eel", 15);
    static final Map<String, Integer> WARM_OCEAN = Map.ofEntries(
            Map.entry("flarefin_koi", 20), Map.entry("ocean_sunfish", 15),
            Map.entry("rabbitfish", 15), Map.entry("wyverntail", 15),
            Map.entry("red_snapper", 12), Map.entry("tropical_barracuda", 10),
            Map.entry("dorado", 6), Map.entry("moray_eel", 5), Map.entry("unicorn_fish", 2));
    static final Map<String, Integer> DEEP_OCEAN = Map.ofEntries(
            Map.entry("deadly_fin", 16), Map.entry("seven_finned_death", 14),
            Map.entry("oarfish", 10), Map.entry("the_sounding", 12),
            Map.entry("the_beastfish", 11), Map.entry("pain_eel", 9),
            Map.entry("touchy_fish", 7), Map.entry("rusty_marlin", 5),
            Map.entry("football_fish", 5), Map.entry("laviathan_fry", 1),
            Map.entry("mystery_fish", 10));
    static final Map<String, Integer> COLD_OCEAN = Map.of(
            "oarfish", 35, "sturgeon", 30, "olive_flounder", 20, "pain_eel", 10, "deadly_fin", 5);
    static final Map<String, Integer> SPECIAL_ROD_WATER = Map.of("netherwrasse", 85, "volcanic_bass", 15);
}
