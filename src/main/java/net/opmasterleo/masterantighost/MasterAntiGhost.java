package net.opmasterleo.masterantighost;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.listener.CommandListener;
import net.opmasterleo.masterantighost.listener.DamageListener;
import net.opmasterleo.masterantighost.listener.SwapListener;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.nms.NmsAccessorImpl_v1_20_R3;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.LongAdder;

/*
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║                    FOLIA MULTITHREADED ARCHITECTURE                         ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                            ║
 * ║  REGION THREAD (per-region, owns player entity)                            ║
 * ║  ├─ Bukkit event handlers (DamageListener, SwapListener)                   ║
 * ║  ├─ NMS access (read/write player inventory, health, effects)              ║
 * ║  ├─ Reconciliation logic (scheduled via Entity Scheduler)                  ║
 * ║  └─ Manual resurrection (NMS totem pop operations)                         ║
 * ║                                                                            ║
 * ║  GLOBAL THREAD                                                             ║
 * ║  ├─ SwapBuffer periodic cleanup (no NMS, no entity access)                 ║
 * ║  ├─ Statistics aggregation (LongAdder reads)                               ║
 * ║  └─ Config reload                                                          ║
 * ║                                                                            ║
 * ║  CONCURRENT DATA STRUCTURES (safe across all threads)                      ║
 * ║  ├─ ConcurrentHashMap  — player states, damage contexts, swap entries      ║
 * ║  ├─ ConcurrentLinkedDeque — per-player swap buffer rolling window          ║
 * ║  ├─ AtomicReference    — lock-free damage coalescing                       ║
 * ║  ├─ LongAdder          — high-throughput statistics counters               ║
 * ║  └─ volatile           — config flags, debug toggle                        ║
 * ║                                                                            ║
 * ║  SAFETY INVARIANTS                                                         ║
 * ║  1. NMS operations execute ONLY on the entity's owning region thread       ║
 * ║  2. Cross-thread data flows through concurrent collections ONLY            ║
 * ║  3. Zero synchronized blocks — eliminates deadlock risk on Folia           ║
 * ║  4. Entity Scheduler used for all player-scoped delayed tasks              ║
 * ║  5. Global Scheduler for periodic non-entity maintenance                   ║
 * ║  6. No Bukkit Scheduler (BukkitRunnable) for critical combat logic         ║
 * ║                                                                            ║
 * ║  RACE CONDITION MITIGATIONS                                                ║
 * ║  • Fast-path reads NMS state atomically on the event's region thread       ║
 * ║  • Lethal intercept uses CAS (compareAndSet) on player combat state        ║
 * ║  • Damage coalescing merges via AtomicReference CAS loop                   ║
 * ║  • Reconciliation re-reads NMS on the same region thread that owns         ║
 * ║    the player — no cross-region NMS access ever occurs                     ║
 * ║  • Bypass set uses ConcurrentHashMap.newKeySet() for lock-free add/remove  ║
 * ║                                                                            ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 */

/**
 * MasterAntiGhost — Extreme anti-totem-ghost plugin for crystal PvP.
 * <p>
 * Eliminates near-instant totem ghosting by intercepting lethal damage at the NMS level,
 * performing fast-path totem checks, and reconciling uncertain states within a configurable
 * tick window. Fully Folia-safe with region-thread-aware scheduling.
 *
 * @author OPmasterLEO
 */
public final class MasterAntiGhost extends JavaPlugin {

    // ── Statistics (thread-safe, lock-free) ─────────────────────────────────────
    // LongAdder chosen over AtomicLong for high write contention: each thread writes
    // to its own cell, reducing cache-line bouncing under heavy crystal PvP load.
    private final LongAdder fastPathPops = new LongAdder();
    private final LongAdder reconciledPops = new LongAdder();
    private final LongAdder reconciledDeaths = new LongAdder();
    private final LongAdder interceptedHits = new LongAdder();

    // ── Core Components ─────────────────────────────────────────────────────────
    private PluginConfig pluginConfig;
    private NmsAccessor nmsAccessor;
    private SwapBuffer swapBuffer;
    private CombatManager combatManager;
    private ManualResurrection manualResurrection;
    private FoliaScheduler foliaScheduler;

    @Override
    public void onEnable() {
        // ── 1. Configuration ────────────────────────────────────────────────────
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        DebugLogger.init(getLogger(), pluginConfig.isDebugMode());

        DebugLogger.info("Initializing MasterAntiGhost v" + getDescription().getVersion());
        DebugLogger.info("Folia detected: " + FoliaScheduler.isFolia());

        // ── 2. NMS Layer ────────────────────────────────────────────────────────
        this.nmsAccessor = createNmsAccessor();
        if (!nmsAccessor.isAvailable()) {
            getLogger().severe("NMS accessor failed to initialize! Plugin will not function.");
            getLogger().severe("Ensure you are running Paper/Folia 1.20.4+");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        DebugLogger.info("NMS accessor initialized successfully for version: " + nmsAccessor.getVersionTag());

        // ── 3. Scheduler ────────────────────────────────────────────────────────
        this.foliaScheduler = new FoliaScheduler(this);

        // ── 4. Swap Buffer ──────────────────────────────────────────────────────
        this.swapBuffer = new SwapBuffer(pluginConfig.getSwapBufferTicks());

        // ── 5. Manual Resurrection ──────────────────────────────────────────────
        this.manualResurrection = new ManualResurrection(nmsAccessor);

        // ── 6. Combat Manager (orchestrates A/B/C/D) ────────────────────────────
        this.combatManager = new CombatManager(
                this,
                pluginConfig,
                nmsAccessor,
                swapBuffer,
                manualResurrection,
                foliaScheduler,
                fastPathPops,
                reconciledPops,
                reconciledDeaths,
                interceptedHits
        );

        // ── 7. Register Event Listeners ─────────────────────────────────────────
        Bukkit.getPluginManager().registerEvents(new DamageListener(combatManager, pluginConfig), this);
        Bukkit.getPluginManager().registerEvents(new SwapListener(swapBuffer, nmsAccessor), this);

        // ── 8. Register Command ─────────────────────────────────────────────────
        var command = getCommand("masterantighost");
        if (command != null) {
            var cmdListener = new CommandListener(this, pluginConfig, fastPathPops, reconciledPops, reconciledDeaths, interceptedHits);
            command.setExecutor(cmdListener);
            command.setTabCompleter(cmdListener);
        }

        // ── 9. Schedule Global Cleanup ──────────────────────────────────────────
        // SwapBuffer cleanup runs on Global Scheduler — no NMS access, just map pruning.
        // Interval: every 100 ticks (5 seconds). Safe to run globally as it only touches
        // ConcurrentHashMap entries keyed by UUID.
        foliaScheduler.runOnGlobalTimer(() -> {
            swapBuffer.cleanupExpired(nmsAccessor.getCurrentTick());
            combatManager.cleanupStaleEntries();
        }, 100L, 100L);

        DebugLogger.info("MasterAntiGhost enabled — zero-ghost crystal PvP active.");
    }

    @Override
    public void onDisable() {
        // Cleanup: clear all pending states. Players in PENDING_LETHAL will have
        // their damage cancelled — acceptable on shutdown since server is stopping.
        if (combatManager != null) {
            combatManager.shutdown();
        }
        DebugLogger.info("MasterAntiGhost disabled. Stats — Fast pops: " + fastPathPops.sum()
                + ", Reconciled pops: " + reconciledPops.sum()
                + ", Reconciled deaths: " + reconciledDeaths.sum()
                + ", Intercepted: " + interceptedHits.sum());
    }

    /**
     * Reload configuration at runtime. Safe to call from any thread
     * because PluginConfig fields are volatile or immutable after construction.
     */
    public void reloadPluginConfig() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        DebugLogger.setEnabled(pluginConfig.isDebugMode());
        swapBuffer.setWindowTicks(pluginConfig.getSwapBufferTicks());
        DebugLogger.info("Configuration reloaded.");
    }

    // ── NMS Accessor Factory ────────────────────────────────────────────────────

    /**
     * Creates the appropriate NMS accessor for the running server version.
     * Currently supports 1.20.4 (v1_20_R3). Additional versions can be added
     * by implementing {@link NmsAccessor} and adding a version check here.
     *
     * <p>Version detection uses the Bukkit.getMinecraftVersion() API which returns
     * the exact game version string (e.g., "1.20.4").</p>
     */
    private NmsAccessor createNmsAccessor() {
        String version = Bukkit.getMinecraftVersion();
        DebugLogger.info("Detected Minecraft version: " + version);

        // Version check: 1.20.4 uses NMS package v1_20_R3
        // For future versions, add additional cases here.
        // The abstraction layer (NmsAccessor interface) ensures each version
        // only needs to implement the defined contract.
        return switch (version) {
            case "1.20.4" -> new NmsAccessorImpl_v1_20_R3();
            // case "1.20.6", "1.21" -> new NmsAccessorImpl_v1_21_R1(); // Future
            default -> {
                getLogger().warning("Untested Minecraft version: " + version
                        + ". Attempting 1.20.4 NMS accessor (may fail).");
                yield new NmsAccessorImpl_v1_20_R3();
            }
        };
    }

    // ── Accessors ───────────────────────────────────────────────────────────────

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }
}
