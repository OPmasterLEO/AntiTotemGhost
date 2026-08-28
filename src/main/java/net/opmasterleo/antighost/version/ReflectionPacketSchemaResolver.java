package net.opmasterleo.AntiTotemGhost.version;

import java.lang.reflect.Method;

import net.opmasterleo.AntiTotemGhost.buffer.SwapBuffer;

public final class ReflectionPacketSchemaResolver implements PacketSchemaResolver {

    private static final int CONTAINER_OFFHAND_SLOT = 45;

    @Override
    public SwapBuffer.SwapType resolveSwapType(Object packet) {
        if (packet == null) {
            return null;
        }
        String name = packet.getClass().getName();
        if (name.endsWith("ServerboundPlayerActionPacket")) {
            Object action = invokeNoArgs(packet, "getAction");
            if (action != null && "SWAP_ITEM_WITH_OFFHAND".equals(action.toString())) {
                return SwapBuffer.SwapType.OFFHAND_SWAP;
            }
            return null;
        }

        if (name.endsWith("ServerboundContainerClickPacket")) {
            return resolveContainerClick(packet);
        }

        if (name.endsWith("PacketPlayInWindowClick")) {
            return resolveContainerClick(packet);
        }

        return null;
    }

    private SwapBuffer.SwapType resolveContainerClick(Object packet) {
        Object clickType = invokeNoArgs(packet, "clickType");
        if (clickType == null) {
            clickType = invokeNoArgs(packet, "getClickType");
        }
        if (clickType != null && "SWAP".equals(clickType.toString())) {
            return SwapBuffer.SwapType.NUMBER_KEY;
        }
        Integer slotNum = getIntValue(packet, "slotNum", "getSlotNum");
        if (slotNum != null && slotNum == CONTAINER_OFFHAND_SLOT) {
            return SwapBuffer.SwapType.WINDOW_CLICK;
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Integer getIntValue(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArgs(target, methodName);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }
}

