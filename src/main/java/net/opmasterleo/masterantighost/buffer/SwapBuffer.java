package net.opmasterleo.masterantighost.buffer;

import net.opmasterleo.masterantighost.debug.DebugLogger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class SwapBuffer {

    public enum SwapType {
        OFFHAND_SWAP,
        WINDOW_CLICK,
        NUMBER_KEY
    }

    public record SwapEntry(long tick, SwapType type, boolean hadTotem) {
    }

    private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<SwapEntry>> playerSwaps;
    private final AtomicInteger windowTicks;

    public SwapBuffer(int windowTicks) {
        this.playerSwaps = new ConcurrentHashMap<>(64);
        this.windowTicks = new AtomicInteger(windowTicks);
    }

    public void recordSwap(UUID playerId, long tick, SwapType type, boolean hadTotem) {
        ConcurrentLinkedDeque<SwapEntry> entries = playerSwaps.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        entries.addLast(new SwapEntry(tick, type, hadTotem));
        int trimWindow = windowTicks.get() + 20;
        while (true) {
            SwapEntry head = entries.peekFirst();
            if (head == null || tick - head.tick() <= trimWindow) {
                break;
            }
            entries.pollFirst();
        }
        DebugLogger.debug("SwapBuffer", "swap %s player=%s tick=%d hadTotem=%s", type, playerId, tick, hadTotem);
    }

    public boolean hasRecentTotemActivity(UUID playerId, long currentTick) {
        ConcurrentLinkedDeque<SwapEntry> entries = playerSwaps.get(playerId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        int window = windowTicks.get();
        for (var it = entries.descendingIterator(); it.hasNext(); ) {
            SwapEntry entry = it.next();
            if (currentTick - entry.tick() > window) {
                break;
            }
            if (entry.hadTotem()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyRecentSwapActivity(UUID playerId, long currentTick) {
        ConcurrentLinkedDeque<SwapEntry> entries = playerSwaps.get(playerId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        SwapEntry latest = entries.peekLast();
        if (latest == null) {
            return false;
        }
        return currentTick - latest.tick() <= windowTicks.get();
    }

    public void cleanupExpired(long currentTick) {
        int window = windowTicks.get();
        playerSwaps.forEach((playerId, entries) -> {
            while (!entries.isEmpty()) {
                SwapEntry head = entries.peekFirst();
                if (head != null && currentTick - head.tick() > window + 20) {
                    entries.pollFirst();
                } else {
                    break;
                }
            }
            if (entries.isEmpty()) {
                playerSwaps.remove(playerId, entries);
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
}
