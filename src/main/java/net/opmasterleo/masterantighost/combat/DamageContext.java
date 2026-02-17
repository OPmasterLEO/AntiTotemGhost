package net.opmasterleo.masterantighost.combat;

import java.util.UUID;

import org.bukkit.event.entity.EntityDamageEvent;

public final class DamageContext {

    private final UUID playerId;
    private final double damage;
    private final EntityDamageEvent.DamageCause cause;
    private final UUID attackerId;
    private final Object nmsDamageSource;
    private final long tickStamp;
    private final long nanoStamp;

    public DamageContext(UUID playerId, double damage, EntityDamageEvent.DamageCause cause,
                         UUID attackerId, Object nmsDamageSource, long tickStamp) {
        this.playerId = playerId;
        this.damage = damage;
        this.cause = cause;
        this.attackerId = attackerId;
        this.nmsDamageSource = nmsDamageSource;
        this.tickStamp = tickStamp;
        this.nanoStamp = System.nanoTime();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public double getDamage() {
        return damage;
    }

    public EntityDamageEvent.DamageCause getCause() {
        return cause;
    }

    public UUID getAttackerId() {
        return attackerId;
    }

    public Object getNmsDamageSource() {
        return nmsDamageSource;
    }

    public long getTickStamp() {
        return tickStamp;
    }

    public long getNanoStamp() {
        return nanoStamp;
    }

    public DamageContext withAddedDamage(double additionalDamage, Object newNmsDamageSource, long newTickStamp) {
        return new DamageContext(
                this.playerId,
                this.damage + additionalDamage,
                this.cause,
                this.attackerId,
                newNmsDamageSource != null ? newNmsDamageSource : this.nmsDamageSource,
                newTickStamp
        );
    }

    @Override
    public String toString() {
        return "DamageContext{player=" + playerId
                + ", damage=" + String.format("%.2f", damage)
                + ", cause=" + cause
                + ", tick=" + tickStamp
                + '}';
    }
}
