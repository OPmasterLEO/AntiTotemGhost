package net.opmasterleo.masterantighost.listener;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for inventory interactions that may move totems to the offhand.
 *
 * <p><b>Purpose:</b> Records swap events in the {@link SwapBuffer} so that the
 * reconciliation system can detect totems "in transit" — being swapped to the
 * offhand but not yet reflected in NMS container state at the tick of damage.</p>
 *
 * <p><b>Tracked Interactions:</b>
 * <ul>
 *   <li>{@link PlayerSwapHandItemsEvent} — F key swap (most common in crystal PvP)</li>
 *   <li>{@link InventoryClickEvent} — Window click, shift-click, number-key swap</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Events run on the entity's region thread (Folia) or main
 * thread (Paper). SwapBuffer uses ConcurrentHashMap + ConcurrentLinkedDeque for
 * thread-safe recording from any region thread.</p>
 *
 * <p><b>Performance:</b> Material.TOTEM_OF_UNDYING checks are O(1) enum comparisons.
 * SwapBuffer recording (ConcurrentLinkedDeque.addLast) is O(1) lock-free.</p>
 *
 * <p><b>Event Priority: MONITOR</b> — We don't modify these events, only observe them.
 * MONITOR priority ensures we see the final state after other plugins have processed.
 * ignoreCancelled: true — cancelled interactions mean the swap didn't happen.</p>
 */
public final class SwapListener implements Listener {

    private static final String TAG = "SwapListener";

    private final SwapBuffer swapBuffer;
    private final NmsAccessor nms;

    public SwapListener(SwapBuffer swapBuffer, NmsAccessor nms) {
        this.swapBuffer = swapBuffer;
        this.nms = nms;
    }

    /**
     * Track F-key offhand swap events.
     *
     * <p>PlayerSwapHandItemsEvent fires when a player presses F (default keybind)
     * to swap mainhand ↔ offhand. In crystal PvP, this is the primary way players
     * move fresh totems to the offhand after a pop.</p>
     *
     * <p>The event provides both the mainHandItem (going TO offhand) and offHandItem
     * (going TO mainhand) AFTER the swap. We check if a totem is involved in either
     * direction.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        long tick = nms.getCurrentTick();

        // Check if either the item going TO offhand or FROM offhand is a totem.
        // getMainHandItem() = item that WAS in mainhand, now going TO offhand.
        // getOffHandItem() = item that WAS in offhand, now going TO mainhand.
        boolean totemInvolved = isTotem(event.getMainHandItem()) || isTotem(event.getOffHandItem());

        swapBuffer.recordSwap(
                player.getUniqueId(),
                tick,
                SwapBuffer.SwapType.OFFHAND_SWAP,
                totemInvolved
        );

        DebugLogger.debug(TAG, "%s F-key swap at tick %d (totem: %s)",
                player.getName(), tick, totemInvolved);
    }

    /**
     * Track inventory click events that may move totems.
     *
     * <p>Covers multiple interaction patterns:
     * <ul>
     *   <li><b>NUMBER_KEY:</b> Player presses a number key to swap hotbar slot with clicked slot.
     *       If the offhand slot (slot 40) is involved, this can move a totem.</li>
     *   <li><b>SHIFT_CLICK:</b> Shift-click on a totem in the inventory may move it
     *       to the offhand if the mainhand and armor slots are full.</li>
     *   <li><b>Direct click:</b> Pick up totem with cursor, place in offhand slot.</li>
     * </ul>
     *
     * <p><b>Slot 40:</b> In the player inventory container, slot 40 is the offhand.
     * This is the raw slot index, not the Bukkit inventory slot number.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        long tick = nms.getCurrentTick();
        boolean totemInvolved = false;
        SwapBuffer.SwapType type = SwapBuffer.SwapType.WINDOW_CLICK;

        ClickType clickType = event.getClick();

        // ── Number Key Swap ─────────────────────────────────────────────────────
        // When a player presses a number key in an inventory, the clicked slot
        // swaps with the corresponding hotbar slot. If the clicked slot is the
        // offhand slot (raw slot 40), a totem could be moved.
        if (clickType == ClickType.NUMBER_KEY) {
            type = SwapBuffer.SwapType.NUMBER_KEY;

            // Check the hotbar slot being swapped (getHotbarButton() returns 0-8)
            int hotbarSlot = event.getHotbarButton();
            ItemStack hotbarItem = player.getInventory().getItem(hotbarSlot);
            ItemStack clickedItem = event.getCurrentItem();

            totemInvolved = isTotem(hotbarItem) || isTotem(clickedItem);

            // Also check if this involves the offhand raw slot (40)
            if (event.getRawSlot() == 40) {
                totemInvolved = totemInvolved || isTotem(event.getCursor());
            }
        }
        // ── Shift Click ─────────────────────────────────────────────────────────
        else if (clickType.isShiftClick()) {
            totemInvolved = isTotem(event.getCurrentItem());
        }
        // ── Direct Click on Offhand Slot ────────────────────────────────────────
        else if (event.getRawSlot() == 40) {
            // Player clicked the offhand slot directly. Check cursor and slot item.
            totemInvolved = isTotem(event.getCurrentItem()) || isTotem(event.getCursor());
        }
        // ── Other Click Types ───────────────────────────────────────────────────
        else {
            // Only track interactions that could potentially move totems to offhand.
            // Other click types (drop, creative, etc.) are irrelevant for our buffer.
            return;
        }

        swapBuffer.recordSwap(
                player.getUniqueId(),
                tick,
                type,
                totemInvolved
        );

        DebugLogger.debug(TAG, "%s inventory %s at tick %d (totem: %s, slot: %d)",
                player.getName(), type, tick, totemInvolved, event.getRawSlot());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Check if an ItemStack is a Totem of Undying.
     * Null-safe: returns false for null or air stacks.
     */
    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }
}
