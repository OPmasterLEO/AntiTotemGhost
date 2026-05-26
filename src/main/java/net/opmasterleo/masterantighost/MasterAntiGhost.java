package net.opmasterleo.masterantighost;

import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.listener.CommandListener;
import net.opmasterleo.masterantighost.listener.DamageListener;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.nms.NmsAccessorDirect;
import net.opmasterleo.masterantighost.nms.PacketSwapInjector;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
public final class MasterAntiGhost extends JavaPlugin {

    private final LongAdder fastPathPops = new LongAdder();
    private final LongAdder reconciledPops = new LongAdder();
    private final LongAdder reconciledDeaths = new LongAdder();
    private final LongAdder interceptedHits = new LongAdder();

    private PluginConfig pluginConfig;
    private NmsAccessor nmsAccessor;
    private SwapBuffer swapBuffer;
    private CombatManager combatManager;
    private ManualResurrection manualResurrection;
    private FoliaScheduler foliaScheduler;
    private PacketSwapInjector packetSwapInjector;
    private FoliaScheduler.ScheduledHandle maintenanceHandle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        DebugLogger.init(getLogger(), pluginConfig.isDebugMode());
        DebugLogger.info("Initializing MasterAntiGhost v" + getDescription().getVersion());
        DebugLogger.info("Server: " + Bukkit.getName() + " | Bukkit: " + Bukkit.getVersion());
        DebugLogger.info("Folia detected: " + FoliaScheduler.isFolia());

        this.nmsAccessor = createNmsAccessor();
        if (!nmsAccessor.isAvailable()) {
            getLogger().severe("NMS accessor failed to initialize! Plugin will not function.");
            getLogger().severe("Server internals were not compatible with dynamic NMS probe for version: "
                    + Bukkit.getMinecraftVersion());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        DebugLogger.info("NMS accessor initialized successfully for version: " + nmsAccessor.getVersionTag());

        this.foliaScheduler = new FoliaScheduler(this);
        DebugLogger.info("Scheduler backend: " + foliaScheduler.getBackend());
        this.swapBuffer = new SwapBuffer(pluginConfig.getSwapBufferTicks());
        this.manualResurrection = new ManualResurrection(nmsAccessor);

        this.combatManager = new CombatManager(
                this::getPluginConfig,
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
                this,
                swapBuffer,
                nmsAccessor,
                foliaScheduler,
                id -> combatManager.onPlayerQuit(id)
            );

        Bukkit.getPluginManager().registerEvents(new DamageListener(combatManager), this);
        packetSwapInjector.start();

        var command = getCommand("masterantighost");
        if (command != null) {
            var cmdListener = new CommandListener(this, fastPathPops, reconciledPops, reconciledDeaths, interceptedHits);
            command.setExecutor(cmdListener);
            command.setTabCompleter(cmdListener);
        }

        maintenanceHandle = foliaScheduler.scheduleGlobalTimer(() -> {
            swapBuffer.cleanupExpired(nmsAccessor.getCurrentTick());
            combatManager.cleanupStaleEntries();
        }, 100L, 100L);

        DebugLogger.info("MasterAntiGhost enabled — zero-ghost crystal PvP active.");
    }

    @Override
    public void onDisable() {
        if (combatManager != null) {
            combatManager.shutdown();
        }
        if (packetSwapInjector != null) {
            packetSwapInjector.shutdown();
        }
        if (maintenanceHandle != null) {
            maintenanceHandle.cancel();
            maintenanceHandle = null;
        }
        DebugLogger.info("MasterAntiGhost disabled. Stats — Fast pops: " + fastPathPops.sum()
                + ", Reconciled pops: " + reconciledPops.sum()
                + ", Reconciled deaths: " + reconciledDeaths.sum()
                + ", Intercepted: " + interceptedHits.sum());
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        DebugLogger.setEnabled(pluginConfig.isDebugMode());
        swapBuffer.setWindowTicks(pluginConfig.getSwapBufferTicks());
        DebugLogger.info("Configuration reloaded.");
    }

    private NmsAccessor createNmsAccessor() {
        String version = Bukkit.getMinecraftVersion();
        DebugLogger.info("Detected Minecraft version: " + version);
        DebugLogger.info("Target compatibility profile: 1.21.0 - 1.21.6");
        return new NmsAccessorDirect(version);
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }
}
