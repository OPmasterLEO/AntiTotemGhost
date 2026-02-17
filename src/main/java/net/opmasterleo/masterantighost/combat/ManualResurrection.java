package net.opmasterleo.masterantighost.combat;

import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import org.bukkit.entity.Player;

/**
 * Performs manual totem resurrection replicating vanilla behavior exactly.
 *
 * <p><b>Vanilla Reference:</b> {@code net.minecraft.world.entity.LivingEntity#checkTotemDeathProtection(DamageSource)}
 * in Mojang-mapped 1.20.4 source. This method is called by the NMS damage pipeline when
 * damage would kill the entity. Our manual resurrection mirrors each step.</p>
 *
 * <p><b>Thread Safety:</b> This class has no mutable state. All operations delegate to
 * {@link NmsAccessor} which must be called on the entity's owning region thread.
 * The caller (CombatManager) ensures this via the Folia Entity Scheduler.</p>
 *
 * <p><b>Execution Sequence:</b>
 * <pre>
 * 1. Verify totem exists in NMS offhand
 * 2. Consume one totem (shrink stack)
 * 3. Set health to 1.0 HP
 * 4. Remove all active effects
 * 5. Extinguish fire
 * 6. Apply totem effects (Regen II, Absorption II, Fire Res I)
 * 7. Broadcast entity event 35 (animation + sound)
 * </pre>
 *
 * <p>Steps 3-7 are ordered to match vanilla exactly. The order matters:
 * <ul>
 *   <li>Health set FIRST to prevent any intermediate death state</li>
 *   <li>Effects cleared BEFORE new effects applied (vanilla behavior)</li>
 *   <li>Fire extinguished BEFORE fire resistance (prevents wasted tick)</li>
 *   <li>Animation broadcast LAST (visual feedback after state is finalized)</li>
 * </ul>
 */
public final class ManualResurrection {

    private final NmsAccessor nms;

    public ManualResurrection(NmsAccessor nms) {
        this.nms = nms;
    }

    /**
     * Attempt a full totem resurrection for the given player.
     *
     * <p><b>MUST be called on the entity's owning region thread.</b></p>
     *
     * @param player the player to resurrect
     * @return true if resurrection was successful (totem was found and consumed),
     *         false if no totem was available
     */
    public boolean attemptResurrection(Player player) {
        DebugLogger.debug("Resurrection", "Attempting resurrection for %s", player.getName());

        // ── Step 1: Verify totem in NMS offhand ─────────────────────────────────
        // Direct NMS read: ServerPlayer.getItemBySlot(EquipmentSlot.OFFHAND)
        // This bypasses Bukkit caching for authoritative container state.
        if (!nms.hasTotemInOffhand(player)) {
            DebugLogger.debug("Resurrection", "No totem in offhand for %s — resurrection failed",
                    player.getName());
            return false;
        }

        // ── Step 2: Consume totem ───────────────────────────────────────────────
        // NMS: offhandStack.shrink(1) + inventoryMenu.broadcastChanges()
        // Must happen BEFORE health/effect changes to maintain causality:
        // the totem is "used" first, then its effects apply.
        nms.consumeOneTotemFromOffhand(player);

        // ── Step 3: Set health to 1.0 HP ────────────────────────────────────────
        // NMS: LivingEntity.setHealth(1.0f)
        // Vanilla sets exactly 1.0 HP — the player survives at half a heart.
        // This is the minimum viable health that doesn't trigger death.
        // Direct NMS call avoids firing RegainHealthEvent.
        nms.setHealthNms(player, 1.0f);

        // ── Step 4: Remove all active potion effects ────────────────────────────
        // NMS: LivingEntity.removeAllEffects()
        // Vanilla behavior: ALL effects are cleared, including beneficial ones.
        // This prevents stacking effects from multiple totem pops.
        // Effect removal packets are sent automatically by NMS.
        nms.removeAllEffectsNms(player);

        // ── Step 5: Extinguish fire ─────────────────────────────────────────────
        // NMS: Entity.setRemainingFireTicks(0)
        // Must happen BEFORE fire resistance is applied — otherwise the first
        // fire resistance tick is wasted on the existing fire damage.
        nms.extinguishFireNms(player);

        // ── Step 6: Apply totem effects ─────────────────────────────────────────
        // NMS: LivingEntity.addEffect(new MobEffectInstance(...))
        // Exact vanilla totem effects from LivingEntity#checkTotemDeathProtection:
        //   • Regeneration II (45 seconds = 900 ticks, amplifier 1)
        //   • Absorption II (5 seconds = 100 ticks, amplifier 1)
        //   • Fire Resistance I (40 seconds = 800 ticks, amplifier 0)
        // Effect packets sent automatically by NMS addEffect().
        nms.applyTotemEffectsNms(player);

        // ── Step 7: Broadcast totem pop animation and sound ─────────────────────
        // NMS: ServerLevel.broadcastEntityEvent(entity, (byte) 35)
        // Sends ClientboundEntityEventPacket to all tracking players + self.
        // Client-side handling of byte 35:
        //   • Renders the golden totem sprite overlay (expanding from center screen)
        //   • Spawns green totem particles around the entity
        //   • Plays SoundEvents.TOTEM_OF_UNDYING_USE at the entity's position
        // No separate sound packet needed — the client handles everything from byte 35.
        nms.broadcastTotemPopAnimation(player);

        DebugLogger.debug("Resurrection", "Successfully resurrected %s — totem consumed, " +
                "health=1.0, effects applied, animation broadcast", player.getName());

        return true;
    }
}
