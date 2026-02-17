package net.opmasterleo.masterantighost.combat;

import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import org.bukkit.entity.Player;

public final class ManualResurrection {

    private final NmsAccessor nms;

    public ManualResurrection(NmsAccessor nms) {
        this.nms = nms;
    }

    public boolean attemptResurrection(Player player) {
        DebugLogger.debug("Resurrection", "Attempting resurrection for %s", player.getName());

        if (!nms.hasTotemInOffhand(player)) {
            DebugLogger.debug("Resurrection", "No totem in offhand for %s — resurrection failed",
                    player.getName());
            return false;
        }

        nms.consumeOneTotemFromOffhand(player);

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
