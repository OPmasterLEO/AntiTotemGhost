package net.opmasterleo.masterantighost.combat;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.nms.NmsAccessor;

public final class SwapEvidenceLedger {

    public record Evidence(
            boolean immediateTotem,
            boolean recentTotemActivity,
            boolean recentAnySwapActivity
    ) {
    }

    private final SwapBuffer swapBuffer;
    private final NmsAccessor nmsAccessor;

    public SwapEvidenceLedger(SwapBuffer swapBuffer, NmsAccessor nmsAccessor) {
        this.swapBuffer = swapBuffer;
        this.nmsAccessor = nmsAccessor;
    }

    public Evidence snapshot(UUID playerId, Player player, long snapshotTick) {
        boolean immediateTotem = player != null && player.isOnline() && nmsAccessor.hasTotemInEitherHand(player);
        boolean recentTotemActivity = swapBuffer.hasRecentTotemActivity(playerId, snapshotTick);
        boolean recentAnySwapActivity = swapBuffer.hasAnyRecentSwapActivity(playerId, snapshotTick);
        return new Evidence(immediateTotem, recentTotemActivity, recentAnySwapActivity);
    }

    public boolean shouldAttemptPop(Evidence evidence, boolean sandboxMode) {
        if (sandboxMode) {
            return true;
        }
        if (evidence.immediateTotem) {
            return true;
        }
        if (evidence.recentTotemActivity) {
            return true;
        }
        return false;
    }
}

