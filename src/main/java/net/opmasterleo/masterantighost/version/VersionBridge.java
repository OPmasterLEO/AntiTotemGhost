package net.opmasterleo.masterantighost.version;

import net.opmasterleo.masterantighost.nms.NmsAccessor;

public interface VersionBridge {

    NmsAccessor nmsAccessor();

    PacketSchemaResolver packetSchemaResolver();

    CapabilityReport capabilityReport();

    boolean isSupported();
}

