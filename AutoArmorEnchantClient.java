package dev.autoarmor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

/**
 * Open an anvil with both input slots empty and this mod applies every useful
 * enchanted book in your inventory to your armor, one by one, then stops.
 * It only uses normal anvil clicks, so the server still decides the result and XP cost.
 */
public final class AutoArmorEnchantClient implements ClientModInitializer {
    private static final int DELAY = 4;        // ticks between clicks
    private static final int MAX_STEPS = 400;  // safety stop
    private static final int OUTPUT_WAIT = 3;  // polls to wait for the server's result

    private static Screen lastScreen;
    private static boolean active;
    private static int cooldown;
    private static int steps;
    private static int waited;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoArmorEnchantClient::tick);
    }

    private static void tick(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (!(screen instanceof AnvilScreen)) {
            lastScreen = null;
            active = false;
            return;
        }
        if (screen != lastScreen) {           // anvil just opened
            lastScreen = screen;
            active = true;
            steps = 0;
            waited = 0;
            cooldown = 10;                    // let the server sync the slots first
        }
        if (!active || client.player == null || client.interactionManager == null) return;
        if (cooldown-- > 0) return;
        if (!(client.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {
            active = false;
            return;
        }

        ItemStack left = handler.getSlot(0).getStack();
        ItemStack right = handler.getSlot(1).getStack();
        ItemStack output = handler.getSlot(2).getStack();

        steps++;
        if (steps == 1 && (!left.isEmpty() || !right.isEmpty() || !handler.getCursorStack().isEmpty())) {
            active = false;                   // player is doing it by hand, stay out of the way
            return;
        }
        if (steps > MAX_STEPS) {
            stop(client, "Stopped: safety limit reached.");
            return;
        }

        // 1) A result is ready: take it.
        if (!output.isEmpty()) {
            int cost = handler.getLevelCost();
            if (!client.player.isCreative() && client.player.experienceLevel < cost) {
                stop(client, "Stopped: not enough XP (next step needs " + cost + " levels).");
                return;
            }
            click(client, handler, 2);
            waited = 0;
            return;
        }

        // 2) Nothing on the left: pick an armor piece that still has a useful book.
        if (left.isEmpty()) {
            if (!right.isEmpty()) {
                click(client, handler, 1);    // return a stray item
                return;
            }
            int armor = findArmorWithBook(handler);
            if (armor < 0) {
                stop(client, "AutoArmorEnchant: done, nothing more to apply.");
                return;
            }
            click(client, handler, armor);
            return;
        }

        // 3) Armor on the left, right slot empty: add a useful book (or give the armor back).
        if (right.isEmpty()) {
            int book = findUsefulBook(handler, left);
            if (book < 0) {
                click(client, handler, 0);
                return;
            }
            click(client, handler, book);
            waited = 0;
            return;
        }

        // 4) Both inputs in but no result yet: wait briefly, then give up.
        if (++waited < OUTPUT_WAIT) {
            cooldown = DELAY;
            return;
        }
        stop(client, "Stopped: anvil gave no result (too expensive or incompatible).");
    }

    private static void click(MinecraftClient client, AnvilScreenHandler handler, int slot) {
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, client.player);
        cooldown = DELAY;
    }

    private static void stop(MinecraftClient client, String message) {
        active = false;
        if (client.player != null) client.player.sendMessage(Text.literal(message), true);
    }

    private static int findArmorWithBook(AnvilScreenHandler handler) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (isArmor(stack) && findUsefulBook(handler, stack) >= 0) return slot;
        }
        return -1;
    }

    private static int findUsefulBook(AnvilScreenHandler handler, ItemStack armor) {
        for (int slot = 3; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isOf(Items.ENCHANTED_BOOK) && bookHelps(stack, armor)) return slot;
        }
        return -1;
    }

    /** True if the book has at least one enchantment that fits, doesn't conflict, and improves the armor. */
    private static boolean bookHelps(ItemStack book, ItemStack armor) {
        ItemEnchantmentsComponent stored = book.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored == null) return false;
        ItemEnchantmentsComponent current =
                armor.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);

        for (RegistryEntry<Enchantment> entry : stored.getEnchantments()) {
            if (!entry.value().isSupportedItem(armor)) continue;
            if (stored.getLevel(entry) <= current.getLevel(entry)) continue;
            boolean conflict = false;
            for (RegistryEntry<Enchantment> existing : current.getEnchantments()) {
                if (!existing.equals(entry) && !Enchantment.canBeCombined(entry, existing)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return true;
        }
        return false;
    }

    private static boolean isArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return id.endsWith("_helmet") || id.endsWith("_chestplate")
                || id.endsWith("_leggings") || id.endsWith("_boots");
    }
}
