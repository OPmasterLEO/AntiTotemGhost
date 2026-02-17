package net.opmasterleo.masterantighost.combat;

import org.bukkit.event.entity.EntityDamageEvent;

import java.util.UUID;

/**
 * Immutable snapshot of a lethal damage event, captured at intercept time.
 *
 * <p>Stores all information needed to re-apply the damage during reconciliation
 * if no totem is found. The NMS DamageSource is stored as {@code Object} to keep
 * this class free of NMS imports — it is cast back in the NMS accessor layer.</p>
 *
 * <p><b>Thread Safety:</b> Fully immutable. Safe to read from any thread.
 * Created on the region thread during damage event processing.</p>
 *
 * <p><b>Design Note:</b> We store the NMS DamageSource (not just the Bukkit DamageCause)
 * to preserve the exact death message, attacker reference, and damage type tag.
 * Within the 1-2 tick reconciliation window, entity references remain valid.</p>
 */
public final class DamageContext {

    private final UUID playerId;
    private final double damage;
    private final EntityDamageEvent.DamageCause cause;
    private final UUID attackerId;        // nullable — may be environmental damage
    private final Object nmsDamageSource; // net.minecraft.world.damagesource.DamageSource — stored opaquely
    private final long tickStamp;         // server tick when damage occurred
    private final long nanoStamp;         // System.nanoTime() for sub-tick precision

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

    /**
     * The final damage amount after all Bukkit event modifications.
     * This is what would have been applied to the player.
     */
    public double getDamage() {
        return damage;
    }

    public EntityDamageEvent.DamageCause getCause() {
        return cause;
    }

    /** UUID of the attacking entity, or null for environmental damage. */
    public UUID getAttackerId() {
        return attackerId;
    }

    /**
     * The NMS DamageSource captured from the original event.
     * Cast to {@code net.minecraft.world.damagesource.DamageSource} in NMS layer.
     * Preserves exact death message and damage type for re-application.
     */
    public Object getNmsDamageSource() {
        return nmsDamageSource;
    }

    /** Server tick when this damage was originally dealt. */
    public long getTickStamp() {
        return tickStamp;
    }

    /** System.nanoTime() at capture, for sub-tick timing analysis. */
    public long getNanoStamp() {
        return nanoStamp;
    }

    /**
     * Returns a new DamageContext with coalesced (summed) damage.
     * Used by DamageCoalescer to merge overlapping damage events.
     */
    public DamageContext withAddedDamage(double additionalDamage, Object newNmsDamageSource, long newTickStamp) {
        // Keep the latest damage source for the death message — the last hit matters most
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
