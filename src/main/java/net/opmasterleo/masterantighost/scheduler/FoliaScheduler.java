package net.opmasterleo.masterantighost.scheduler;

import net.opmasterleo.masterantighost.debug.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia-safe scheduler abstraction.
 *
 * <p>Detects Folia at runtime and routes scheduling calls appropriately:
 * <ul>
 *   <li><b>Folia:</b> Uses Entity Scheduler for player-scoped tasks (runs on the
 *       region thread that owns the entity) and Global Region Scheduler for
 *       non-entity periodic tasks.</li>
 *   <li><b>Paper:</b> Falls back to Bukkit Scheduler which runs on the main thread.</li>
 * </ul>
 *
 * <p><b>Why Entity Scheduler for player tasks?</b>
 * In Folia, each region has its own thread. A player belongs to exactly one region at a time.
 * {@code entity.getScheduler().run()} guarantees the task executes on the region thread that
 * currently owns that entity. This is REQUIRED for NMS access — touching NMS state from a
 * non-owning thread causes undefined behavior or crashes.</p>
 *
 * <p><b>Why Global Scheduler for cleanup?</b>
 * SwapBuffer cleanup and statistics aggregation don't touch entity state or NMS.
 * They only operate on ConcurrentHashMap entries, which are safe from any thread.
 * Global Scheduler provides a predictable scheduling context for these operations.</p>
 *
 * <p><b>Thread Safety:</b> {@code FOLIA} flag is computed once at class load (final static).
 * Scheduling methods delegate to Paper/Folia APIs which are themselves thread-safe.</p>
 */
public final class FoliaScheduler {

    /**
     * Cached Folia detection result. Computed once at class initialization.
     * Uses class existence check for RegionizedServer — the canonical Folia indicator.
     */
    private static final boolean FOLIA;

    static {
        boolean hasFolia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            hasFolia = true;
        } catch (ClassNotFoundException e) {
            hasFolia = false;
        }
        FOLIA = hasFolia;
    }

    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Whether the server is running Folia (regionized multithreading). */
    public static boolean isFolia() {
        return FOLIA;
    }

    // ── Entity-Scoped Scheduling ────────────────────────────────────────────────

    /**
     * Run a task on the region thread that owns the given entity.
     * <p>
     * <b>Folia:</b> Uses {@code entity.getScheduler().run()} — guarantees execution
     * on the owning region thread. If the entity is removed before execution,
     * the retired callback runs instead (we pass null to silently discard).
     * <p>
     * <b>Paper:</b> Uses {@code Bukkit.getScheduler().runTask()} — main thread.
     *
     * @param entity the entity whose region thread should execute the task
     * @param task   the task to execute (must be NMS-safe for the entity)
     */
    public void runOnEntityThread(Entity entity, Runnable task) {
        if (FOLIA) {
            // Folia: Entity Scheduler ensures we're on the region thread owning this entity.
            // The 'null' retired callback means: if the entity is removed before the task
            // executes, silently discard. This is safe because a removed player can't be
            // resurrected anyway.
            entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
        } else {
            // Paper: single main thread, always safe for NMS.
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run a task on the entity's region thread after a delay.
     * <p>
     * This is the primary method for reconciliation scheduling.
     * The delay is in server ticks (1 tick ≈ 50ms at 20 TPS).
     *
     * @param entity    the entity whose region thread should execute the task
     * @param task      the task to execute
     * @param delayTicks delay in ticks before execution
     */
    public void runOnEntityThreadDelayed(Entity entity, Runnable task, long delayTicks) {
        if (FOLIA) {
            // Folia: Entity Scheduler with delay. Task runs on the entity's owning region
            // thread after the specified tick delay. Thread ownership may change if the
            // entity teleports to a different region — the scheduler handles this correctly.
            entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
        } else {
            // Paper: delayed task on main thread.
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    // ── Global Scheduling ───────────────────────────────────────────────────────

    /**
     * Run a repeating task on the Global Region Scheduler.
     * <p>
     * <b>Folia:</b> Uses {@code Bukkit.getGlobalRegionScheduler().runAtFixedRate()}.
     * The global thread is for tasks that don't interact with any specific region's entities.
     * <p>
     * <b>Paper:</b> Uses {@code Bukkit.getScheduler().runTaskTimer()}.
     *
     * @param task         the repeating task
     * @param initialDelay initial delay in ticks
     * @param periodTicks  period between executions in ticks
     */
    public void runOnGlobalTimer(Runnable task, long initialDelay, long periodTicks) {
        if (FOLIA) {
            // Folia: Global Region Scheduler — no entity access allowed here.
            // Only ConcurrentHashMap operations and logging.
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduledTask -> task.run(), initialDelay, periodTicks);
        } else {
            // Paper: repeating task on main thread.
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, periodTicks);
        }
    }

    /**
     * Run a task on the Global Region Scheduler once.
     */
    public void runOnGlobal(Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
