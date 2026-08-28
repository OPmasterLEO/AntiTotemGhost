package net.opmasterleo.AntiTotemGhost.version;

import net.opmasterleo.AntiTotemGhost.nms.NmsAccessor;

public interface VersionBridge {

    NmsAccessor nmsAccessor();

    PacketSchemaResolver packetSchemaResolver();

    CapabilityReport capabilityReport();

    boolean isSupported();
}

