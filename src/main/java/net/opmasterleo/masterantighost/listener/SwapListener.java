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

public final class SwapListener implements Listener {

    private static final String TAG = "SwapListener";
    private final SwapBuffer swapBuffer;
    private final NmsAccessor nms;

    public SwapListener(SwapBuffer swapBuffer, NmsAccessor nms) {
        this.swapBuffer = swapBuffer;
        this.nms = nms;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        long tick = nms.getCurrentTick();
        boolean totemInvolved = isTotem(event.getMainHandItem()) || isTotem(event.getOffHandItem());
        swapBuffer.recordSwap(player.getUniqueId(), tick, SwapBuffer.SwapType.OFFHAND_SWAP, totemInvolved);
        DebugLogger.debug(TAG, "%s offhand swap tick=%d totem=%s", player.getName(), tick, totemInvolved);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        long tick = nms.getCurrentTick();
        boolean totemInvolved = false;
        SwapBuffer.SwapType type = SwapBuffer.SwapType.WINDOW_CLICK;
        ClickType clickType = event.getClick();

        if (clickType == ClickType.NUMBER_KEY) {
            type = SwapBuffer.SwapType.NUMBER_KEY;
            int hotbarSlot = event.getHotbarButton();
            ItemStack hotbarItem = player.getInventory().getItem(hotbarSlot);
            ItemStack clickedItem = event.getCurrentItem();
            totemInvolved = isTotem(hotbarItem) || isTotem(clickedItem);
            if (event.getRawSlot() == 40) {
                totemInvolved = totemInvolved || isTotem(event.getCursor());
            }
        } else if (clickType.isShiftClick()) {
            totemInvolved = isTotem(event.getCurrentItem());
        } else if (event.getRawSlot() == 40) {
            totemInvolved = isTotem(event.getCurrentItem()) || isTotem(event.getCursor());
        } else {
            return;
        }

        swapBuffer.recordSwap(player.getUniqueId(), tick, type, totemInvolved);
        DebugLogger.debug(TAG, "%s inventory swap tick=%d type=%s totem=%s", player.getName(), tick, type, totemInvolved);
    }

    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }
}
