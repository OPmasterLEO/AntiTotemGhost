package net.opmasterleo.AntiTotemGhost.combat;

import org.bukkit.entity.Player;

import net.opmasterleo.AntiTotemGhost.debug.DebugLogger;
import net.opmasterleo.AntiTotemGhost.nms.NmsAccessor;

public final class ManualResurrection {

    private final NmsAccessor nms;

    public ManualResurrection(NmsAccessor nms) {
        this.nms = nms;
    }

    public boolean attemptResurrection(Player player) {
        DebugLogger.debug("Resurrection", "Attempting resurrection for %s", player.getName());

        if (!nms.consumeTotemFromEitherHandIfPresent(player)) {
            DebugLogger.debug("Resurrection", "No consumable hand totem for %s — resurrection failed",
                    player.getName());
            return false;
        }

        nms.setHealthNms(player, 1.0f);

        nms.removeAllEffectsNms(player);

        nms.extinguishFireNms(player);

        nms.applyTotemEffectsNms(player);

        nms.broadcastTotemPopAnimation(player);

        DebugLogger.debug("Resurrection", "Successfully resurrected %s — totem consumed, " +
                "health=1.0, effects applied, animation broadcast", player.getName());

        return true;
    }
}
