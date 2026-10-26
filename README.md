# VillagerWorkstation 0.1.0

A standalone Paper/Purpur plugin project for profession-based villager work. This first prototype implements a Farmer workstation and is kept in its own folder/build so it does not change the economy plugin's source or JAR.

## Target environment

- Paper/Purpur API: `26.3.build.195-stable`
- Java: 25
- Build: `gradle clean test build` from the repository root
- Output JAR: `build/libs/VillagerWorkstation-0.1.0.jar`

GitHub Actions builds this project independently and publishes the JAR as a workflow artifact.

## First job: Farmer

1. Register a composter using `/vw set` while looking at it.
2. Have a villager with the Farmer profession within the configured work radius.
3. Place a supported copper chest within the configured chest radius.
4. When the workstation chunk is loaded, the plugin periodically checks nearby loaded blocks for fully mature wheat, carrots, potatoes, and beetroot.
5. It harvests only when the crop's vanilla drops include the correct replanting item and the copper chest can fit the remaining drops.
6. The crop is replanted immediately at age zero, and the remaining drops are inserted into the copper chest inventory. No normal crop-drop entities are spawned.

Supported copper chest material names are identified by the `COPPER_CHEST` suffix to accommodate copper oxidation and waxing variants exposed by the server API.

## Commands

- `/vw set` — register the composter you're looking at (within 6 blocks).
- `/vw remove` — unregister the block you're looking at.
- `/vw list` — list saved workstations.
- `/vw reload` — reload configuration and saved workstations.
- `/vw help` — show help.

Permission: `villagerworkstation.admin` (default: OP).

## Performance safeguards

- No chunk tickets are requested and no chunks are force-loaded.
- A station is skipped if its chunk is unloaded.
- Crop/chest scans skip any unloaded chunk they would cross.
- Work runs on a configurable scheduled interval, not every tick.
- Each workstation harvests a limited number of crops per cycle.
- One villager cannot be assigned to more than one workstation in the same work cycle.
- Workstation records persist in `plugins/VillagerWorkstation/workstations.yml`.
- If no copper chest exists or it has insufficient room, crops are left untouched.

## Configuration

`config.yml` controls scan interval, work radius, chest radius, crop limit per cycle, and the farmer enable toggle. The interval is in server ticks (20 ticks = approximately one second at full server tick rate).

## Important limitations before production use

- This is the initial Farmer prototype, not a claim of full live-server verification.
- It currently supports wheat, carrots, potatoes, and beetroot only.
- It does not simulate offline work or force unloaded farms to run.
- It does not yet implement other professions; the project should add each job as a separate handler.
- Claim/protection plugins differ in how they handle automated block changes. Test on a copy of the server with your actual protection plugins before allowing public use.
- Copper chest block-state/inventory behavior must be confirmed against the exact Paper/Purpur build used by the server.
- Villagers are located near the workstation; the plugin does not currently pathfind villagers to individual crops.

## Development checklist

- [x] Standalone Gradle project and plugin metadata
- [x] Scheduled work loop with loaded-chunk checks
- [x] Persistent composter workstation registry
- [x] Mature crop detection and same-crop replanting
- [x] Copper-chest-only storage and capacity pre-check
- [x] Configurable work limits and GitHub Actions build
- [ ] Confirm CI build against the server's exact Paper/Purpur API
- [ ] Test harvesting and storage in a disposable server world
- [ ] Test compatibility with claims/protection plugins
- [ ] Add additional profession handlers after the Farmer behavior is verified
