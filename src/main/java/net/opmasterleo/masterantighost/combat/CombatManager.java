package net.opmasterleo.masterantighost.combat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;

public final class CombatManager {

    private static final String TAG = "CombatManager";

    private final Supplier<PluginConfig> configSupplier;
    private final NmsAccessor nms;
    private final SwapBuffer swapBuffer;
    private final ManualResurrection manualResurrection;
    private final FoliaScheduler scheduler;

    private final LongAdder fastPathPops;
    private final LongAdder reconciledPops;
    private final LongAdder reconciledDeaths;
    private final LongAdder interceptedHits;

    private final ConcurrentHashMap<UUID, CombatState> playerStates = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<UUID, DamageCoalescer> lethalDamageBuffer = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<UUID, Long> pendingSinceTick = new ConcurrentHashMap<>(64);

    public CombatManager(Supplier<PluginConfig> configSupplier,
                         NmsAccessor nms,
                         SwapBuffer swapBuffer,
                         ManualResurrection manualResurrection,
                         FoliaScheduler scheduler,
                         LongAdder fastPathPops,
                         LongAdder reconciledPops,
                         LongAdder reconciledDeaths,
                         LongAdder interceptedHits) {
        this.configSupplier = configSupplier;
        this.nms = nms;
        this.swapBuffer = swapBuffer;
        this.manualResurrection = manualResurrection;
        this.scheduler = scheduler;
        this.fastPathPops = fastPathPops;
        this.reconciledPops = reconciledPops;
        this.reconciledDeaths = reconciledDeaths;
        this.interceptedHits = interceptedHits;
    }

    public void handleLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();
        PluginConfig config = configSupplier.get();

        if (playerStates.get(playerId) == CombatState.PENDING_LETHAL) {
            event.setCancelled(true);
            coalesceDamage(playerId, event);
            return;
        }

        if (nms.hasTotemInEitherHand(player)) {
            if (config.isEnableFastPath()) {
                fastPathPops.increment();
            }
            return;
        }

        event.setCancelled(true);
        interceptedHits.increment();

        playerStates.put(playerId, CombatState.PENDING_LETHAL);
        Object nmsDamageSource = nms.captureDamageSource(event);
        DamageContext initialContext = createContext(playerId, event, nmsDamageSource, event.getFinalDamage());
        lethalDamageBuffer.put(playerId, new DamageCoalescer(initialContext));
        pendingSinceTick.put(playerId, initialContext.tick());

        long tick = initialContext.tick();
        scheduler.runOnEntityThread(player, () -> {
            boolean hasRecent = !config.isSandboxMode() && (
                    swapBuffer.hasAnyRecentSwapActivity(playerId, tick)
                            || nms.hasTotemInEitherHand(player)
            );
            if (!hasRecent) {
                DamageContext merged = getCurrentContext(playerId, initialContext);
                applyTrueLethalDamage(player, merged.damage(), merged.nmsDamageSource());
                lethalDamageBuffer.remove(playerId);
                return;
            }

            scheduler.runOnEntityThreadDelayed(player, () -> {
                Player target = org.bukkit.Bukkit.getPlayer(playerId);
                if (target != null && target.isOnline()) {
                    reconcileLethalDamage(target);
                }
            }, config.getReconciliationTicks());
        });
    }

    private void reconcileLethalDamage(Player player) {
        UUID playerId = player.getUniqueId();
        try {
            DamageContext context = getCurrentContext(playerId, null);
            if (context == null) {
                return;
            }

            boolean validSwap = swapBuffer.hasRecentTotemActivity(playerId, nms.getCurrentTick())
                    || nms.hasTotemInEitherHand(player);
            if (configSupplier.get().isSandboxMode()) {
                validSwap = true;
            }

            if (validSwap && manualResurrection.attemptResurrection(player)) {
                reconciledPops.increment();
                playerStates.put(playerId, CombatState.RESURRECTED);
                DebugLogger.debug(TAG, "Reconciled POP for %s after latent swap", player.getName());
                return;
            }

            reconciledDeaths.increment();
            DebugLogger.debug(TAG, "Reconciled DEATH for %s (no latent totem)", player.getName());
            applyTrueLethalDamage(player, context.damage(), context.nmsDamageSource());
        } finally {
            lethalDamageBuffer.remove(playerId);
            pendingSinceTick.remove(playerId);
            CombatState state = playerStates.get(playerId);
            if (state == CombatState.PENDING_LETHAL || state == CombatState.RESURRECTED) {
                playerStates.put(playerId, CombatState.NORMAL);
            }
        }
    }

    private void applyTrueLethalDamage(Player player, double damage, Object nmsDamageSource) {
        UUID playerId = player.getUniqueId();
        playerStates.put(playerId, CombatState.DEAD);
        nms.dealDamageWithSource(player, (float) damage, nmsDamageSource);
        playerStates.put(playerId, CombatState.NORMAL);
    }

    public boolean isBypassing(UUID playerId) {
        CombatState state = playerStates.get(playerId);
        return state == CombatState.PENDING_LETHAL || state == CombatState.DEAD;
    }

    public void onPlayerQuit(UUID playerId) {
        playerStates.remove(playerId);
        lethalDamageBuffer.remove(playerId);
        pendingSinceTick.remove(playerId);
        swapBuffer.clearPlayer(playerId);
    }

    public void cleanupStaleEntries() {
        long now = nms.getCurrentTick();
        long maxAge = Math.max(10L, configSupplier.get().getReconciliationTicks() + 6L);
        pendingSinceTick.forEach((playerId, pendingTick) -> {
            if (now - pendingTick > maxAge) {
                pendingSinceTick.remove(playerId);
                lethalDamageBuffer.remove(playerId);
                playerStates.put(playerId, CombatState.NORMAL);
            }
        });
    }

    public void shutdown() {
        playerStates.clear();
        lethalDamageBuffer.clear();
        pendingSinceTick.clear();
    }

    private void coalesceDamage(UUID playerId, EntityDamageEvent event) {
        double damage = event.getFinalDamage();
        if (!Double.isFinite(damage) || damage <= 0.0d) {
            return;
        }

        Object nmsDamageSource = nms.captureDamageSource(event);
        long tick = nms.getCurrentTick();

        lethalDamageBuffer.compute(playerId, (id, current) -> {
            if (current == null) {
                DamageContext fallback = createContext(id, event, nmsDamageSource, damage);
                return new DamageCoalescer(fallback);
            }
            current.addDamage(damage, nmsDamageSource, tick);
            return current;
        });
    }

    private DamageContext createContext(UUID playerId, EntityDamageEvent event, Object nmsDamageSource, double damage) {
        Entity attacker = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            attacker = byEntity.getDamager();
        }

        return new DamageContext(
                playerId,
                damage,
                event.getCause(),
                attacker,
                nmsDamageSource,
                nms.getCurrentTick()
        );
    }

    private DamageContext getCurrentContext(UUID playerId, DamageContext fallback) {
        DamageCoalescer coalescer = lethalDamageBuffer.get(playerId);
        if (coalescer == null) {
            return fallback;
        }
        return coalescer.getCoalescedDamage();
    }
}
