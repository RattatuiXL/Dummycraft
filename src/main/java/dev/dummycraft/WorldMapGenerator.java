package dev.dummycraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/** Builds the flat strategy board: connected country land, irregular coasts and readable flags. */
final class WorldMapGenerator {
    private WorldMapGenerator() {}

    static void generate(ServerLevel level, Game game) {
        int minChunkX = game.mapCenterX - Game.MAP_RADIUS;
        int maxChunkX = game.mapCenterX + Game.MAP_RADIUS;
        int minChunkZ = game.mapCenterZ - Game.MAP_RADIUS;
        int maxChunkZ = game.mapCenterZ + Game.MAP_RADIUS;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                String tile = Game.key(chunkX, chunkZ);
                Game.Nation nation = game.ownerOf(tile);
                var surface = nation == null ? Blocks.WATER.defaultBlockState() : nationSurface(nation);
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
                        level.setBlock(new BlockPos(x, Game.MAP_SURFACE_Y, z), surface, 3);
                    }
                }
                if (nation != null) {
                    Block flag;
                    if (tile.equals(nation.capital)) flag = nationBanner(nation.color);
                    else if (nation.cities != null && nation.cities.contains(tile)) flag = Blocks.CYAN_BANNER;
                    else flag = switch (game.terrainType(tile)) {
                        case 0 -> Blocks.YELLOW_BANNER; // farms
                        case 1 -> Blocks.BLACK_BANNER;  // oil
                        default -> Blocks.GRAY_BANNER;  // nuclear power
                    };
                    BlockPos flagPos = new BlockPos(minX + 8, Game.MAP_SURFACE_Y + 1, minZ + 8);
                    placeOrnateFlag(level, flagPos, flag, game.mapSeed ^ tile.hashCode());
                }
            }
        }
        // Small neutral landing pad so players spawn above the board, not in the sea.
        int centerX = game.mapCenterX * 16 + 8, centerZ = game.mapCenterZ * 16 + 8;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            level.setBlock(new BlockPos(centerX + dx, Game.MAP_SURFACE_Y + 1, centerZ + dz), Blocks.GRAY_CONCRETE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(centerX + dx, Game.MAP_SURFACE_Y + 2, centerZ + dz), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void placeOrnateFlag(ServerLevel level, BlockPos pos, Block block, long seed) {
        level.setBlock(pos, block.defaultBlockState(), 3);
        if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity banner)) return;
        String[] ornaments = {"stripe_middle", "cross", "circle", "rhombus", "border", "triangle_top", "diagonal_left", "flower"};
        Random random = new Random(seed);
        var registry = level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        for (int i = 0; i < 2; i++) {
            ResourceKey<BannerPattern> key = ResourceKey.create(Registries.BANNER_PATTERN,
                    Identifier.fromNamespaceAndPath("minecraft", ornaments[random.nextInt(ornaments.length)]));
            Holder<BannerPattern> pattern = registry.getOrThrow(key);
            layers.add(pattern, i == 0 ? DyeColor.WHITE : DyeColor.BLACK);
        }
        ItemStack stack = new ItemStack(block.asItem());
        stack.set(DataComponents.BANNER_PATTERNS, layers.build());
        banner.applyComponentsFromItemStack(stack);
        banner.setChanged();
    }

    private static Block nationBanner(String color) {
        return switch (color == null ? "" : color) {
            case "RED" -> Blocks.RED_BANNER;
            case "BLUE" -> Blocks.BLUE_BANNER;
            case "GREEN", "DARK_GREEN" -> Blocks.GREEN_BANNER;
            case "YELLOW" -> Blocks.YELLOW_BANNER;
            case "LIGHT_PURPLE" -> Blocks.MAGENTA_BANNER;
            case "AQUA" -> Blocks.CYAN_BANNER;
            case "GOLD" -> Blocks.ORANGE_BANNER;
            case "DARK_AQUA" -> Blocks.LIGHT_BLUE_BANNER;
            case "DARK_PURPLE" -> Blocks.PURPLE_BANNER;
            case "DARK_RED" -> Blocks.BROWN_BANNER;
            default -> Blocks.WHITE_BANNER;
        };
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