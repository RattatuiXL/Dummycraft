package dev.dummycraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.sounds.SoundEvents;

import java.util.*;
import java.util.function.Function;

/** /nation ... command tree. All rules live in {@link Game}; this only translates input and output. */
final class NationCommands {
    private static final int NATION = 1, LEADER = 2, OVERWORLD = 4;
    private static final Map<UUID, Integer> TUTORIAL_PROGRESS = new HashMap<>();
    private static final String[] TUTORIAL = {
            "Terracotta in your nation's colour marks its land. Your capital starts with 20 infantry, 100 gold and 10 oil. Right-click your Field Guide to open the menu.",
            "Farms make infantry, oilfields make oil, and plains make mixed income. Statistics shows what your land earns each round.",
            "Open Army Store, pick Ground, Air or Water, then click a unit. Left-click buys one; right-click buys five.",
            "Open Deploy while on your land. Pick a nearby chunk, unit and amount, then click the sword. Ground forces stay on land; aircraft and ships cross sea.",
            "Empty land can be captured by attacking it. Declare war before attacking another nation. Sea cannot be captured.",
            "Upgrades improve income or attack and defense for ground, air and water forces. Statistics shows each defense separately.",
            "After your moves, right-click the End Turn Bell. Income and battles resolve as the randomized turn order advances.",
            "Tutorial complete! Use /nation tutorial restart whenever you want to see it again."
    };


    private interface Body {
        /** Return a result to print, or null if the body already printed its own output. */
        Game.R run(ServerPlayer p, Game.Nation n, CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) ->
            SharedSuggestionProvider.suggest(DummyCraft.game.nations.values().stream().map(n -> n.name), b);
    private static final SuggestionProvider<CommandSourceStack> COUNTRIES = (ctx, b) ->
            SharedSuggestionProvider.suggest(DummyCraft.game.availableCountries(), b);

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
                .executes(c -> run(c, OVERWORLD, (p, n, ctx) -> DummyCraft.generateMap(p)));
        start.then(Commands.literal("as")
                .then(Commands.argument("country", StringArgumentType.word()).suggests(COUNTRIES)
                        .executes(c -> run(c, OVERWORLD, (p, n, ctx) ->
                                chooseCountry(p, StringArgumentType.getString(ctx, "country"))))));
        start.then(Commands.literal("random").executes(c -> run(c, OVERWORLD, (p, n, ctx) -> {
            if (!DummyCraft.game.mapGenerated) {
                Game.R generated = DummyCraft.generateMap(p);
                if (!generated.ok()) return generated;
            }
            return chooseCountry(p, "random");
        })));
        start.then(Commands.literal("order").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.startTurns())));
        d.register(start);

        LiteralArgumentBuilder<CommandSourceStack> nation = Commands.literal("nation")
                .executes(c -> run(c, 0, (p, n, ctx) -> { help(p); return null; }));
        nation.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> run(c, OVERWORLD, (p, n, ctx) ->
                        createNation(p, StringArgumentType.getString(ctx, "name"))))));
        nation.then(Commands.literal("info")
                .executes(c -> run(c, NATION, (p, n, ctx) -> { info(p, n); return null; }))
                .then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                        .executes(c -> run(c, 0, (p, n, ctx) ->
                                withTarget(ctx, t -> { info(p, t); return null; })))));
        nation.then(Commands.literal("list").executes(c -> run(c, 0, (p, n, ctx) -> { list(p); return null; })));
        nation.then(Commands.literal("map").executes(c -> run(c, OVERWORLD, (p, n, ctx) -> { map(p); return null; })));
        nation.then(Commands.literal("borders").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.toggleBorders(p))));
        nation.then(Commands.literal("bordercolor").then(Commands.argument("color", StringArgumentType.word())
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) ->
                        DummyCraft.game.setBorderColor(n, StringArgumentType.getString(ctx, "color"))))));
        nation.then(Commands.literal("endturn").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.endTurn(p.getUUID()))));
        LiteralArgumentBuilder<CommandSourceStack> tutorial = Commands.literal("tutorial")
                .executes(c -> run(c, 0, (p, n, ctx) -> { startTutorial(p); return null; }));
        tutorial.then(Commands.literal("next").executes(c -> run(c, 0, (p, n, ctx) -> { nextTutorial(p); return null; })));
        tutorial.then(Commands.literal("restart").executes(c -> run(c, 0, (p, n, ctx) -> { startTutorial(p); return null; })));
        nation.then(tutorial);
        nation.then(Commands.literal("recruit").then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                .executes(c -> run(c, NATION, (p, n, ctx) ->
                        DummyCraft.game.recruit(n, IntegerArgumentType.getInteger(ctx, "amount"))))));
        nation.then(Commands.literal("attack").then(Commands.argument("troops", IntegerArgumentType.integer(1, 1000))
                .executes(c -> run(c, NATION | OVERWORLD, (p, n, ctx) ->
                        DummyCraft.game.attack(n, cx(p), cz(p), IntegerArgumentType.getInteger(ctx, "troops"))))));
        nation.then(buyCommand());
        nation.then(unitMoveCommand("move", false));
        nation.then(unitMoveCommand("attackunit", true));
        nation.then(Commands.literal("menu").executes(c -> run(c, NATION, (p, n, ctx) -> {
            NationMenu.open(p, DummyCraft.game);
            return null;
        })));
        nation.then(Commands.literal("retreat").executes(c -> run(c, NATION | OVERWORLD,
                (p, n, ctx) -> DummyCraft.game.retreat(n, cx(p), cz(p)))));
        nation.then(Commands.literal("war").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.war(n, t))))));
        nation.then(Commands.literal("peace").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.peace(n, t))))));
        nation.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    Game.R r = DummyCraft.game.invite(n, target.getUUID());
                    if (r.ok()) target.sendSystemMessage(Component.literal(
                            n.name + " invited you. Accept with /nation join " + n.name).withStyle(ChatFormatting.GOLD));
                    return r;
                }))));
        nation.then(Commands.literal("join").then(Commands.argument("nation", StringArgumentType.word()).suggests(NAMES)
                .executes(c -> run(c, 0, (p, n, ctx) -> withTarget(ctx, t -> DummyCraft.game.join(p.getUUID(), t))))));
        nation.then(Commands.literal("leave").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.leave(p.getUUID()))));
        nation.then(Commands.literal("disband").executes(c -> run(c, 0, (p, n, ctx) -> DummyCraft.game.disband(p.getUUID()))));
        d.register(nation);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buyCommand() {
        return Commands.literal("buy").then(Commands.argument("unit", StringArgumentType.word())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                        .executes(c -> run(c, NATION | OVERWORLD, (p, n, ctx) ->
                                DummyCraft.game.buy(n, UnitType.parse(StringArgumentType.getString(ctx, "unit")),
                                        IntegerArgumentType.getInteger(ctx, "amount"), Game.key(cx(p), cz(p)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> unitMoveCommand(String name, boolean attack) {
        return Commands.literal(name).then(Commands.argument("unit", StringArgumentType.word())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                        .then(Commands.argument("targetX", IntegerArgumentType.integer())
                                .then(Commands.argument("targetZ", IntegerArgumentType.integer())
                                        .executes(c -> run(c, NATION | OVERWORLD, (p, n, ctx) -> {
                                            UnitType type = UnitType.parse(StringArgumentType.getString(ctx, "unit"));
                                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                            int targetX = IntegerArgumentType.getInteger(ctx, "targetX");
                                            int targetZ = IntegerArgumentType.getInteger(ctx, "targetZ");
                                            return attack
                                                    ? DummyCraft.game.attackUnits(n, type, amount, Game.key(cx(p), cz(p)), targetX, targetZ)
                                                    : DummyCraft.game.moveUnits(n, type, amount, Game.key(cx(p), cz(p)), Game.key(targetX, targetZ));
                                        }))))));
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
        if (r != null) {
            p.sendSystemMessage(Component.literal(r.msg()).withStyle(r.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
            p.playSound(r.ok() ? SoundEvents.UI_TOAST_IN : SoundEvents.UI_BUTTON_CLICK.value(), 0.55f, r.ok() ? 1.0f : 0.8f);
        }
        return r != null && r.ok() ? 1 : 0;
    }

    private static Game.R withTarget(CommandContext<CommandSourceStack> ctx, Function<Game.Nation, Game.R> f) {
        Game.Nation t = DummyCraft.game.byName(StringArgumentType.getString(ctx, "nation"));
        return t == null ? Game.err("No such nation.") : f.apply(t);
    }

    private static int cx(ServerPlayer p) { return p.blockPosition().getX() >> 4; }
    private static int cz(ServerPlayer p) { return p.blockPosition().getZ() >> 4; }

    private static Game.R chooseCountry(ServerPlayer p, String country) {
        Game.R result = DummyCraft.game.chooseCountry(p.getUUID(), country);
        if (!result.ok()) return result;
        Game.Nation n = DummyCraft.game.nationOf(p.getUUID());
        int bx = Game.cx(n.capital) * 16 + 8, bz = Game.cz(n.capital) * 16 + 8;
        ServerLevel level = (ServerLevel) p.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        p.teleportTo(bx + 0.5, y + 0.1, bz + 0.5);
        p.playSound(SoundEvents.UI_TOAST_IN, 0.8f, 1.0f);
        NationItems.giveStarterKit(p);
        tutorial(p);
        NationMenu.openTutorial(p, DummyCraft.game);
        return result;
    }

    private static Game.R createNation(ServerPlayer p, String name) {
        Game.R result = DummyCraft.game.create(p.getUUID(), name, cx(p), cz(p));
        if (result.ok()) {
            NationItems.giveStarterKit(p);
            tutorial(p);
            NationMenu.openTutorial(p, DummyCraft.game);
        }
        return result;
    }

    // ------------------------------------------------------------ output

    private static void help(ServerPlayer p) {
        String[] lines = {
            "/nation create <name>  - found a nation; your current chunk becomes the capital",
            "/nation map            - territory map around you",
            "/nation borders        - show or hide particle borders (on by default)",
            "/nation bordercolor <color> - change your bright ground-line hue (nation leader)",
            "/start                 - generate randomized countries; /start as <name> or /start random to choose",
            "/start order           - reshuffle turn order between already-created nations",
            "/nation endturn        - end your nation's turn; income arrives after every nation moves",
            "/nation menu           - open the chest menu; guide and bell are in your starter kit",
            "/nation tutorial       - beginner guide",
            "/nation info [nation]  - stats; /nation list - ranking",
            "/nation recruit <n>    - turn gold into troops (5 gold each)",
            "/nation buy <unit> <n> - buy infantry, tank, artillery, fighter, bomber or ship at your current chunk",
            "/nation move <unit> <n> <targetX> <targetZ> - move a stack to a neighboring chunk you own",
            "/nation attackunit <unit> <n> <targetX> <targetZ> - attack from your current chunk",
            "/nation menu            - open the chest-style command and store menu",
            "/nation attack <n>     - send n troops to occupy the chunk you stand in (must border your land)",
            "/nation retreat        - pull your troops out of this chunk",
            "/nation war|peace <nation>, invite <player>, join <nation>, leave, disband"
        };
        for (String l : lines) p.sendSystemMessage(Component.literal(l).withStyle(ChatFormatting.YELLOW));
    }

    private static void tutorial(ServerPlayer p) { startTutorial(p); }

    static int tutorialNumber(UUID player) { return TUTORIAL_PROGRESS.getOrDefault(player, 0); }

    static void startTutorial(ServerPlayer player) {
        TUTORIAL_PROGRESS.put(player.getUUID(), 0);
        nextTutorial(player);
    }

    static void nextTutorial(ServerPlayer player) {
        int step = TUTORIAL_PROGRESS.getOrDefault(player.getUUID(), 0);
        if (step >= TUTORIAL.length) step = TUTORIAL.length - 1;
        else TUTORIAL_PROGRESS.put(player.getUUID(), step + 1);
        player.sendSystemMessage(Component.literal("Dymmynation tutorial • Lesson " + (step + 1) + " of " + TUTORIAL.length)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal(TUTORIAL[step]).withStyle(ChatFormatting.WHITE));
        if (step + 1 < TUTORIAL.length)
            player.sendSystemMessage(Component.literal("Click Next lesson in the guide or type /nation tutorial next.").withStyle(ChatFormatting.AQUA));
        else player.sendSystemMessage(Component.literal("Tutorial complete. Type /nation tutorial restart to begin again.").withStyle(ChatFormatting.GREEN));
    }

    private static void info(ServerPlayer p, Game.Nation n) {
        Game g = DummyCraft.game;
        int chunks = g.chunksOf(n);
        p.sendSystemMessage(Component.literal("== " + n.name + " ==").withStyle(DummyCraft.color(n), ChatFormatting.BOLD));
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Chunks %d (efficiency %d%%) | Gold %d | Oil %d | Units %d (+%d deployed) | Score %d",
                chunks, Math.round(g.efficiency(n, chunks) * 100), (int) n.gold, (int) n.oil,
                g.unitCount(n), (int) g.deployed(n), (int) g.score(n))));
        double[] income = g.incomePerRound(n);
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Per round: +%.1f gold, +%.1f oil, +%.1f infantry | Ground ATK/DEF %d/%d | Air %d/%d | Water %d/%d",
                income[0], income[2], income[1], (int)g.power(n, "ground", false), (int)g.power(n, "ground", true),
                (int)g.power(n, "air", false), (int)g.power(n, "air", true),
                (int)g.power(n, "water", false), (int)g.power(n, "water", true))));
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
