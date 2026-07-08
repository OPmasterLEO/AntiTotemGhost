package net.opmasterleo.masterantighost.version;

import net.opmasterleo.masterantighost.buffer.SwapBuffer;

public interface PacketSchemaResolver {

    SwapBuffer.SwapType resolveSwapType(Object packet);
}

