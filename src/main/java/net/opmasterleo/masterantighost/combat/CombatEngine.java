package net.opmasterleo.masterantighost.combat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.runtime.scheduler.SchedulerHub;

public final class CombatEngine {

    public enum Phase {
        IDLE,
        PENDING_LETHAL,
        RESURRECTED,
        DEAD
    }

    private final Supplier<PluginConfig> configSupplier;
    private final NmsAccessor nms;
    private final SwapBuffer swapBuffer;
    private final ManualResurrection manualResurrection;
    private final SchedulerHub schedulerHub;
    private final SwapEvidenceLedger ledger;

    private final LongAdder fastPathPops;
    private final LongAdder reconciledPops;
    private final LongAdder reconciledDeaths;
    private final LongAdder interceptedHits;

    private final ConcurrentHashMap<UUID, Phase> phases = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<UUID, DamageCoalescer> damageCoalescers = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<UUID, Long> pendingSinceTick = new ConcurrentHashMap<>(64);

    public CombatEngine(Supplier<PluginConfig> configSupplier,
                         NmsAccessor nms,
                         SwapBuffer swapBuffer,
                         ManualResurrection manualResurrection,
                         SchedulerHub schedulerHub,
                         LongAdder fastPathPops,
                         LongAdder reconciledPops,
                         LongAdder reconciledDeaths,
                         LongAdder interceptedHits) {
        this.configSupplier = configSupplier;
        this.nms = nms;
        this.swapBuffer = swapBuffer;
        this.manualResurrection = manualResurrection;
        this.schedulerHub = schedulerHub;
        this.ledger = new SwapEvidenceLedger(swapBuffer, nms);
        this.fastPathPops = fastPathPops;
        this.reconciledPops = reconciledPops;
        this.reconciledDeaths = reconciledDeaths;
        this.interceptedHits = interceptedHits;
    }

    public boolean isBypassing(UUID playerId) {
        Phase phase = phases.get(playerId);
        return phase == Phase.PENDING_LETHAL || phase == Phase.DEAD;
    }

    public void handleLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();
        PluginConfig config = configSupplier.get();

        if (isBypassing(playerId)) {
            event.setCancelled(true);
            coalesceDamage(playerId, event);
            return;
        }

        if (config.isEnableFastPath() && nms.hasTotemInEitherHand(player)) {
            fastPathPops.increment();
            return;
        }

        event.setCancelled(true);
        interceptedHits.increment();

        long tick = nms.getCurrentTick();
        Object nmsDamageSource = nms.captureDamageSource(event);
        DamageContext initialContext = createContext(playerId, event, nmsDamageSource, tick);
        damageCoalescers.put(playerId, new DamageCoalescer(initialContext));
        pendingSinceTick.put(playerId, tick);
        phases.put(playerId, Phase.PENDING_LETHAL);

        schedulerHub.runEntity(player, () -> {
            boolean hasRecent = !config.isSandboxMode() && swapBuffer.hasAnyRecentSwapActivity(playerId, tick);
            if (!hasRecent) {
                DamageContext merged = getCurrentContext(playerId);
                if (merged != null) {
                    applyTrueLethalDamage(player, merged.damage(), merged.nmsDamageSource());
                }
                clearState(playerId);
                return;
            }
            schedulerHub.runEntityLater(player, () -> reconcileLethalDamage(player), config.getReconciliationTicks());
        });
    }

    public void onPlayerQuit(UUID playerId) {
        clearState(playerId);
        swapBuffer.clearPlayer(playerId);
    }

    public void cleanupStaleEntries() {
        PluginConfig config = configSupplier.get();
        long now = nms.getCurrentTick();
        long maxAge = Math.max(10L, config.getReconciliationTicks() + 6L);
        pendingSinceTick.forEach((id, pendingTick) -> {
            if (now - pendingTick > maxAge) {
                clearState(id);
            }
        });
    }

    public void shutdown() {
        phases.clear();
        damageCoalescers.clear();
        pendingSinceTick.clear();
    }

    private void reconcileLethalDamage(Player player) {
        UUID playerId = player.getUniqueId();
        try {
            DamageContext context = getCurrentContext(playerId);
            if (context == null) {
                return;
            }

            boolean validSwap = configSupplier.get().isSandboxMode()
                    || swapBuffer.hasRecentTotemActivity(playerId, nms.getCurrentTick())
                    || nms.hasTotemInEitherHand(player);

            if (validSwap) {
                boolean popped = manualResurrection.attemptResurrection(player);
                if (popped) {
                    reconciledPops.increment();
                    phases.put(playerId, Phase.RESURRECTED);
                    return;
                }
            }

            reconciledDeaths.increment();
            applyTrueLethalDamage(player, context.damage(), context.nmsDamageSource());
        } finally {
            clearState(playerId);
        }
    }

    private void applyTrueLethalDamage(Player player, double damage, Object nmsDamageSource) {
        UUID playerId = player.getUniqueId();
        phases.put(playerId, Phase.DEAD);
        nms.dealDamageWithSource(player, (float) damage, nmsDamageSource);
        phases.put(playerId, Phase.IDLE);
        phases.remove(playerId);
    }

    private void coalesceDamage(UUID playerId, EntityDamageEvent event) {
        DamageCoalescer current = damageCoalescers.get(playerId);
        if (current == null) {
            long tick = nms.getCurrentTick();
            Object nmsDamageSource = nms.captureDamageSource(event);
            DamageContext ctx = createContext(playerId, event, nmsDamageSource, tick);
            damageCoalescers.put(playerId, new DamageCoalescer(ctx));
            pendingSinceTick.put(playerId, tick);
            return;
        }

        Object nmsDamageSource = nms.captureDamageSource(event);
        long tick = nms.getCurrentTick();
        current.addDamage(event.getFinalDamage(), nmsDamageSource, tick);
    }

    private DamageContext getCurrentContext(UUID playerId) {
        DamageCoalescer coalescer = damageCoalescers.get(playerId);
        return coalescer == null ? null : coalescer.getCoalescedDamage();
    }

    private void clearState(UUID playerId) {
        phases.remove(playerId);
        damageCoalescers.remove(playerId);
        pendingSinceTick.remove(playerId);
    }

    private DamageContext createContext(UUID playerId, EntityDamageEvent event, Object nmsDamageSource, long tick) {
        org.bukkit.entity.Entity attacker = null;
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
            attacker = byEntity.getDamager();
        }
        return new DamageContext(
                playerId,
                event.getFinalDamage(),
                event.getCause(),
                attacker,
                nmsDamageSource,
                tick
        );
    }
}

