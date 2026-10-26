# VillagerWorkstation 0.4.0

A standalone Paper/Purpur plugin with workstation-linked villager jobs and always-visible profession labels above villagers' heads. Farmers harvest mature wheat, carrots, potatoes and beetroot throughout their scan area, replant them, and send remaining drops straight to a copper chest. Fishermen catch one fish per worker cycle when water is nearby. Other professions collect matching, already-dropped item stacks.

## Target environment

- Paper/Purpur API: `26.3.build.195-stable`
- Java: 25
- Build: `gradle clean test build`
- Output JAR: `build/libs/VillagerWorkstation-0.4.0.jar`

GitHub Actions builds the project independently and publishes the JAR as a workflow artifact.

## Setup

1. Place a copper chest within 8 blocks of the workstation.
2. Place the correct workstation block and run `/vw set` while looking at it.
3. Keep a villager with the matching profession within 8 blocks.
4. For fisherman jobs, keep water within 8 horizontal blocks of the barrel; the water check allows 3 blocks above or below the barrel.
5. Work only runs while the station chunk is loaded. The plugin never force-loads chunks.
6. Use `/vw list`, `/vw remove`, `/vw reload`, and `/vw help` as needed.

## Profession workstations

| Profession | Workstation | Job |
|---|---|---|
| Farmer | Composter | Harvests mature wheat, carrots, potatoes and beetroot throughout the scan area, replants them, and stores the remaining harvest in the copper chest. The composter is not used as crop storage. |
| Fisherman | Barrel | If water is nearby, catches one fish each worker cycle and stores it directly in the copper chest. |
| Shepherd | Loom | Collects dropped wool, carpets and shears |
| Fletcher | Fletching table | Collects dropped arrows, feathers, flint, bows, crossbows and sticks |
| Librarian | Lectern | Collects dropped books, paper and ink sacs |
| Cartographer | Cartography table | Collects dropped maps, paper, compasses and glass panes |
| Cleric | Brewing stand | Collects dropped rotten flesh, gold, redstone, glowstone dust, lapis, bottles and potions |
| Armorer | Blast furnace | Collects dropped armor, shields and iron/chain items |
| Butcher | Smoker | Collects dropped meat and fish |
| Leatherworker | Cauldron | Collects dropped leather, leather armor and rabbit items |
| Mason | Stonecutter | Collects dropped stone, bricks, terracotta, quartz and clay balls |
| Toolsmith | Smithing table | Collects dropped pickaxes, shovels, hoes, iron and diamonds |
| Weaponsmith | Grindstone | Collects dropped swords, axes, iron and diamonds |

Fisherman catches are simulated by the plugin rather than vanilla rod-based fishing. Fish are inserted directly into the chest. Other non-farmer professions remain item collectors, not autonomous crafting or gathering AI.

## Commands and permission

- `/vw set` — register the supported workstation block you're looking at (within 6 blocks).
- `/vw remove` — unregister the block you're looking at.
- `/vw list` — list saved workstations.
- `/vw reload` — reload configuration and saved workstation records.
- `/vw help` — show help.

Permission: `villagerworkstation.admin` (default: OP).

## Configuration

- `scan-interval-ticks`: scheduled scan interval (40 ticks ≈ 2 seconds at full TPS).
- `work-radius`: villager and crop/item scan radius.
- `chest-radius`: copper-chest search radius.
- `farmer-enabled`: enable or disable farmer crop work.
- `fisherman-fishing-enabled`: enable or disable simulated fishing.
- `profession-drop-collection-enabled`: enable or disable item collection for the other professions.
- `profession-names.enabled`: show each loaded villager's profession above its head (default: `true`). Enabling this replaces existing villager custom names.

## Safety and performance

- No chunk tickets are requested and no chunks are force-loaded.
- Unloaded chunks are skipped.
- Farmers scan the configured crop area each cycle; crops are only harvested if the drops can fit in the chest.
- Fish are only generated when the chest can fit the catch.
- A villager cannot be assigned to multiple workstations in the same cycle.
- Workstation records persist in `plugins/VillagerWorkstation/workstations.yml`.
- Test on a copy of your server first, especially if claim/protection plugins are installed. Compatibility depends on your exact Paper/Purpur build and other plugins.
