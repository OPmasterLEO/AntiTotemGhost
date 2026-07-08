package net.opmasterleo.masterantighost.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import net.opmasterleo.masterantighost.combat.CombatEngine;
import net.opmasterleo.masterantighost.debug.DebugLogger;

public final class DamageListener implements Listener {

    private static final String TAG = "DamageListener";
    private final CombatEngine combatEngine;

    public DamageListener(CombatEngine combatEngine) {
        this.combatEngine = combatEngine;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (combatEngine.isBypassing(player.getUniqueId())) {
            return;
        }

        double effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
        double finalDamage = event.getFinalDamage();
        if (!Double.isFinite(finalDamage) || finalDamage <= 0.0d) {
            return;
        }
        if (finalDamage < effectiveHealth) {
            return;
        }

        DebugLogger.debug(TAG, "Lethal damage on %s: %.2f", player.getName(), finalDamage);
        combatEngine.handleLethalDamage(player, event);
    }

}
