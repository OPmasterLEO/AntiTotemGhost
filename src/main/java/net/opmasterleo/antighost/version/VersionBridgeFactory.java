package net.opmasterleo.AntiTotemGhost.version;

import org.bukkit.Bukkit;

import net.opmasterleo.AntiTotemGhost.nms.NmsAccessor;
import net.opmasterleo.AntiTotemGhost.nms.NmsAccessorDirect;

public final class VersionBridgeFactory {

    private VersionBridgeFactory() {
    }

    public static VersionBridge create() {
        String mcVersion = Bukkit.getMinecraftVersion();
        boolean folia = detectFolia();
        NmsAccessor nms = new NmsAccessorDirect(mcVersion);
        boolean nmsAvailable = nms.isAvailable();
        CapabilityReport report = new CapabilityReport(
                Bukkit.getVersion(),
                mcVersion,
                folia,
                nmsAvailable,
                nmsAvailable ? nms.getVersionTag() : "unavailable"
        );
        return new ReflectionVersionBridge(nms, new ReflectionPacketSchemaResolver(), report);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private record ReflectionVersionBridge(
            NmsAccessor nmsAccessor,
            PacketSchemaResolver packetSchemaResolver,
            CapabilityReport capabilityReport
    ) implements VersionBridge {

        @Override
        public boolean isSupported() {
            return capabilityReport.nmsAvailable();
        }
    }
}

