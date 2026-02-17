package net.opmasterleo.masterantighost.nms;

import net.opmasterleo.masterantighost.debug.DebugLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

// ── NMS Imports (Mojang Mappings via paperweight-userdev) ──────────────────
// At compile time: Mojang-mapped names are used directly.
// At build time: paperweight reobfuscates these to Spigot mappings in the JAR.
// Reference: https://docs.papermc.io/paper/dev/userdev
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// ── CraftBukkit Imports ────────────────────────────────────────────────────
// paperweight-userdev provides these at compile time.
// The exact package path depends on the dev bundle version:
//   1.20.4 → org.bukkit.craftbukkit (unversioned in dev, reobf adds v1_20_R3)
// If compilation fails due to package mismatch, add the version-specific suffix.
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.damage.CraftDamageSource;

/**
 * NMS accessor implementation for Minecraft 1.20.4 (Paper mapping: v1_20_R3).
 *
 * <p><b>Target Server:</b> Paper/Folia 1.20.4 with Mojang mappings via paperweight-userdev 1.5.x.</p>
 *
 * <p><b>Thread Safety:</b> ALL methods MUST be called on the region thread that owns
 * the target player. This is guaranteed by the {@link net.opmasterleo.masterantighost.scheduler.FoliaScheduler}
 * which uses Entity Scheduler for player-scoped tasks.</p>
 *
 * <p><b>NMS References (Mojang names):</b>
 * <ul>
 *   <li>{@code net.minecraft.server.level.ServerPlayer} — the NMS player entity</li>
 *   <li>{@code net.minecraft.world.entity.EquipmentSlot} — slot addressing</li>
 *   <li>{@code net.minecraft.world.item.ItemStack} — NMS item representation</li>
 *   <li>{@code net.minecraft.world.item.Items} — item type registry</li>
 *   <li>{@code net.minecraft.world.effect.MobEffects} — effect registry</li>
 *   <li>{@code net.minecraft.world.effect.MobEffectInstance} — effect application</li>
 *   <li>{@code net.minecraft.world.damagesource.DamageSource} — damage metadata</li>
 * </ul>
 *
 * <p><b>Vanilla Totem Logic Reference:</b>
 * {@code net.minecraft.world.entity.LivingEntity#checkTotemDeathProtection(DamageSource)}
 * in the 1.20.4 Mojang-mapped source. Our manual resurrection replicates this method exactly.</p>
 */
public class NmsAccessorImpl_v1_20_R3 implements NmsAccessor {

    private static final String VERSION_TAG = "1.20.4/v1_20_R3";
    private final boolean available;

    public NmsAccessorImpl_v1_20_R3() {
        boolean init;
        try {
            // Verify NMS classes are accessible at runtime.
            // This will fail if the server version doesn't match our target.
            Class.forName("net.minecraft.server.level.ServerPlayer");
            Class.forName("net.minecraft.world.item.Items");
            init = true;
            DebugLogger.debug("NMS", "NMS classes verified for " + VERSION_TAG);
        } catch (ClassNotFoundException e) {
            DebugLogger.severe("NMS classes not found for " + VERSION_TAG + ". Wrong server version?", e);
            init = false;
        }
        this.available = init;
    }

    // ── Helper: Bukkit → NMS Player ─────────────────────────────────────────────

    /**
     * Convert Bukkit Player to NMS ServerPlayer.
     *
     * <p><b>CraftPlayer.getHandle()</b> returns the underlying NMS entity.
     * This is the standard pattern for Bukkit→NMS conversion and is used by
     * virtually all NMS-dependent plugins.</p>
     */
    private ServerPlayer toNms(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    // ── Inventory State ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b>
     * <pre>
     * CraftPlayer.getHandle()               → ServerPlayer
     * ServerPlayer.getItemBySlot(OFFHAND)    → ItemStack (NMS)
     * ItemStack.is(Items.TOTEM_OF_UNDYING)   → boolean
     * </pre>
     *
     * <p><b>Why not Bukkit?</b> {@code player.getInventory().getItemInOffHand()} goes through
     * CraftInventoryPlayer which wraps NMS but may have a one-tick cache depending on
     * when the inventory was last synced. Direct NMS read is authoritative.</p>
     */
    @Override
    public boolean hasTotemInOffhand(Player player) {
        ServerPlayer nms = toNms(player);

        // Direct NMS offhand read — authoritative server-side container state.
        // EquipmentSlot.OFFHAND maps to the player's offhand slot in the equipment array.
        ItemStack offhand = nms.getItemBySlot(EquipmentSlot.OFFHAND);

        // ItemStack.is(Item) checks the item type without comparing NBT/count.
        // Items.TOTEM_OF_UNDYING is the singleton registry entry for totems.
        boolean hasTotom = !offhand.isEmpty() && offhand.is(Items.TOTEM_OF_UNDYING);

        DebugLogger.debug("NMS", "hasTotemInOffhand(%s) = %s (stack: %s)",
                player.getName(), hasTotom, offhand);

        return hasTotom;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b>
     * <pre>
     * ServerPlayer.getItemBySlot(OFFHAND) → offhandStack
     * offhandStack.shrink(1)              → reduces count by 1 (removes if count → 0)
     * ServerPlayer.inventoryMenu.broadcastChanges() → syncs inventory to client
     * </pre>
     *
     * <p><b>broadcastChanges() Note:</b> After modifying NMS inventory directly,
     * we MUST call broadcastChanges() to send the updated slot to the client.
     * Without this, the client's inventory display desyncs from the server.</p>
     */
    @Override
    public void consumeOneTotemFromOffhand(Player player) {
        ServerPlayer nms = toNms(player);
        ItemStack offhand = nms.getItemBySlot(EquipmentSlot.OFFHAND);

        if (!offhand.isEmpty() && offhand.is(Items.TOTEM_OF_UNDYING)) {
            // shrink(1) decrements the stack count. If count reaches 0,
            // the slot becomes ItemStack.EMPTY internally.
            offhand.shrink(1);

            // Sync the inventory change to the client.
            // inventoryMenu is the player's always-present inventory container.
            // broadcastChanges() sends slot update packets for all changed slots.
            nms.inventoryMenu.broadcastChanges();

            DebugLogger.debug("NMS", "Consumed 1 totem from %s offhand. Remaining: %s",
                    player.getName(), offhand.isEmpty() ? "empty" : String.valueOf(offhand.getCount()));
        } else {
            DebugLogger.warn("consumeOneTotemFromOffhand called but no totem in offhand for " + player.getName());
        }
    }

    // ── Health & Effects ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b> {@code LivingEntity.setHealth(float)}
     * <p>Direct NMS call — no Bukkit RegainHealthEvent or EntityDamageEvent fired.</p>
     */
    @Override
    public void setHealthNms(Player player, float health) {
        ServerPlayer nms = toNms(player);
        // LivingEntity.setHealth() directly sets the health data watcher value.
        // No events are fired by this NMS call, giving us a clean state transition.
        nms.setHealth(health);
        DebugLogger.debug("NMS", "Set %s health to %.1f", player.getName(), health);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b> {@code LivingEntity.removeAllEffects()}
     * <p>Matches vanilla: totem resurrection clears ALL existing effects before
     * applying the totem effect set. This includes negative effects like poison/wither.</p>
     */
    @Override
    public void removeAllEffectsNms(Player player) {
        ServerPlayer nms = toNms(player);
        // removeAllEffects() clears the active effects map and sends removal packets.
        // Returns true if any effects were actually removed.
        nms.removeAllEffects();
        DebugLogger.debug("NMS", "Cleared all effects from %s", player.getName());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Vanilla Source (1.20.4 Mojang-mapped):</b>
     * {@code LivingEntity#checkTotemDeathProtection(DamageSource)} applies exactly:
     * <pre>
     * addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1))  // Regen II, 45s
     * addEffect(new MobEffectInstance(MobEffects.ABSORPTION,   100, 1))  // Absorption II, 5s
     * addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0)) // Fire Res I, 40s
     * </pre>
     *
     * <p><b>Effect Parameters:</b>
     * <ul>
     *   <li>MobEffectInstance(effect, duration_ticks, amplifier)</li>
     *   <li>amplifier 0 = Level I, amplifier 1 = Level II</li>
     *   <li>duration in ticks (20 ticks = 1 second)</li>
     * </ul>
     */
    @Override
    public void applyTotemEffectsNms(Player player) {
        ServerPlayer nms = toNms(player);

        // ── Regeneration II (45 seconds = 900 ticks, amplifier 1 = level II) ────
        // Restores 1 HP per tick × amplifier multiplier. Vanilla default for totem.
        nms.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));

        // ── Absorption II (5 seconds = 100 ticks, amplifier 1 = level II) ───────
        // Grants 8 absorption hearts (4 per amplifier level). Short duration by design.
        nms.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));

        // ── Fire Resistance I (40 seconds = 800 ticks, amplifier 0 = level I) ───
        // Prevents all fire/lava damage. Critical for crystal PvP survivability.
        nms.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));

        DebugLogger.debug("NMS", "Applied totem effects to %s: Regen II 45s, Absorption II 5s, Fire Res I 40s",
                player.getName());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b> {@code Entity.setRemainingFireTicks(0)}
     * <p>Vanilla totem code calls this before applying effects. Ensures the fire
     * resistance effect isn't immediately consumed by existing fire damage.</p>
     */
    @Override
    public void extinguishFireNms(Player player) {
        ServerPlayer nms = toNms(player);
        // setRemainingFireTicks(0) immediately stops fire damage and clears the fire visual.
        // The NMS fire tick counter must be zeroed BEFORE applying fire resistance,
        // otherwise the first fire resistance tick is wasted on the existing fire.
        nms.setRemainingFireTicks(0);
        DebugLogger.debug("NMS", "Extinguished fire on %s", player.getName());
    }

    // ── Animation & Sound ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Entity Event Byte 35 — Totem Pop:</b>
     * When clients receive {@code ClientboundEntityEventPacket} with status byte 35 for an entity,
     * they render:
     * <ul>
     *   <li>The golden totem overlay animation (2D sprite expanding from center)</li>
     *   <li>Green particle effects around the entity</li>
     *   <li>{@code SoundEvents.TOTEM_OF_UNDYING_USE} sound effect</li>
     * </ul>
     *
     * <p><b>NMS Path:</b>
     * <pre>
     * ServerLevel.broadcastEntityEvent(entity, (byte) 35)
     *   → creates ClientboundEntityEventPacket(entity, 35)
     *   → sends to all players tracking this entity + the entity itself
     * </pre>
     *
     * <p>This is identical to what vanilla calls in {@code LivingEntity#checkTotemDeathProtection}.
     * The sound is played CLIENT-SIDE — no server-side sound packet needed.</p>
     */
    @Override
    public void broadcastTotemPopAnimation(Player player) {
        ServerPlayer nms = toNms(player);

        // broadcastEntityEvent sends a ClientboundEntityEventPacket to all tracking players.
        // Byte 35 = TOTEM_OF_UNDYING animation. The client handles all visual/audio.
        // Using serverLevel() (not just level()) ensures we get the ServerLevel which has
        // the correct getChunkSource() for broadcasting.
        nms.serverLevel().broadcastEntityEvent(nms, (byte) 35);

        DebugLogger.debug("NMS", "Broadcast totem pop animation for %s (entity event byte 35)",
                player.getName());
    }

    // ── Damage Source ───────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Paper 1.20.4 DamageSource API:</b>
     * Paper 1.20.4 introduced {@code EntityDamageEvent.getDamageSource()} returning
     * {@code org.bukkit.damage.DamageSource}. The CraftBukkit implementation
     * ({@code CraftDamageSource}) wraps the NMS {@code DamageSource}.</p>
     *
     * <p><b>Extraction Path:</b>
     * <pre>
     * event.getDamageSource()                    → Bukkit DamageSource
     * (CraftDamageSource) bukkitSource           → CraftBukkit wrapper
     * craftSource.getHandle()                    → NMS DamageSource
     * </pre>
     *
     * <p><b>Why store the NMS DamageSource?</b> The NMS DamageSource contains:
     * <ul>
     *   <li>The exact damage type (registry reference)</li>
     *   <li>Direct entity reference (for "was slain by" messages)</li>
     *   <li>Causing entity reference (for "was blown up by" messages)</li>
     *   <li>Source position (for directional damage)</li>
     * </ul>
     * All of this is needed to produce faithful death messages during reconciliation.</p>
     */
    @Override
    public Object captureDamageSource(EntityDamageEvent event) {
        try {
            org.bukkit.damage.DamageSource bukkitSource = event.getDamageSource();
            if (bukkitSource instanceof CraftDamageSource craftSource) {
                DamageSource nmsSource = craftSource.getHandle();
                DebugLogger.debug("NMS", "Captured DamageSource: type=%s", nmsSource.type().msgId());
                return nmsSource;
            }
        } catch (Exception e) {
            // Graceful fallback: if DamageSource API is unavailable (shouldn't happen on 1.20.4),
            // return null. The reconciliation will use a generic damage source instead.
            DebugLogger.warn("Failed to capture NMS DamageSource: " + e.getMessage());
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b>
     * <pre>
     * ServerPlayer.invulnerableTime = 0    → reset I-frames (prevent hit cooldown)
     * ServerPlayer.hurt(DamageSource, amount) → apply damage through normal pipeline
     * </pre>
     *
     * <p><b>I-Frame Reset:</b> After the original damage was cancelled, the player may
     * still have invulnerability frames from earlier hits. We zero this to ensure
     * the re-applied damage registers. Without this, {@code hurt()} returns false.</p>
     *
     * <p><b>Fallback:</b> If the captured NMS damage source is null or invalid,
     * we use {@code damageSources().generic()} which produces a "was killed" death message.
     * This is acceptable as a fallback — the player dies correctly even if the message
     * is less specific.</p>
     */
    @Override
    public void dealDamageWithSource(Player player, float amount, Object nmsDamageSource) {
        ServerPlayer nms = toNms(player);

        // Reset invulnerability ticks to ensure the damage registers.
        // NMS invulnerableTime is decremented each tick; if > 0, hurt() ignores damage.
        nms.invulnerableTime = 0;

        DamageSource source;
        if (nmsDamageSource instanceof DamageSource captured) {
            source = captured;
        } else {
            // Fallback: generic damage source produces "Player was killed" death message.
            // This only triggers if DamageSource capture failed (shouldn't happen on 1.20.4).
            DebugLogger.warn("Using generic DamageSource for " + player.getName()
                    + " — original source was not captured.");
            source = nms.damageSources().generic();
        }

        DebugLogger.debug("NMS", "Re-applying %.1f damage to %s with source type: %s",
                amount, player.getName(), source.type().msgId());

        // hurt() sends the damage through the normal NMS pipeline:
        // armor reduction, enchantment protection, absorption, etc.
        // Since we're re-applying a lethal amount, this should kill the player.
        nms.hurt(source, amount);
    }

    // ── Tick / Version ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>NMS Path:</b> {@code MinecraftServer.getTickCount()}
     * <p>Returns the total number of ticks the server has processed since startup.
     * This is a monotonically increasing counter, thread-safe to read.</p>
     */
    @Override
    public long getCurrentTick() {
        // MinecraftServer.getTickCount() is an int internally but we return long
        // to avoid overflow issues on very long-running servers (rollover at ~3.4 years).
        return MinecraftServer.getServer().getTickCount();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getVersionTag() {
        return VERSION_TAG;
    }
}
