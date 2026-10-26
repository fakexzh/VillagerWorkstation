# VillagerWorkstation 0.4.2

A standalone Paper/Purpur plugin that adds custom production only for **farmers and fishermen**. All other villager professions are left to vanilla Minecraft: this plugin does not collect their dropped items, rename them, or replace their normal job behavior.

## Target environment

- Paper/Purpur API: `26.3.build.195-stable`
- Java: 25
- Build: `gradle clean test build`
- Output JAR: `build/libs/VillagerWorkstation-0.4.2.jar`

GitHub Actions builds the project independently and publishes the JAR as a workflow artifact.

## Setup

1. Place a copper chest within 8 blocks of the workstation.
2. Place a composter for a farmer or a barrel for a fisherman.
3. Run `/vw set` while looking at the workstation.
4. Keep the matching profession villager within 32 blocks so it can be guided back during daytime.
5. For fisherman jobs, keep water within 8 horizontal blocks of the barrel; the water check allows 3 blocks above or below the barrel.
6. Work pauses during the normal night window and resumes during daytime. Work only runs while the workstation chunk is loaded; the plugin never force-loads chunks.
7. Use `/vw list`, `/vw remove`, `/vw reload`, and `/vw help` as needed.

## Custom jobs

| Profession | Workstation | Custom behavior |
|---|---|---|
| Farmer | Composter | Harvests mature wheat, carrots, potatoes and beetroot in the scan area, replants them, and sends all harvest drops directly into a copper chest. A Paper entity-compost event is cancelled at registered farmer workstations, so vanilla farmer AI cannot put items into that composter. |
| Fisherman | Barrel | Simulates one fish catch per work cycle when water is nearby, then stores the fish directly in the copper chest. |

Farmer harvests do not require wheat seeds to randomly drop before the crop is harvested: the plugin replants the crop itself and keeps all drops for storage. If the chest cannot fit the drops, the crop is left unharvested.

Fisherman catches are simulated by the plugin rather than vanilla rod-based fishing. Fish are only added when the chest has space.

**Every other profession stays vanilla.** The plugin no longer collects dropped items for shepherds, fletchers, librarians, cartographers, clerics, armorers, butchers, leatherworkers, masons, toolsmiths, or weaponsmiths. It also no longer changes villager custom names.

## Commands and permission

- `/vw set` — register the composter you're looking at (farmer) or barrel (fisherman), within 6 blocks.
- `/vw remove` — unregister the workstation you're looking at.
- `/vw list` — list saved workstations.
- `/vw reload` — reload configuration and saved workstation records.
- `/vw help` — show commands.

Permission: `villagerworkstation.admin` (default: OP).

## Configuration

- `scan-interval-ticks`: scheduled scan interval (40 ticks ≈ 2 seconds at full TPS).
- `work-radius`: crop/item scan radius and fisherman water-search radius.
- `return-to-work-radius`: daytime search radius for guiding matching villagers back (default: `32`).
- `chest-radius`: copper-chest search radius (default: `8`).
- `farmer-enabled`: enable or disable farmer crop work.
- `fisherman-fishing-enabled`: enable or disable simulated fishing.

## Diagnostics and safety

- The console reports when a workstation has no matching villager, is guiding a worker back, cannot find a copper chest, lacks water, or has a full chest. Messages only appear when the workstation status changes, to avoid log spam.
- No chunk tickets are requested and no chunks are force-loaded; unloaded chunks are skipped.
- A villager cannot be assigned to multiple workstations in the same cycle.
- Workstation records persist in `plugins/VillagerWorkstation/workstations.yml`.
- Test on a copy of your server first, especially if claim/protection plugins are installed. Compatibility depends on your exact Paper/Purpur build and other plugins.
