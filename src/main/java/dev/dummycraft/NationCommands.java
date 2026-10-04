package dev.dummycraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Function;

/** /nation ... command tree. All rules live in {@link Game}; this only translates input and output. */
final class NationCommands {
    private static final int NATION = 1, LEADER = 2, OVERWORLD = 4;

    private interface Body {
        /** Return a result to print, or null if the body already printed its own output. */
        Game.R run(ServerPlayer p, Game.Nation n, CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) ->
            SharedSuggestionProvider.suggest(DummyCraft.game.nations.values().stream().map(n -> n.name), b);

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nation")
            .executes(c -> run(c, 0, (p, n, ctx) -> { help(p); return null; }))

            .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> run(c, OVERWORLD, (p, n, ctx) ->
                    DummyCraft.game.create(p.getUUID(), StringArgumentType.getString(ctx, "name"), cx(p), cz(p))))))

            .then(Commands.literal("info")
                .executes(c -> run(c, NATION, (p, n, ctx) -> { info(p, n); return null; }))
                .then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                    .executes(c -> run(c, 0, (p, n, ctx) -> withTarget(ctx, t -> { info(p, t); return null; })))))

            .then(Commands.literal("list").executes(c -> run(c, 0, (p, n, ctx) -> { list(p); return null; })))
            .then(Commands.literal("map").executes(c -> run(c, OVERWORLD, (p, n, ctx) -> { map(p); return null; })))
            .then(Commands.literal("borders").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.toggleBorders(p))))

            .then(Commands.literal("recruit").then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                .executes(c -> run(c, NATION, (p, n, ctx) ->
                    DummyCraft.game.recruit(n, IntegerArgumentType.getInteger(ctx, "amount"))))))

            .then(Commands.literal("attack").then(Commands.argument("troops", IntegerArgumentType.integer(1, 1000))
                .executes(c -> run(c, NATION | OVERWORLD, (p, n, ctx) ->
                    DummyCraft.game.attack(n, cx(p), cz(p), IntegerArgumentType.getInteger(ctx, "troops"))))))

            .then(Commands.literal("retreat")
                .executes(c -> run(c, NATION | OVERWORLD, (p, n, ctx) -> DummyCraft.game.retreat(n, cx(p), cz(p)))))

            .then(Commands.literal("war").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.war(n, t))))))

            .then(Commands.literal("peace").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.peace(n, t))))))

            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    Game.R r = DummyCraft.game.invite(n, target.getUUID());
                    if (r.ok()) target.sendSystemMessage(Component.literal(
                            n.name + " invited you. Accept with /nation join " + n.name).withStyle(ChatFormatting.GOLD));
                    return r;
                }))))

            .then(Commands.literal("join").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, 0, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.join(p.getUUID(), t))))))

            .then(Commands.literal("leave").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.leave(p.getUUID()))))
            .then(Commands.literal("disband").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.disband(p.getUUID()))))
        );
    }

    // ------------------------------------------------------------ plumbing

    private static int run(CommandContext<CommandSourceStack> c, int flags, Body body) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Game.Nation n = DummyCraft.game.nationOf(p.getUUID());
        Game.R r;
        if ((flags & NATION) != 0 && n == null) r = Game.err("You are not in a nation. Use /nation create <name>.");
        else if ((flags & LEADER) != 0 && !n.leader.equals(p.getUUID())) r = Game.err("Only the nation leader can do that.");
        else if ((flags & OVERWORLD) != 0 && p.level().dimension() != Level.OVERWORLD) r = Game.err("Nations only exist in the overworld.");
        else r = body.run(p, n, c);
        if (r != null) p.sendSystemMessage(Component.literal(r.msg()).withStyle(r.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
        return r != null && r.ok() ? 1 : 0;
    }

    private static Game.R withTarget(CommandContext<CommandSourceStack> ctx, Function<Game.Nation, Game.R> f) {
        Game.Nation t = DummyCraft.game.byName(StringArgumentType.getString(ctx, "nation"));
        return t == null ? Game.err("No such nation.") : f.apply(t);
    }

    private static int cx(ServerPlayer p) { return p.blockPosition().getX() >> 4; }
    private static int cz(ServerPlayer p) { return p.blockPosition().getZ() >> 4; }

    // ------------------------------------------------------------ output

    private static void help(ServerPlayer p) {
        String[] lines = {
            "/nation create <name>  - found a nation; your current chunk becomes the capital",
            "/nation map            - territory map around you",
            "/nation borders        - show or hide particle borders (on by default)",
            "/nation info [nation]  - stats; /nation list - ranking",
            "/nation recruit <n>    - turn gold into troops (5 gold each)",
            "/nation attack <n>     - send n troops to occupy the chunk you stand in (must border your land)",
            "/nation retreat        - pull your troops out of this chunk",
            "/nation war|peace <nation>, invite <player>, join <nation>, leave, disband"
        };
        for (String l : lines) p.sendSystemMessage(Component.literal(l).withStyle(ChatFormatting.YELLOW));
    }

    private static void info(ServerPlayer p, Game.Nation n) {
        Game g = DummyCraft.game;
        int chunks = g.chunksOf(n);
        p.sendSystemMessage(Component.literal("== " + n.name + " ==").withStyle(DummyCraft.color(n), ChatFormatting.BOLD));
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Chunks %d (efficiency %d%%) | Gold %d | Troops %d (+%d deployed) | Score %d",
                chunks, Math.round(g.efficiency(n, chunks) * 100), (int) n.gold, (int) n.troops,
                (int) g.deployed(n), (int) g.score(n))));
        StringBuilder war = new StringBuilder();
        for (String id : n.enemies) { Game.Nation e = g.nations.get(id); if (e != null) war.append(war.length() > 0 ? ", " : "").append(e.name); }
        p.sendSystemMessage(Component.literal("Members: " + n.members.size() + " | At war with: " + (war.length() == 0 ? "nobody" : war))
                .withStyle(ChatFormatting.GRAY));
    }

    private static void list(ServerPlayer p) {
        Game g = DummyCraft.game;
        List<Game.Nation> sorted = new ArrayList<>(g.nations.values());
        sorted.sort(Comparator.comparingDouble((Game.Nation n) -> g.score(n)).reversed());
        if (sorted.isEmpty()) { p.sendSystemMessage(Component.literal("No nations yet. /nation create <name>").withStyle(ChatFormatting.GRAY)); return; }
        int i = 1;
        for (Game.Nation n : sorted)
            p.sendSystemMessage(Component.literal(i++ + ". ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(n.name).withStyle(DummyCraft.color(n)))
                    .append(Component.literal(" - " + g.chunksOf(n) + " chunks, score " + (int) g.score(n)).withStyle(ChatFormatting.GRAY)));
    }

    private static void map(ServerPlayer p) {
        Game g = DummyCraft.game;
        int px = cx(p), pz = cz(p), r = 7;
        Set<Game.Nation> seen = new LinkedHashSet<>();
        p.sendSystemMessage(Component.literal("Map, north is up. White = you, \u2593 = under attack").withStyle(ChatFormatting.GRAY));
        for (int z = pz - r; z <= pz + r; z++) {
            MutableComponent row = Component.empty();
            for (int x = px - r; x <= px + r; x++) {
                Game.Nation o = g.at(x, z);
                if (o != null) seen.add(o);
                String glyph = g.opAt(x, z) != null ? "\u2593" : (o != null ? "\u2588" : "\u2592");
                ChatFormatting col = (x == px && z == pz) ? ChatFormatting.WHITE : (o == null ? ChatFormatting.DARK_GRAY : DummyCraft.color(o));
                row.append(Component.literal(glyph).withStyle(col));
            }
            p.sendSystemMessage(row);
        }
        if (!seen.isEmpty()) {
            MutableComponent legend = Component.empty();
            for (Game.Nation n : seen) legend.append(Component.literal("\u2588 " + n.name + "  ").withStyle(DummyCraft.color(n)));
            p.sendSystemMessage(legend);
        }
    }
}
