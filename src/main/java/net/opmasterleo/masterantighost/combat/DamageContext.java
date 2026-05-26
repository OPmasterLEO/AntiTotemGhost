package net.opmasterleo.masterantighost.combat;

import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;

public record DamageContext(UUID playerId,
                            double damage,
                            EntityDamageEvent.DamageCause cause,
                            Entity attacker,
                            Object nmsDamageSource,
                            long tick) {

    public DamageContext withAdditionalDamage(double additionalDamage, Object latestDamageSource, long latestTick) {
        double safeAdditional = (Double.isFinite(additionalDamage) && additionalDamage > 0.0d) ? additionalDamage : 0.0d;
        Object source = latestDamageSource != null ? latestDamageSource : nmsDamageSource;
        long mergedTick = Math.max(tick, latestTick);
        return new DamageContext(playerId, damage + safeAdditional, cause, attacker, source, mergedTick);
    }
}
