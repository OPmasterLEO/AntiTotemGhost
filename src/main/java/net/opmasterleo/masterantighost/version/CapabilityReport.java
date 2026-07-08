package net.opmasterleo.masterantighost.version;

public record CapabilityReport(
        String bukkitVersion,
        String minecraftVersion,
        boolean foliaDetected,
        boolean nmsAvailable,
        String nmsTag
) {
}

