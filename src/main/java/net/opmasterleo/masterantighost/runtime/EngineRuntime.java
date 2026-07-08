package net.opmasterleo.masterantighost.runtime;

import java.util.Objects;

import org.bukkit.plugin.Plugin;

import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.runtime.scheduler.SchedulerHub;
import net.opmasterleo.masterantighost.runtime.task.TaskSupervisor;

public final class EngineRuntime {

    public enum State {
        CONSTRUCTED,
        PROBING,
        STARTING,
        HEALTHY,
        DRAINING,
        STOPPED
    }

    private final Plugin plugin;
    private final ServiceRegistry services;
    private final SchedulerHub schedulerHub;
    private final TaskSupervisor taskSupervisor;

    private volatile State state = State.CONSTRUCTED;

    public EngineRuntime(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.schedulerHub = new SchedulerHub(plugin);
        this.taskSupervisor = new TaskSupervisor(plugin, schedulerHub);
        this.services = new ServiceRegistry(plugin, schedulerHub, taskSupervisor);
    }

    public void start() {
        if (state != State.CONSTRUCTED && state != State.STOPPED) {
            return;
        }
        state = State.PROBING;
        if (!services.probe()) {
            DebugLogger.severe("Compatibility probe failed, engine will not start.");
            state = State.STOPPED;
            return;
        }
        state = State.STARTING;
        services.start();
        state = State.HEALTHY;
    }

    public void beginDrain() {
        if (state != State.HEALTHY) {
            return;
        }
        state = State.DRAINING;
        services.beginDrain();
    }

    public void stop() {
        services.stop();
        state = State.STOPPED;
    }

    public State getState() {
        return state;
    }

    public ServiceRegistry services() {
        return services;
    }

    public SchedulerHub scheduler() {
        return schedulerHub;
    }

    public TaskSupervisor tasks() {
        return taskSupervisor;
    }

    public Plugin plugin() {
        return plugin;
    }
}

