package net.opmasterleo.masterantighost.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed configuration loader for MasterAntiGhost.
 * <p>
 * All fields are either effectively immutable (set once in constructor) or volatile
 * for safe cross-thread reads. A new PluginConfig instance is created on reload,
 * and the reference is swapped atomically in the plugin main class.
 *
 * <p><b>Thread Safety:</b> Instances are effectively immutable after construction.
 * The plugin holds a volatile reference to the current PluginConfig, allowing
 * lock-free reads from any thread (region, global, or async).</p>
 */
public final class PluginConfig {

    // ── Fields ──────────────────────────────────────────────────────────────────
    // No synchronization needed: this object is immutable after construction.
    // A new instance is created on reload and the reference is swapped atomically.

    private final int reconciliationTicks;
    private final int swapBufferTicks;
    private final boolean enableFastPath;
    private final boolean debugMode;
    private final boolean sandboxMode;

    // ── Defaults ────────────────────────────────────────────────────────────────

    /** Default reconciliation window: 1 tick. Balances latency vs. reliability. */
    private static final int DEFAULT_RECONCILIATION_TICKS = 1;

    /** Default swap buffer window: 2 ticks. Covers worst-case client→server timing. */
    private static final int DEFAULT_SWAP_BUFFER_TICKS = 2;

    private static final boolean DEFAULT_ENABLE_FAST_PATH = true;
    private static final boolean DEFAULT_DEBUG_MODE = false;
    private static final boolean DEFAULT_SANDBOX_MODE = false;

    // ── Constructor ─────────────────────────────────────────────────────────────

    /**
     * Loads configuration from the given FileConfiguration with validated defaults.
     *
     * @param config the Bukkit FileConfiguration from plugin's config.yml
     */
    public PluginConfig(FileConfiguration config) {
        this.reconciliationTicks = clamp(
                config.getInt("reconciliation-ticks", DEFAULT_RECONCILIATION_TICKS),
                1, 10, "reconciliation-ticks"
        );

        this.swapBufferTicks = clamp(
                config.getInt("swap-buffer-ticks", DEFAULT_SWAP_BUFFER_TICKS),
                1, 20, "swap-buffer-ticks"
        );

        this.enableFastPath = config.getBoolean("enable-fast-path", DEFAULT_ENABLE_FAST_PATH);
        this.debugMode = config.getBoolean("debug-mode", DEFAULT_DEBUG_MODE);
        this.sandboxMode = config.getBoolean("sandbox-mode", DEFAULT_SANDBOX_MODE);
    }

    // ── Getters ─────────────────────────────────────────────────────────────────

    /**
     * Number of ticks to wait before reconciliation makes its final pop-or-die decision.
     * Range: [1, 10]. Default: 1.
     */
    public int getReconciliationTicks() {
        return reconciliationTicks;
    }

    /**
     * Number of ticks to retain swap buffer entries.
     * Any totem swap within this window is considered valid for reconciliation.
     * Range: [1, 20]. Default: 2.
     */
    public int getSwapBufferTicks() {
        return swapBufferTicks;
    }

    /**
     * Whether fast-path (immediate same-tick) totem check is enabled.
     * When false, all lethal damage goes through reconciliation pipeline.
     */
    public boolean isEnableFastPath() {
        return enableFastPath;
    }

    /** Whether debug logging is enabled. */
    public boolean isDebugMode() {
        return debugMode;
    }

    /** Whether sandbox stress-testing mode is enabled. */
    public boolean isSandboxMode() {
        return sandboxMode;
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    /**
     * Clamps a config value to [min, max], logging a warning if out of range.
     */
    private static int clamp(int value, int min, int max, String key) {
        if (value < min || value > max) {
            System.out.println("[MasterAntiGhost] Config value '" + key + "' = " + value
                    + " out of range [" + min + ", " + max + "]. Clamping.");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    @Override
    public String toString() {
        return "PluginConfig{"
                + "reconciliationTicks=" + reconciliationTicks
                + ", swapBufferTicks=" + swapBufferTicks
                + ", enableFastPath=" + enableFastPath
                + ", debugMode=" + debugMode
                + ", sandboxMode=" + sandboxMode
                + '}';
    }
}
