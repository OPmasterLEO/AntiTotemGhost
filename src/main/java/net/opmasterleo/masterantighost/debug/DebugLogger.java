package net.opmasterleo.masterantighost.debug;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized debug logging utility with runtime toggle.
 *
 * <p><b>Thread Safety:</b> The {@code enabled} flag is volatile, ensuring
 * visibility across all threads (region, global, async) without synchronization.
 * Logger itself is thread-safe per java.util.logging contract.</p>
 *
 * <p>All debug output is prefixed with {@code [MAG-DEBUG]} for easy filtering
 * in server logs. Component tags allow filtering by subsystem.</p>
 */
public final class DebugLogger {

    private static final String PREFIX = "[MAG] ";
    private static final String DEBUG_PREFIX = "[MAG-DEBUG] ";

    // volatile: toggled at runtime via config reload or command.
    // No lock needed — eventual consistency is acceptable for debug logging.
    private static volatile boolean enabled = false;

    private static Logger logger;

    private DebugLogger() {
        // Utility class
    }

    /**
     * Initialize with the plugin's logger and initial debug state.
     */
    public static void init(Logger pluginLogger, boolean debugEnabled) {
        logger = pluginLogger;
        enabled = debugEnabled;
    }

    /**
     * Toggle debug mode at runtime. Volatile write propagates to all threads.
     */
    public static void setEnabled(boolean debugEnabled) {
        enabled = debugEnabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ── Logging Methods ─────────────────────────────────────────────────────────

    /**
     * Log a debug message. Only outputs when debug mode is enabled.
     * Use for high-frequency events (every damage intercept, swap buffer update, etc.)
     *
     * @param component subsystem tag (e.g., "CombatManager", "SwapBuffer")
     * @param message   the debug message
     */
    public static void debug(String component, String message) {
        // volatile read — fast path when disabled (no method call overhead on Logger)
        if (!enabled) return;
        if (logger != null) {
            logger.info(DEBUG_PREFIX + "[" + component + "] " + message);
        }
    }

    /**
     * Log a debug message with format arguments. Only outputs when debug mode is enabled.
     */
    public static void debug(String component, String format, Object... args) {
        if (!enabled) return;
        if (logger != null) {
            logger.info(DEBUG_PREFIX + "[" + component + "] " + String.format(format, args));
        }
    }

    /**
     * Log an informational message. Always outputs regardless of debug mode.
     */
    public static void info(String message) {
        if (logger != null) {
            logger.info(PREFIX + message);
        }
    }

    /**
     * Log a warning. Always outputs regardless of debug mode.
     */
    public static void warn(String message) {
        if (logger != null) {
            logger.warning(PREFIX + message);
        }
    }

    /**
     * Log a severe error. Always outputs regardless of debug mode.
     */
    public static void severe(String message) {
        if (logger != null) {
            logger.severe(PREFIX + message);
        }
    }

    /**
     * Log a severe error with exception. Always outputs regardless of debug mode.
     */
    public static void severe(String message, Throwable throwable) {
        if (logger != null) {
            logger.log(Level.SEVERE, PREFIX + message, throwable);
        }
    }
}
