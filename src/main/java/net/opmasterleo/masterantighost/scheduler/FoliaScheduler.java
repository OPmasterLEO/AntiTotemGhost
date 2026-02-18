package net.opmasterleo.masterantighost.scheduler;

import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

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
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            entity.getScheduler().run(plugin, scheduledTask -> safeTask.run(), () -> {
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, safeTask);
        }
    }

    public void runOnEntityThreadDelayed(Entity entity, Runnable task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, scheduledTask -> safeTask.run(), () -> {
            }, delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, safeTask, delay);
        }
    }

    public void runOnGlobalTimer(Runnable task, long initialDelay, long periodTicks) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduledTask -> safeTask.run(), Math.max(0L, initialDelay), Math.max(1L, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, safeTask, Math.max(0L, initialDelay), Math.max(1L, periodTicks));
        }
    }

    public void runOnAsync(Runnable task) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> safeTask.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, safeTask);
        }
    }

    public void runOnAsyncDelayed(Runnable task, long delayTicks) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            long delayMillis = ticksToMillis(delayTicks);
            Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> safeTask.run(), delayMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, safeTask, Math.max(0L, delayTicks));
        }
    }

    public ScheduledHandle scheduleGlobalTimer(Runnable task, long initialDelay, long periodTicks) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    tickTask -> safeTask.run(), Math.max(0L, initialDelay), Math.max(1L, periodTicks));
            return scheduled::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, safeTask, Math.max(0L, initialDelay), Math.max(1L, periodTicks));
        return bukkitTask::cancel;
    }

    public ScheduledHandle scheduleAsyncTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            long initialDelayMillis = ticksToMillis(initialDelayTicks);
            long periodMillis = ticksToMillis(periodTicks);
            var scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    asyncTask -> safeTask.run(), initialDelayMillis, periodMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, safeTask,
                Math.max(0L, initialDelayTicks), Math.max(1L, periodTicks));
        return bukkitTask::cancel;
    }

    public void runOnGlobal(Runnable task) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> safeTask.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, safeTask);
        }
    }

    public ScheduledHandle scheduleEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        Runnable safeTask = wrap(task);
        if (FOLIA) {
            var scheduled = entity.getScheduler().runAtFixedRate(plugin,
                    scheduledTask -> safeTask.run(), () -> {
                    }, Math.max(0L, initialDelayTicks), Math.max(1L, periodTicks));
            return scheduled::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, safeTask,
                Math.max(0L, initialDelayTicks), Math.max(1L, periodTicks));
        return bukkitTask::cancel;
    }

    public void runOnEntityThreadIfOnline(java.util.UUID playerId, Consumer<org.bukkit.entity.Player> task) {
        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        runOnEntityThread(player, () -> task.accept(player));
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

    private Runnable wrap(Runnable delegate) {
        return () -> {
            try {
                delegate.run();
            } catch (Throwable throwable) {
                plugin.getLogger().severe("Scheduled task failed: " + throwable.getMessage());
            }
        };
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
