package net.opmasterleo.masterantighost.combat;

import java.util.concurrent.atomic.AtomicReference;

public final class HitAggregator {

    private final AtomicReference<DamageCoalescer> coalescer;

    public HitAggregator(DamageCoalescer initial) {
        this.coalescer = new AtomicReference<>(initial);
    }

    public void addDamage(DamageCoalescer additional, double damage, Object latestDamageSource, long tick) {
        if (additional == null) {
            return;
        }
        additional.addDamage(damage, latestDamageSource, tick);
    }

    public void addDamage(double damage, Object latestDamageSource, long tick) {
        DamageCoalescer current = coalescer.get();
        if (current != null) {
            current.addDamage(damage, latestDamageSource, tick);
        }
    }

    public DamageContext merged() {
        DamageCoalescer current = coalescer.get();
        return current == null ? null : current.getCoalescedDamage();
    }

    public Object latestSource() {
        DamageCoalescer current = coalescer.get();
        return current == null ? null : current.getLatestDamageSource();
    }
}

