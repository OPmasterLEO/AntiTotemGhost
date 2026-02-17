package net.opmasterleo.masterantighost.listener;

import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class DamageListener implements Listener {

    private static final String TAG = "DamageListener";
    private final CombatManager combatManager;

    public DamageListener(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (combatManager.isBypassing(player.getUniqueId())) {
            return;
        }

        double health = player.getHealth();
        double finalDamage = event.getFinalDamage();
        double absorptionHearts = player.getAbsorptionAmount();
        double effectiveDamage = finalDamage - absorptionHearts;

        if (effectiveDamage < health) {
            return;
        }

        DebugLogger.debug(TAG, "Lethal damage on %s: %.2f", player.getName(), finalDamage);
        combatManager.handleLethalDamage(player, event);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        combatManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
