package dev.dummycraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;

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
        sp.displayClientMessage(Component.literal("This land belongs to " + (o == null ? "?" : o.name) + ".")
                .withStyle(ChatFormatting.RED), true);
        return false;
    }

    private void tick(MinecraftServer s) {
        ticks++;
        if (ticks % 20 == 0) game.tick();                 // economy + occupations, once per second
        if (ticks % 6000 == 0) Store.save(game);          // autosave every 5 minutes
        if (ticks % 10 == 0)
            for (ServerPlayer p : s.getPlayerList().getPlayers()) showChunk(p);
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
        p.displayClientMessage(c, true);
    }
}
