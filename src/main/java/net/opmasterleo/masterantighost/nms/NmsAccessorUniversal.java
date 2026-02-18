package net.opmasterleo.masterantighost.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import net.opmasterleo.masterantighost.debug.DebugLogger;

public final class NmsAccessorUniversal implements NmsAccessor {

    private final String versionTag;
    private final boolean available;

    private final Class<?> serverPlayerClass;
    private final Class<?> equipmentSlotClass;
    private final Class<?> itemStackClass;
    private final Class<?> itemsClass;
    private final Class<?> mobEffectClass;
    private final Class<?> mobEffectsClass;
    private final Class<?> mobEffectInstanceClass;
    private final Class<?> damageSourceClass;
    private final Class<?> minecraftServerClass;

    private final Object equipmentMainhand;
    private final Object equipmentOffhand;
    private final Object totemItem;
    private final Object effectRegeneration;
    private final Object effectAbsorption;
    private final Object effectFireResistance;

    private final Method getHandleMethod;
    private final Method getItemBySlotMethod;
    private final Method itemIsMethod;
    private final Method itemIsEmptyMethod;
    private final Method itemShrinkMethod;
    private final Method setHealthMethod;
    private final Method removeAllEffectsMethod;
    private final Method addEffectMethod;
    private final Method setRemainingFireTicksMethod;
    private final Method serverLevelMethod;
    private final Method broadcastEntityEventMethod;
    private final Method hurtMethod;
    private final Method damageSourcesMethod;
    private final Method genericDamageSourceMethod;
    private final Method getTickCountMethod;
    private final Method getServerMethod;
    private final Method bukkitCurrentTickMethod;

    private final Method inventoryBroadcastChangesMethod;
    private final Field inventoryMenuField;
    private final Field invulnerableTimeField;

    private final Constructor<?> mobEffectInstanceCtor3;
    private final Constructor<?> mobEffectInstanceCtor6;

    private final ConcurrentHashMap<Class<?>, Method> bukkitDamageSourceGetterCache;
    private final ConcurrentHashMap<Class<?>, Method> bukkitDamageSourceHandleCache;

    public NmsAccessorUniversal(String bukkitVersion) {
        this.versionTag = bukkitVersion;
        Class<?> localServerPlayerClass = null;
        Class<?> localEquipmentSlotClass = null;
        Class<?> localItemStackClass = null;
        Class<?> localItemsClass = null;
        Class<?> localMobEffectClass = null;
        Class<?> localMobEffectsClass = null;
        Class<?> localMobEffectInstanceClass = null;
        Class<?> localDamageSourceClass = null;
        Class<?> localMinecraftServerClass = null;
        Object localEquipmentMainhand = null;
        Object localEquipmentOffhand = null;
        Object localTotemItem = null;
        Object localEffectRegeneration = null;
        Object localEffectAbsorption = null;
        Object localEffectFireResistance = null;
        Method localGetHandleMethod = null;
        Method localGetItemBySlotMethod = null;
        Method localItemIsMethod = null;
        Method localItemIsEmptyMethod = null;
        Method localItemShrinkMethod = null;
        Method localSetHealthMethod = null;
        Method localRemoveAllEffectsMethod = null;
        Method localAddEffectMethod = null;
        Method localSetRemainingFireTicksMethod = null;
        Method localServerLevelMethod = null;
        Method localBroadcastEntityEventMethod = null;
        Method localHurtMethod = null;
        Method localDamageSourcesMethod = null;
        Method localGenericDamageSourceMethod = null;
        Method localGetTickCountMethod = null;
        Method localGetServerMethod = null;
        Method localBukkitCurrentTickMethod = null;
        Method localInventoryBroadcastChangesMethod = null;
        Field localInventoryMenuField = null;
        Field localInvulnerableTimeField = null;
        Constructor<?> localMobEffectInstanceCtor3 = null;
        Constructor<?> localMobEffectInstanceCtor6 = null;
        boolean ok;

        try {
            localServerPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            localEquipmentSlotClass = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            localItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
            localItemsClass = Class.forName("net.minecraft.world.item.Items");
            localMobEffectClass = Class.forName("net.minecraft.world.effect.MobEffect");
            localMobEffectsClass = Class.forName("net.minecraft.world.effect.MobEffects");
            localMobEffectInstanceClass = Class.forName("net.minecraft.world.effect.MobEffectInstance");
            localDamageSourceClass = Class.forName("net.minecraft.world.damagesource.DamageSource");
            localMinecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");

            localGetHandleMethod = CraftPlayer.class.getMethod("getHandle");
            localGetItemBySlotMethod = localServerPlayerClass.getMethod("getItemBySlot", localEquipmentSlotClass);
            localItemIsMethod = localItemStackClass.getMethod("is", Class.forName("net.minecraft.world.level.ItemLike"));
            localItemIsEmptyMethod = localItemStackClass.getMethod("isEmpty");
            localItemShrinkMethod = localItemStackClass.getMethod("shrink", int.class);
            localSetHealthMethod = localServerPlayerClass.getMethod("setHealth", float.class);
            localRemoveAllEffectsMethod = localServerPlayerClass.getMethod("removeAllEffects");
            localAddEffectMethod = localServerPlayerClass.getMethod("addEffect", localMobEffectInstanceClass);
            localSetRemainingFireTicksMethod = localServerPlayerClass.getMethod("setRemainingFireTicks", int.class);
            localServerLevelMethod = localServerPlayerClass.getMethod("serverLevel");
            localHurtMethod = localServerPlayerClass.getMethod("hurt", localDamageSourceClass, float.class);
            localDamageSourcesMethod = localServerPlayerClass.getMethod("damageSources");
            localGetTickCountMethod = localMinecraftServerClass.getMethod("getTickCount");
            localGetServerMethod = localMinecraftServerClass.getMethod("getServer");
            try {
                localBukkitCurrentTickMethod = Bukkit.class.getMethod("getCurrentTick");
            } catch (NoSuchMethodException ignored) {
                localBukkitCurrentTickMethod = null;
            }

            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
            localBroadcastEntityEventMethod = levelClass.getMethod("broadcastEntityEvent", Class.forName("net.minecraft.world.entity.Entity"), byte.class);

            Object[] slots = localEquipmentSlotClass.getEnumConstants();
                localEquipmentMainhand = Arrays.stream(slots)
                    .filter(slot -> {
                    String name = ((Enum<?>) slot).name();
                    return name.equals("MAINHAND") || name.equals("HAND");
                    })
                    .findFirst()
                    .orElseThrow();
            localEquipmentOffhand = Arrays.stream(slots)
                    .filter(slot -> ((Enum<?>) slot).name().equals("OFFHAND"))
                    .findFirst()
                    .orElseThrow();

            localTotemItem = localItemsClass.getField("TOTEM_OF_UNDYING").get(null);
            localEffectRegeneration = localMobEffectsClass.getField("REGENERATION").get(null);
            localEffectAbsorption = localMobEffectsClass.getField("ABSORPTION").get(null);
            localEffectFireResistance = localMobEffectsClass.getField("FIRE_RESISTANCE").get(null);

            localInventoryMenuField = findField(localServerPlayerClass, List.of("inventoryMenu", "containerMenu"));
            Class<?> containerMenuClass = localInventoryMenuField.getType();
            localInventoryBroadcastChangesMethod = containerMenuClass.getMethod("broadcastChanges");

            localInvulnerableTimeField = findField(localServerPlayerClass, List.of("invulnerableTime", "invulnerableDuration"));

            localGenericDamageSourceMethod = findMethodByName(localDamageSourcesMethod.getReturnType(), "generic");

            for (Constructor<?> ctor : localMobEffectInstanceClass.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 3 && p[0] == localMobEffectClass && p[1] == int.class && p[2] == int.class) {
                    localMobEffectInstanceCtor3 = ctor;
                }
                if (p.length == 6 && p[0] == localMobEffectClass && p[1] == int.class && p[2] == int.class
                        && p[3] == boolean.class && p[4] == boolean.class && p[5] == boolean.class) {
                    localMobEffectInstanceCtor6 = ctor;
                }
            }

            if (localMobEffectInstanceCtor3 == null && localMobEffectInstanceCtor6 == null) {
                throw new IllegalStateException("No compatible MobEffectInstance constructor found");
            }

            ok = true;
        } catch (Exception e) {
            ok = false;
            DebugLogger.severe("NMS init failed for " + bukkitVersion, e);
        }

        this.serverPlayerClass = localServerPlayerClass;
        this.equipmentSlotClass = localEquipmentSlotClass;
        this.itemStackClass = localItemStackClass;
        this.itemsClass = localItemsClass;
        this.mobEffectClass = localMobEffectClass;
        this.mobEffectsClass = localMobEffectsClass;
        this.mobEffectInstanceClass = localMobEffectInstanceClass;
        this.damageSourceClass = localDamageSourceClass;
        this.minecraftServerClass = localMinecraftServerClass;
        this.equipmentMainhand = localEquipmentMainhand;
        this.equipmentOffhand = localEquipmentOffhand;
        this.totemItem = localTotemItem;
        this.effectRegeneration = localEffectRegeneration;
        this.effectAbsorption = localEffectAbsorption;
        this.effectFireResistance = localEffectFireResistance;
        this.getHandleMethod = localGetHandleMethod;
        this.getItemBySlotMethod = localGetItemBySlotMethod;
        this.itemIsMethod = localItemIsMethod;
        this.itemIsEmptyMethod = localItemIsEmptyMethod;
        this.itemShrinkMethod = localItemShrinkMethod;
        this.setHealthMethod = localSetHealthMethod;
        this.removeAllEffectsMethod = localRemoveAllEffectsMethod;
        this.addEffectMethod = localAddEffectMethod;
        this.setRemainingFireTicksMethod = localSetRemainingFireTicksMethod;
        this.serverLevelMethod = localServerLevelMethod;
        this.broadcastEntityEventMethod = localBroadcastEntityEventMethod;
        this.hurtMethod = localHurtMethod;
        this.damageSourcesMethod = localDamageSourcesMethod;
        this.genericDamageSourceMethod = localGenericDamageSourceMethod;
        this.getTickCountMethod = localGetTickCountMethod;
        this.getServerMethod = localGetServerMethod;
        this.bukkitCurrentTickMethod = localBukkitCurrentTickMethod;
        this.inventoryBroadcastChangesMethod = localInventoryBroadcastChangesMethod;
        this.inventoryMenuField = localInventoryMenuField;
        this.invulnerableTimeField = localInvulnerableTimeField;
        this.mobEffectInstanceCtor3 = localMobEffectInstanceCtor3;
        this.mobEffectInstanceCtor6 = localMobEffectInstanceCtor6;
        this.bukkitDamageSourceGetterCache = new ConcurrentHashMap<>(4);
        this.bukkitDamageSourceHandleCache = new ConcurrentHashMap<>(4);
        this.available = ok;

        if (this.invulnerableTimeField != null) {
            this.invulnerableTimeField.setAccessible(true);
        }
    }

    @Override
    public boolean hasTotemInOffhand(Player player) {
        if (!available) return false;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            return hasTotemInSlot(nms, equipmentOffhand);
        } catch (Exception e) {
            DebugLogger.warn("hasTotemInOffhand failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasTotemInEitherHand(Player player) {
        if (!available) return false;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            return hasTotemInSlot(nms, equipmentOffhand) || hasTotemInSlot(nms, equipmentMainhand);
        } catch (Exception e) {
            DebugLogger.warn("hasTotemInEitherHand failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean consumeOffhandTotemIfPresent(Player player) {
        if (!available) return false;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            return consumeTotemInSlotIfPresent(nms, equipmentOffhand);
        } catch (Exception e) {
            DebugLogger.warn("consumeOffhandTotemIfPresent failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean consumeTotemFromEitherHandIfPresent(Player player) {
        if (!available) return false;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            if (consumeTotemInSlotIfPresent(nms, equipmentOffhand)) {
                return true;
            }
            return consumeTotemInSlotIfPresent(nms, equipmentMainhand);
        } catch (Exception e) {
            DebugLogger.warn("consumeTotemFromEitherHandIfPresent failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void consumeOneTotemFromOffhand(Player player) {
        consumeOffhandTotemIfPresent(player);
    }

    @Override
    public void setHealthNms(Player player, float health) {
        if (!available) return;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            setHealthMethod.invoke(nms, health);
        } catch (Exception e) {
            player.setHealth(Math.max(0.0, health));
        }
    }

    @Override
    public void removeAllEffectsNms(Player player) {
        if (!available) return;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            removeAllEffectsMethod.invoke(nms);
        } catch (Exception e) {
            player.getActivePotionEffects().forEach(pe -> player.removePotionEffect(pe.getType()));
        }
    }

    @Override
    public void applyTotemEffectsNms(Player player) {
        if (!available) return;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            addEffectMethod.invoke(nms, createEffect(effectRegeneration, 900, 1));
            addEffectMethod.invoke(nms, createEffect(effectAbsorption, 100, 1));
            addEffectMethod.invoke(nms, createEffect(effectFireResistance, 800, 0));
        } catch (Exception e) {
            DebugLogger.warn("applyTotemEffectsNms failed: " + e.getMessage());
        }
    }

    @Override
    public void extinguishFireNms(Player player) {
        if (!available) return;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            setRemainingFireTicksMethod.invoke(nms, 0);
        } catch (Exception e) {
            player.setFireTicks(0);
        }
    }

    @Override
    public void broadcastTotemPopAnimation(Player player) {
        if (!available) return;
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            Object level = serverLevelMethod.invoke(nms);
            broadcastEntityEventMethod.invoke(level, nms, (byte) 35);
        } catch (Exception e) {
            DebugLogger.warn("broadcastTotemPopAnimation failed: " + e.getMessage());
        }
    }

    @Override
    public Object captureDamageSource(EntityDamageEvent event) {
        if (!available) return null;
        try {
            Method damageSourceMethod = bukkitDamageSourceGetterCache.computeIfAbsent(event.getClass(), cls -> {
                try {
                    return cls.getMethod("getDamageSource");
                } catch (NoSuchMethodException e) {
                    return null;
                }
            });
            if (damageSourceMethod == null) {
                return null;
            }
            Object bukkitDamageSource = damageSourceMethod.invoke(event);
            if (bukkitDamageSource == null) return null;
            Method getHandle = bukkitDamageSourceHandleCache.computeIfAbsent(bukkitDamageSource.getClass(), cls -> {
                try {
                    return cls.getMethod("getHandle");
                } catch (NoSuchMethodException e) {
                    return null;
                }
            });
            if (getHandle == null) {
                return null;
            }
            Object nmsDamageSource = getHandle.invoke(bukkitDamageSource);
            if (damageSourceClass.isInstance(nmsDamageSource)) {
                return nmsDamageSource;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void dealDamageWithSource(Player player, float amount, Object nmsDamageSource) {
        if (!available) {
            player.damage(amount);
            return;
        }
        try {
            Object nms = getHandleMethod.invoke((CraftPlayer) player);
            if (invulnerableTimeField != null) {
                invulnerableTimeField.setInt(nms, 0);
            }
            Object source = nmsDamageSource;
            if (source == null || !damageSourceClass.isInstance(source)) {
                Object ds = damageSourcesMethod.invoke(nms);
                source = genericDamageSourceMethod.invoke(ds);
            }
            hurtMethod.invoke(nms, source, amount);
        } catch (Exception e) {
            player.damage(amount);
        }
    }

    @Override
    public long getCurrentTick() {
        try {
            if (bukkitCurrentTickMethod != null) {
                Object tick = bukkitCurrentTickMethod.invoke(null);
                return ((Number) tick).longValue();
            }
            Object server = getServerMethod.invoke(null);
            Object tick = getTickCountMethod.invoke(server);
            return ((Number) tick).longValue();
        } catch (Exception ignored) {
            return System.currentTimeMillis() / 50L;
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getVersionTag() {
        return versionTag;
    }

    private Object createEffect(Object mobEffect, int duration, int amplifier) throws Exception {
        if (mobEffectInstanceCtor3 != null) {
            return mobEffectInstanceCtor3.newInstance(mobEffect, duration, amplifier);
        }
        return mobEffectInstanceCtor6.newInstance(mobEffect, duration, amplifier, false, true, true);
    }

    private boolean hasTotemInSlot(Object nmsPlayer, Object equipmentSlot) throws Exception {
        Object item = getItemBySlotMethod.invoke(nmsPlayer, equipmentSlot);
        boolean empty = (boolean) itemIsEmptyMethod.invoke(item);
        if (empty) {
            return false;
        }
        return (boolean) itemIsMethod.invoke(item, totemItem);
    }

    private boolean consumeTotemInSlotIfPresent(Object nmsPlayer, Object equipmentSlot) throws Exception {
        Object item = getItemBySlotMethod.invoke(nmsPlayer, equipmentSlot);
        boolean empty = (boolean) itemIsEmptyMethod.invoke(item);
        if (empty) {
            return false;
        }
        boolean isTotem = (boolean) itemIsMethod.invoke(item, totemItem);
        if (!isTotem) {
            return false;
        }
        itemShrinkMethod.invoke(item, 1);
        Object menu = inventoryMenuField.get(nmsPlayer);
        inventoryBroadcastChangesMethod.invoke(menu);
        return true;
    }

    private static Field findField(Class<?> type, List<String> names) {
        for (String n : names) {
            try {
                return type.getField(n);
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (Field f : type.getFields()) {
            if (names.contains(f.getName())) {
                return f;
            }
        }
        throw new IllegalStateException("Field not found in " + type.getName() + " among " + names);
    }

    private static Method findMethodByName(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        throw new IllegalStateException("Method not found: " + type.getName() + "." + name + "()");
    }
}
