package net.opmasterleo.AntiTotemGhost.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class PluginConfig {

    private final int reconciliationTicks;
    private final int swapBufferTicks;
    private final boolean enableFastPath;
    private final boolean debugMode;
    private final boolean sandboxMode;

    private static final int DEFAULT_RECONCILIATION_TICKS = 1;
    private static final int DEFAULT_SWAP_BUFFER_TICKS = 2;
    private static final boolean DEFAULT_ENABLE_FAST_PATH = true;
    private static final boolean DEFAULT_DEBUG_MODE = false;
    private static final boolean DEFAULT_SANDBOX_MODE = false;

    public PluginConfig(FileConfiguration config) {
        this.reconciliationTicks = clamp(
                config.getInt("reconciliation-ticks", DEFAULT_RECONCILIATION_TICKS),
            0, 10, "reconciliation-ticks"
        );

        this.swapBufferTicks = clamp(
                config.getInt("swap-buffer-ticks", DEFAULT_SWAP_BUFFER_TICKS),
                1, 20, "swap-buffer-ticks"
        );

        this.enableFastPath = config.getBoolean("enable-fast-path", DEFAULT_ENABLE_FAST_PATH);
        this.debugMode = config.getBoolean("debug-mode", DEFAULT_DEBUG_MODE);
        this.sandboxMode = config.getBoolean("sandbox-mode", DEFAULT_SANDBOX_MODE);
    }

    public int getReconciliationTicks() {
        return reconciliationTicks;
    }

    public int getSwapBufferTicks() {
        return swapBufferTicks;
    }

    public boolean isEnableFastPath() {
        return enableFastPath;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public boolean isSandboxMode() {
        return sandboxMode;
    }

    private static int clamp(int value, int min, int max, String key) {
        if (value < min || value > max) {
            System.out.println("[AntiTotemGhost] Config value '" + key + "' = " + value
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
