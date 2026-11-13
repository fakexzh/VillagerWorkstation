# XZH Custom Fishing

This plugin retains the independent XZH custom-fishing and specimen-collection feature. The villager job/workstation system has been removed completely: there are no farmer or fisherman jobs, villager profession checks, crop automation, workstation registrations, copper-chest production, or villager-name labels.

## Target environment

- Paper/Purpur API: 26.3
- Java: 25
- Build: `gradle clean build`

## Features retained

- Custom fish catalogue loaded from `plugins/VillagerWorkstation/fish.yml`.
- Player fishing integration and individually generated fish specimens.
- Unique specimen identity, rarity, weight, physical dimensions and size category.
- Per-player fish collection records.
- Existing fish catalogue data and specimen item format are preserved.

## Commands

- `/vw fish list [page]` — browse the loaded fish catalogue.
- `/vw reload` — reload the fishing config and catalogue (OP permission).
- `/vw help` — show available commands.

Permission: `villagerworkstation.admin` (default: OP).

## Configuration

Only the `custom-fishing` section remains active in `config.yml`. Villager job and workstation settings have been removed.
