package net.opmasterleo.AntiTotemGhost.version;

public record CapabilityReport(
        String bukkitVersion,
        String minecraftVersion,
        boolean foliaDetected,
        boolean nmsAvailable,
        String nmsTag
) {
}

