package net.opmasterleo.masterantighost.nms;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;

public final class PacketSwapInjector implements Listener {

    private static final String TAG = "PacketSwapInjector";
    private static final String HANDLER_PREFIX = "mag_swap_";
    private static final int PLAYER_OFFHAND_SLOT = 40;
    private static final int CONTAINER_OFFHAND_SLOT = 45;

    private final Plugin plugin;
    private final SwapBuffer swapBuffer;
    private final NmsAccessor nmsAccessor;
    private final FoliaScheduler scheduler;
    private final Consumer<UUID> onPlayerQuit;
    private final Map<UUID, Channel> injectedChannels = new ConcurrentHashMap<>();

    private volatile boolean started;

    public PacketSwapInjector(Plugin plugin,
                              SwapBuffer swapBuffer,
                              NmsAccessor nmsAccessor,
                              FoliaScheduler scheduler,
                              Consumer<UUID> onPlayerQuit) {
        this.plugin = plugin;
        this.swapBuffer = swapBuffer;
        this.nmsAccessor = nmsAccessor;
        this.scheduler = scheduler;
        this.onPlayerQuit = onPlayerQuit;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        int injected = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (injectWithRetry(player, 2)) {
                injected++;
            }
        }

        DebugLogger.info("Swap tracking initialized. Packet-injected players=" + injected
                + ", Bukkit fallback listeners=enabled");
    }

    public void shutdown() {
        if (!started) {
            return;
        }
        started = false;

        HandlerList.unregisterAll(this);

        for (UUID playerId : injectedChannels.keySet()) {
            uninject(playerId);
        }
        injectedChannels.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectWithRetry(event.getPlayer(), 5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuitEvent(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        uninject(playerId);
        onPlayerQuit.accept(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        boolean hadTotemInSwap = isTotem(event.getMainHandItem()) || isTotem(event.getOffHandItem());
        recordSwap(event.getPlayer(), SwapBuffer.SwapType.OFFHAND_SWAP, hadTotemInSwap);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ClickType clickType = event.getClick();
        InventoryAction action = event.getAction();

        if (clickType == ClickType.SWAP_OFFHAND) {
            recordSwap(player, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            return;
        }

        if (clickType == ClickType.NUMBER_KEY
                || action == InventoryAction.HOTBAR_SWAP) {
            recordSwap(player, SwapBuffer.SwapType.NUMBER_KEY, true);
            return;
        }

        if (event.getSlot() == PLAYER_OFFHAND_SLOT || event.getRawSlot() == CONTAINER_OFFHAND_SLOT) {
            recordSwap(player, SwapBuffer.SwapType.WINDOW_CLICK, true);
        }
    }

    private boolean injectWithRetry(Player player, int retries) {
        if (inject(player)) {
            return true;
        }

        if (retries <= 0) {
            return false;
        }

        scheduler.runOnEntityThreadDelayed(player, () -> injectWithRetry(player, retries - 1), 1L);
        return false;
    }

    private boolean inject(Player player) {
        UUID playerId = player.getUniqueId();
        if (injectedChannels.containsKey(playerId)) {
            return true;
        }

        try {
            Channel channel = resolveChannel(player);
            if (channel == null) {
                return false;
            }

            String handlerName = handlerName(playerId);
            channel.eventLoop().execute(() -> {
                if (!channel.isOpen() || channel.pipeline().get(handlerName) != null) {
                    return;
                }

                ChannelDuplexHandler handler = new ChannelDuplexHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        try {
                            handleInboundPacket(playerId, msg);
                        } catch (Throwable throwable) {
                            DebugLogger.debug(TAG, "Inbound packet inspect failed for %s: %s",
                                    playerId, throwable.getMessage());
                        }
                        super.channelRead(ctx, msg);
                    }
                };

                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", handlerName, handler);
                } else {
                    channel.pipeline().addLast(handlerName, handler);
                }
            });

            injectedChannels.put(playerId, channel);
            return true;
        } catch (Throwable throwable) {
            DebugLogger.debug(TAG, "Packet injection unavailable for %s: %s",
                    player.getName(), throwable.getClass().getSimpleName());
            return false;
        }
    }

    private void uninject(UUID playerId) {
        Channel channel = injectedChannels.remove(playerId);
        if (channel == null) {
            return;
        }

        String handlerName = handlerName(playerId);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        });
    }

    private void handleInboundPacket(UUID playerId, Object packet) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        if (packet instanceof ServerboundPlayerActionPacket actionPacket) {
            if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
                recordSwap(player, SwapBuffer.SwapType.OFFHAND_SWAP, true);
            }
            return;
        }

        if (packet instanceof ServerboundContainerClickPacket clickPacket) {
            if (clickPacket.clickType() == net.minecraft.world.inventory.ClickType.SWAP) {
                recordSwap(player, SwapBuffer.SwapType.NUMBER_KEY, true);
                return;
            }
            if (clickPacket.slotNum() == CONTAINER_OFFHAND_SLOT) {
                recordSwap(player, SwapBuffer.SwapType.WINDOW_CLICK, true);
            }
        }
    }

    private void recordSwap(Player player, SwapBuffer.SwapType swapType, boolean optimisticTotem) {
        UUID playerId = player.getUniqueId();
        scheduler.runOnEntityThread(player, () -> {
            long tick = nmsAccessor.getCurrentTick();
            boolean hadTotem = optimisticTotem || nmsAccessor.hasTotemInEitherHand(player);
            swapBuffer.recordSwap(playerId, tick, swapType, hadTotem);
        });
    }

    private static Channel resolveChannel(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object packetListener = getFieldValue(handle, "connection");
        if (packetListener == null) {
            return null;
        }

        Object connection = getFieldValue(packetListener, "connection");
        if (connection == null) {
            return null;
        }

        Field channelField = findFieldByType(connection.getClass(), Channel.class);
        if (channelField == null) {
            return null;
        }
        channelField.setAccessible(true);
        return (Channel) channelField.get(connection);
    }

    private static Object getFieldValue(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> type, Class<?> fieldType) {
        Class<?> current = type;
        while (current != null) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String handlerName(UUID playerId) {
        return HANDLER_PREFIX + playerId.toString().replace("-", "");
    }

    private static boolean isTotem(ItemStack stack) {
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING;
    }
}