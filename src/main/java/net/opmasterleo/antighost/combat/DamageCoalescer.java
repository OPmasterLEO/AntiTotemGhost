package net.opmasterleo.AntiTotemGhost.combat;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class DamageCoalescer {

    private final AtomicReference<DamageContext> context;

    public DamageCoalescer(DamageContext initialContext) {
        this.context = new AtomicReference<>(Objects.requireNonNull(initialContext, "initialContext"));
    }

    public void addDamage(double additionalDamage, Object latestDamageSource, long tick) {
        if (!Double.isFinite(additionalDamage) || additionalDamage <= 0.0d) {
            return;
        }

        while (true) {
            DamageContext current = context.get();
            DamageContext updated = current.withAdditionalDamage(additionalDamage, latestDamageSource, tick);
            if (context.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    public double getTotalDamage() {
        return context.get().damage();
    }

    public Object getLatestDamageSource() {
        return context.get().nmsDamageSource();
    }

    public DamageContext getCoalescedDamage() {
        return context.get();
    }
}
