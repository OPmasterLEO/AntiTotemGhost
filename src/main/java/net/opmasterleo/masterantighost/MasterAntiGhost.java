package net.opmasterleo.masterantighost;

import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.masterantighost.combat.CombatManager;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.runtime.EngineRuntime;
import net.opmasterleo.masterantighost.runtime.ServiceRegistry;
public final class MasterAntiGhost extends JavaPlugin {

    private EngineRuntime engine;

    @Override
    public void onEnable() {
        this.engine = new EngineRuntime(this);
        engine.start();
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.beginDrain();
            engine.stop();
            engine = null;
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (engine != null) {
            ServiceRegistry services = engine.services();
            services.stop();
            services.probe();
            services.start();
        }
    }

    public PluginConfig getPluginConfig() {
        if (engine == null) {
            return new PluginConfig(getConfig());
        }
        return engine.services().pluginConfig();
    }

    public CombatManager getCombatManager() {
        if (engine == null) {
            return null;
        }
        return engine.services().combatManager();
    }
}
