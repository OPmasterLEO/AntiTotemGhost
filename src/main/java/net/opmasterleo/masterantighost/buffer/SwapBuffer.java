package net.opmasterleo.masterantighost.buffer;

import net.opmasterleo.masterantighost.debug.DebugLogger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rolling tick-indexed buffer tracking recent totem-related interactions.
 *
 * <p><b>Purpose:</b> In crystal PvP, players rapidly swap totems to the offhand.
 * Due to client→server packet latency, a swap may be "in flight" when lethal
 * damage arrives. The swap buffer records recent swap activity so reconciliation
 * can account for totems that were being moved but hadn't yet arrived in the
 * NMS offhand slot at the exact tick of damage.</p>
 *
 * <p><b>Tracked Interactions:</b>
 * <ul>
 *   <li>{@link SwapType#OFFHAND_SWAP} — Player pressed F key (PlayerSwapHandItemsEvent)</li>
 *   <li>{@link SwapType#WINDOW_CLICK} — Shift-click or direct click involving totem</li>
 *   <li>{@link SwapType#NUMBER_KEY} — Number key press to hotbar swap totem</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * <ul>
 *   <li>{@link ConcurrentHashMap} for the per-player entry map.
 *       Chosen over HashMap because swap events and reconciliation reads may occur
 *       on different region threads (e.g., damage from a crystal in region A affects
 *       a player who just swapped in region A — same thread in this case, but the
 *       cleanup task runs on the Global thread).</li>
 *   <li>{@link ConcurrentLinkedDeque} for per-player entry lists.
 *       Lock-free append (addLast) from event handlers and lock-free iteration
 *       from reconciliation. Thread-safe removal during cleanup.</li>
 *   <li>{@link AtomicInteger} for windowTicks allows runtime config changes
 *       without synchronization.</li>
 * </ul>
 *
 * <p><b>Memory Model:</b> Each SwapEntry is immutable. The deque only grows during
 * swap events and shrinks during periodic cleanup. Maximum entries per player per
 * swap window is bounded by player interaction rate (~20 swaps/second theoretical max),
 * so memory is negligible.</p>
 */
public final class SwapBuffer {

    /**
     * Types of totem-related interactions tracked by the buffer.
     */
    public enum SwapType {
        /** F-key offhand swap (PlayerSwapHandItemsEvent) */
        OFFHAND_SWAP,
        /** Window click involving totem (InventoryClickEvent) */
        WINDOW_CLICK,
        /** Number-key hotbar swap involving totem */
        NUMBER_KEY
    }

    /**
     * Immutable record of a single swap event.
     * Stored in the per-player deque for the rolling window.
     */
    public record SwapEntry(
            long tick,          // Server tick when swap occurred
            SwapType type,      // Type of swap interaction
            boolean hadTotem    // Whether the swap involved a totem
    ) {
    }

    // ── Data Structures ─────────────────────────────────────────────────────────

    // ConcurrentHashMap: UUID → per-player swap deque.
    // Justification: Multiple region threads may record swaps for different players
    // concurrently. ConcurrentHashMap provides segment-level locking (Java 8+
    // uses CAS + synchronized on tree nodes) for O(1) put/get without global locks.
    private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<SwapEntry>> playerSwaps;

    // AtomicInteger: allows runtime config changes without synchronization.
    // The setWindowTicks() method can be called from the Global thread during
    // config reload while region threads are reading this value.
    private final AtomicInteger windowTicks;

    /**
     * @param windowTicks number of ticks to retain entries (swap buffer window)
     */
    public SwapBuffer(int windowTicks) {
        // Initial capacity 64: sized for a typical 50-player crystal PvP server.
        // Load factor 0.75 (default): good balance of space vs. resize frequency.
        this.playerSwaps = new ConcurrentHashMap<>(64);
        this.windowTicks = new AtomicInteger(windowTicks);
    }

    // ── Recording ───────────────────────────────────────────────────────────────

    /**
     * Record a swap event for a player.
     *
     * <p>Called from event handlers on the entity's region thread.
     * The ConcurrentLinkedDeque.addLast() is lock-free and O(1).</p>
     *
     * @param playerId  the player's UUID
     * @param tick      current server tick
     * @param type      the type of swap interaction
     * @param hadTotem  whether the interaction involved a totem
     */
    public void recordSwap(UUID playerId, long tick, SwapType type, boolean hadTotem) {
        // computeIfAbsent: atomically creates the deque if absent.
        // ConcurrentLinkedDeque chosen over ArrayList for lock-free concurrent access.
        ConcurrentLinkedDeque<SwapEntry> entries = playerSwaps.computeIfAbsent(
                playerId, k -> new ConcurrentLinkedDeque<>()
        );

        entries.addLast(new SwapEntry(tick, type, hadTotem));

        DebugLogger.debug("SwapBuffer", "Recorded %s for %s at tick %d (hadTotem=%s)",
                type, playerId, tick, hadTotem);
    }

    // ── Querying ────────────────────────────────────────────────────────────────

    /**
     * Check if a player has any recent totem-related swap activity within the buffer window.
     *
     * <p>Called during reconciliation on the entity's region thread.
     * Iterates the ConcurrentLinkedDeque in reverse (newest first) for early exit.</p>
     *
     * @param playerId    the player's UUID
     * @param currentTick the current server tick
     * @return true if a totem swap was recorded within the window
     */
    public boolean hasRecentTotemActivity(UUID playerId, long currentTick) {
        ConcurrentLinkedDeque<SwapEntry> entries = playerSwaps.get(playerId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        int window = windowTicks.get();

        // Iterate newest-first (descendingIterator) for O(1) best case:
        // if the most recent entry is within the window, we're done.
        for (var it = entries.descendingIterator(); it.hasNext(); ) {
            SwapEntry entry = it.next();

            // Entries older than the window — stop searching.
            // Since entries are chronologically ordered (addLast), once we pass
            // the window boundary, all remaining entries are also expired.
            if (currentTick - entry.tick() > window) {
                break;
            }

            // Found a totem swap within the window.
            if (entry.hadTotem()) {
                DebugLogger.debug("SwapBuffer", "Found recent totem %s for %s at tick %d (window: %d)",
                        entry.type(), playerId, entry.tick(), window);
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a player has any swap activity at all (totem or not) within the window.
     * Used for heuristic: if any swap happened recently, the player might be mid-swap.
     */
    public boolean hasAnyRecentSwapActivity(UUID playerId, long currentTick) {
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
            return true; // Any swap within window
        }
        return false;
    }

    // ── Maintenance ─────────────────────────────────────────────────────────────

    /**
     * Remove expired entries from all player buffers.
     *
     * <p>Called periodically from the Global Scheduler (every 100 ticks).
     * Safe to call from any thread because ConcurrentLinkedDeque.pollFirst()
     * is thread-safe and ConcurrentHashMap.forEach is weakly consistent.</p>
     *
     * @param currentTick the current server tick
     */
    public void cleanupExpired(long currentTick) {
        int window = windowTicks.get();

        // forEach: weakly consistent iteration over the map.
        // Weakly consistent = may miss entries added during iteration, which is fine
        // for cleanup — they'll be cleaned next cycle.
        playerSwaps.forEach((playerId, entries) -> {
            // pollFirst: atomically removes and returns the head.
            // Since entries are chronologically ordered, we remove from the head
            // (oldest first) until we find an entry within the window.
            while (!entries.isEmpty()) {
                SwapEntry head = entries.peekFirst();
                if (head != null && currentTick - head.tick() > window + 20) {
                    // Entry is well past expiry (+20 tick grace period). Remove it.
                    entries.pollFirst();
                } else {
                    break; // Remaining entries are newer — stop.
                }
            }

            // Remove empty deques to free memory for disconnected players.
            if (entries.isEmpty()) {
                playerSwaps.remove(playerId, entries);
            }
        });
    }

    /**
     * Remove all entries for a specific player (e.g., on disconnect).
     */
    public void clearPlayer(UUID playerId) {
        playerSwaps.remove(playerId);
    }

    /**
     * Update the buffer window at runtime (e.g., config reload).
     * AtomicInteger.set() is a volatile write, visible to all threads.
     */
    public void setWindowTicks(int ticks) {
        windowTicks.set(ticks);
        DebugLogger.debug("SwapBuffer", "Window updated to %d ticks", ticks);
    }

    /**
     * @return the current number of tracked players
     */
    public int getTrackedPlayerCount() {
        return playerSwaps.size();
    }
}
