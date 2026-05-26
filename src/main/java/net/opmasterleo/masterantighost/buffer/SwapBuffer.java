package net.opmasterleo.masterantighost.buffer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

import net.opmasterleo.masterantighost.debug.DebugLogger;

public final class SwapBuffer {

    public enum SwapType {
        OFFHAND_SWAP,
        WINDOW_CLICK,
        NUMBER_KEY
    }

    private static final int INACTIVE_TICK = -1;
    private static final int BUFFER_SIZE = 16;
    private static final int BUFFER_MASK = BUFFER_SIZE - 1;

    private final ConcurrentHashMap<UUID, AtomicIntegerArray> playerTotemTicks;
    private final ConcurrentHashMap<UUID, AtomicIntegerArray> playerSwapTicks;
    private volatile int windowTicks;

    public SwapBuffer(int windowTicks) {
        this.playerTotemTicks = new ConcurrentHashMap<>(64);
        this.playerSwapTicks = new ConcurrentHashMap<>(64);
        this.windowTicks = windowTicks;
    }

    public void recordSwap(UUID playerId, long tick, SwapType type, boolean hadTotem) {
        int iTick = (int) tick;
        int index = iTick & BUFFER_MASK;

        if (hadTotem) {
            AtomicIntegerArray totemBuffer = playerTotemTicks.computeIfAbsent(playerId, id -> createBuffer());
            totemBuffer.set(index, iTick);
        }

        AtomicIntegerArray swapBuffer = playerSwapTicks.computeIfAbsent(playerId, id -> createBuffer());
        swapBuffer.set(index, iTick);

        DebugLogger.debug("SwapBuffer", "swap %s player=%s tick=%d hadTotem=%s", type, playerId, tick, hadTotem);
    }

    public boolean hasRecentTotemActivity(UUID playerId, long currentTick) {
        return checkRecent(playerTotemTicks.get(playerId), (int) currentTick);
    }

    public boolean hasAnyRecentSwapActivity(UUID playerId, long currentTick) {
        return checkRecent(playerSwapTicks.get(playerId), (int) currentTick);
    }

    private boolean checkRecent(AtomicIntegerArray buffer, int currentTick) {
        if (buffer == null) return false;
        
        int wT = windowTicks;
        // Check the last `wT` ticks directly from the circular buffer
        for (int offset = 0; offset <= wT; offset++) {
            int checkTick = currentTick - offset;
            int index = checkTick & BUFFER_MASK;
            if (buffer.get(index) == checkTick) {
                return true;
            }
        }
        return false;
    }

    public void cleanupExpired(long currentTick) {
        int cTick = (int) currentTick;
        int retention = windowTicks + 40;
        
        playerTotemTicks.entrySet().removeIf(entry -> isStale(entry.getValue(), cTick, retention));
        playerSwapTicks.entrySet().removeIf(entry -> isStale(entry.getValue(), cTick, retention));
    }

    private boolean isStale(AtomicIntegerArray buffer, int currentTick, int retention) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            int tick = buffer.get(i);
            if (tick != INACTIVE_TICK && (currentTick - tick) <= retention) {
                return false;
            }
        }
        return true;
    }

    public void clearPlayer(UUID playerId) {
        playerTotemTicks.remove(playerId);
        playerSwapTicks.remove(playerId);
    }

    public void setWindowTicks(int ticks) {
        this.windowTicks = ticks;
    }

    public int getTrackedPlayerCount() {
        return playerTotemTicks.size();
    }

    private AtomicIntegerArray createBuffer() {
        AtomicIntegerArray arr = new AtomicIntegerArray(BUFFER_SIZE);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            arr.set(i, INACTIVE_TICK);
        }
        return arr;
    }
}
