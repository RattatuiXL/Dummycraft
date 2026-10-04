package dev.dummycraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Builds the flat strategy board when the host starts a match. */
final class WorldMapGenerator {
    private WorldMapGenerator() {}

    static void generate(ServerLevel level, Game game) {
        for (int chunkX = game.mapCenterX - Game.MAP_RADIUS; chunkX <= game.mapCenterX + Game.MAP_RADIUS; chunkX++) {
            for (int chunkZ = game.mapCenterZ - Game.MAP_RADIUS; chunkZ <= game.mapCenterZ + Game.MAP_RADIUS; chunkZ++) {
                String tile = Game.key(chunkX, chunkZ);
                Game.Nation nation = game.ownerOf(tile);
                int minX = chunkX * 16, minZ = chunkZ * 16;
                for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                    int x = minX + dx, z = minZ + dz;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    for (int y = Math.max(top, Game.MAP_SURFACE_Y + 1); y > Game.MAP_SURFACE_Y; y--)
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    if (nation == null) {
                        level.setBlock(new BlockPos(x, Game.MAP_SURFACE_Y - 5, z), Blocks.SAND.defaultBlockState(), 3);
                        for (int y = Game.MAP_SURFACE_Y - 4; y <= Game.MAP_SURFACE_Y; y++)
                            level.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), 3);
                    } else {
                        level.setBlock(new BlockPos(x, Game.MAP_SURFACE_Y - 1, z), Blocks.STONE.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, Game.MAP_SURFACE_Y, z), nationSurface(nation), 3);
                    }
                }
            }
        }
        int centerX = game.mapCenterX * 16 + 8, centerZ = game.mapCenterZ * 16 + 8;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            level.setBlock(new BlockPos(centerX + dx, Game.MAP_SURFACE_Y + 1, centerZ + dz), Blocks.GRAY_CONCRETE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(centerX + dx, Game.MAP_SURFACE_Y + 2, centerZ + dz), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static net.minecraft.world.level.block.state.BlockState nationSurface(Game.Nation nation) {
        return switch (nation.color == null ? "" : nation.color) {
            case "RED" -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case "BLUE" -> Blocks.BLUE_TERRACOTTA.defaultBlockState();
            case "GREEN", "DARK_GREEN" -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case "YELLOW" -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case "LIGHT_PURPLE" -> Blocks.MAGENTA_TERRACOTTA.defaultBlockState();
            case "AQUA" -> Blocks.CYAN_TERRACOTTA.defaultBlockState();
            case "GOLD" -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case "DARK_AQUA" -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case "DARK_PURPLE" -> Blocks.PURPLE_TERRACOTTA.defaultBlockState();
            case "DARK_RED" -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            default -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
        };
    }
}
