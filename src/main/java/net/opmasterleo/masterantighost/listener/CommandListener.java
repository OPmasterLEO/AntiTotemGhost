package net.opmasterleo.masterantighost.listener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import net.opmasterleo.masterantighost.MasterAntiGhost;
import net.opmasterleo.masterantighost.config.PluginConfig;
import net.opmasterleo.masterantighost.debug.DebugLogger;
import net.opmasterleo.masterantighost.version.CapabilityReport;

public final class CommandListener implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[MasterAntiGhost] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "debug", "stats", "compat");

    private final MasterAntiGhost plugin;
    private final LongAdder fastPathPops;
    private final LongAdder reconciledPops;
    private final LongAdder reconciledDeaths;
    private final LongAdder interceptedHits;
    private final CapabilityReport capabilityReport;

    public CommandListener(MasterAntiGhost plugin,
                           LongAdder fastPathPops, LongAdder reconciledPops,
                           LongAdder reconciledDeaths, LongAdder interceptedHits,
                           CapabilityReport capabilityReport) {
        this.plugin = plugin;
        this.fastPathPops = fastPathPops;
        this.reconciledPops = reconciledPops;
        this.reconciledDeaths = reconciledDeaths;
        this.interceptedHits = interceptedHits;
        this.capabilityReport = capabilityReport;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("masterantighost.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
            }
            case "debug" -> {
                boolean newState = !DebugLogger.isEnabled();
                DebugLogger.setEnabled(newState);
                sender.sendMessage(PREFIX + "Debug mode: " +
                        (newState ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));
            }
            case "stats" -> {
                PluginConfig config = plugin.getPluginConfig();
                sender.sendMessage(PREFIX + ChatColor.AQUA + "=== Anti-Ghost Statistics ===");
                sender.sendMessage(PREFIX + "Fast-path totem pops: " + ChatColor.WHITE + fastPathPops.sum());
                sender.sendMessage(PREFIX + "Reconciled totem pops: " + ChatColor.WHITE + reconciledPops.sum());
                sender.sendMessage(PREFIX + "Reconciled deaths: " + ChatColor.WHITE + reconciledDeaths.sum());
                sender.sendMessage(PREFIX + "Total intercepted hits: " + ChatColor.WHITE + interceptedHits.sum());
                sender.sendMessage(PREFIX + ChatColor.AQUA + "=== Configuration ===");
                sender.sendMessage(PREFIX + "Reconciliation ticks: " + ChatColor.WHITE + config.getReconciliationTicks());
                sender.sendMessage(PREFIX + "Swap buffer ticks: " + ChatColor.WHITE + config.getSwapBufferTicks());
                sender.sendMessage(PREFIX + "Fast path: " +
                        (config.isEnableFastPath() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
                sender.sendMessage(PREFIX + "Debug mode: " +
                        (DebugLogger.isEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
                sender.sendMessage(PREFIX + ChatColor.AQUA + "=== Compatibility ===");
                if (capabilityReport != null) {
                    sender.sendMessage(PREFIX + "Minecraft version: " + ChatColor.WHITE + capabilityReport.minecraftVersion());
                    sender.sendMessage(PREFIX + "NMS available: " + ChatColor.WHITE + capabilityReport.nmsAvailable());
                    sender.sendMessage(PREFIX + "NMS tag: " + ChatColor.WHITE + capabilityReport.nmsTag());
                    sender.sendMessage(PREFIX + "Folia detected: " + ChatColor.WHITE + capabilityReport.foliaDetected());
                }
            }
            case "compat" -> {
                sender.sendMessage(PREFIX + ChatColor.AQUA + "=== Compatibility Report ===");
                if (capabilityReport == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "No capability report available.");
                } else {
                    sender.sendMessage(PREFIX + "Bukkit version: " + ChatColor.WHITE + capabilityReport.bukkitVersion());
                    sender.sendMessage(PREFIX + "Minecraft version: " + ChatColor.WHITE + capabilityReport.minecraftVersion());
                    sender.sendMessage(PREFIX + "Folia detected: " + ChatColor.WHITE + capabilityReport.foliaDetected());
                    sender.sendMessage(PREFIX + "NMS available: " + ChatColor.WHITE + capabilityReport.nmsAvailable());
                    sender.sendMessage(PREFIX + "NMS tag: " + ChatColor.WHITE + capabilityReport.nmsTag());
                }
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.AQUA + "MasterAntiGhost v" + plugin.getDescription().getVersion());
        sender.sendMessage(PREFIX + ChatColor.GRAY + "/" + label + " reload " + ChatColor.WHITE + "— Reload config");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "/" + label + " debug " + ChatColor.WHITE + "— Toggle debug");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "/" + label + " stats " + ChatColor.WHITE + "— Show statistics");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "/" + label + " compat " + ChatColor.WHITE + "— Show compatibility");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(partial)).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
