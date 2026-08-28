package net.opmasterleo.AntiTotemGhost;

import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.AntiTotemGhost.combat.CombatEngine;
import net.opmasterleo.AntiTotemGhost.config.PluginConfig;
import net.opmasterleo.AntiTotemGhost.runtime.EngineRuntime;
import net.opmasterleo.AntiTotemGhost.runtime.ServiceRegistry;
public final class AntiTotemGhost extends JavaPlugin {

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

    public CombatEngine getCombatEngine() {
        if (engine == null) {
            return null;
        }
        return engine.services().combatEngine();
    }
}
