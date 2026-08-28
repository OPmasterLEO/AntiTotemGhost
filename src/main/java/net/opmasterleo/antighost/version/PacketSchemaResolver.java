package net.opmasterleo.AntiTotemGhost.version;

import net.opmasterleo.AntiTotemGhost.buffer.SwapBuffer;

public interface PacketSchemaResolver {

    SwapBuffer.SwapType resolveSwapType(Object packet);
}

