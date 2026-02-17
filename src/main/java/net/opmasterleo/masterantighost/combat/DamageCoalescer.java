package net.opmasterleo.masterantighost.combat;

import java.util.concurrent.atomic.AtomicReference;

public final class DamageCoalescer {

    private final AtomicReference<DamageContext> coalescedDamage;

    public DamageCoalescer(DamageContext initialContext) {
        this.coalescedDamage = new AtomicReference<>(initialContext);
    }

    public void addDamage(double additionalDamage, Object nmsDamageSource, long tickStamp) {
        DamageContext current;
        DamageContext updated;
        do {
            current = coalescedDamage.get();
            updated = current.withAddedDamage(additionalDamage, nmsDamageSource, tickStamp);
        } while (!coalescedDamage.compareAndSet(current, updated));
    }

    public DamageContext getCoalescedDamage() {
        return coalescedDamage.get();
    }

    public double getTotalDamage() {
        return coalescedDamage.get().getDamage();
    }

    public Object getLatestDamageSource() {
        return coalescedDamage.get().getNmsDamageSource();
    }
}
