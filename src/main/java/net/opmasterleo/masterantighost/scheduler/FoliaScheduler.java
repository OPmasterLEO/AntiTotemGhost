package net.opmasterleo.masterantighost.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;

public final class FoliaScheduler {

    private static final boolean FOLIA;
    private static final SchedulerBackend BACKEND;

    static {
        boolean hasFolia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            hasFolia = true;
        } catch (ClassNotFoundException e) {
            hasFolia = false;
        }
        FOLIA = hasFolia;
        BACKEND = detectBackend(hasFolia);
    }

    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public SchedulerBackend getBackend() {
        return BACKEND;
    }

    public void runOnEntityThread(Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runOnEntityThreadDelayed(Entity entity, Runnable task, long delayTicks) {
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public void runOnGlobalTimer(Runnable task, long initialDelay, long periodTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduledTask -> task.run(), initialDelay, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, periodTicks);
        }
    }

    public void runOnAsync(Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runOnAsyncDelayed(Runnable task, long delayTicks) {
        if (FOLIA) {
            long delayMillis = ticksToMillis(delayTicks);
            Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    public ScheduledHandle scheduleGlobalTimer(Runnable task, long initialDelay, long periodTicks) {
        if (FOLIA) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    tickTask -> task.run(), initialDelay, periodTicks);
            return scheduled::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, periodTicks);
        return bukkitTask::cancel;
    }

    public ScheduledHandle scheduleAsyncTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        if (FOLIA) {
            long initialDelayMillis = ticksToMillis(initialDelayTicks);
            long periodMillis = ticksToMillis(periodTicks);
            var scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    asyncTask -> task.run(), initialDelayMillis, periodMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks);
        return bukkitTask::cancel;
    }

    public void runOnGlobal(Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static SchedulerBackend detectBackend(boolean folia) {
        if (folia) {
            return SchedulerBackend.FOLIA;
        }
        String name = Bukkit.getName().toLowerCase(Locale.ROOT);
        String version = Bukkit.getVersion().toLowerCase(Locale.ROOT);
        String fingerprint = name + " " + version;
        if (fingerprint.contains("paper") || fingerprint.contains("purpur") || fingerprint.contains("pufferfish")) {
            return SchedulerBackend.PAPER;
        }
        if (fingerprint.contains("arclight")) {
            return SchedulerBackend.ARCLIGHT;
        }
        if (fingerprint.contains("mohist")) {
            return SchedulerBackend.MOHIST;
        }
        if (fingerprint.contains("catserver") || fingerprint.contains("magma")) {
            return SchedulerBackend.HYBRID;
        }
        return SchedulerBackend.BUKKIT;
    }

    private static long ticksToMillis(long ticks) {
        return Math.max(0L, ticks) * 50L;
    }

    public enum SchedulerBackend {
        FOLIA,
        PAPER,
        ARCLIGHT,
        MOHIST,
        HYBRID,
        BUKKIT
    }

    @FunctionalInterface
    public interface ScheduledHandle {
        void cancel();
    }
}
