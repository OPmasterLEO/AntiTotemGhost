package net.opmasterleo.AntiTotemGhost.debug;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class DebugLogger {

    private static final String PREFIX = "[MAG] ";
    private static final String DEBUG_PREFIX = "[MAG-DEBUG] ";

    private static volatile boolean enabled = false;

    private static Logger logger;

    private DebugLogger() {
    }

    public static void init(Logger pluginLogger, boolean debugEnabled) {
        logger = pluginLogger;
        enabled = debugEnabled;
    }

    public static void setEnabled(boolean debugEnabled) {
        enabled = debugEnabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void debug(String component, String message) {
        if (!enabled) return;
        if (logger != null) {
            logger.info(DEBUG_PREFIX + "[" + component + "] " + message);
        }
    }

    public static void debug(String component, String format, Object... args) {
        if (!enabled) return;
        if (logger != null) {
            logger.info(DEBUG_PREFIX + "[" + component + "] " + String.format(format, args));
        }
    }

    public static void info(String message) {
        if (logger != null) {
            logger.info(PREFIX + message);
        }
    }

    public static void warn(String message) {
        if (logger != null) {
            logger.warning(PREFIX + message);
        }
    }

    public static void severe(String message) {
        if (logger != null) {
            logger.severe(PREFIX + message);
        }
    }

    public static void severe(String message, Throwable throwable) {
        if (logger != null) {
            logger.log(Level.SEVERE, PREFIX + message, throwable);
        }
    }
}
