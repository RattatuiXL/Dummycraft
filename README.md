# DummyCraft

A Minecraft take on **Dummynation**: every chunk is territory with its own resources, you conquer land
by sending troops and the border shifts gradually, and a country that spreads too thin gets weaker.
Server-side only (Fabric, Minecraft 26.1.2); players don't need the mod installed.

## Build

You need JDK 25. The Gradle wrapper isn't included: either run `gradle wrapper --gradle-version 9.4.0`
here, or copy the `gradle/` folder, `gradlew` and `gradlew.bat` from the Fabric example mod.

    ./gradlew build        # jar ends up in build/libs/
    ./gradlew runServer    # try it locally

Drop the jar and Fabric API into a 26.1.2 Fabric server's `mods/` folder. Check `gradle.properties`
against https://fabricmc.net/develop/ if the versions have moved on.

## How to play

| Command | What it does |
|---|---|
| `/nation create <name>` | Found a nation. The chunk you stand in becomes your capital (needs 2 free chunks around it). |
| `/nation map` | 15x15 chunk map, coloured by nation. |
| `/nation recruit <n>` | Turn gold into troops (5 gold each). |
| `/nation attack <n>` | Send `n` troops to occupy the chunk you stand in. It must border your land. |
| `/nation retreat` | Pull your troops out of the chunk you stand in. |
| `/nation war <nation>` / `peace <nation>` | Declare war / offer or accept peace (leader only). |
| `/nation invite <player>`, `join`, `leave`, `disband` | Membership. |
| `/nation info [nation]`, `/nation list` | Stats and ranking. |

- **Annexation takes time.** An occupation fills 0 to 100% while you watch it in the action bar. Progress per
  second is 0.4 per committed troop minus the defender's resistance.
- **Defenders are spread thin.** Resistance is 0.3 x (defender's reserve troops / defender's chunk count), so a
  big empire with a small army is easy prey. Wilderness only has a small flat resistance.
- **Resources are per chunk.** Each chunk is farmland (manpower), a mine (gold) or plains (a bit of both),
  fixed by its coordinates. The capital produces triple plus a flat gold bonus.
- **Overextension.** Past roughly `8 + troops/4` chunks, income drops 3% per extra chunk (floor 25%).
- **Upkeep.** Troops cost gold. If you run dry, troops desert.
- **Protection.** Outsiders can't break or place blocks in claimed land, unless their nation is at war with the
  owner. Losing your last chunk eliminates the nation; losing your capital moves it.

All numbers are constants at the top of `Game.java` (and in `tick()` / `step()`), so balancing is a one-file job.

## Not done yet

- Only the overworld is claimable. Data is saved to `config/dummycraft.json` (one world per server).
- No alliances, ideologies, or research. No protection for chests and doors, only block break/place.
- The rules in `Game.java` were compiled and simulated outside Minecraft. The Minecraft-facing files
  (`DummyCraft`, `NationCommands`) were only syntax-checked, so expect a few small API-name fixes on the first build.
