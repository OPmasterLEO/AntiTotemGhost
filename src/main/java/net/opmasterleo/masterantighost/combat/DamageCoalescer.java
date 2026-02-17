package net.opmasterleo.masterantighost.combat;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe damage coalescer for the PENDING_LETHAL window.
 *
 * <p><b>Problem:</b> During the 1-2 tick reconciliation window, a player in
 * PENDING_LETHAL state may receive additional damage events (e.g., multiple
 * crystal explosions in rapid succession). Each damage event must NOT trigger
 * a separate reconciliation cycle — that would create race conditions.</p>
 *
 * <p><b>Solution:</b> Coalesce (merge) all incoming damage into a single
 * DamageContext using an AtomicReference CAS loop. When reconciliation runs,
 * it reads the final coalesced damage in one atomic operation.</p>
 *
 * <p><b>Thread Safety:</b> Uses {@link AtomicReference} with compare-and-set (CAS)
 * loop for lock-free damage accumulation. This is chosen over synchronized blocks
 * because:
 * <ol>
 *   <li>In Folia, the same player's events typically run on the same region thread,
 *       but cross-region damage (TNT, crystals) can arrive from different threads.</li>
 *   <li>CAS loops are wait-free for uncontended cases (fast path).</li>
 *   <li>No deadlock risk — critical for Folia's multithreaded model.</li>
 * </ol>
 *
 * <p><b>Contention Analysis:</b> In practice, CAS contention is near-zero because
 * damage events for the same player within a 1-2 tick window rarely exceed 2-3
 * concurrent writers. The CAS retry loop handles the rare contention case gracefully.</p>
 */
public final class DamageCoalescer {

    // AtomicReference chosen over volatile for atomic read-modify-write (CAS).
    // volatile alone would not support safe damage accumulation.
    private final AtomicReference<DamageContext> coalescedDamage;

    /**
     * Create a new coalescer with an initial damage context.
     *
     * @param initialContext the first intercepted lethal damage event
     */
    public DamageCoalescer(DamageContext initialContext) {
        this.coalescedDamage = new AtomicReference<>(initialContext);
    }

    /**
     * Add damage to the coalesced total using a CAS loop.
     *
     * <p><b>Algorithm:</b>
     * <pre>
     * do {
     *   current = atomicRef.get()                                    // read
     *   updated = current.withAddedDamage(newDamage, newSource, tick) // compute
     * } while (!atomicRef.compareAndSet(current, updated))           // CAS write
     * </pre>
     *
     * <p>If another thread modifies the reference between get() and CAS, the CAS
     * fails and we retry with the updated value. This guarantees no damage is lost.</p>
     *
     * @param additionalDamage  the new damage amount to add
     * @param nmsDamageSource   the NMS DamageSource from the new hit (may replace previous)
     * @param tickStamp         the tick when the new damage occurred
     */
    public void addDamage(double additionalDamage, Object nmsDamageSource, long tickStamp) {
        // CAS loop — retries on contention (extremely rare for same-player damage).
        // Bounded: in the worst case, N threads retry N times, then all succeed.
        DamageContext current;
        DamageContext updated;
        do {
            current = coalescedDamage.get();
            updated = current.withAddedDamage(additionalDamage, nmsDamageSource, tickStamp);
        } while (!coalescedDamage.compareAndSet(current, updated));
    }

    /**
     * Get the coalesced damage context for reconciliation.
     *
     * <p>AtomicReference.get() provides a consistent snapshot.
     * After this read, no further damage should be added (player exits PENDING_LETHAL).</p>
     *
     * @return the accumulated damage context
     */
    public DamageContext getCoalescedDamage() {
        return coalescedDamage.get();
    }

    /**
     * Get the total coalesced damage amount.
     */
    public double getTotalDamage() {
        return coalescedDamage.get().getDamage();
    }

    /**
     * Get the NMS DamageSource from the latest coalesced hit.
     * Used for death message generation during reconciliation.
     */
    public Object getLatestDamageSource() {
        return coalescedDamage.get().getNmsDamageSource();
    }
}
