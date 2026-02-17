package net.opmasterleo.masterantighost.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaScheduler {

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

    public static boolean isFolia() {
        return FOLIA;
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

    public void runOnGlobal(Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
