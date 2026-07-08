package net.opmasterleo.masterantighost.runtime;

import java.util.Objects;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.CombatEngine;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.listener.CommandListener;
import net.opmasterleo.masterantighost.listener.DamageListener;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.nms.PacketSwapInjector;
import net.opmasterleo.masterantighost.runtime.scheduler.SchedulerHub;
import net.opmasterleo.masterantighost.runtime.task.TaskSupervisor;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import net.opmasterleo.masterantighost.version.CapabilityReport;
import net.opmasterleo.masterantighost.version.VersionBridge;
import net.opmasterleo.masterantighost.version.VersionBridgeFactory;

public final class ServiceRegistry {

    private final Plugin plugin;
    private final SchedulerHub schedulerHub;
    private final TaskSupervisor taskSupervisor;

    private PluginConfig pluginConfig;
    private VersionBridge versionBridge;
    private NmsAccessor nmsAccessor;
    private FoliaScheduler foliaScheduler;
    private SwapBuffer swapBuffer;
    private ManualResurrection manualResurrection;
    private CombatEngine combatEngine;
    private PacketSwapInjector packetSwapInjector;
    private DamageListener damageListener;
    private SchedulerHub.SchedulerHandle maintenanceHandle;

    private java.util.concurrent.atomic.LongAdder fastPathPops;
    private java.util.concurrent.atomic.LongAdder reconciledPops;
    private java.util.concurrent.atomic.LongAdder reconciledDeaths;
    private java.util.concurrent.atomic.LongAdder interceptedHits;

    public ServiceRegistry(Plugin plugin, SchedulerHub schedulerHub, TaskSupervisor taskSupervisor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.schedulerHub = Objects.requireNonNull(schedulerHub, "schedulerHub");
        this.taskSupervisor = Objects.requireNonNull(taskSupervisor, "taskSupervisor");
    }

    public boolean probe() {
        this.plugin.saveDefaultConfig();
        this.pluginConfig = new PluginConfig(plugin.getConfig());
        DebugLogger.init(plugin.getLogger(), pluginConfig.isDebugMode());
        this.versionBridge = VersionBridgeFactory.create();
        this.nmsAccessor = versionBridge.nmsAccessor();
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
        this.fastPathPops = new java.util.concurrent.atomic.LongAdder();
        this.reconciledPops = new java.util.concurrent.atomic.LongAdder();
        this.reconciledDeaths = new java.util.concurrent.atomic.LongAdder();
        this.interceptedHits = new java.util.concurrent.atomic.LongAdder();
        this.combatEngine = new CombatEngine(
                () -> pluginConfig,
                nmsAccessor,
                swapBuffer,
                manualResurrection,
                schedulerHub,
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
                versionBridge.packetSchemaResolver(),
                id -> combatEngine.onPlayerQuit(id)
        );
        this.damageListener = new DamageListener(combatEngine);
        org.bukkit.Bukkit.getPluginManager().registerEvents(damageListener, plugin);
        packetSwapInjector.start();

        CapabilityReport report = versionBridge.capabilityReport();
        var command = ((org.bukkit.plugin.java.JavaPlugin) plugin).getCommand("masterantighost");
        if (command != null) {
            var cmdListener = new CommandListener((net.opmasterleo.masterantighost.MasterAntiGhost) plugin,
                    fastPathPops, reconciledPops, reconciledDeaths, interceptedHits,
                    report);
            command.setExecutor(cmdListener);
            command.setTabCompleter(cmdListener);
        }

        this.maintenanceHandle = schedulerHub.scheduleRepeatingMain(() -> {
            swapBuffer.cleanupExpired(nmsAccessor.getCurrentTick());
            combatEngine.cleanupStaleEntries();
        }, 100L, 100L);
    }

    public void beginDrain() {
    }

    public void stop() {
        if (damageListener != null) {
            HandlerList.unregisterAll(damageListener);
            damageListener = null;
        }
        if (maintenanceHandle != null) {
            maintenanceHandle.cancel();
            maintenanceHandle = null;
        }
        if (combatEngine != null) {
            combatEngine.shutdown();
            combatEngine = null;
        }
        if (packetSwapInjector != null) {
            packetSwapInjector.shutdown();
            packetSwapInjector = null;
        }
    }

    public CombatEngine combatEngine() {
        return combatEngine;
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

    public CapabilityReport capabilityReport() {
        return versionBridge == null ? null : versionBridge.capabilityReport();
    }
}

