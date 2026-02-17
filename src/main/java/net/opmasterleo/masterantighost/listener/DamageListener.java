package net.opmasterleo.masterantighost.listener;

import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Damage event listener — entry point for the anti-ghost pipeline.
 *
 * <p><b>Event Priority: HIGHEST</b>
 * We run at HIGHEST priority so that:
 * <ol>
 *   <li>Other plugins (protection, region, permissions) have already modified/cancelled the event.</li>
 *   <li>The finalDamage() reflects all armor, enchantment, and plugin modifications.</li>
 *   <li>We intercept AFTER all modifications but BEFORE vanilla applies the damage.</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b> Bukkit event handlers run on the region thread that owns
 * the entity (Folia) or the main thread (Paper). All operations here are safe
 * for that execution context. The CombatManager handles cross-thread coordination.</p>
 *
 * <p><b>Performance:</b> The lethality check ({@code health - finalDamage <= 0}) is
 * a simple comparison with no allocations. Non-lethal hits exit in nanoseconds.</p>
 */
public final class DamageListener implements Listener {

    private static final String TAG = "DamageListener";

    private final CombatManager combatManager;
    private final PluginConfig config;

    public DamageListener(CombatManager combatManager, PluginConfig config) {
        this.combatManager = combatManager;
        this.config = config;
    }

    /**
     * Intercept EntityDamageEvent to detect lethal damage on players.
     *
     * <p>Priority: HIGHEST — after other plugins, before vanilla application.</p>
     * <p>ignoreCancelled: true — don't process events already cancelled by other plugins.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        // ── Filter: Only players ────────────────────────────────────────────────
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // ── Filter: Skip if this damage is being re-applied by our system ───────
        // The bypass set check is done first for fast exit (ConcurrentHashMap.contains is O(1)).
        if (combatManager.isBypassing(player.getUniqueId())) {
            DebugLogger.debug(TAG, "Bypassed damage event for %s (re-application)", player.getName());
            return;
        }

        // ── Lethality Check ─────────────────────────────────────────────────────
        // A hit is "lethal" if the player's current health minus the final damage
        // would drop to zero or below. finalDamage includes all Bukkit event
        // modifications (armor, resistance, enchantments, other plugins).
        double health = player.getHealth();
        double finalDamage = event.getFinalDamage();

        // Account for absorption hearts which absorb damage before health.
        // Absorption is tracked as a separate value in Bukkit.
        double absorptionHearts = player.getAbsorptionAmount();
        double effectiveDamage = finalDamage - absorptionHearts;

        if (effectiveDamage < health) {
            // Non-lethal hit — exit immediately. This is the fast path for
            // the vast majority of damage events (no totem logic needed).
            return;
        }

        // ── Lethal Hit Detected ─────────────────────────────────────────────────
        DebugLogger.debug(TAG, "Lethal damage detected on %s: %.1f damage, %.1f health, %.1f absorption",
                player.getName(), finalDamage, health, absorptionHearts);

        // Delegate to CombatManager which orchestrates the A/B/C/D pipeline.
        combatManager.handleLethalDamage(player, event);
    }

    /**
     * Cleanup player state on disconnect.
     *
     * <p>If a player disconnects while in PENDING_LETHAL state, the reconciliation
     * task (scheduled via Entity Scheduler) will be retired automatically. We clean
     * up our data structures here to prevent memory leaks.</p>
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        combatManager.onPlayerQuit(event.getPlayer().getUniqueId());
        DebugLogger.debug(TAG, "Player %s quit — cleaned up combat state", event.getPlayer().getName());
    }
}
