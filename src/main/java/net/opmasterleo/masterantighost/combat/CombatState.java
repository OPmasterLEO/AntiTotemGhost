package net.opmasterleo.masterantighost.combat;

/**
 * Per-player combat state machine for the anti-ghost system.
 *
 * <pre>
 * State Transitions:
 *
 *   NORMAL ──(lethal damage intercepted)──► PENDING_LETHAL
 *     ▲                                         │
 *     │                                    ┌────┴────┐
 *     │                                    ▼         ▼
 *     └──────────(cooldown)────────── RESURRECTED   DEAD
 *
 * NORMAL         → Default state. Player takes damage normally.
 * PENDING_LETHAL → Lethal damage intercepted; awaiting reconciliation.
 *                  Incoming damage is coalesced, not re-intercepted.
 * RESURRECTED    → Reconciliation found a totem; player was manually popped.
 *                  Transitions back to NORMAL after 1 tick.
 * DEAD           → Reconciliation found no totem; player death is re-applied.
 *                  Transitions back to NORMAL after death processing.
 * </pre>
 *
 * <p><b>Thread Safety:</b> This enum is immutable. The mutable state is the
 * AtomicReference&lt;CombatState&gt; per player in CombatManager's ConcurrentHashMap.</p>
 */
public enum CombatState {

    /**
     * No pending lethal damage. Normal gameplay.
     */
    NORMAL,

    /**
     * Lethal damage has been intercepted and cancelled.
     * Reconciliation is scheduled for the next tick(s).
     * Additional damage during this phase is coalesced.
     */
    PENDING_LETHAL,

    /**
     * Reconciliation confirmed a totem in offhand (directly or via swap buffer).
     * Manual resurrection was performed successfully.
     */
    RESURRECTED,

    /**
     * Reconciliation confirmed no totem available.
     * Original (coalesced) damage is being re-applied to kill the player.
     */
    DEAD
}
