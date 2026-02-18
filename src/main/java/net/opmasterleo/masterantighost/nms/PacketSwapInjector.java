package net.opmasterleo.masterantighost.nms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;

public final class PacketSwapInjector {

    private static final String TAG = "PacketSwapInjector";
    private static final String HANDLER_PREFIX = "mag_swap_inject_";

    private final Plugin plugin;
    private final SwapBuffer swapBuffer;
    private final NmsAccessor nms;
    private final FoliaScheduler scheduler;
    private final Consumer<UUID> disconnectHandler;

    private final Map<UUID, InjectionState> injected;
    private final ConcurrentLinkedQueue<SwapSignal> pendingSignals;
    private final Map<Class<?>, Method> actionGetterCache;
    private final Map<Class<?>, Method> clickTypeGetterCache;
    private final Map<Class<?>, Method> slotGetterCache;
    private final Map<Class<?>, Method> buttonGetterCache;
    private final Map<Class<?>, Method> handleGetterCache;
    private final Map<Class<?>, Field> connectionFieldCache;
    private final Map<Class<?>, Field> channelFieldCache;
    private volatile FoliaScheduler.ScheduledHandle refreshHandle;
    private volatile FoliaScheduler.ScheduledHandle drainHandle;

    public PacketSwapInjector(Plugin plugin,
                              SwapBuffer swapBuffer,
                              NmsAccessor nms,
                              FoliaScheduler scheduler,
                              Consumer<UUID> disconnectHandler) {
        this.plugin = plugin;
        this.swapBuffer = swapBuffer;
        this.nms = nms;
        this.scheduler = scheduler;
        this.disconnectHandler = disconnectHandler;
        this.injected = new ConcurrentHashMap<>(128);
        this.pendingSignals = new ConcurrentLinkedQueue<>();
        this.actionGetterCache = new ConcurrentHashMap<>(8);
        this.clickTypeGetterCache = new ConcurrentHashMap<>(8);
        this.slotGetterCache = new ConcurrentHashMap<>(8);
        this.buttonGetterCache = new ConcurrentHashMap<>(8);
        this.handleGetterCache = new ConcurrentHashMap<>(8);
        this.connectionFieldCache = new ConcurrentHashMap<>(8);
        this.channelFieldCache = new ConcurrentHashMap<>(8);
    }

    public void start() {
        refreshInjections();
        refreshHandle = scheduler.scheduleGlobalTimer(this::refreshInjections, 20L, 20L);
        drainHandle = scheduler.scheduleGlobalTimer(this::drainSignals, 1L, 1L);
    }

    public void shutdown() {
        FoliaScheduler.ScheduledHandle localRefresh = refreshHandle;
        if (localRefresh != null) {
            localRefresh.cancel();
            refreshHandle = null;
        }
        FoliaScheduler.ScheduledHandle localDrain = drainHandle;
        if (localDrain != null) {
            localDrain.cancel();
            drainHandle = null;
        }
        drainSignals();
        for (InjectionState state : injected.values()) {
            safeUninject(state);
            disconnectHandler.accept(state.playerId());
        }
        injected.clear();
    }

    private void refreshInjections() {
        Set<UUID> online = ConcurrentHashMap.newKeySet(Bukkit.getOnlinePlayers().size() + 2);
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            injected.computeIfAbsent(playerId, id -> {
                Channel channel = resolveChannel(player);
                if (channel == null) {
                    return null;
                }
                InjectionState state = new InjectionState(id, channel, HANDLER_PREFIX + id.toString().replace("-", ""));
                safeInject(player, state);
                return state;
            });
        }

        injected.forEach((playerId, state) -> {
            if (state != null && !online.contains(playerId)) {
                safeUninject(state);
                injected.remove(playerId, state);
                disconnectHandler.accept(playerId);
            }
        });
    }

    private void drainSignals() {
        long tick = nms.getCurrentTick();
        SwapSignal signal;
        while ((signal = pendingSignals.poll()) != null) {
            final SwapSignal resolvedSignal = signal;
            Player player = Bukkit.getPlayer(resolvedSignal.playerId());
            if (player == null || !player.isOnline()) {
                disconnectHandler.accept(resolvedSignal.playerId());
                continue;
            }
            scheduler.runOnEntityThread(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                boolean hadTotem = resolveTotemSignal(player, resolvedSignal);
                swapBuffer.recordSwap(resolvedSignal.playerId(), tick, resolvedSignal.type(), hadTotem);
            });
        }
    }

    private boolean resolveTotemSignal(Player player, SwapSignal signal) {
        if (signal.type() == SwapBuffer.SwapType.OFFHAND_SWAP) {
            return isTotem(player.getInventory().getItemInOffHand()) || isTotem(player.getInventory().getItemInMainHand());
        }
        if (signal.slot() != 45) {
            return false;
        }
        if (isTotem(player.getInventory().getItemInOffHand())) {
            return true;
        }
        int button = signal.button();
        if (button >= 0 && button <= 8) {
            return isTotem(player.getInventory().getItem(button));
        }
        try {
            return isTotem(player.getItemOnCursor()) || isTotem(player.getOpenInventory().getItem(signal.slot()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }

    private void safeInject(Player player, InjectionState state) {
        Channel channel = state.channel();
        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(state.handlerName()) != null) {
                    return;
                }
                ChannelDuplexHandler handler = new SwapPacketHandler(player.getUniqueId());
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", state.handlerName(), handler);
                } else {
                    pipeline.addLast(state.handlerName(), handler);
                }
            } catch (Exception e) {
                DebugLogger.warn(TAG + " inject failed for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    private void safeUninject(InjectionState state) {
        Channel channel = state.channel();
        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(state.handlerName()) != null) {
                    pipeline.remove(state.handlerName());
                }
            } catch (Exception ignored) {
            }
        });
    }

    private Channel resolveChannel(Player player) {
        try {
            Method getHandle = handleGetterCache.computeIfAbsent(player.getClass(), cls -> {
                try {
                    return cls.getMethod("getHandle");
                } catch (NoSuchMethodException e) {
                    return null;
                }
            });
            if (getHandle == null) {
                return null;
            }

            Object serverPlayer = getHandle.invoke(player);
            if (serverPlayer == null) {
                return null;
            }

            Field connectionField = connectionFieldCache.computeIfAbsent(serverPlayer.getClass(), cls ->
                    findField(cls, "connection", "c", "b", "playerConnection")
            );
            if (connectionField == null) {
                return null;
            }

            Object listener = connectionField.get(serverPlayer);
            if (listener == null) {
                return null;
            }

            Field netConnectionField = connectionFieldCache.computeIfAbsent(listener.getClass(), cls ->
                    findField(cls, "connection", "h", "b", "c")
            );
            if (netConnectionField == null) {
                return null;
            }

            Object netConnection = netConnectionField.get(listener);
            if (netConnection == null) {
                return null;
            }

            Field channelField = channelFieldCache.computeIfAbsent(netConnection.getClass(), cls ->
                    findField(cls, "channel", "n", "m")
            );
            if (channelField == null) {
                return null;
            }
            Object channelObj = channelField.get(netConnection);
            if (channelObj instanceof Channel channel) {
                return channel;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Field findField(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Field field = type.getField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Field field : type.getDeclaredFields()) {
            String lower = field.getName().toLowerCase(Locale.ROOT);
            for (String target : names) {
                if (lower.equals(target.toLowerCase(Locale.ROOT))) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private final class SwapPacketHandler extends ChannelDuplexHandler {

        private final UUID playerId;

        private SwapPacketHandler(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            tryHandle(msg);
            super.channelRead(ctx, msg);
        }

        private void tryHandle(Object packet) {
            Class<?> packetClass = packet.getClass();
            String simple = packetClass.getSimpleName();

            if (simple.endsWith("ServerboundPlayerActionPacket")) {
                Method getter = actionGetterCache.computeIfAbsent(packetClass, this::resolveActionGetter);
                if (getter != null) {
                    try {
                        Object action = getter.invoke(packet);
                        if (action instanceof Enum<?> actionEnum
                                && "SWAP_ITEM_WITH_OFFHAND".equals(actionEnum.name())) {
                            pendingSignals.add(new SwapSignal(playerId, SwapBuffer.SwapType.OFFHAND_SWAP, -1, -1));
                        }
                    } catch (Exception ignored) {
                    }
                }
                return;
            }

            if (simple.endsWith("ServerboundContainerClickPacket")) {
                Method clickTypeGetter = clickTypeGetterCache.computeIfAbsent(packetClass, this::resolveClickTypeGetter);
                if (clickTypeGetter == null) {
                    return;
                }

                try {
                    Object clickType = clickTypeGetter.invoke(packet);
                    if (!(clickType instanceof Enum<?> enumType)) {
                        return;
                    }

                    String clickName = enumType.name();
                    Method slotGetter = slotGetterCache.computeIfAbsent(packetClass, this::resolveSlotGetter);
                    int slot = -1;
                    if (slotGetter != null) {
                        Object slotObj = slotGetter.invoke(packet);
                        if (slotObj instanceof Number slotNum) {
                            slot = slotNum.intValue();
                        }
                    }
                    Method buttonGetter = buttonGetterCache.computeIfAbsent(packetClass, this::resolveButtonGetter);
                    int button = -1;
                    if (buttonGetter != null) {
                        Object buttonObj = buttonGetter.invoke(packet);
                        if (buttonObj instanceof Number buttonNum) {
                            button = buttonNum.intValue();
                        }
                    }

                    if ("SWAP".equals(clickName)) {
                        if (slot == 45) {
                            pendingSignals.add(new SwapSignal(playerId, SwapBuffer.SwapType.NUMBER_KEY, slot, button));
                        }
                        return;
                    }

                    if ("QUICK_MOVE".equals(clickName) || "PICKUP".equals(clickName) || "PICKUP_ALL".equals(clickName)) {
                        if (slot == 45) {
                            pendingSignals.add(new SwapSignal(playerId, SwapBuffer.SwapType.WINDOW_CLICK, slot, button));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        private Method resolveActionGetter(Class<?> type) {
            try {
                Method method = type.getMethod("getAction");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().isEnum()) {
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }

        private Method resolveClickTypeGetter(Class<?> type) {
            try {
                Method method = type.getMethod("getClickType");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().isEnum()
                        && method.getReturnType().getSimpleName().endsWith("ClickType")) {
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }

        private Method resolveSlotGetter(Class<?> type) {
            try {
                Method method = type.getMethod("getSlotNum");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("slot")) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
            return null;
        }

        private Method resolveButtonGetter(Class<?> type) {
            try {
                Method method = type.getMethod("getButtonNum");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method method = type.getMethod("getButton");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("button") || name.contains("mouse")) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
            return null;
        }
    }

    private record InjectionState(UUID playerId, Channel channel, String handlerName) {
    }

    private record SwapSignal(UUID playerId, SwapBuffer.SwapType type, int slot, int button) {
    }
}
