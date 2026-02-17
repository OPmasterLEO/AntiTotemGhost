package net.opmasterleo.masterantighost.combat;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class CombatManager {

    private static final String TAG = "CombatManager";

    private final Plugin plugin;
    private final Supplier<PluginConfig> configSupplier;
    private final NmsAccessor nms;
    private final SwapBuffer swapBuffer;
    private final ManualResurrection resurrection;
    private final FoliaScheduler scheduler;

    private final LongAdder fastPathPops;
    private final LongAdder reconciledPops;
    private final LongAdder reconciledDeaths;
    private final LongAdder interceptedHits;

    private final ConcurrentHashMap<UUID, AtomicReference<CombatState>> playerStates;
    private final ConcurrentHashMap<UUID, DamageCoalescer> damageCoalescers;
    private final Set<UUID> bypassSet;
    private final Set<UUID> reconciliationScheduled;

    public CombatManager(Plugin plugin, Supplier<PluginConfig> configSupplier, NmsAccessor nms,
                         SwapBuffer swapBuffer, ManualResurrection resurrection,
                         FoliaScheduler scheduler,
                         LongAdder fastPathPops, LongAdder reconciledPops,
                         LongAdder reconciledDeaths, LongAdder interceptedHits) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.nms = nms;
        this.swapBuffer = swapBuffer;
        this.resurrection = resurrection;
        this.scheduler = scheduler;
        this.fastPathPops = fastPathPops;
        this.reconciledPops = reconciledPops;
        this.reconciledDeaths = reconciledDeaths;
        this.interceptedHits = interceptedHits;
        this.playerStates = new ConcurrentHashMap<>(128);
        this.damageCoalescers = new ConcurrentHashMap<>(64);
        this.bypassSet = ConcurrentHashMap.newKeySet(64);
        this.reconciliationScheduled = ConcurrentHashMap.newKeySet(64);
    }

    public void handleLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();

        if (bypassSet.remove(playerId)) {
            DebugLogger.debug(TAG, "Bypass active for %s", player.getName());
            return;
        }

        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef != null && stateRef.get() == CombatState.PENDING_LETHAL) {
            coalesceDamage(player, event);
            return;
        }

        if (configSupplier.get().isEnableFastPath()) {
            if (resurrection.attemptResurrection(player)) {
                event.setCancelled(true);
                fastPathPops.increment();
                return;
            }
        }

        interceptLethalDamage(player, event);
    }

    public boolean isBypassing(UUID playerId) {
        return bypassSet.contains(playerId);
    }

    public CombatState getPlayerState(UUID playerId) {
        AtomicReference<CombatState> ref = playerStates.get(playerId);
        return ref != null ? ref.get() : CombatState.NORMAL;
    }

    private void interceptLethalDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();
        AtomicReference<CombatState> stateRef = playerStates.computeIfAbsent(
                playerId, k -> new AtomicReference<>(CombatState.NORMAL)
        );

        if (!stateRef.compareAndSet(CombatState.NORMAL, CombatState.PENDING_LETHAL)) {
            if (stateRef.get() == CombatState.PENDING_LETHAL) {
                coalesceDamage(player, event);
            }
            return;
        }

        event.setCancelled(true);
        interceptedHits.increment();

        Object nmsDamageSource = nms.captureDamageSource(event);
        long currentTick = nms.getCurrentTick();
        UUID attackerId = null;

        try {
            if (event.getDamageSource() != null) {
                Entity causing = event.getDamageSource().getCausingEntity();
                if (causing != null) {
                    attackerId = causing.getUniqueId();
                }
            }
        } catch (Exception ignored) {
        }

        DamageContext context = new DamageContext(
                playerId,
                event.getFinalDamage(),
                event.getCause(),
                attackerId,
                nmsDamageSource,
                currentTick
        );

        damageCoalescers.put(playerId, new DamageCoalescer(context));
        scheduleReconciliation(player, playerId, 0);
    }

    private void coalesceDamage(Player player, EntityDamageEvent event) {
        UUID playerId = player.getUniqueId();
        event.setCancelled(true);

        DamageCoalescer coalescer = damageCoalescers.get(playerId);
        if (coalescer != null) {
            Object newSource = nms.captureDamageSource(event);
            long currentTick = nms.getCurrentTick();
            coalescer.addDamage(event.getFinalDamage(), newSource, currentTick);
            DebugLogger.debug(TAG, "Coalesced for %s total=%.2f", player.getName(), coalescer.getTotalDamage());
        }
    }

    private void scheduleReconciliation(Player player, UUID playerId, int attempt) {
        if (attempt == 0 && !reconciliationScheduled.add(playerId)) {
            return;
        }

        int delayTicks = configSupplier.get().getReconciliationTicks();
        scheduler.runOnEntityThreadDelayed(player, () -> performReconciliation(player, playerId, attempt), delayTicks);
    }

    private void performReconciliation(Player player, UUID playerId, int attempt) {
        if (!player.isOnline()) {
            cleanupPlayer(playerId);
            return;
        }

        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef == null || stateRef.get() != CombatState.PENDING_LETHAL) {
            return;
        }

        long currentTick = nms.getCurrentTick();

        if (nms.hasTotemInOffhand(player)) {
            resolveAsResurrection(player, playerId, stateRef);
            return;
        }

        int maxAttempts = configSupplier.get().getSwapBufferTicks();
        if (swapBuffer.hasRecentTotemActivity(playerId, currentTick)) {
            if (attempt < maxAttempts) {
                scheduleReconciliation(player, playerId, attempt + 1);
                return;
            }
            if (nms.hasTotemInOffhand(player)) {
                resolveAsResurrection(player, playerId, stateRef);
                return;
            }
        }

        if (attempt == 0 && swapBuffer.hasAnyRecentSwapActivity(playerId, currentTick) && attempt < maxAttempts) {
            scheduleReconciliation(player, playerId, attempt + 1);
            return;
        }

        resolveAsDeath(player, playerId, stateRef);
    }

    private void resolveAsResurrection(Player player, UUID playerId, AtomicReference<CombatState> stateRef) {
        if (!stateRef.compareAndSet(CombatState.PENDING_LETHAL, CombatState.RESURRECTED)) {
            return;
        }

        if (resurrection.attemptResurrection(player)) {
            reconciledPops.increment();
        } else {
            stateRef.set(CombatState.PENDING_LETHAL);
            resolveAsDeath(player, playerId, stateRef);
            return;
        }

        scheduler.runOnEntityThreadDelayed(player, () -> {
            stateRef.set(CombatState.NORMAL);
            cleanupPlayer(playerId);
        }, 2L);
    }

    private void resolveAsDeath(Player player, UUID playerId, AtomicReference<CombatState> stateRef) {
        if (!stateRef.compareAndSet(CombatState.PENDING_LETHAL, CombatState.DEAD)) {
            return;
        }

        DamageCoalescer coalescer = damageCoalescers.get(playerId);
        reconciledDeaths.increment();

        if (coalescer != null) {
            bypassSet.add(playerId);
            float totalDamage = (float) coalescer.getTotalDamage();
            Object damageSource = coalescer.getLatestDamageSource();
            float lethalDamage = Math.max(totalDamage, (float) player.getHealth() + 1.0f);
            nms.dealDamageWithSource(player, lethalDamage, damageSource);
        } else {
            bypassSet.add(playerId);
            player.setHealth(0);
        }

        scheduler.runOnEntityThreadDelayed(player, () -> {
            stateRef.set(CombatState.NORMAL);
            cleanupPlayer(playerId);
        }, 2L);
    }

    public void cleanupPlayer(UUID playerId) {
        damageCoalescers.remove(playerId);
        bypassSet.remove(playerId);
        reconciliationScheduled.remove(playerId);
    }

    public void cleanupStaleEntries() {
        playerStates.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            CombatState state = entry.getValue().get();
            if (state == CombatState.NORMAL) {
                var player = plugin.getServer().getPlayer(playerId);
                return player == null || !player.isOnline();
            }
            return false;
        });
    }

    public void onPlayerQuit(UUID playerId) {
        AtomicReference<CombatState> stateRef = playerStates.get(playerId);
        if (stateRef != null) {
            stateRef.set(CombatState.NORMAL);
        }
        cleanupPlayer(playerId);
        swapBuffer.clearPlayer(playerId);
    }

    public void shutdown() {
        playerStates.clear();
        damageCoalescers.clear();
        bypassSet.clear();
        reconciliationScheduled.clear();
        DebugLogger.info("CombatManager shutdown");
    }
}
