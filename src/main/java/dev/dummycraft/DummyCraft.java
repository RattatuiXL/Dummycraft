package dev.dummycraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Entrypoint. Server-side only: vanilla clients can join a server running this mod. */
public class DummyCraft implements ModInitializer {
    public static final String ID = "dummycraft";

    /** The one live game state (replaced when a server starts). */
    public static Game game = new Game();
    private static MinecraftServer server;
    private int ticks;

    @Override
    public void onInitialize() {
        NationItems.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            game = Store.load();
            game.events = new Game.Events() {
                @Override public void toNation(Game.Nation n, String msg) {
                    Component c = Component.literal("[" + n.name + "] ").withStyle(color(n))
                            .append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
                    for (ServerPlayer p : s.getPlayerList().getPlayers()) if (n.members.contains(p.getUUID())) {
                        p.sendSystemMessage(c);
                        p.playSound(net.minecraft.sounds.SoundEvents.UI_TOAST_IN, 0.5f, 1.1f);
                    }
                }
                @Override public void toAll(String msg) {
                    s.getPlayerList().broadcastSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
                    for (ServerPlayer p : s.getPlayerList().getPlayers())
                        p.playSound(net.minecraft.sounds.SoundEvents.UI_TOAST_IN, 0.55f, 1.0f);
                }
            };
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Store.save(game));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NationCommands.register(dispatcher));

        // Territory protection: outsiders cannot break or place blocks in claimed chunks,
        // unless their nation is at war with the owner (raiding is part of the game).
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> allowed(level, player, pos));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player.getItemInHand(hand).getItem() instanceof BlockItem
                    && !allowed(level, player, hit.getBlockPos().relative(hit.getDirection()))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                entity instanceof ArmorStand stand && game.markerIds.containsValue(stand.getUUID().toString())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
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
        if (ticks % 6000 == 0) Store.save(game);          // autosave every 5 minutes
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
        return new DustParticleOptions(rgb, 1.35f);
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

    /** One persistent armor stand per unit stack gives vanilla clients a visible battlefield marker. */
    private void updateUnitMarkers(ServerLevel level) {
        Set<String> wanted = new HashSet<>();
        for (Map.Entry<String, java.util.Map<String, Integer>> chunk : game.garrisons.entrySet()) {
            int cx = Game.cx(chunk.getKey()), cz = Game.cz(chunk.getKey());
            int x = cx * 16 + 8, z = cz * 16 + 8;
            BlockPos base = new BlockPos(x, 0, z);
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
                ArmorStand stand;
                if (found instanceof ArmorStand existing) stand = existing;
                else {
                    stand = new ArmorStand(EntityType.ARMOR_STAND, level);
                    stand.setShowArms(true); stand.setNoGravity(true);
                    stand.setInvulnerable(true); stand.setSilent(true); stand.setCustomNameVisible(true);
                    stand.addTag("dummycraft.unit_marker");
                    level.addFreshEntity(stand);
                    game.markerIds.put(key, stand.getUUID().toString());
                }
                int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double angle = type.ordinal() * (Math.PI / 3.0);
                double spreadX = Math.cos(angle) * 2.5, spreadZ = Math.sin(angle) * 2.5;
                stand.setPos(x + 0.5 + spreadX, ground + (type.domain.equals("air") ? 3.0 : 0.1), z + 0.5 + spreadZ);
                stand.setCustomName(Component.literal(stack.getValue() + " × " + type.title).withStyle(color(nation)));
                stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(markerHelmet(type)));
                stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(markerChest(type)));
                stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(markerWeapon(type)));
            }
        }
        for (String key : new HashSet<>(game.markerIds.keySet())) {
            String chunkKey = key.substring(0, key.indexOf('|'));
            BlockPos pos = new BlockPos(Game.cx(chunkKey) * 16 + 8, 0, Game.cz(chunkKey) * 16 + 8);
            if (!level.hasChunkAt(pos)) continue;
            if (wanted.contains(key)) continue;
            try {
                Entity marker = level.getEntity(UUID.fromString(game.markerIds.get(key)));
                if (marker != null) marker.discard();
            } catch (Exception ignored) { }
            game.markerIds.remove(key);
        }
    }

    private static net.minecraft.world.item.Item markerHelmet(UnitType type) {
        return switch (type) { case INFANTRY -> NationItems.UNIT_INFANTRY; case TANK -> NationItems.UNIT_TANK;
            case ARTILLERY -> NationItems.UNIT_ARTILLERY; case FIGHTER -> NationItems.UNIT_FIGHTER; case BOMBER -> NationItems.UNIT_BOMBER; case SHIP -> NationItems.UNIT_SHIP; };
    }
    private static net.minecraft.world.item.Item markerChest(UnitType type) {
        return switch (type) { case FIGHTER, BOMBER -> Items.ELYTRA; case TANK -> Items.IRON_CHESTPLATE; default -> Items.LEATHER_CHESTPLATE; };
    }
    private static net.minecraft.world.item.Item markerWeapon(UnitType type) {
        return switch (type) { case INFANTRY -> Items.IRON_SWORD; case TANK -> Items.IRON_AXE; case ARTILLERY, FIGHTER -> Items.CROSSBOW;
            case BOMBER -> Items.FIREWORK_ROCKET; case SHIP -> Items.TRIDENT; };
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
