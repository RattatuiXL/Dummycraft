package dev.dummycraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;



/** Entrypoint. Server-side only: vanilla clients can join a server running this mod. */
public class DummyCraft implements ModInitializer {
    public static final String ID = "dummycraft";

    /** The one live game state (replaced when a server starts). */
    public static Game game = new Game();
    
    private int ticks;
    private static final Map<UUID, String> selectedUnitStacks = new HashMap<>();

    @Override
    public void onInitialize() {
        NationItems.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            
            game = Store.load(s);
            setWorldRules(s);
            game.events = new Game.Events() {
                @Override public void toNation(Game.Nation n, String msg) {
                    Component c = Component.literal("[" + n.name + "] ").withStyle(color(n))
                            .append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
                    for (ServerPlayer p : s.getPlayerList().getPlayers()) if (n.members.contains(p.getUUID())) {
                        p.sendSystemMessage(c);
                        p.playSound(net.minecraft.sounds.SoundEvents.UI_TOAST_IN.value(), 0.5f, 1.1f);
                    }
                }
                @Override public void toAll(String msg) {
                    s.getPlayerList().broadcastSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
                    for (ServerPlayer p : s.getPlayerList().getPlayers())
                        p.playSound(net.minecraft.sounds.SoundEvents.UI_TOAST_IN.value(), 0.55f, 1.0f);
                }
            };
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (!game.mapGenerated || player.level().dimension() != Level.OVERWORLD) return;
            Game.Nation nation = game.nationOf(player.getUUID());
            int chunkX = nation == null ? game.mapCenterX : Game.cx(nation.capital);
            int chunkZ = nation == null ? game.mapCenterZ : Game.cz(nation.capital);
            int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
            int y = server.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            player.teleportTo(x + 0.5, y + 0.1, z + 0.5);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Store.save(game, s));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NationCommands.register(dispatcher));

        // Territory protection: outsiders cannot break or place blocks in claimed chunks,
        // unless their nation is at war with the owner (raiding is part of the game).
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> allowed(level, player, pos));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel && hand == InteractionHand.MAIN_HAND) {
                String selected = selectedUnitStacks.get(sp.getUUID());
                if (selected != null) return commandSelectedStack(sp, selected, hit.getBlockPos());
            }
            if (player.getItemInHand(hand).getItem() instanceof BlockItem
                    && !allowed(level, player, hit.getBlockPos().relative(hit.getDirection())))
                return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp && entity instanceof ItemFrame frame) {
                String marker = markerKey(frame);
                if (marker != null) return selectUnitStack(sp, marker);
            }
            // Stop interaction with old mannequin markers saved by earlier mod versions.
            return game.markerIds.containsValue(entity.getUUID().toString())
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });

    }

    private static void setWorldRules(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
    }

    static Game.R generateMap(ServerPlayer host) {
        Game.R result = game.generateMap(host.blockPosition().getX() >> 4, host.blockPosition().getZ() >> 4);
        if (!result.ok()) return result;
        MinecraftServer server = host.level().getServer();
        if (server != null) {
            setWorldRules(server);
            WorldMapGenerator.generate(server.overworld(), game);
            int x = game.mapCenterX * 16 + 8, z = game.mapCenterZ * 16 + 8;
            int y = server.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (ServerPlayer player : server.getPlayerList().getPlayers())
                if (player.level().dimension() == Level.OVERWORLD) player.teleportTo(x + 0.5, y + 0.1, z + 0.5);
            Store.save(game, server);
        }
        return result;
    }

    public static ChatFormatting color(Game.Nation n) {
        try { return ChatFormatting.valueOf(n.color); } catch (Exception e) { return ChatFormatting.WHITE; }
    }

    private static boolean allowed(Level level, Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp) || level.dimension() != Level.OVERWORLD) return true;
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        if (game.canBuild(sp.getUUID(), x, z)) return true;
        Game.Nation o = game.at(x, z);
        sp.sendOverlayMessage(Component.literal("This land belongs to " + (o == null ? "?" : o.name) + ".")
                .withStyle(ChatFormatting.RED));
        return false;
    }

    private void tick(MinecraftServer s) {
        ticks++;
        if (ticks % 20 == 0) game.tick();                 // economy + occupations, once per second
        if (ticks % 6000 == 0) Store.save(game, s);          // autosave every 5 minutes
        if (ticks % 10 == 0)
            for (ServerPlayer p : s.getPlayerList().getPlayers()) { showChunk(p); showBorders(p); }
        if (ticks % 100 == 0) updateUnitMarkers(s.overworld());
    }

    /** Players who turned the particle borders off (on by default; resets on restart). */
    private static final Set<UUID> bordersOff = new HashSet<>();

    static Game.R toggleBorders(ServerPlayer p) {
        boolean nowOn = bordersOff.remove(p.getUUID());   // was off -> now on
        if (!nowOn) bordersOff.add(p.getUUID());
        return Game.ok(nowOn ? "Borders shown." : "Borders hidden.");
    }

    /**
     * Live borders: twice a second, particles are drawn along every territory edge in the
     * 3x3 chunks around the player. Green = your border, flame = border with a nation you are
     * at war with, white sparks = any other border. They update as chunks are captured.
     */
    private void showBorders(ServerPlayer p) {
        if (p.level().dimension() != Level.OVERWORLD || bordersOff.contains(p.getUUID())) return;
        ServerLevel level = (ServerLevel) p.level();
        BlockPos b = p.blockPosition();
        int px = b.getX() >> 4, pz = b.getZ() >> 4;
        Game.Nation mine = game.nationOf(p.getUUID());
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = px - 1; x <= px + 1; x++) {
            for (int z = pz - 1; z <= pz + 1; z++) {
                String k = Game.key(x, z);
                Game.Nation a = game.ownerOf(k);
                if (a == null) continue;
                for (int[] d : dirs) {
                    String nk = Game.key(x + d[0], z + d[1]);
                    Game.Nation o = game.ownerOf(nk);
                    if (o == a) continue;
                    if (o != null && k.compareTo(nk) > 0) continue; // draw a shared edge only once
                    boolean war = mine != null
                            && ((a != mine && mine.enemies.contains(a.id)) || (o != null && o != mine && mine.enemies.contains(o.id)));
                    boolean ownBorder = mine != null && (a == mine || o == mine);
                    ParticleOptions type = war ? ParticleTypes.FLAME
                            : ownBorder ? borderDust(a == mine ? a : o) : ParticleTypes.END_ROD;
                    drawEdge(level, x, z, d, type);
                }
                if (a == mine) drawTerritory(level, x, z, borderDust(a));
            }
        }
    }

    private static ParticleOptions borderDust(Game.Nation nation) {
        String name = nation.borderColor == null ? nation.color : nation.borderColor;
        int rgb = switch (name) {
            case "RED" -> 0xFF5555; case "BLUE" -> 0x5555FF; case "GREEN" -> 0x55FF55;
            case "YELLOW" -> 0xFFFF55; case "LIGHT_PURPLE" -> 0xFF55FF; case "AQUA" -> 0x55FFFF;
            case "GOLD" -> 0xFFAA00; case "DARK_GREEN" -> 0x00AA00; case "DARK_AQUA" -> 0x00AAAA;
            case "DARK_PURPLE" -> 0xAA00AA; case "DARK_RED" -> 0xAA0000; default -> 0xFFFFFF;
        };
        // Brighten the nation's chosen hue so the line stands out from its map colour.
        float r = (((rgb >> 16) & 255) / 255f) * 0.58f + 0.42f;
        float g = (((rgb >> 8) & 255) / 255f) * 0.58f + 0.42f;
        float b = ((rgb & 255) / 255f) * 0.58f + 0.42f;
        int brightR = (int) (((rgb >> 16) & 255) * 0.52f + 255 * 0.48f);
        int brightG = (int) (((rgb >> 8) & 255) * 0.52f + 255 * 0.48f);
        int brightB = (int) ((rgb & 255) * 0.52f + 255 * 0.48f);
        int brightRgb = (brightR << 16) | (brightG << 8) | brightB;
        return new DustParticleOptions(brightRgb, 1.6f);
    }

    private static void drawEdge(ServerLevel level, int x, int z, int[] d, ParticleOptions type) {
        for (int i = 0; i <= 16; i += 2) {
            double wx, wz;
            int bx, bz;
            if (d[0] != 0) { bx = (d[0] > 0 ? x * 16 + 16 : x * 16); bz = z * 16 + i; wx = bx + 0.5; wz = bz + 0.5; }
            else           { bz = (d[1] > 0 ? z * 16 + 16 : z * 16); bx = x * 16 + i; wx = bx + 0.5; wz = bz + 0.5; }
            double ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 0.08;
            level.sendParticles(type, wx, ground, wz, 1, 0, 0, 0, 0);
            level.sendParticles(type, wx, ground + 0.65, wz, 1, 0, 0, 0, 0);
        }
    }

    /** Sparse colored surface flecks mark owned ground without changing the actual terrain blocks. */
    private static void drawTerritory(ServerLevel level, int chunkX, int chunkZ, ParticleOptions color) {
        for (int dx = 2; dx < 16; dx += 4) for (int dz = 2; dz < 16; dz += 4) {
            int x = chunkX * 16 + dx, z = chunkZ * 16 + dz;
            double y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 0.06;
            level.sendParticles(color, x + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
        }
    }

    /** Clickable banner frames show each deployed stack and act as its right-click selection marker. */
    private void updateUnitMarkers(ServerLevel level) {
        Set<String> wanted = new HashSet<>();
        for (Map.Entry<String, Map<String, Integer>> chunk : game.garrisons.entrySet()) {
            int cx = Game.cx(chunk.getKey()), cz = Game.cz(chunk.getKey());
            BlockPos base = new BlockPos(cx * 16 + 8, 0, cz * 16 + 8);
            if (!level.hasChunkAt(base)) continue;
            Game.Nation nation = game.ownerOf(chunk.getKey());
            if (nation == null) continue;
            for (Map.Entry<String, Integer> stack : chunk.getValue().entrySet()) {
                UnitType type = UnitType.parse(stack.getKey());
                if (type == null || stack.getValue() <= 0) continue;
                String key = chunk.getKey() + "|" + type.id();
                wanted.add(key);
                UUID id = null;
                try { id = UUID.fromString(game.markerIds.get(key)); } catch (Exception ignored) { }
                Entity found = id == null ? null : level.getEntity(id);
                ItemFrame flag;
                if (found instanceof ItemFrame existing) flag = existing;
                else {
                    if (found != null) found.discard();
                    int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            cx * 16 + 8, cz * 16 + 8);
                    int localX = 2 + (type.ordinal() % 3) * 5;
                    int localZ = 2 + (type.ordinal() / 3) * 10;
                    BlockPos anchor = new BlockPos(cx * 16 + localX, ground, cz * 16 + localZ);
                    flag = new ItemFrame(EntityType.ITEM_FRAME, level, anchor, Direction.SOUTH);
                    flag.setFixed(true);
                    flag.setInvulnerable(true);
                    flag.setSilent(true);
                    flag.setCustomNameVisible(true);
                    flag.addTag("dummycraft.unit_flag");
                    level.addFreshEntity(flag);
                    game.markerIds.put(key, flag.getUUID().toString());
                }
                flag.setItem(ornateUnitBanner(level, nation, key), false);
                flag.setCustomName(Component.literal(stack.getValue() + " × " + type.title).withStyle(color(nation)));
            }
        }
        for (String key : new HashSet<>(game.markerIds.keySet())) {
            String chunkKey = key.substring(0, key.indexOf('|'));
            BlockPos pos = new BlockPos(Game.cx(chunkKey) * 16 + 8, 0, Game.cz(chunkKey) * 16 + 8);
            if (!level.hasChunkAt(pos) || wanted.contains(key)) continue;
            try {
                Entity marker = level.getEntity(UUID.fromString(game.markerIds.get(key)));
                if (marker != null) marker.discard();
            } catch (Exception ignored) { }
            game.markerIds.remove(key);
        }
    }

    private static String markerKey(Entity entity) {
        String id = entity.getUUID().toString();
        for (Map.Entry<String, String> marker : game.markerIds.entrySet())
            if (id.equals(marker.getValue())) return marker.getKey();
        return null;
    }

    private static InteractionResult selectUnitStack(ServerPlayer player, String marker) {
        int split = marker.indexOf('|');
        if (split <= 0) return InteractionResult.PASS;
        String chunk = marker.substring(0, split);
        UnitType type = UnitType.parse(marker.substring(split + 1));
        Game.Nation nation = game.nationOf(player.getUUID());
        if (nation == null || !nation.id.equals(game.owner.get(chunk)) || type == null) {
            player.sendSystemMessage(Component.literal("You can only command your own troop flags.").withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }
        int amount = game.unitsAt(chunk).getOrDefault(type.id(), 0);
        if (amount <= 0) return InteractionResult.SUCCESS;
        selectedUnitStacks.put(player.getUUID(), marker);
        player.sendSystemMessage(Component.literal("Selected " + amount + " " + type.title + " at " + chunk.replace(",", ", ")
                + ". Right-click a destination; crouch-right-click spreads ground troops along the route.").withStyle(ChatFormatting.AQUA));
        player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.2f);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult commandSelectedStack(ServerPlayer player, String marker, BlockPos clicked) {
        int split = marker.indexOf('|');
        if (split <= 0) { selectedUnitStacks.remove(player.getUUID()); return InteractionResult.PASS; }
        String from = marker.substring(0, split);
        UnitType type = UnitType.parse(marker.substring(split + 1));
        Game.Nation nation = game.nationOf(player.getUUID());
        if (nation == null || type == null) { selectedUnitStacks.remove(player.getUUID()); return InteractionResult.FAIL; }
        int amount = game.unitsAt(from).getOrDefault(type.id(), 0);
        int targetX = clicked.getX() >> 4, targetZ = clicked.getZ() >> 4;
        Game.Nation targetNation = game.at(targetX, targetZ);
        Game.R result = targetNation == nation
                ? game.moveUnitsAlongLine(nation, type, amount, from, targetX, targetZ, player.isShiftKeyDown())
                : game.attackUnits(nation, type, amount, from, targetX, targetZ);
        player.sendSystemMessage(Component.literal(result.msg()).withStyle(result.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
        player.playSound(result.ok() ? net.minecraft.sounds.SoundEvents.UI_TOAST_IN.value()
                : net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.65f, result.ok() ? 1.0f : 0.8f);
        if (result.ok()) selectedUnitStacks.remove(player.getUUID());
        return result.ok() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static ItemStack ornateUnitBanner(ServerLevel level, Game.Nation nation, String marker) {
        ItemStack banner = new ItemStack(nationBannerItem(nation.color));
        String[] patterns = {"stripe_middle", "cross", "circle", "rhombus", "border", "triangle_top", "diagonal_left", "flower"};
        Random random = new Random(game.mapSeed ^ marker.hashCode());
        var registry = level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        for (int i = 0; i < 2; i++) {
            ResourceKey<BannerPattern> key = ResourceKey.create(Registries.BANNER_PATTERN,
                    Identifier.fromNamespaceAndPath("minecraft", patterns[random.nextInt(patterns.length)]));
            layers.add(registry.getOrThrow(key), i == 0 ? DyeColor.WHITE : DyeColor.BLACK);
        }
        banner.set(DataComponents.BANNER_PATTERNS, layers.build());
        return banner;
    }

    private static net.minecraft.world.item.Item nationBannerItem(String color) {
        return switch (color == null ? "" : color) {
            case "RED" -> Items.RED_BANNER;
            case "BLUE" -> Items.BLUE_BANNER;
            case "GREEN", "DARK_GREEN" -> Items.GREEN_BANNER;
            case "YELLOW" -> Items.YELLOW_BANNER;
            case "LIGHT_PURPLE" -> Items.MAGENTA_BANNER;
            case "AQUA" -> Items.CYAN_BANNER;
            case "GOLD" -> Items.ORANGE_BANNER;
            case "DARK_AQUA" -> Items.LIGHT_BLUE_BANNER;
            case "DARK_PURPLE" -> Items.PURPLE_BANNER;
            case "DARK_RED" -> Items.BROWN_BANNER;
            default -> Items.WHITE_BANNER;
        };
    }



    /** Action bar: who owns the chunk you stand in, its resource type, and occupation progress. */
    private void showChunk(ServerPlayer p) {
        if (p.level().dimension() != Level.OVERWORLD) return;
        BlockPos b = p.blockPosition();
        int x = b.getX() >> 4, z = b.getZ() >> 4;
        Game.Nation o = game.at(x, z);
        MutableComponent c;
        if (o == null) {
            c = Component.literal("Wilderness (" + game.terrainName(Game.key(x, z)) + ")").withStyle(ChatFormatting.GRAY);
        } else {
            c = Component.literal(o.name).withStyle(color(o), ChatFormatting.BOLD)
                    .append(Component.literal(" (" + game.terrainName(Game.key(x, z)) + ")").withStyle(ChatFormatting.GRAY));
        }
        Game.Op op = game.opAt(x, z);
        if (op != null) {
            Game.Nation att = game.nations.get(op.attacker);
            c.append(Component.literal("  \u2694 " + (att == null ? "?" : att.name) + " " + (int) Math.max(0, op.progress) + "%")
                    .withStyle(ChatFormatting.RED));
        }
        p.sendOverlayMessage(c);
    }
}
