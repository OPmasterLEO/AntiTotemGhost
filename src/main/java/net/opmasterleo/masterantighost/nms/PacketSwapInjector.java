package net.opmasterleo.masterantighost.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PacketSwapInjector {

    private static final String TAG = "PacketSwapInjector";
    private static final String HANDLER_PREFIX = "mag_swap_inject_";

    private final Plugin plugin;
    private final SwapBuffer swapBuffer;
    private final NmsAccessor nms;
    private final FoliaScheduler scheduler;
    private final Consumer<UUID> disconnectHandler;

    private final Map<UUID, InjectionState> injected;
    private final Map<UUID, SwapSignal> queuedSignals;
    private final Set<UUID> scheduledPlayers;
    private final Map<Class<?>, Method> actionGetterCache;
    private final Map<Class<?>, Method> clickTypeGetterCache;
    private final Map<Class<?>, Method> slotGetterCache;
    private final Map<Class<?>, Method> buttonGetterCache;
    private final Map<Class<?>, PacketKind> packetKindCache;
    private final Map<Class<?>, Method> handleGetterCache;
    private final Map<Class<?>, Field> playerConnectionFieldCache;
    private final Map<Class<?>, Field> listenerConnectionFieldCache;
    private final Map<Class<?>, Field> channelFieldCache;

    private volatile FoliaScheduler.ScheduledHandle refreshHandle;

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
        this.queuedSignals = new ConcurrentHashMap<>(128);
        this.scheduledPlayers = ConcurrentHashMap.newKeySet(128);
        this.actionGetterCache = new ConcurrentHashMap<>(8);
        this.clickTypeGetterCache = new ConcurrentHashMap<>(8);
        this.slotGetterCache = new ConcurrentHashMap<>(8);
        this.buttonGetterCache = new ConcurrentHashMap<>(8);
        this.packetKindCache = new ConcurrentHashMap<>(16);
        this.handleGetterCache = new ConcurrentHashMap<>(8);
        this.playerConnectionFieldCache = new ConcurrentHashMap<>(8);
        this.listenerConnectionFieldCache = new ConcurrentHashMap<>(8);
        this.channelFieldCache = new ConcurrentHashMap<>(8);
    }

    public void start() {
        refreshInjections();
        refreshHandle = scheduler.scheduleGlobalTimer(this::refreshInjections, 20L, 20L);
    }

    public void shutdown() {
        FoliaScheduler.ScheduledHandle localRefresh = refreshHandle;
        if (localRefresh != null) {
            localRefresh.cancel();
            refreshHandle = null;
        }
        queuedSignals.clear();
        scheduledPlayers.clear();
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
                queuedSignals.remove(playerId);
                scheduledPlayers.remove(playerId);
                disconnectHandler.accept(playerId);
            }
        });
    }

    private void enqueueSignal(SwapSignal signal) {
        UUID playerId = signal.playerId();
        queuedSignals.merge(playerId, signal, this::mergeSignals);
        if (!scheduledPlayers.add(playerId)) {
            return;
        }
        scheduler.runOnEntityThreadIfOnline(playerId, player -> {
            if (!player.isOnline()) {
                queuedSignals.remove(playerId);
                scheduledPlayers.remove(playerId);
                return;
            }
            processPlayerSignals(playerId, player);
        });
    }

    private void processPlayerSignals(UUID playerId, Player player) {
        try {
            while (true) {
                SwapSignal signal = queuedSignals.remove(playerId);
                if (signal == null) {
                    break;
                }
                boolean hadTotem = resolveTotemSignal(player, signal);
                long tick = nms.getCurrentTick();
                swapBuffer.recordSwap(playerId, tick, signal.type(), hadTotem);
            }
        } finally {
            scheduledPlayers.remove(playerId);
            if (queuedSignals.containsKey(playerId) && scheduledPlayers.add(playerId)) {
                scheduler.runOnEntityThreadIfOnline(playerId, target -> {
                    if (!target.isOnline()) {
                        queuedSignals.remove(playerId);
                        scheduledPlayers.remove(playerId);
                        return;
                    }
                    processPlayerSignals(playerId, target);
                });
            }
        }
    }

    private SwapSignal mergeSignals(SwapSignal left, SwapSignal right) {
        if (signalPriority(right.type()) > signalPriority(left.type())) {
            return right;
        }
        int mergedSlot = right.slot() >= 0 ? right.slot() : left.slot();
        int mergedButton = right.button() >= 0 ? right.button() : left.button();
        return new SwapSignal(left.playerId(), left.type(), mergedSlot, mergedButton);
    }

    private int signalPriority(SwapBuffer.SwapType type) {
        return switch (type) {
            case OFFHAND_SWAP -> 3;
            case NUMBER_KEY -> 2;
            case WINDOW_CLICK -> 1;
        };
    }

    private boolean resolveTotemSignal(Player player, SwapSignal signal) {
        if (signal.type() == SwapBuffer.SwapType.OFFHAND_SWAP) {
            return isTotem(player.getInventory().getItemInOffHand()) || isTotem(player.getInventory().getItemInMainHand());
        }

        if (!isLikelyOffhandInteraction(player, signal)) {
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

    private boolean isLikelyOffhandInteraction(Player player, SwapSignal signal) {
        int slot = signal.slot();
        if (slot < 0) {
            return false;
        }
        if (slot == 45 || slot == 40) {
            return true;
        }

        try {
            int topSize = player.getOpenInventory().getTopInventory().getSize();
            if (slot == topSize + 45 || slot == topSize + 40) {
                return true;
            }
        } catch (Exception ignored) {
        }

        if (signal.type() == SwapBuffer.SwapType.NUMBER_KEY) {
            int button = signal.button();
            if (button >= 0 && button <= 8 && isTotem(player.getInventory().getItem(button))) {
                return true;
            }
        }

        return false;
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

            Field connectionField = playerConnectionFieldCache.computeIfAbsent(serverPlayer.getClass(), cls ->
                    findField(cls, "connection", "c", "b", "playerConnection")
            );
            if (connectionField == null) {
                return null;
            }

            Object listener = connectionField.get(serverPlayer);
            if (listener == null) {
                return null;
            }

            Field netConnectionField = listenerConnectionFieldCache.computeIfAbsent(listener.getClass(), cls ->
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
            PacketKind kind = packetKindCache.computeIfAbsent(packetClass, this::resolvePacketKind);

            if (kind == PacketKind.PLAYER_ACTION) {
                Method actionGetter = actionGetterCache.computeIfAbsent(packetClass, this::resolveActionGetter);
                if (actionGetter == null) {
                    return;
                }
                try {
                    Object action = actionGetter.invoke(packet);
                    if (action instanceof Enum<?> actionEnum && "SWAP_ITEM_WITH_OFFHAND".equals(actionEnum.name())) {
                        enqueueSignal(new SwapSignal(playerId, SwapBuffer.SwapType.OFFHAND_SWAP, -1, -1));
                    }
                } catch (Exception ignored) {
                }
                return;
            }

            if (kind != PacketKind.CONTAINER_CLICK) {
                return;
            }

            Method clickTypeGetter = clickTypeGetterCache.computeIfAbsent(packetClass, this::resolveClickTypeGetter);
            if (clickTypeGetter == null) {
                return;
            }

            try {
                Object clickType = clickTypeGetter.invoke(packet);
                if (!(clickType instanceof Enum<?> clickEnum)) {
                    return;
                }

                String click = clickEnum.name();
                if (!("SWAP".equals(click)
                        || "QUICK_MOVE".equals(click)
                        || "PICKUP".equals(click)
                        || "PICKUP_ALL".equals(click))) {
                    return;
                }

                int slot = -1;
                Method slotGetter = slotGetterCache.computeIfAbsent(packetClass, this::resolveSlotGetter);
                if (slotGetter != null) {
                    Object slotObj = slotGetter.invoke(packet);
                    if (slotObj instanceof Number slotNum) {
                        slot = slotNum.intValue();
                    }
                }

                int button = -1;
                Method buttonGetter = buttonGetterCache.computeIfAbsent(packetClass, this::resolveButtonGetter);
                if (buttonGetter != null) {
                    Object buttonObj = buttonGetter.invoke(packet);
                    if (buttonObj instanceof Number buttonNum) {
                        button = buttonNum.intValue();
                    }
                }

                if ("SWAP".equals(click)) {
                    enqueueSignal(new SwapSignal(playerId, SwapBuffer.SwapType.NUMBER_KEY, slot, button));
                } else {
                    enqueueSignal(new SwapSignal(playerId, SwapBuffer.SwapType.WINDOW_CLICK, slot, button));
                }
            } catch (Exception ignored) {
            }
        }

        private PacketKind resolvePacketKind(Class<?> type) {
            Method actionGetter = resolveActionGetter(type);
            if (actionGetter != null && actionGetter.getReturnType().isEnum()) {
                Object[] constants = actionGetter.getReturnType().getEnumConstants();
                if (containsEnum(constants, "SWAP_ITEM_WITH_OFFHAND")) {
                    return PacketKind.PLAYER_ACTION;
                }
            }

            Method clickTypeGetter = resolveClickTypeGetter(type);
            if (clickTypeGetter != null && clickTypeGetter.getReturnType().isEnum()) {
                Object[] constants = clickTypeGetter.getReturnType().getEnumConstants();
                if (containsEnum(constants, "SWAP")
                        || containsEnum(constants, "PICKUP")
                        || containsEnum(constants, "QUICK_MOVE")) {
                    return PacketKind.CONTAINER_CLICK;
                }
            }

            return PacketKind.OTHER;
        }

        private boolean containsEnum(Object[] constants, String name) {
            if (constants == null) {
                return false;
            }
            for (Object value : constants) {
                if (value instanceof Enum<?> enumValue && name.equals(enumValue.name())) {
                    return true;
                }
            }
            return false;
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
                    String methodName = method.getName().toLowerCase(Locale.ROOT);
                    if (methodName.contains("action")) {
                        method.setAccessible(true);
                        return method;
                    }
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
                if (method.getParameterCount() == 0 && method.getReturnType().isEnum()) {
                    String methodName = method.getName().toLowerCase(Locale.ROOT);
                    String returnName = method.getReturnType().getSimpleName().toLowerCase(Locale.ROOT);
                    if (methodName.contains("click") || returnName.contains("clicktype")) {
                        method.setAccessible(true);
                        return method;
                    }
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
                if (method.getParameterCount() == 0
                        && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
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
                if (method.getParameterCount() == 0
                        && (method.getReturnType() == int.class || method.getReturnType() == Integer.class)) {
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

    private enum PacketKind {
        PLAYER_ACTION,
        CONTAINER_CLICK,
        OTHER
    }
}
