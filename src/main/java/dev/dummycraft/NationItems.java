package dev.dummycraft;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Right-click tools issued as part of each country's starter kit. */
final class NationItems {
    static final Item GUIDE = register("nation_guide", "Nation Field Guide", (settings) -> new Item(settings) {
        @Override public InteractionResult use(Level level, Player user, InteractionHand hand) {
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (user instanceof ServerPlayer player) {
                if (DummyCraft.game.nationOf(player.getUUID()) == null) NationMenu.openTutorial(player, DummyCraft.game);
                else NationMenu.open(player, DummyCraft.game);
            }
            return InteractionResult.SUCCESS;
        }
    });

    static final Item TURN_BELL = register("turn_bell", "End Turn Bell", (settings) -> new Item(settings) {
        @Override public InteractionResult use(Level level, Player user, InteractionHand hand) {
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (user instanceof ServerPlayer player) {
                Game.R result = DummyCraft.game.endTurn(player.getUUID());
                player.sendSystemMessage(Component.literal(result.msg()));
                player.playSound(result.ok() ? net.minecraft.sounds.SoundEvents.UI_TOAST_IN
                                : net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(),
                        0.8f, result.ok() ? 1.0f : 0.7f);
            }
            return InteractionResult.SUCCESS;
        }
    });

    static final Item UNIT_INFANTRY = marker("unit_infantry", "Infantry insignia");
    static final Item UNIT_TANK = marker("unit_tank", "Tank insignia");
    static final Item UNIT_ARTILLERY = marker("unit_artillery", "Artillery insignia");
    static final Item UNIT_FIGHTER = marker("unit_fighter", "Fighter insignia");
    static final Item UNIT_BOMBER = marker("unit_bomber", "Bomber insignia");
    static final Item UNIT_SHIP = marker("unit_ship", "Ship insignia");

    private NationItems() {}
    static void initialize() { }

    private static Item marker(String id, String title) {
        return register(id, title, Item::new);
    }

    private static Item register(String id, String title, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DummyCraft.ID, id));
        Item item = factory.apply(new Item.Properties().setId(key).stacksTo(1)
                .component(DataComponents.CUSTOM_NAME, Component.literal(title)));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    static void giveStarterKit(ServerPlayer p) {
        giveOrDrop(p, new ItemStack(GUIDE));
        giveOrDrop(p, new ItemStack(TURN_BELL));
    }

    private static void giveOrDrop(ServerPlayer p, ItemStack item) {
        if (!p.getInventory().add(item)) p.drop(item, false);
    }
}
