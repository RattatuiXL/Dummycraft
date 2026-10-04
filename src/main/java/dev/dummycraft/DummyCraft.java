package dev.dummycraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;

import java.util.HashSet;
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
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            game = Store.load();
            game.events = new Game.Events() {
                @Override public void toNation(Game.Nation n, String msg) {
                    Component c = Component.literal("[" + n.name + "] ").withStyle(color(n))
                            .append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
                    for (ServerPlayer p : s.getPlayerList().getPlayers())
                        if (n.members.contains(p.getUUID())) p.sendSystemMessage(c);
                }
                @Override public void toAll(String msg) {
                    s.getPlayerList().broadcastSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
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
                            : ownBorder ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.END_ROD;
                    drawEdge(level, x, z, d, type, b.getY());
                }
            }
        }
    }

    private static void drawEdge(ServerLevel level, int x, int z, int[] d, ParticleOptions type, double y) {
        for (int i = 0; i <= 16; i += 2) {
            double wx, wz;
            if (d[0] != 0) { wx = d[0] > 0 ? x * 16 + 16 : x * 16; wz = z * 16 + i; }
            else           { wz = d[1] > 0 ? z * 16 + 16 : z * 16; wx = x * 16 + i; }
            level.sendParticles(type, wx, y + 0.5, wz, 1, 0, 0, 0, 0);
            level.sendParticles(type, wx, y + 2.0, wz, 1, 0, 0, 0, 0);
        }
    }

    /** Action bar: who owns the chunk you stand in, its resource type, and occupation progress. */
    private void showChunk(ServerPlayer p) {
        if (p.level().dimension() != Level.OVERWORLD) return;
        BlockPos b = p.blockPosition();
        int x = b.getX() >> 4, z = b.getZ() >> 4;
        Game.Nation o = game.at(x, z);
        MutableComponent c;
        if (o == null) {
            c = Component.literal("Wilderness (" + Game.typeName(Game.key(x, z)) + ")").withStyle(ChatFormatting.GRAY);
        } else {
            c = Component.literal(o.name).withStyle(color(o), ChatFormatting.BOLD)
                    .append(Component.literal(" (" + Game.typeName(Game.key(x, z)) + ")").withStyle(ChatFormatting.GRAY));
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
