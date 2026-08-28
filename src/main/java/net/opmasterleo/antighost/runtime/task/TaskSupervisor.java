package net.opmasterleo.AntiTotemGhost.runtime.task;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.opmasterleo.AntiTotemGhost.runtime.scheduler.SchedulerHub;

public final class TaskSupervisor {

    private final Plugin plugin;
    private final SchedulerHub schedulerHub;
    private final Map<String, TrackedTask> tasks = new ConcurrentHashMap<>();

    public TaskSupervisor(Plugin plugin, SchedulerHub schedulerHub) {
        this.plugin = plugin;
        this.schedulerHub = schedulerHub;
    }

    public TaskTicket submit(String name, Runnable task) {
        String id = name + "-" + java.util.UUID.randomUUID();
        TrackedTask tracked = new TrackedTask(id, name, System.nanoTime());
        tasks.put(id, tracked);
        schedulerHub.runAsync(() -> runTracked(tracked, task));
        return () -> cancel(id);
    }

    public TaskTicket submitForPlayer(UUID playerId, String name, java.util.function.Consumer<Player> action) {
        String id = name + "-" + playerId + "-" + java.util.UUID.randomUUID();
        TrackedTask tracked = new TrackedTask(id, name, System.nanoTime());
        tasks.put(id, tracked);
        schedulerHub.runAsync(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                tasks.remove(id);
                return;
            }
            runTracked(tracked, () -> action.accept(player));
        });
        return () -> cancel(id);
    }

    public void cancel(String id) {
        tasks.remove(id);
    }

    public int activeTasks() {
        return tasks.size();
    }

    public void shutdown(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!tasks.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        tasks.clear();
    }

    private void runTracked(TrackedTask tracked, Runnable delegate) {
        try {
            delegate.run();
        } catch (Throwable throwable) {
            plugin.getLogger().severe("Task " + tracked.name + " failed: " + throwable.getMessage());
        } finally {
            tasks.remove(tracked.id);
        }
    }

    private record TrackedTask(String id, String name, long createdNanos) {
    }
}

