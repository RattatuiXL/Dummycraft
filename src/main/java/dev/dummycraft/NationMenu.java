package dev.dummycraft;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Server-controlled six-row chest menu. Clicking an icon performs a game action; items never leave the menu. */
final class NationMenu extends ChestMenu {
    private enum Page { HOME, SHOP, UNITS, TARGETS, DEPLOY, UPGRADES, MAP, STATS, BORDERS, TUTORIAL }

    private final SimpleContainer icons;
    private final Game game;
    private final ServerPlayer player;
    private final Page page;
    private final int sourceX, sourceZ, targetX, targetZ, amount;
    private final UnitType selected;

    private NationMenu(int id, Inventory inventory, SimpleContainer icons, Game game, ServerPlayer player,
                       Page page, int sourceX, int sourceZ, int targetX, int targetZ, int amount, UnitType selected) {
        super(MenuType.GENERIC_9x6, id, inventory, icons, 6);
        this.icons = icons; this.game = game; this.player = player; this.page = page;
        this.sourceX = sourceX; this.sourceZ = sourceZ; this.targetX = targetX; this.targetZ = targetZ;
        this.amount = amount; this.selected = selected;
        draw();
    }

    static void open(ServerPlayer p, Game g) {
        int x = p.blockPosition().getX() >> 4, z = p.blockPosition().getZ() >> 4;
        open(p, g, Page.HOME, x, z, x, z, 1, null);
    }
    static void openTutorial(ServerPlayer p, Game g) {
        open(p, g, Page.TUTORIAL, p.blockPosition().getX() >> 4, p.blockPosition().getZ() >> 4,
                p.blockPosition().getX() >> 4, p.blockPosition().getZ() >> 4, 1, null);
    }

    private static void open(ServerPlayer p, Game g, Page page, int sx, int sz, int tx, int tz, int amount, UnitType selected) {
        String title = switch (page) {
            case HOME -> "Nation Command"; case SHOP -> "Army Store"; case UNITS -> "Unit Categories";
            case TARGETS -> "Choose a neighboring chunk"; case DEPLOY -> "Choose units and strength";
            case UPGRADES -> "Nation Upgrades"; case MAP -> "Territory Map"; case STATS -> "Nation Statistics";
            case BORDERS -> "Border Line Colour"; case TUTORIAL -> "Quick-start Guide";
        };
        p.openMenu(new SimpleMenuProvider((id, inventory, who) -> new NationMenu(id, inventory,
                new SimpleContainer(54), g, p, page, sx, sz, tx, tz, amount, selected), Component.literal(title)));
        p.playSound(SoundEvents.UI_BUTTON_CLICK, 0.65f, 1.1f);
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player ignored) {
        if (input != ContainerInput.PICKUP || slot < 0 || slot >= 54) return;
        click(slot, button);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) { return ItemStack.EMPTY; }

    private void click(int slot, int button) {
        Game.Nation n = game.nationOf(player.getUUID());
        if (page != Page.TUTORIAL && n == null) { player.sendSystemMessage(Component.literal("Join or create a nation first.").withStyle(ChatFormatting.RED)); return; }
        switch (page) {
            case HOME -> {
                if (slot == 10) reopen(Page.UNITS, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 13) reopen(Page.MAP, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 16) reopen(Page.UPGRADES, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 19) reopen(Page.STATS, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 25) reopen(Page.BORDERS, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 28) reopen(Page.TARGETS, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 31) { Game.R r = game.endTurn(player.getUUID()); report(r); }
                else if (slot == 34) reopen(Page.TUTORIAL, sourceX, sourceZ, targetX, targetZ, amount, selected);
                else if (slot == 49) reopen(Page.UNITS, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case SHOP -> {
                UnitType type = unitAtShopSlot(slot);
                if (type != null && (selected == null || type.domain.equals(selected.domain))) {
                    report(game.buy(n, type, button == 1 ? 5 : 1, Game.key(sourceX, sourceZ))); refresh();
                }
                else if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case UNITS -> {
                UnitType type = switch (slot) { case 21 -> UnitType.INFANTRY; case 22 -> UnitType.FIGHTER; case 23 -> UnitType.SHIP; default -> null; };
                if (type != null) reopen(Page.SHOP, sourceX, sourceZ, targetX, targetZ, amount, type);
                else if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case TARGETS -> {
                int[] d = directionAt(slot);
                if (d != null) reopen(Page.DEPLOY, sourceX, sourceZ, sourceX + d[0], sourceZ + d[1], 1, null);
                else if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case DEPLOY -> {
                UnitType type = unitAtDeploySlot(slot);
                if (type != null) reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, amount, type);
                else if (slot == 28) reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, amount + 1, selected);
                else if (slot == 30) reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, amount + 5, selected);
                else if (slot == 32) reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, amount + 10, selected);
                else if (slot == 34 && selected != null) {
                    int available = game.unitsAt(Game.key(sourceX, sourceZ)).getOrDefault(selected.id(), 0);
                    reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, available, selected);
                } else if (slot == 40 && selected != null) {
                    String destination = Game.key(targetX, targetZ);
                    Game.R result = game.at(targetX, targetZ) == n
                            ? game.moveUnits(n, selected, amount, Game.key(sourceX, sourceZ), destination)
                            : game.attackUnits(n, selected, amount, Game.key(sourceX, sourceZ), targetX, targetZ);
                    report(result);
                    reopen(Page.DEPLOY, sourceX, sourceZ, targetX, targetZ, 1, null);
                } else if (slot == 49) reopen(Page.TARGETS, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case UPGRADES -> {
                String upgrade = upgradeAt(slot);
                if (upgrade != null) { report(game.buyUpgrade(n, upgrade)); refresh(); }
                else if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case MAP -> {
                if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case STATS -> { if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected); }
            case BORDERS -> {
                String color = colorAt(slot);
                if (color != null) {
                    if (!n.leader.equals(player.getUUID())) report(Game.err("Only your nation leader can change the border colour."));
                    else report(game.setBorderColor(n, color));
                    refresh();
                } else if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
            case TUTORIAL -> {
                if (slot == 49) reopen(Page.HOME, sourceX, sourceZ, targetX, targetZ, amount, selected);
            }
        }
        player.playSound(SoundEvents.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void report(Game.R result) {
        player.sendSystemMessage(Component.literal(result.msg()).withStyle(result.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
    private void reopen(Page p, int sx, int sz, int tx, int tz, int amount, UnitType selected) {
        open(player, game, p, sx, sz, tx, tz, Math.max(1, amount), selected);
    }
    private void put(int slot, Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        icons.setItem(slot, stack);
    }
    private void fill(Item item) { for (int i = 0; i < 54; i++) put(i, item, " "); }

    private void draw() {
        fill(Items.GRAY_STAINED_GLASS_PANE);
        Game.Nation n = game.nationOf(player.getUUID());
        if (page == Page.TUTORIAL) {
            put(10, Items.BOOK, "1. Host uses /start. Choose with /start as <country> or /start random");
            put(12, Items.CHEST, "2. Right-click the Field Guide for this menu");
            put(14, Items.GOLD_INGOT, "3. Starter pack: 20 infantry, 100 gold and 10 oil");
            put(16, Items.IRON_SWORD, "4. Buy a force, then deploy from a chunk you own");
            put(28, Items.SHIELD, "5. Your owned neighbor is a move; other land is an attack");
            put(30, Items.WHEAT, "6. Farms make infantry; oilfields make oil; plains make mixed income");
            put(32, Items.BELL, "7. Upgrade stats, then end your turn with the bell");
            put(49, Items.ARROW, "Back"); return;
        }
        if (n == null) return;
        switch (page) {
            case HOME -> {
                put(4, Items.PAPER, n.name + "  |  Round " + game.round);
                put(10, Items.IRON_SWORD, "Army store and unit types");
                put(13, Items.MAP, "Map and territory");
                put(16, Items.ANVIL, "Power and defense upgrades");
                put(19, Items.BOOK, "Nation statistics");
                put(25, Items.GLOWSTONE_DUST, "Bright border line colour");
                put(28, Items.COMPASS, "Deploy / attack from this chunk");
                put(31, Items.BELL, "End turn  |  right-click bell item also works");
                put(34, Items.BOOK, "Beginner tutorial");
                put(49, Items.GOLD_INGOT, "Buy units");
                if (game.turnBased && game.activeNation() != null)
                    put(22, Items.CLOCK, "Current turn: " + game.activeNation().name);
            }
            case UNITS -> {
                put(21, Items.IRON_SWORD, "GROUND FORCES  |  infantry, tank, artillery");
                put(22, Items.ELYTRA, "AIR FORCES  |  fighter, bomber");
                put(23, Items.OAK_BOAT, "WATER FORCES  |  ship");
                put(31, Items.PAPER, "Choose a branch to open its store");
                put(49, Items.ARROW, "Back");
            }
            case SHOP -> {
                put(4, Items.GOLD_INGOT, "Gold " + (int)n.gold + " | Oil " + (int)n.oil + " | buying at " + sourceX + "," + sourceZ);
                for (UnitType type : UnitType.values()) {
                    if (selected != null && !type.domain.equals(selected.domain)) continue;
                    int slot = unitSlot(type);
                    Item icon = icon(type);
                    int count = game.unitsAt(Game.key(sourceX, sourceZ)).getOrDefault(type.id(), 0);
                    put(slot, icon, type.title + " | here: " + count + " | " + type.gold + " gold + " + type.oil + " oil");
                }
                put(31, Items.PAPER, "Left buys 1; right buys 5  |  " + (selected == null ? "all forces" : selected.domain + " forces"));
                put(49, Items.ARROW, "Back");
            }
            case TARGETS -> {
                int[][] dirs = {{0,-1},{-1,0},{1,0},{0,1}};
                int[] slots = {12, 21, 23, 30}; String[] labels = {"North", "West", "East", "South"};
                for (int i = 0; i < dirs.length; i++) {
                    int x = sourceX + dirs[i][0], z = sourceZ + dirs[i][1];
                    Game.Nation owner = game.at(x, z);
                    put(slots[i], owner == null ? Items.GRASS_BLOCK : Items.RED_BANNER,
                            labels[i] + "  " + x + "," + z + "  " + (owner == null ? "Wilderness" : owner.name) + "  " + game.terrainName(Game.key(x,z)));
                }
                put(10, Items.CHEST, "Source: " + sourceX + "," + sourceZ + " | your units");
                put(49, Items.ARROW, "Back");
            }
            case DEPLOY -> {
                put(4, Items.TARGET, "Target: " + targetX + "," + targetZ + "  " + game.terrainName(Game.key(targetX,targetZ)));
                for (UnitType type : UnitType.values()) {
                    int slot = unitSlot(type);
                    int count = game.unitsAt(Game.key(sourceX, sourceZ)).getOrDefault(type.id(), 0);
                    put(slot, icon(type), type.title + " | available: " + count + (selected == type ? "  [selected]" : ""));
                }
                put(28, Items.LIME_DYE, "Amount +1  (now " + amount + ")");
                put(30, Items.LIME_DYE, "Amount +5  (now " + amount + ")");
                put(32, Items.LIME_DYE, "Amount +10  (now " + amount + ")");
                put(34, Items.HOPPER, "Set amount to all available");
                put(40, Items.IRON_SWORD, game.at(targetX, targetZ) == n ? "Move this force" : "Attack with this force");
                put(49, Items.ARROW, "Choose another target");
            }
            case UPGRADES -> {
                String[] ids = {"ground_attack", "ground_defense", "air_attack", "air_defense", "water_attack", "water_defense", "income"};
                Item[] items = {Items.IRON_SWORD, Items.SHIELD, Items.FEATHER, Items.ELYTRA, Items.TRIDENT, Items.TURTLE_HELMET, Items.EMERALD};
                put(4, Items.GOLD_INGOT, "Gold " + (int)n.gold + " | Oil " + (int)n.oil);
                for (int i = 0; i < ids.length; i++) {
                    int level = n.upgrades.getOrDefault(ids[i], 0);
                    int goldCost = 30 + level * 20, oilCost = 3 + level * 2;
                    put(10 + i, items[i], ids[i].replace('_', ' ') + " | level " + level
                            + " | next cost " + goldCost + " gold + " + oilCost + " oil");
                }
                put(31, Items.PAPER, "Each level adds 15% to this stat");
                put(49, Items.ARROW, "Back");
            }
            case MAP -> drawMap(n);
            case STATS -> drawStats(n);
            case BORDERS -> drawBorderColors(n);
            default -> { }
        }
    }

    private void drawMap(Game.Nation mine) {
        for (int dz = -3; dz <= 2; dz++) for (int dx = -4; dx <= 3; dx++) {
            int x = sourceX + dx, z = sourceZ + dz, slot = (dz + 3) * 9 + (dx + 4);
            Game.Nation owner = game.at(x, z);
            Item icon = owner == mine ? Items.LIME_STAINED_GLASS : owner == null ? Items.GRAY_STAINED_GLASS : Items.RED_STAINED_GLASS;
            put(slot, icon, x + "," + z + " | " + (owner == null ? "wilderness" : owner.name) + " | " + game.terrainName(Game.key(x,z)));
        }
        put(49, Items.ARROW, "Back");
    }

    private void drawStats(Game.Nation n) {
        double[] income = game.incomePerRound(n);
        put(10, Items.GOLD_INGOT, "Gold " + (int)n.gold + "  |  +" + String.format(Locale.ROOT, "%.1f", income[0]) + " per round");
        put(12, Items.BARREL, "Oil " + (int)n.oil + "  |  +" + String.format(Locale.ROOT, "%.1f", income[2]) + " per round");
        put(14, Items.WHEAT, "Infantry reinforcement +" + String.format(Locale.ROOT, "%.1f", income[1]) + " per round");
        put(16, Items.IRON_SWORD, "Ground attack power " + (int)game.power(n, "ground", false));
        put(18, Items.SHIELD, "Ground defense " + (int)game.power(n, "ground", true));
        put(20, Items.FIREWORK_ROCKET, "Air attack power " + (int)game.power(n, "air", false));
        put(22, Items.ELYTRA, "Air defense " + (int)game.power(n, "air", true));
        put(24, Items.TRIDENT, "Water attack power " + (int)game.power(n, "water", false));
        put(26, Items.TURTLE_HELMET, "Water defense " + (int)game.power(n, "water", true));
        put(28, Items.LIME_STAINED_GLASS, "Territory chunks " + game.chunksOf(n));
        put(30, Items.IRON_SWORD, "Total units " + game.unitCount(n));
        put(49, Items.ARROW, "Back");
    }

    private void drawBorderColors(Game.Nation n) {
        put(4, Items.GLOWSTONE_DUST, "Choose a bright border hue for " + n.name);
        for (int i = 0; i < Game.COLORS.length; i++) {
            String color = Game.COLORS[i];
            String active = color.equals(n.borderColor == null ? n.color : n.borderColor) ? "  [current]" : "";
            put(10 + i, colorItem(color), color.replace('_', ' ').toLowerCase(Locale.ROOT) + active);
        }
        put(31, Items.PAPER, "The colored line runs along the ground at your chunk borders");
        put(49, Items.ARROW, "Back");
    }

    private static Item colorItem(String color) {
        return switch (color) {
            case "RED" -> Items.RED_DYE;
            case "BLUE" -> Items.BLUE_DYE;
            case "GREEN" -> Items.LIME_DYE;
            case "YELLOW" -> Items.YELLOW_DYE;
            case "LIGHT_PURPLE" -> Items.MAGENTA_DYE;
            case "AQUA" -> Items.CYAN_DYE;
            case "GOLD" -> Items.ORANGE_DYE;
            case "DARK_GREEN" -> Items.GREEN_DYE;
            case "DARK_AQUA" -> Items.LIGHT_BLUE_DYE;
            case "DARK_PURPLE" -> Items.PURPLE_DYE;
            case "DARK_RED" -> Items.BROWN_DYE;
            default -> Items.WHITE_DYE;
        };
    }

    private static String colorAt(int slot) {
        int index = slot - 10;
        return index >= 0 && index < Game.COLORS.length ? Game.COLORS[index] : null;
    }

    private static UnitType unitAtShopSlot(int slot) {
        for (UnitType u : UnitType.values()) if (unitSlot(u) == slot) return u;
        return null;
    }
    private static UnitType unitAtDeploySlot(int slot) { return unitAtShopSlot(slot); }
    private static int unitSlot(UnitType type) { return switch (type) { case INFANTRY -> 10; case TANK -> 12; case ARTILLERY -> 14; case FIGHTER -> 16; case BOMBER -> 21; case SHIP -> 23; }; }
    private static Item icon(UnitType type) { return switch (type) { case INFANTRY -> NationItems.UNIT_INFANTRY; case TANK -> NationItems.UNIT_TANK; case ARTILLERY -> NationItems.UNIT_ARTILLERY; case FIGHTER -> NationItems.UNIT_FIGHTER; case BOMBER -> NationItems.UNIT_BOMBER; case SHIP -> NationItems.UNIT_SHIP; }; }
    private static int[] directionAt(int slot) { return switch (slot) { case 12 -> new int[]{0,-1}; case 21 -> new int[]{-1,0}; case 23 -> new int[]{1,0}; case 30 -> new int[]{0,1}; default -> null; }; }
    private static String upgradeAt(int slot) {
        String[] ids = {"ground_attack", "ground_defense", "air_attack", "air_defense", "water_attack", "water_defense", "income"};
        int index = slot - 10; return index >= 0 && index < ids.length ? ids[index] : null;
    }
    private void refresh() { draw(); }
}
