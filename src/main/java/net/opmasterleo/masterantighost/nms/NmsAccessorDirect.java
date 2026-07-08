package net.opmasterleo.masterantighost.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

public final class NmsAccessorDirect implements NmsAccessor {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");
    private static final int SUPPORTED_MAJOR = 1;
    private static final int SUPPORTED_MINOR_MIN = 20;
    private static final int SUPPORTED_MINOR_MAX = 60;

    private static final String CLASS_CRAFT_DAMAGE_SOURCE = "org.bukkit.craftbukkit.damage.CraftDamageSource";
    private static final String CLASS_MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer";
    private static final String CLASS_SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String CLASS_DAMAGE_SOURCE = "net.minecraft.world.damagesource.DamageSource";
    private static final String CLASS_MOB_EFFECT = "net.minecraft.world.effect.MobEffect";
    private static final String CLASS_MOB_EFFECTS = "net.minecraft.world.effect.MobEffects";
    private static final String CLASS_MOB_EFFECT_INSTANCE = "net.minecraft.world.effect.MobEffectInstance";
    private static final String CLASS_LEVEL = "net.minecraft.world.level.Level";
    private static final String CLASS_ENTITY = "net.minecraft.world.entity.Entity";
    private static final String CLASS_DAMAGE_SOURCES = "net.minecraft.world.damagesource.DamageSources";

    private final String versionTag;
    private final boolean available;
    private final RuntimeBindings runtimeBindings;

    public NmsAccessorDirect(String versionTag) {
        this.versionTag = versionTag;
        this.runtimeBindings = RuntimeBindings.tryCreate();
        this.available = isSupportedVersion(versionTag) && runtimeBindings != null;
    }

    private Object getServerPlayer(Player player) {
        return invoke(player, "getHandle");
    }

    @Override
    public boolean hasTotemInOffhand(Player player) {
        ItemStack stack = player.getInventory().getItemInOffHand();
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING;
    }

    @Override
    public boolean hasTotemInEitherHand(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack main = player.getInventory().getItemInMainHand();
        return (off != null && off.getType() == Material.TOTEM_OF_UNDYING)
                || (main != null && main.getType() == Material.TOTEM_OF_UNDYING);
    }

    @Override
    public boolean consumeOffhandTotemIfPresent(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING) {
            offhand.setAmount(Math.max(0, offhand.getAmount() - 1));
            return true;
        }
        return false;
    }

    @Override
    public boolean consumeTotemFromEitherHandIfPresent(Player player) {
        ItemStack mainhand = player.getInventory().getItemInMainHand();
        if (mainhand != null && mainhand.getType() == Material.TOTEM_OF_UNDYING) {
            mainhand.setAmount(Math.max(0, mainhand.getAmount() - 1));
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING) {
            offhand.setAmount(Math.max(0, offhand.getAmount() - 1));
            return true;
        }
        return false;
    }

    @Override
    public void consumeOneTotemFromOffhand(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING) {
            offhand.setAmount(Math.max(0, offhand.getAmount() - 1));
        }
    }

    @Override
    public void setHealthNms(Player player, float health) {
        player.setHealth(Math.max(0.0d, Math.min(health, player.getMaxHealth())));
    }

    @Override
    public void removeAllEffectsNms(Player player) {
        Object serverPlayer = getServerPlayer(player);
        if (serverPlayer == null || runtimeBindings == null) {
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            return;
        }
        invoke(serverPlayer, runtimeBindings.removeAllEffects.getName(), new Object[0]);
    }

    @Override
    public void applyTotemEffectsNms(Player player) {
        Object serverPlayer = getServerPlayer(player);
        if (serverPlayer == null || runtimeBindings == null) {
            player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), 4.0d));
            return;
        }
        Object regen = runtimeBindings.newMobEffect(runtimeBindings.mobEffectsRegen, 900, 1);
        Object absorption = runtimeBindings.newMobEffect(runtimeBindings.mobEffectsAbsorption, 100, 1);
        Object fireRes = runtimeBindings.newMobEffect(runtimeBindings.mobEffectsFireResistance, 800, 0);
        invoke(serverPlayer, runtimeBindings.addEffect.getName(), regen);
        invoke(serverPlayer, runtimeBindings.addEffect.getName(), absorption);
        invoke(serverPlayer, runtimeBindings.addEffect.getName(), fireRes);
    }

    @Override
    public void extinguishFireNms(Player player) {
        player.setFireTicks(0);
    }

    @Override
    public void broadcastTotemPopAnimation(Player player) {
        Object serverPlayer = getServerPlayer(player);
        if (serverPlayer == null || runtimeBindings == null) {
            return;
        }
        Object level = invoke(serverPlayer, runtimeBindings.levelMethod.getName());
        if (level != null) {
            invoke(level, runtimeBindings.broadcastEntityEvent.getName(), serverPlayer, (byte) 35);
        }
    }

    @Override
    public Object captureDamageSource(EntityDamageEvent event) {
        Object source = event.getDamageSource();
        if (source != null && runtimeBindings != null && runtimeBindings.craftDamageSourceClass.isInstance(source)) {
            return invoke(source, runtimeBindings.craftDamageSourceGetHandle.getName());
        }
        return null;
    }

    @Override
    public void dealDamageWithSource(Player player, float amount, Object nmsDamageSource) {
        Object serverPlayer = getServerPlayer(player);
        if (serverPlayer == null || runtimeBindings == null) {
            player.damage(amount);
            return;
        }
        if (nmsDamageSource != null && runtimeBindings.damageSourceClass.isInstance(nmsDamageSource)) {
            invoke(serverPlayer, runtimeBindings.hurtMethod.getName(), nmsDamageSource, amount);
            return;
        }
        Object damageSources = invoke(serverPlayer, runtimeBindings.damageSourcesMethod.getName());
        Object genericSource = damageSources == null ? null : invoke(damageSources, runtimeBindings.genericMethod.getName());
        if (genericSource != null) {
            invoke(serverPlayer, runtimeBindings.hurtMethod.getName(), genericSource, amount);
        } else {
            player.damage(amount);
        }
    }

    @Override
    public long getCurrentTick() {
        try {
            Method getCurrentTick = Bukkit.class.getMethod("getCurrentTick");
            Object value = getCurrentTick.invoke(null);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        if (runtimeBindings != null) {
            Object server = invokeStatic(runtimeBindings.minecraftServerClass, runtimeBindings.getServerMethod.getName());
            Object tickCount = server == null ? null : invoke(server, runtimeBindings.getTickCountMethod.getName());
            if (tickCount instanceof Number number) {
                return number.longValue();
            }
        }
        return System.currentTimeMillis() / 50L;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getVersionTag() {
        return versionTag;
    }

    private static boolean isSupportedVersion(String versionTag) {
        Matcher matcher = VERSION_PATTERN.matcher(versionTag);
        if (!matcher.matches()) {
            return false;
        }

        int major = parseGroup(matcher, 1);
        int minor = parseGroup(matcher, 2);
        return major == SUPPORTED_MAJOR && minor >= SUPPORTED_MINOR_MIN && minor <= SUPPORTED_MINOR_MAX;
    }

    private static int parseGroup(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeStatic(Class<?> type, String methodName, Object... args) {
        if (type == null) {
            return null;
        }
        Method method = findCompatibleMethod(type, methodName, args);
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, Object... args) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    Object arg = args[i];
                    if (arg == null) {
                        continue;
                    }
                    if (!box(parameterTypes[i]).isAssignableFrom(arg.getClass())) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        return type;
    }

    private static final class RuntimeBindings {
        private final Class<?> damageSourceClass;
        private final Class<?> mobEffectInstanceClass;
        private final Class<?> mobEffectClass;
        private final Class<?> craftDamageSourceClass;
        private final Class<?> minecraftServerClass;

        private final Method hurtMethod;
        private final Method removeAllEffects;
        private final Method addEffect;
        private final Method damageSourcesMethod;
        private final Method genericMethod;
        private final Method levelMethod;
        private final Method broadcastEntityEvent;
        private final Method craftDamageSourceGetHandle;
        private final Constructor<?> mobEffectInstanceCtor;
        private final Method getServerMethod;
        private final Method getTickCountMethod;

        private final Object mobEffectsRegen;
        private final Object mobEffectsAbsorption;
        private final Object mobEffectsFireResistance;

        private RuntimeBindings(Class<?> damageSourceClass,
                                Class<?> mobEffectInstanceClass,
                                Class<?> mobEffectClass,
                                Class<?> craftDamageSourceClass,
                                Class<?> minecraftServerClass,
                                Method hurtMethod,
                                Method removeAllEffects,
                                Method addEffect,
                                Method damageSourcesMethod,
                                Method genericMethod,
                                Method levelMethod,
                                Method broadcastEntityEvent,
                                Method craftDamageSourceGetHandle,
                                Constructor<?> mobEffectInstanceCtor,
                                Method getServerMethod,
                                Method getTickCountMethod,
                                Object mobEffectsRegen,
                                Object mobEffectsAbsorption,
                                Object mobEffectsFireResistance) {
            this.damageSourceClass = damageSourceClass;
            this.mobEffectInstanceClass = mobEffectInstanceClass;
            this.mobEffectClass = mobEffectClass;
            this.craftDamageSourceClass = craftDamageSourceClass;
            this.minecraftServerClass = minecraftServerClass;
            this.hurtMethod = hurtMethod;
            this.removeAllEffects = removeAllEffects;
            this.addEffect = addEffect;
            this.damageSourcesMethod = damageSourcesMethod;
            this.genericMethod = genericMethod;
            this.levelMethod = levelMethod;
            this.broadcastEntityEvent = broadcastEntityEvent;
            this.craftDamageSourceGetHandle = craftDamageSourceGetHandle;
            this.mobEffectInstanceCtor = mobEffectInstanceCtor;
            this.getServerMethod = getServerMethod;
            this.getTickCountMethod = getTickCountMethod;
            this.mobEffectsRegen = mobEffectsRegen;
            this.mobEffectsAbsorption = mobEffectsAbsorption;
            this.mobEffectsFireResistance = mobEffectsFireResistance;
        }

        private static RuntimeBindings tryCreate() {
            try {
                Class<?> serverPlayerClass = Class.forName(CLASS_SERVER_PLAYER);
                Class<?> damageSourceClass = Class.forName(CLASS_DAMAGE_SOURCE);
                Class<?> mobEffectInstanceClass = Class.forName(CLASS_MOB_EFFECT_INSTANCE);
                Class<?> mobEffectsClass = Class.forName(CLASS_MOB_EFFECTS);
                Class<?> mobEffectClass = Class.forName(CLASS_MOB_EFFECT);
                Class<?> craftDamageSourceClass = Class.forName(CLASS_CRAFT_DAMAGE_SOURCE);
                Class<?> minecraftServerClass = Class.forName(CLASS_MINECRAFT_SERVER);
                Class<?> levelClass = Class.forName(CLASS_LEVEL);
                Class<?> damageSourcesClass = Class.forName(CLASS_DAMAGE_SOURCES);
                Class<?> entityClass = Class.forName(CLASS_ENTITY);

                Method hurtMethod = serverPlayerClass.getMethod("hurt", damageSourceClass, float.class);
                Method removeAllEffects = serverPlayerClass.getMethod("removeAllEffects");
                Method addEffect = serverPlayerClass.getMethod("addEffect", mobEffectInstanceClass);
                Method damageSourcesMethod = serverPlayerClass.getMethod("damageSources");
                Method genericMethod = damageSourcesClass.getMethod("generic");
                Method levelMethod = serverPlayerClass.getMethod("level");
                Method broadcastEntityEvent = levelClass.getMethod("broadcastEntityEvent", entityClass, byte.class);
                Method craftDamageSourceGetHandle = craftDamageSourceClass.getMethod("getHandle");
                Constructor<?> mobEffectInstanceCtor = mobEffectInstanceClass.getConstructor(mobEffectClass, int.class, int.class);
                Method getServerMethod = minecraftServerClass.getMethod("getServer");
                Method getTickCountMethod = minecraftServerClass.getMethod("getTickCount");

                Object regen = mobEffectsClass.getField("REGENERATION").get(null);
                Object absorption = mobEffectsClass.getField("ABSORPTION").get(null);
                Object fireRes = mobEffectsClass.getField("FIRE_RESISTANCE").get(null);

                return new RuntimeBindings(
                        damageSourceClass,
                        mobEffectInstanceClass,
                        mobEffectClass,
                        craftDamageSourceClass,
                        minecraftServerClass,
                        hurtMethod,
                        removeAllEffects,
                        addEffect,
                        damageSourcesMethod,
                        genericMethod,
                        levelMethod,
                        broadcastEntityEvent,
                        craftDamageSourceGetHandle,
                        mobEffectInstanceCtor,
                        getServerMethod,
                        getTickCountMethod,
                        regen,
                        absorption,
                        fireRes
                );
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object newMobEffect(Object effect, int duration, int amplifier) {
            try {
                return mobEffectInstanceCtor.newInstance(effect, duration, amplifier);
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }
}