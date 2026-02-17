package net.opmasterleo.masterantighost.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * NMS abstraction layer for version-independent server internals access.
 *
 * <p><b>Design Rationale:</b> Bukkit APIs abstract away NMS, but for critical
 * anti-ghost logic, we MUST bypass Bukkit's inventory caching and use direct
 * NMS container reads. Bukkit's {@code PlayerInventory} can return stale data
 * when packet processing is in-flight — a one-tick desync that causes ghost pops.</p>
 *
 * <p><b>Version Adaptability:</b> Each server version has a concrete implementation
 * (e.g., {@code NmsAccessorImpl_v1_20_R3} for 1.20.4). The main plugin class
 * selects the correct implementation at startup based on {@code Bukkit.getMinecraftVersion()}.
 * Adding support for new versions requires only implementing this interface.</p>
 *
 * <p><b>Thread Safety Contract:</b> ALL methods in this interface MUST be called
 * from the region thread that owns the target player entity. In Folia, this is
 * enforced by the Entity Scheduler. In Paper, this is the main server thread.
 * Calling NMS methods from the wrong thread causes undefined behavior.</p>
 *
 * <p><b>NMS Mapping Notes:</b> Implementations use Mojang mappings at compile time
 * (via paperweight-userdev). The build system reobfuscates to Spigot mappings
 * for the production JAR. All NMS class/method references use Mojang names in source.</p>
 */
public interface NmsAccessor {

    // ── Inventory State (Direct NMS Container Access) ───────────────────────────

    /**
     * Check if the player's NMS offhand slot contains a Totem of Undying.
     *
     * <p><b>Why NMS?</b> Bukkit's {@code Player.getInventory().getItemInOffHand()}
     * may return cached/stale state during the same tick that a swap packet is being
     * processed. Direct NMS read from {@code ServerPlayer.getItemBySlot(OFFHAND)}
     * reflects the authoritative server-side container state.</p>
     *
     * <p><b>NMS Path:</b> ServerPlayer → getItemBySlot(EquipmentSlot.OFFHAND) → ItemStack.is(Items.TOTEM_OF_UNDYING)</p>
     *
     * @param player the Bukkit player to inspect
     * @return true if offhand contains a Totem of Undying
     */
    boolean hasTotemInOffhand(Player player);

    /**
     * Consume exactly one Totem of Undying from the player's NMS offhand slot.
     *
     * <p><b>NMS Path:</b> ServerPlayer → getItemBySlot(OFFHAND) → ItemStack.shrink(1)</p>
     * <p>Also syncs the container to the client via {@code inventoryMenu.broadcastChanges()}.</p>
     *
     * @param player the player whose totem to consume
     */
    void consumeOneTotemFromOffhand(Player player);

    // ── Health & Effects (NMS for Immediate Application) ────────────────────────

    /**
     * Set health directly via NMS, bypassing Bukkit event dispatch.
     *
     * <p><b>Why NMS?</b> {@code Player.setHealth()} fires additional events that
     * could interfere with the resurrection sequence. Direct NMS write via
     * {@code LivingEntity.setHealth()} applies immediately without side effects.</p>
     *
     * @param player the target player
     * @param health the health value to set (typically 1.0f for totem pop)
     */
    void setHealthNms(Player player, float health);

    /**
     * Remove all active potion effects via NMS.
     *
     * <p><b>NMS Path:</b> ServerPlayer → removeAllEffects()</p>
     * <p>This matches vanilla totem behavior which clears all effects before applying new ones.</p>
     *
     * @param player the target player
     */
    void removeAllEffectsNms(Player player);

    /**
     * Apply the vanilla totem-of-undying effect set via NMS.
     *
     * <p><b>Vanilla Totem Effects (1.20.4):</b>
     * <ul>
     *   <li>Regeneration II (amplifier 1) — 900 ticks (45 seconds)</li>
     *   <li>Absorption II (amplifier 1) — 100 ticks (5 seconds)</li>
     *   <li>Fire Resistance I (amplifier 0) — 800 ticks (40 seconds)</li>
     * </ul>
     *
     * <p><b>NMS Path:</b> LivingEntity.addEffect(new MobEffectInstance(...))</p>
     * <p>Source: {@code net.minecraft.world.entity.LivingEntity#checkTotemDeathProtection}</p>
     *
     * @param player the target player
     */
    void applyTotemEffectsNms(Player player);

    /**
     * Extinguish fire via NMS.
     *
     * <p><b>NMS Path:</b> Entity.setRemainingFireTicks(0)</p>
     * <p>Vanilla totem behavior extinguishes the player on resurrection.</p>
     *
     * @param player the target player
     */
    void extinguishFireNms(Player player);

    // ── Animation & Sound ───────────────────────────────────────────────────────

    /**
     * Broadcast the totem pop animation and sound to all nearby players.
     *
     * <p><b>Packet:</b> {@code ClientboundEntityEventPacket} with entity status
     * byte {@code 35}. This triggers the client-side totem overlay animation
     * (golden sparkles) and plays {@code SoundEvents.TOTEM_OF_UNDYING_USE}.</p>
     *
     * <p><b>NMS Path:</b> Level.broadcastEntityEvent(entity, (byte) 35)</p>
     * <p>The packet is sent to all tracking players AND the player themselves,
     * replicating exact vanilla behavior for the totem pop visual.</p>
     *
     * @param player the player whose totem pop to broadcast
     */
    void broadcastTotemPopAnimation(Player player);

    // ── Damage Source Capture & Re-Application ──────────────────────────────────

    /**
     * Capture the NMS DamageSource from a Bukkit EntityDamageEvent.
     *
     * <p><b>Why?</b> When reconciliation decides the player should die (no totem
     * found), we re-apply the original damage with the SAME DamageSource to produce
     * identical death messages and combat logging. The NMS DamageSource contains
     * the attacker reference, damage type tag, and message parameters.</p>
     *
     * <p><b>Implementation:</b> Paper 1.20.4+ exposes {@code EntityDamageEvent.getDamageSource()}
     * which returns a Bukkit {@code DamageSource} wrapping the NMS one.
     * We extract the NMS object via CraftDamageSource.getHandle().</p>
     *
     * @param event the Bukkit damage event
     * @return the NMS DamageSource as an opaque Object, or null if extraction fails
     */
    Object captureDamageSource(EntityDamageEvent event);

    /**
     * Re-apply damage using a previously captured NMS DamageSource.
     *
     * <p>Used during reconciliation when no totem is found. Resets invulnerability
     * ticks to zero before applying damage, ensuring the hit registers even if
     * the player recently took damage.</p>
     *
     * <p><b>NMS Path:</b>
     * <pre>
     * ServerPlayer.invulnerableTime = 0;  // Reset I-frames
     * ServerPlayer.hurt(DamageSource, amount);
     * </pre>
     *
     * @param player          the target player
     * @param amount          the damage amount to apply
     * @param nmsDamageSource the NMS DamageSource captured earlier (cast internally)
     */
    void dealDamageWithSource(Player player, float amount, Object nmsDamageSource);

    // ── Tick / Version Info ─────────────────────────────────────────────────────

    /**
     * Get the current server tick count.
     *
     * <p><b>NMS Path:</b> MinecraftServer.getTickCount()</p>
     * <p>Used for tick-indexed swap buffer and timing calculations.</p>
     *
     * @return the current server tick
     */
    long getCurrentTick();

    /**
     * Check whether this NMS accessor initialized successfully and is operational.
     * If false, the plugin should disable itself.
     *
     * @return true if NMS access is functional
     */
    boolean isAvailable();

    /**
     * @return human-readable version tag for logging (e.g., "1.20.4/v1_20_R3")
     */
    String getVersionTag();
}
