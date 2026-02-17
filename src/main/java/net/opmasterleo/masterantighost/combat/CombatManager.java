package net.opmasterleo.masterantighost.combat;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core combat manager — orchestrates the A/B/C/D anti-ghost pipeline.
 *
 * <p><b>Pipeline Overview:</b>
 * <pre>
 * ⚡ A — FAST PATH (Same Tick)
 * │  Read NMS offhand immediately on event tick.
 * │  If totem found → manual resurrection → done.
 * │
 * ▼ (no totem found on fast path)
 *
 * 💠 B — LETHAL INTERCEPT GATE
 * │  Cancel the damage event (prevent vanilla death).
 * │  Store DamageContext + NMS DamageSource.
 * │  Set player state: NORMAL → PENDING_LETHAL (CAS).
 * │
 * ▼ (scheduled on Folia Entity Scheduler)
 *
 * 🛠 C — NEXT-TICK RECONCILIATION
 * │  Re-read NMS offhand container state.
 * │  Check swap buffer for recent totem activity.
 * │  If totem found → manual resurrection.
 * │  If swap buffer active → retry one more tick (up to swapBufferTicks).
 * │  If neither → re-apply coalesced damage (player dies).
 * │
 * ▼ (during PENDING_LETHAL window)
 *
 * 📦 D — DAMAGE COALESCING
 *    Incoming damage while PENDING_LETHAL is merged into the
 *    DamageCoalescer, not intercepted separately.
 * </pre>
 *
 * <p><b>Thread Safety Model:</b>
 * <ul>
 *   <li>{@code playerStates}: {@link ConcurrentHashMap} with {@link AtomicReference} values.
 *       State transitions use CAS (compareAndSet) to prevent race conditions.
 *       Multiple region threads (Folia) may attempt transitions for the same player
 *       if cross-region damage occurs simultaneously.</li>
 *   <li>{@code damageCoalescers}: {@link ConcurrentHashMap} keyed by UUID.
 *       Each {@link DamageCoalescer} uses internal AtomicReference CAS for damage merging.</li>
 *   <li>{@code bypassSet}: {@link ConcurrentHashMap}-backed Set for lock-free add/check/remove.
 *       Marks players whose re-applied damage should not be intercepted.</li>
 *   <li>{@code reconciliationScheduled}: {@link ConcurrentHashMap}-backed Set.
 *       Prevents duplicate reconciliation scheduling for the same player.</li>
 * </ul>
 *
 * <p><b>Race Condition Analysis:</b>
 * <ul>
 *   <li><b>Double intercept:</b> Prevented by CAS on NORMAL→PENDING_LETHAL. Only one thread wins.</li>
 *   <li><b>Reconciliation during coalesce:</b> Reconciliation reads coalesced damage atomically.
 *       Late damage addedafter reconciliation read is harmless (player already decided).</li>
 *   <li><b>Re-applied damage re-intercept:</b> Bypass set checked before interception.</li>
 *   <li><b>Player disconnect during PENDING:</b> Entity Scheduler retired callback handles this.
 *       Cleanup also runs periodically.</li>
 * </ul>
 */
public final class CombatManager {

    private static final String TAG = "CombatManager";

    // ── Dependencies ────────────────────────────────────────────────────────────
    private final Plugin plugin;
    private final PluginConfig config;
    private final NmsAccessor nms;
    private final SwapBuffer swapBuffer;
    private final ManualResurrection resurrection;
    private final FoliaScheduler scheduler;

    // ── Statistics (LongAdder: high-throughput, low-contention counters) ─────────
    // LongAdder internally stripes across CPU cores. Each core writes to its own
    // cell, avoiding cache-line bouncing. sum() aggregates all cells.
    // Chosen over AtomicLong because crystal PvP generates high-frequency increments.
    private final LongAdder fastPathPops;
    private final LongAdder reconciledPops;
    private final LongAdder reconciledDeaths;
    private final LongAdder interceptedHits;

    // ── Per-Player State ────────────────────────────────────────────────────────

    // ConcurrentHashMap<UUID, AtomicReference<CombatState>>
    // Why AtomicReference? State transitions (NORMAL→PENDING_LETHAL, PENDING_LETHAL→RESURRECTED, etc.)
    // must be atomic CAS operations to prevent double-intercept race conditions.
    // Why ConcurrentHashMap? Multiple region threads (Folia) may access different players concurrently.
    private final ConcurrentHashMap<UUID, AtomicReference<CombatState>> playerStates;

    // ConcurrentHashMap<UUID, DamageCoalescer>
    // Active only while player is in PENDING_LETHAL state. Created on intercept, removed on resolution.
    private final ConcurrentHashMap<UUID, DamageCoalescer> damageCoalescers;

    // Bypass set: players whose re-applied damage should NOT be intercepted.
    // ConcurrentHashMap.newKeySet() provides a lock-free concurrent set backed by ConcurrentHashMap.
    // This is faster than Collections.synchronizedSet(new HashSet<>()) because it avoids global locks.
    private final Set<UUID> bypassSet;

    // Tracks whether reconciliation has been scheduled for a player.
    // Prevents duplicate scheduling if multiple damage events arrive in the same tick.
    private final Set<UUID> reconciliationScheduled;

    public CombatManager(Plugin plugin, PluginConfig config, NmsAccessor nms,
                         SwapBuffer swapBuffer, ManualResurrection resurrection,
                         FoliaScheduler scheduler,
                         LongAdder fastPathPops, LongAdder reconciledPops,
                         LongAdder reconciledDeaths, LongAdder interceptedHits) {
        this.plugin = plugin;
        this.config = config;
        this.nms = nms;
        this.swapBuffer = swapBuffer;
        this.resurrection = resurrection;
        this.scheduler = scheduler;
        this.fastPathPops = fastPathPops;
        this.reconciledPops = reconciledPops;
        this.reconciledDeaths = reconciledDeaths;
        this.interceptedHits = interceptedHits;

        // Initial capacity 128: sized for large crystal PvP servers (100+ players).
        // Concurrency level is handled internally by ConcurrentHashMap (adaptive in Java 8+).
        this.playerStates = new ConcurrentHashMap<>(128);
        this.damageCoalescers = new ConcurrentHashMap<>(64);
        this.bypassSet = ConcurrentHashMap.newKeySet(64);
        this.reconciliationScheduled = ConcurrentHashMap.newKeySet(64);
    }

    // ── Public Entry Point (called from DamageListener) ─────────────────────────

    /**
     * Handle a potentially lethal damage event. This is the main pipeline entry point.
     *
     * <p><b>MUST be called on the entity's owning region thread</b> (guaranteed by
     * Bukkit event dispatch in Folia / main thread in Paper).</p>
     *
     * @param player the player receiving lethal damage
     * @param event  the Bukkit damage event (may be cancelled by this method)
     */
    public void handleLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();

        // ── Bypass Check ────────────────────────────────────────────────────────
        // If this player is in the bypass set, the damage is being re-applied by
        // our reconciliation logic. Let it through without interception.
        if (bypassSet.remove(playerId)) {
            DebugLogger.debug(TAG, "Bypass active for %s — letting damage through", player.getName());
            return;
        }

        // ── State Check: Already PENDING_LETHAL? → Coalesce ─────────────────────
        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef != null && stateRef.get() == CombatState.PENDING_LETHAL) {
            // Player is already in the reconciliation window. Coalesce this damage
            // instead of creating a new interception cycle.
            coalesceDamage(player, event);
            return;
        }

        // ── A: Fast Path — Same-Tick NMS Totem Check ────────────────────────────
        if (config.isEnableFastPath()) {
            if (attemptFastPath(player)) {
                event.setCancelled(true);
                // Fast path succeeded: totem consumed, player resurrected.
                // The event is cancelled so vanilla doesn't process the death.
                fastPathPops.increment();
                DebugLogger.debug(TAG, "Fast path SUCCESS for %s", player.getName());
                return;
            }
            DebugLogger.debug(TAG, "Fast path MISS for %s — entering intercept gate", player.getName());
        }

        // ── B: Lethal Intercept Gate ────────────────────────────────────────────
        interceptLethalDamage(player, event);
    }

    /**
     * Check if a player is in the bypass set (damage should not be intercepted).
     * Used by the DamageListener to skip non-lethal processing.
     */
    public boolean isBypassing(UUID playerId) {
        return bypassSet.contains(playerId);
    }

    /**
     * Get the current combat state for a player, or NORMAL if not tracked.
     */
    public CombatState getPlayerState(UUID playerId) {
        AtomicReference<CombatState> ref = playerStates.get(playerId);
        return ref != null ? ref.get() : CombatState.NORMAL;
    }

    // ── A: Fast Path ────────────────────────────────────────────────────────────

    /**
     * Attempt immediate resurrection by reading NMS offhand on the same tick.
     *
     * <p>This is the "happy path" — if the totem is already in the offhand when
     * damage arrives, we can resurrect instantly with zero additional latency.
     * This handles 95%+ of cases in normal gameplay.</p>
     *
     * @param player the player to check
     * @return true if resurrection was successful
     */
    private boolean attemptFastPath(Player player) {
        // ManualResurrection.attemptResurrection reads NMS offhand, consumes totem,
        // sets health, applies effects, and broadcasts animation — all in one call.
        return resurrection.attemptResurrection(player);
    }

    // ── B: Lethal Intercept Gate ────────────────────────────────────────────────

    /**
     * Intercept lethal damage: cancel the event, store context, schedule reconciliation.
     */
    private void interceptLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();

        // ── CAS: NORMAL → PENDING_LETHAL ────────────────────────────────────────
        // AtomicReference CAS prevents double-intercept if two lethal damage events
        // arrive on the same tick (e.g., two crystal explosions in the same tick).
        // Only one thread wins the CAS; the loser coalesces instead.
        AtomicReference<CombatState> stateRef = playerStates.computeIfAbsent(
                playerId, k -> new AtomicReference<>(CombatState.NORMAL)
        );

        if (!stateRef.compareAndSet(CombatState.NORMAL, CombatState.PENDING_LETHAL)) {
            // Another thread already intercepted. Coalesce this damage.
            CombatState currentState = stateRef.get();
            if (currentState == CombatState.PENDING_LETHAL) {
                coalesceDamage(player, event);
            }
            // If RESURRECTED or DEAD, the player is already being handled — ignore.
            return;
        }

        // ── Cancel event to prevent vanilla death ───────────────────────────────
        event.setCancelled(true);
        interceptedHits.increment();

        // ── Capture damage context ──────────────────────────────────────────────
        Object nmsDamageSource = nms.captureDamageSource(event);
        long currentTick = nms.getCurrentTick();

        DamageContext context = new DamageContext(
                playerId,
                event.getFinalDamage(),
                event.getCause(),
                event.getEntity() instanceof Player ? null :
                        (event.getDamageSource().getCausingEntity() != null ?
                                event.getDamageSource().getCausingEntity().getUniqueId() : null),
                nmsDamageSource,
                currentTick
        );

        // ── Initialize damage coalescer ─────────────────────────────────────────
        damageCoalescers.put(playerId, new DamageCoalescer(context));

        DebugLogger.debug(TAG, "Intercepted lethal damage for %s: %.1f from %s at tick %d",
                player.getName(), event.getFinalDamage(), event.getCause(), currentTick);

        // ── C: Schedule Reconciliation ──────────────────────────────────────────
        scheduleReconciliation(player, playerId, 0);
    }

    // ── D: Damage Coalescing ────────────────────────────────────────────────────

    /**
     * Coalesce additional damage into the existing PENDING_LETHAL context.
     */
    private void coalesceDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();
        event.setCancelled(true); // Prevent this damage from applying

        DamageCoalescer coalescer = damageCoalescers.get(playerId);
        if (coalescer != null) {
            Object newSource = nms.captureDamageSource(event);
            long currentTick = nms.getCurrentTick();
            coalescer.addDamage(event.getFinalDamage(), newSource, currentTick);

            DebugLogger.debug(TAG, "Coalesced %.1f damage for %s (total: %.1f)",
                    event.getFinalDamage(), player.getName(), coalescer.getTotalDamage());
        }
    }

    // ── C: Reconciliation ───────────────────────────────────────────────────────

    /**
     * Schedule reconciliation on the entity's region thread.
     *
     * @param player   the player to reconcile
     * @param playerId the player's UUID
     * @param attempt  the current attempt number (0-based)
     */
    private void scheduleReconciliation(Player player, UUID playerId, int attempt) {
        // Prevent duplicate scheduling: only one reconciliation chain per player.
        if (attempt == 0 && !reconciliationScheduled.add(playerId)) {
            DebugLogger.debug(TAG, "Reconciliation already scheduled for %s — skipping", player.getName());
            return;
        }

        int delayTicks = config.getReconciliationTicks();

        DebugLogger.debug(TAG, "Scheduling reconciliation for %s: attempt %d, delay %d ticks",
                player.getName(), attempt, delayTicks);

        // Folia: Entity Scheduler guarantees execution on the region thread owning the player.
        // Paper: Bukkit scheduler runs on main thread.
        // Both ensure NMS access is safe.
        scheduler.runOnEntityThreadDelayed(player, () -> {
            performReconciliation(player, playerId, attempt);
        }, delayTicks);
    }

    /**
     * Perform the actual reconciliation decision: pop or die.
     *
     * <p><b>MUST run on the entity's owning region thread</b> (guaranteed by Entity Scheduler).</p>
     */
    private void performReconciliation(Player player, UUID playerId, int attempt) {
        // ── Safety: verify player is still online and in PENDING_LETHAL ──────────
        if (!player.isOnline()) {
            cleanupPlayer(playerId);
            DebugLogger.debug(TAG, "Player %s went offline during reconciliation — cleaned up", playerId);
            return;
        }

        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef == null || stateRef.get() != CombatState.PENDING_LETHAL) {
            // State changed (e.g., another code path resolved this). Nothing to do.
            DebugLogger.debug(TAG, "Player %s no longer PENDING_LETHAL — aborting reconciliation",
                    player.getName());
            return;
        }

        long currentTick = nms.getCurrentTick();

        // ── Check 1: Direct NMS offhand totem read ──────────────────────────────
        // Re-read the NMS container state. By now (1+ ticks later), any pending
        // swap packets should have been processed and reflected in NMS state.
        if (nms.hasTotemInOffhand(player)) {
            // Totem found! Resurrect the player.
            resolveAsResurrection(player, playerId, stateRef);
            return;
        }

        // ── Check 2: Swap buffer — was there recent totem swap activity? ────────
        // If the swap buffer shows totem activity within the window, the totem might
        // still be "in transit" (packet processed but NMS state not yet reflected,
        // or the swap was partially processed). Retry.
        if (swapBuffer.hasRecentTotemActivity(playerId, currentTick)) {
            int maxAttempts = config.getSwapBufferTicks();
            if (attempt < maxAttempts) {
                DebugLogger.debug(TAG, "Swap buffer active for %s — retrying (attempt %d/%d)",
                        player.getName(), attempt + 1, maxAttempts);
                scheduleReconciliation(player, playerId, attempt + 1);
                return;
            }
            // Max retries exhausted. Do one final NMS check.
            if (nms.hasTotemInOffhand(player)) {
                resolveAsResurrection(player, playerId, stateRef);
                return;
            }
            DebugLogger.debug(TAG, "Swap buffer retries exhausted for %s — proceeding to death",
                    player.getName());
        }

        // ── Check 3: Heuristic — any swap activity at all? ─────────────────────
        // Even non-totem swaps might indicate the player is actively managing inventory.
        // Give one extra attempt if we haven't exhausted swap buffer retries.
        if (attempt == 0 && swapBuffer.hasAnyRecentSwapActivity(playerId, currentTick)) {
            DebugLogger.debug(TAG, "Non-totem swap activity detected for %s — one more attempt",
                    player.getName());
            scheduleReconciliation(player, playerId, attempt + 1);
            return;
        }

        // ── Resolution: No totem found. Player dies. ────────────────────────────
        resolveAsDeath(player, playerId, stateRef);
    }

    /**
     * Resolve PENDING_LETHAL as successful resurrection.
     */
    private void resolveAsResurrection(Player player, UUID playerId, AtomicReference<CombatState> stateRef) {
        // CAS: PENDING_LETHAL → RESURRECTED
        if (!stateRef.compareAndSet(CombatState.PENDING_LETHAL, CombatState.RESURRECTED)) {
            return; // Another thread resolved first — no-op
        }

        boolean success = resurrection.attemptResurrection(player);
        if (success) {
            reconciledPops.increment();
            DebugLogger.debug(TAG, "Reconciliation RESURRECTED %s", player.getName());
        } else {
            // Edge case: totem disappeared between check and resurrection (consumed by vanilla?).
            // Fall back to death.
            DebugLogger.warn("Totem disappeared during resurrection for " + player.getName());
            stateRef.set(CombatState.PENDING_LETHAL);
            resolveAsDeath(player, playerId, stateRef);
            return;
        }

        // Cleanup after a short delay to handle any late-arriving damage events.
        scheduler.runOnEntityThreadDelayed(player, () -> {
            stateRef.set(CombatState.NORMAL);
            cleanupPlayer(playerId);
        }, 2L);
    }

    /**
     * Resolve PENDING_LETHAL as death. Re-apply the coalesced damage.
     */
    private void resolveAsDeath(Player player, UUID playerId, AtomicReference<CombatState> stateRef) {
        // CAS: PENDING_LETHAL → DEAD
        if (!stateRef.compareAndSet(CombatState.PENDING_LETHAL, CombatState.DEAD)) {
            return; // Another thread resolved first — no-op
        }

        DamageCoalescer coalescer = damageCoalescers.get(playerId);
        reconciledDeaths.increment();

        DebugLogger.debug(TAG, "Reconciliation DEATH for %s (coalesced damage: %.1f)",
                player.getName(), coalescer != null ? coalescer.getTotalDamage() : 0.0);

        if (coalescer != null) {
            // ── Re-apply coalesced damage ───────────────────────────────────────
            // Add to bypass set BEFORE dealing damage to prevent re-interception.
            bypassSet.add(playerId);

            float totalDamage = (float) coalescer.getTotalDamage();
            Object damageSource = coalescer.getLatestDamageSource();

            // Ensure damage is actually lethal (player may have been healed during window).
            float lethalDamage = Math.max(totalDamage, (float) player.getHealth() + 1.0f);

            // NMS: reset invulnerability, apply damage with original source.
            nms.dealDamageWithSource(player, lethalDamage, damageSource);
        } else {
            // Fallback: no coalescer (shouldn't happen). Kill directly.
            bypassSet.add(playerId);
            player.setHealth(0);
        }

        // Cleanup after death processing completes.
        scheduler.runOnEntityThreadDelayed(player, () -> {
            stateRef.set(CombatState.NORMAL);
            cleanupPlayer(playerId);
        }, 2L);
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────────

    /**
     * Cleanup all state for a player. Called after resolution or on disconnect.
     */
    public void cleanupPlayer(UUID playerId) {
        damageCoalescers.remove(playerId);
        bypassSet.remove(playerId);
        reconciliationScheduled.remove(playerId);
        // Don't remove from playerStates — the AtomicReference is reused and set to NORMAL.
        DebugLogger.debug(TAG, "Cleaned up state for %s", playerId);
    }

    /**
     * Remove stale entries for disconnected players.
     * Called periodically from the Global Scheduler.
     */
    public void cleanupStaleEntries() {
        // ConcurrentHashMap.entrySet() provides a weakly consistent view.
        // Safe to iterate and remove — no ConcurrentModificationException.
        playerStates.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            CombatState state = entry.getValue().get();
            // Only remove NORMAL state entries for players no longer online.
            // PENDING_LETHAL entries should not be removed — they're actively being reconciled.
            if (state == CombatState.NORMAL) {
                var player = plugin.getServer().getPlayer(playerId);
                return player == null || !player.isOnline();
            }
            return false;
        });
    }

    /**
     * Handle player disconnect — cleanup all pending state.
     */
    public void onPlayerQuit(UUID playerId) {
        // Reset state to NORMAL (cancels any pending reconciliation effectively).
        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef != null) {
            stateRef.set(CombatState.NORMAL);
        }
        cleanupPlayer(playerId);
        swapBuffer.clearPlayer(playerId);
    }

    /**
     * Shutdown: clear all pending states.
     */
    public void shutdown() {
        playerStates.clear();
        damageCoalescers.clear();
        bypassSet.clear();
        reconciliationScheduled.clear();
        DebugLogger.info("CombatManager shutdown — all pending states cleared.");
    }
}
