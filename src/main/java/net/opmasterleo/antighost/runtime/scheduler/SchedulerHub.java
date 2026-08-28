package net.opmasterleo.AntiTotemGhost.runtime.scheduler;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class SchedulerHub {

    private final Plugin plugin;
    private final ExecutorService workerPool;
    private static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
    }

    public SchedulerHub(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.workerPool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                new NamedThreadFactory("mag-worker-")
        );
    }

    public void runMain(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, safe(task));
    }

    public void runMainLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, safe(task), Math.max(0L, delayTicks));
    }

    public void runAsync(Runnable task) {
        workerPool.execute(safe(task));
    }

    public void runAsyncLater(Runnable task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> workerPool.execute(safe(task)), delay);
    }

    public void runEntity(Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, scheduledTask -> safe(task).run(), () -> {
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, safe(task));
        }
    }

    public void runEntityLater(Entity entity, Runnable task, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, scheduledTask -> safe(task).run(), () -> {
            }, delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, safe(task), delay);
        }
    }

    public SchedulerHandle scheduleRepeatingMain(Runnable task, long initialDelayTicks, long periodTicks) {
        long initial = Math.max(0L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduledTask -> safe(task).run(), initial, period);
            return scheduled::cancel;
        }
        var bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, safe(task), initial, period);
        return bukkitTask::cancel;
    }

    public void shutdown() {
        workerPool.shutdownNow();
    }

    private Runnable safe(Runnable delegate) {
        return () -> {
            try {
                delegate.run();
            } catch (Throwable throwable) {
                plugin.getLogger().severe("Scheduler task failed: " + throwable.getMessage());
            }
        };
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    public interface SchedulerHandle {
        void cancel();
    }
}

