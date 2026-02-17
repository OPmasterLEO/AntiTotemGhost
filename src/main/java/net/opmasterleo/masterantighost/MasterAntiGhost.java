package net.opmasterleo.masterantighost;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;
import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.combat.ManualResurrection;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.listener.CommandListener;
import net.opmasterleo.masterantighost.listener.DamageListener;
import net.opmasterleo.masterantighost.listener.SwapListener;
import net.opmasterleo.masterantighost.nms.NmsAccessor;
import net.opmasterleo.masterantighost.nms.NmsAccessorUniversal;
import net.opmasterleo.masterantighost.scheduler.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.LongAdder;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        DebugLogger.init(getLogger(), pluginConfig.isDebugMode());
        DebugLogger.info("Initializing MasterAntiGhost v" + getDescription().getVersion());
        DebugLogger.info("Folia detected: " + FoliaScheduler.isFolia());

        this.nmsAccessor = createNmsAccessor();
        if (!nmsAccessor.isAvailable()) {
            getLogger().severe("NMS accessor failed to initialize! Plugin will not function.");
            getLogger().severe("Ensure you are running Paper/Folia 1.20.3 - 1.21.11");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        DebugLogger.info("NMS accessor initialized successfully for version: " + nmsAccessor.getVersionTag());

        this.foliaScheduler = new FoliaScheduler(this);
        this.swapBuffer = new SwapBuffer(pluginConfig.getSwapBufferTicks());
        this.manualResurrection = new ManualResurrection(nmsAccessor);

        this.combatManager = new CombatManager(
                this,
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

        Bukkit.getPluginManager().registerEvents(new DamageListener(combatManager), this);
        Bukkit.getPluginManager().registerEvents(new SwapListener(swapBuffer, nmsAccessor), this);

        var command = getCommand("masterantighost");
        if (command != null) {
            var cmdListener = new CommandListener(this, fastPathPops, reconciledPops, reconciledDeaths, interceptedHits);
            command.setExecutor(cmdListener);
            command.setTabCompleter(cmdListener);
        }

        foliaScheduler.runOnGlobalTimer(() -> {
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

        if (!isSupportedVersion(version)) {
            getLogger().severe("Unsupported Minecraft version " + version + ". Supported range: 1.20.3 - 1.21.11");
            return new NmsAccessorUniversal(version);
        }

        return new NmsAccessorUniversal(version);
    }

    private boolean isSupportedVersion(String version) {
        int[] v = parseVersion(version);
        int[] min = new int[]{1, 20, 3};
        int[] max = new int[]{1, 21, 11};
        return compareVersion(v, min) >= 0 && compareVersion(v, max) <= 0;
    }

    private int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? parseInt(parts[2]) : 0;
        return new int[]{major, minor, patch};
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }
}
