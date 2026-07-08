package net.opmasterleo.masterantighost.runtime;

import java.util.Objects;

import org.bukkit.plugin.Plugin;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.listener.DamageListener;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.nms.NmsAccessorDirect;
import net.opmasterleo.masterantighost.nms.PacketSwapInjector;
import net.opmasterleo.masterantighost.runtime.scheduler.SchedulerHub;
import net.opmasterleo.masterantighost.runtime.task.TaskSupervisor;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;

public final class ServiceRegistry {

    private final Plugin plugin;
    private final SchedulerHub schedulerHub;
    private final TaskSupervisor taskSupervisor;

    private PluginConfig pluginConfig;
    private NmsAccessor nmsAccessor;
    private FoliaScheduler foliaScheduler;
    private SwapBuffer swapBuffer;
    private ManualResurrection manualResurrection;
    private CombatManager combatManager;
    private PacketSwapInjector packetSwapInjector;

    public ServiceRegistry(Plugin plugin, SchedulerHub schedulerHub, TaskSupervisor taskSupervisor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.schedulerHub = Objects.requireNonNull(schedulerHub, "schedulerHub");
        this.taskSupervisor = Objects.requireNonNull(taskSupervisor, "taskSupervisor");
    }

    public boolean probe() {
        this.plugin.saveDefaultConfig();
        this.pluginConfig = new PluginConfig(plugin.getConfig());
        DebugLogger.init(plugin.getLogger(), pluginConfig.isDebugMode());
        this.nmsAccessor = new NmsAccessorDirect(org.bukkit.Bukkit.getMinecraftVersion());
        if (!nmsAccessor.isAvailable()) {
            plugin.getLogger().severe("NMS accessor not available, version: " + org.bukkit.Bukkit.getMinecraftVersion());
            return false;
        }
        return true;
    }

    public void start() {
        this.foliaScheduler = new FoliaScheduler(plugin);
        this.swapBuffer = new SwapBuffer(pluginConfig.getSwapBufferTicks());
        this.manualResurrection = new ManualResurrection(nmsAccessor);
        java.util.concurrent.atomic.LongAdder fastPathPops = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder reconciledPops = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder reconciledDeaths = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder interceptedHits = new java.util.concurrent.atomic.LongAdder();
        this.combatManager = new CombatManager(
                () -> pluginConfig,
                nmsAccessor,
                swapBuffer,
                manualResurrection,
                foliaScheduler,
                fastPathPops,
                reconciledPops,
                reconciledDeaths,
                interceptedHits
        );
        this.packetSwapInjector = new PacketSwapInjector(
                plugin,
                swapBuffer,
                nmsAccessor,
                foliaScheduler,
                id -> combatManager.onPlayerQuit(id)
        );
        org.bukkit.Bukkit.getPluginManager().registerEvents(new DamageListener(combatManager), plugin);
        packetSwapInjector.start();
    }

    public void beginDrain() {
    }

    public void stop() {
        if (combatManager != null) {
            combatManager.shutdown();
        }
        if (packetSwapInjector != null) {
            packetSwapInjector.shutdown();
        }
    }

    public CombatManager combatManager() {
        return combatManager;
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public NmsAccessor nmsAccessor() {
        return nmsAccessor;
    }

    public SchedulerHub schedulerHub() {
        return schedulerHub;
    }

    public TaskSupervisor taskSupervisor() {
        return taskSupervisor;
    }

    public FoliaScheduler foliaScheduler() {
        return foliaScheduler;
    }

    public SwapBuffer swapBuffer() {
        return swapBuffer;
    }
}

