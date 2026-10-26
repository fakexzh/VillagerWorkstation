# VillagerWorkstation 0.3.0

A standalone Paper/Purpur plugin with workstation-linked villager jobs and always-visible profession labels above villagers' heads. It supports all 13 standard villager professions. Farmer villagers harvest and replant mature crops; the other 12 professions collect matching, already-dropped item stacks near their workstation into a nearby copper chest. These collectors do not break blocks or harm entities.

## Target environment

- Paper/Purpur API: `26.3.build.195-stable`
- Java: 25
- Build: `gradle clean test build`
- Output JAR: `build/libs/VillagerWorkstation-0.3.0.jar`

GitHub Actions builds the project independently and publishes the JAR as a workflow artifact.

## Setup

1. Put a copper chest near the workstation (within the configured chest radius).
2. Place the correct workstation block and run `/vw set` while looking at it.
3. Keep a villager with the matching profession within the configured work radius.
4. Work only runs while the station chunk is loaded. The plugin never force-loads chunks.
5. Use `/vw list`, `/vw remove`, `/vw reload`, and `/vw help` as needed.

## Profession workstations

| Profession | Workstation | Job |
|---|---|---|
| Farmer | Composter | Harvests and replants mature wheat, carrots, potatoes and beetroot; sends remaining drops to the copper chest |
| Fisherman | Barrel | Collects dropped fish and fishing rods |
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

The non-farmer professions are item collectors rather than autonomous crafting or gathering AI: they only pick up matching item entities already on the ground. They do not generate items, kill mobs, or mine blocks. Collection can be disabled with `profession-drop-collection-enabled: false`.

## Commands and permission

- `/vw set` — register the supported workstation block you're looking at (within 6 blocks).
- `/vw remove` — unregister the block you're looking at.
- `/vw list` — list saved workstations.
- `/vw reload` — reload configuration and saved workstation records.
- `/vw help` — show help.

Permission: `villagerworkstation.admin` (default: OP).

## Configuration

- `scan-interval-ticks`: scheduled scan interval (20 ticks ≈ 1 second at full TPS).
- `work-radius`: villager and crop/item scan radius.
- `chest-radius`: search radius for the output copper chest.
- `max-crops-per-cycle`: maximum farmer crops per scan.
- `max-items-per-cycle`: maximum item stacks collected per scan for non-farmer jobs.
- `farmer-enabled`: enable or disable farmer crop work.
- `profession-drop-collection-enabled`: enable or disable item collection for the other professions.
- `profession-names.enabled`: show each loaded villager's profession above its head (default: `true`). Enabling this replaces existing villager custom names.

## Safety and performance

- No chunk tickets are requested and no chunks are force-loaded.
- Unloaded chunks are skipped.
- Each workstation does bounded work per cycle.
- A villager cannot be assigned to multiple workstations in the same cycle.
- Workstation records persist in `plugins/VillagerWorkstation/workstations.yml`.
- If no copper chest exists or it cannot fit an item stack, that item is left on the ground.
- Test on a copy of your server first, especially if claim/protection plugins are installed. Compatibility depends on your exact Paper/Purpur build and other plugins.
