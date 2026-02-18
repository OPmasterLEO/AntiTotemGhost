package net.opmasterleo.masterantighost.buffer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.opmasterleo.masterantighost.debug.DebugLogger;

public final class SwapBuffer {

    public enum SwapType {
        OFFHAND_SWAP,
        WINDOW_CLICK,
        NUMBER_KEY
    }

    private static final long UNSET_TICK = Long.MIN_VALUE;

    private final ConcurrentHashMap<UUID, PlayerSwapState> playerSwaps;
    private final AtomicInteger windowTicks;

    public SwapBuffer(int windowTicks) {
        this.playerSwaps = new ConcurrentHashMap<>(64);
        this.windowTicks = new AtomicInteger(windowTicks);
    }

    public void recordSwap(UUID playerId, long tick, SwapType type, boolean hadTotem) {
        PlayerSwapState state = playerSwaps.computeIfAbsent(playerId, ignored -> new PlayerSwapState());
        state.latestSwapTick.accumulateAndGet(tick, Math::max);
        if (hadTotem) {
            state.latestTotemTick.accumulateAndGet(tick, Math::max);
        }
        DebugLogger.debug("SwapBuffer", "swap %s player=%s tick=%d hadTotem=%s", type, playerId, tick, hadTotem);
    }

    public boolean hasRecentTotemActivity(UUID playerId, long currentTick) {
        PlayerSwapState state = playerSwaps.get(playerId);
        if (state == null) {
            return false;
        }

        long latestTotemTick = state.latestTotemTick.get();
        if (latestTotemTick == UNSET_TICK) {
            return false;
        }
        return currentTick - latestTotemTick <= windowTicks.get();
    }

    public boolean hasAnyRecentSwapActivity(UUID playerId, long currentTick) {
        PlayerSwapState state = playerSwaps.get(playerId);
        if (state == null) {
            return false;
        }

        long latestSwapTick = state.latestSwapTick.get();
        if (latestSwapTick == UNSET_TICK) {
            return false;
        }
        return currentTick - latestSwapTick <= windowTicks.get();
    }

    public void cleanupExpired(long currentTick) {
        int retentionTicks = windowTicks.get() + 20;
        playerSwaps.forEach((playerId, state) -> {
            long latest = Math.max(state.latestSwapTick.get(), state.latestTotemTick.get());
            if (latest == UNSET_TICK) {
                playerSwaps.remove(playerId, state);
                return;
            }
            if (currentTick - latest > retentionTicks) {
                playerSwaps.remove(playerId, state);
            }
        });
    }

    public void clearPlayer(UUID playerId) {
        playerSwaps.remove(playerId);
    }

    public void setWindowTicks(int ticks) {
        windowTicks.set(ticks);
    }

    public int getTrackedPlayerCount() {
        return playerSwaps.size();
    }

    private static final class PlayerSwapState {
        private final AtomicLong latestSwapTick = new AtomicLong(UNSET_TICK);
        private final AtomicLong latestTotemTick = new AtomicLong(UNSET_TICK);
    }
}
