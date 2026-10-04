# DummyCraft

A turn-based, Dummynation-inspired Minecraft strategy mod. Players choose a country on a randomized chunk map, build an army, move units between chunks, and fight for resources.

## Build

Requires Java 25, Gradle 9.4, Fabric Loader and Fabric API for Minecraft 26.1.2. This source archive does not include the Gradle wrapper. Create it with `gradle wrapper --gradle-version 9.4.0`, then use `./gradlew build`. The mod JAR is written to `build/libs/`.

Install the mod and Fabric API on the server and on each client. The unit insignia textures and custom menu/turn items are part of the client resources.

## Start a match

1. The host runs `/start` to generate a randomized map with six countries. The map is centered around the host and each country begins with a capital, surrounding territory, 100 gold, 10 oil and 20 infantry.
2. Players choose a country with `/start as <country>` or `/start random`. A chest menu opens with a beginner guide. The match begins and shuffles turn order after the second country is chosen; more players can join the order afterward.
3. Use the Nation Field Guide item or `/nation menu` to open the chest-style game menu. Use the End Turn Bell item or `/nation endturn` to pass your turn. Income and battles resolve after the full player order.

Use `/start order` to start turn order between nations created manually with `/nation create <name>`.

## Menu and commands

| Action | How |
|---|---|
| Country map | `/start`, then choose with `/start as <name>` or `/start random` |
| Nation menu | Right-click the Nation Field Guide or use `/nation menu` |
| Unit store | Choose Ground, Air or Water forces; left-click buys 1, right-click buys 5 |
| Units | Infantry, tanks, artillery, fighters, bombers and ships; each has separate cost and combat stats |
| Move | Open Deploy on a chunk you own, select a neighboring owned chunk, unit stack and amount |
| Attack | Open Deploy, choose a neighboring chunk and send a selected unit stack; declare war first to attack another country |
| Upgrades | Buy income, ground/air/water attack or defense upgrades in the Upgrades page |
| Stats | View resources, projected income, territory, units and attack/defense power in the Statistics page |
| Map | The chest map shows nearby chunk ownership and resource quality; `/nation map` prints a wider map in chat |
| Borders | `/nation borders` toggles bright surface lines and nation-colored territory markers; change the hue in the menu or with `/nation bordercolor <color>` (leader only) |
| Tutorial | Right-click the field guide, open Tutorial from the menu, or use `/nation tutorial` |

## Economy and combat

There are three resources: gold, oil and infantry manpower. Farmland produces manpower, oilfields produce gold and oil, and plains produce a small amount of each. Each generated chunk receives a random resource type and a poor, standard or rich quality that changes its yield. Capitals produce triple resource yield and a gold bonus. Holding too much land for the size of the army reduces income.

Ground, air and water attack and defense powers are tracked separately. Tanks and artillery use ground power; fighters and bombers use air power; ships use water power. The terrain quality, garrison and purchased upgrades affect the outcome. A chunk can hold several unit types. The menu lets players choose the source chunk, neighboring destination and amount to move or attack.

Unit stacks appear in the world as labeled armor-stand markers with individual pixel-art insignia, using vanilla-visible equipment. Open the Border Line Colour page from the main menu to pick among 12 hues. Borders and territory are bright particle overlays, so the mod marks owned ground without replacing or recoloring the world's blocks.

## Current limitations

- The unit markers are static visual markers, not independently moving or animated vehicle entities. Combat and travel are resolved on turns.
- Ships are a strategic unit category on the chunk map; there is no separate ocean map or naval pathfinding.
- Unselected countries remain passive; there is no computer turn AI yet.
- The project does not yet include alliances, research trees, chest/door protection, or a bespoke client-rendered screen. The main menu uses Minecraft's chest interface and familiar sounds.
- This source has not been build-verified against the included Minecraft/Fabric versions. The original archive lacked a Gradle wrapper, and API differences may still need fixes before the first build.

Game data is saved to `data/dummycraft.json in each world's save folder`.
