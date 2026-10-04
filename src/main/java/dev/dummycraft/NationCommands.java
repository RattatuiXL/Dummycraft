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
    private static final String[] TUTORIAL = {
            "Welcome. Your coloured land is your country. The capital is always your richest city; each country has one to three cities.",
            "Cities earn extra gold: the capital earns the most, and other cities earn a smaller bonus. Yellow flags mark farms, black flags oil, and gray flags nuclear sites.",
            "Right-click a troop flag to select that unit stack, then right-click friendly land to move. Crouch and right-click a farther tile to spread ground troops along the land route.",
            "Right-click the Field Guide to open the chest menu. Statistics shows income and ground, air, and water power; Army Store buys units and Upgrades improves your country.",
            "Buy troops with the gold and oil earned each round. The starter pack gives 20 infantry, 100 gold, and 10 oil. Use the menu to choose unit type, amount, and location.",
            "Ground troops need a connected friendly land route and cannot cross sea. Fighters, bombers, and ships can move farther; ships and aircraft cross water.",
            "Attack neighboring empty land with a selected troop flag and right-click the target. Declare war before attacking another country. Turn order is random; ring the End Turn Bell when finished.",
            "Rename your country with /nation rename <name>. Type /nation tutorial ru for this guide in Russian, /nation info for your numbers, and /nation menu for the chest menu."
    };
    private static final String[] TUTORIAL_RU = {
            "Добро пожаловать! Земля вашего цвета — ваша страна. Столица всегда самый богатый город; в каждой стране от одного до трёх городов.",
            "Города дают дополнительное золото: столица приносит больше всего, остальные города — меньше. Жёлтые флаги обозначают фермы, чёрные — нефть, серые — атомные станции.",
            "Щёлкните правой кнопкой по флагу войск, чтобы выбрать отряд, затем щёлкните по своим землям для перемещения. Присядьте и щёлкните по дальней клетке, чтобы распределить пехоту вдоль сухопутного пути.",
            "Щёлкните правой кнопкой по Полевому справочнику, чтобы открыть меню-сундук. Статистика показывает доход и силу на земле, в воздухе и на воде; в магазине покупают войска, а улучшения усиливают страну.",
            "Покупайте войска за золото и нефть, получаемые каждый раунд. Стартовый набор содержит 20 пехотинцев, 100 золота и 10 нефти. В меню выберите тип войск, количество и место размещения.",
            "Наземным войскам нужен непрерывный дружественный сухопутный путь; море им не пройти. Истребители, бомбардировщики и корабли перемещаются дальше; самолёты и корабли пересекают воду.",
            "Атакуйте соседнюю ничейную землю: выберите флаг войск и щёлкните по цели. Перед атакой другой страны объявите ей войну. Порядок ходов случаен; завершите ход звонком в колокол.",
            "Переименуйте страну командой /nation rename <название>. Введите /nation tutorial en для английского текста, /nation stats для статистики или /nation menu для меню-сундука."
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
        nation.then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(c -> run(c, NATION | LEADER, (p, n, ctx) ->
                        DummyCraft.game.rename(n, StringArgumentType.getString(ctx, "name"))))));
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
                .executes(c -> run(c, 0, (p, n, ctx) -> { tutorial(p, "en"); return null; }));
        tutorial.then(Commands.argument("language", StringArgumentType.word())
                .executes(c -> run(c, 0, (p, n, ctx) -> {
                    String language = StringArgumentType.getString(ctx, "language");
                    if (!language.equalsIgnoreCase("en") && !language.equalsIgnoreCase("ru")
                            && !language.equalsIgnoreCase("english") && !language.equalsIgnoreCase("russian"))
                        return Game.err("Choose /nation tutorial en or /nation tutorial ru.");
                    tutorial(p, language);
                    return null;
                })));
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

        return result;
    }

    private static Game.R createNation(ServerPlayer p, String name) {
        Game.R result = DummyCraft.game.create(p.getUUID(), name, cx(p), cz(p));
        if (result.ok()) {
            NationItems.giveStarterKit(p);
            tutorial(p);
    
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
            "/nation rename <name> - set your own nation name (leader)",
            "/start                 - generate randomized countries; /start as <name> or /start random to choose",
            "/start order           - reshuffle turn order between already-created nations",
            "/nation endturn        - end your nation's turn; income arrives after every nation moves",
            "/nation menu           - open the chest menu; guide and bell are in your starter kit",
            "/nation tutorial [en|ru] - full text tutorial in English or Russian",
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

    static void tutorial(ServerPlayer p) { tutorial(p, "en"); }

    private static void tutorial(ServerPlayer p, String language) {
        boolean russian = language.equalsIgnoreCase("ru") || language.equalsIgnoreCase("russian");
        String[] lines = russian ? TUTORIAL_RU : TUTORIAL;
        p.sendSystemMessage(Component.literal("Dymmynation — " + (russian ? "обучение" : "quick tutorial"))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (int i = 0; i < lines.length; i++)
            p.sendSystemMessage(Component.literal((i + 1) + ". " + lines[i]).withStyle(ChatFormatting.WHITE));
        p.sendSystemMessage(Component.literal(russian
                ? "Перевод: /nation tutorial en"
                : "Russian text: /nation tutorial ru").withStyle(ChatFormatting.AQUA));
    }

    // Compatibility hooks for the old menu page; all now print the full chat guide.
    static int tutorialNumber(UUID player) { return 0; }
    static void startTutorial(ServerPlayer player) { tutorial(player); }
    static void nextTutorial(ServerPlayer player) { tutorial(player); }



    private static void info(ServerPlayer p, Game.Nation n) {
        Game g = DummyCraft.game;
        int chunks = g.chunksOf(n);
        p.sendSystemMessage(Component.literal("== " + n.name + " ==").withStyle(DummyCraft.color(n), ChatFormatting.BOLD));
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Chunks %d | Cities %d | Gold %d | Oil %d | Units %d (+%d deployed) | Score %d",
                chunks, g.citiesOf(n), (int) n.gold, (int) n.oil,
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
