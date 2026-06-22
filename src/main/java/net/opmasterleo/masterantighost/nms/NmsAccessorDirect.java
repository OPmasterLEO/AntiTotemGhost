package net.opmasterleo.masterantighost.nms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class NmsAccessorDirect implements NmsAccessor {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");
    private static final int SUPPORTED_MAJOR = 1;
    private static final int SUPPORTED_MINOR = 21;
    private static final int SUPPORTED_MAX_PATCH = 6;

    private final String versionTag;
    private final boolean available;

    public NmsAccessor(String versionTag) {
        this.versionTag = versionTag;
        this.available = isSupportedVersion(versionTag) && hasRequiredRuntimeSymbols();
    }

    private ServerPlayer getServerPlayer(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    @Override
    public boolean hasTotemInOffhand(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        return sp.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING);
    }

    @Override
    public boolean hasTotemInEitherHand(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        return sp.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.TOTEM_OF_UNDYING) ||
               sp.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING);
    }

    @Override
    public boolean consumeOffhandTotemIfPresent(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        ItemStack offhand = sp.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            offhand.shrink(1);
            return true;
        }
        return false;
    }

    @Override
    public boolean consumeTotemFromEitherHandIfPresent(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        ItemStack mainhand = sp.getItemBySlot(EquipmentSlot.MAINHAND);
        if (mainhand.is(Items.TOTEM_OF_UNDYING)) {
            mainhand.shrink(1);
            return true;
        }
        ItemStack offhand = sp.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            offhand.shrink(1);
            return true;
        }
        return false;
    }

    @Override
    public void consumeOneTotemFromOffhand(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        ItemStack offhand = sp.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty()) {
            offhand.shrink(1);
        }
    }

    @Override
    public void setHealthNms(Player player, float health) {
        getServerPlayer(player).setHealth(health);
    }

    @Override
    public void removeAllEffectsNms(Player player) {
        getServerPlayer(player).removeAllEffects();
    }

    @Override
    public void applyTotemEffectsNms(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        sp.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        sp.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        sp.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
    }

    @Override
    public void extinguishFireNms(Player player) {
        getServerPlayer(player).clearFire();
    }

    @Override
    public void broadcastTotemPopAnimation(Player player) {
        ServerPlayer sp = getServerPlayer(player);
        sp.level().broadcastEntityEvent(sp, (byte) 35);
    }

    @Override
    public Object captureDamageSource(EntityDamageEvent event) {
        if (event.getDamageSource() instanceof org.bukkit.craftbukkit.damage.CraftDamageSource craftDamageSource) {
            return craftDamageSource.getHandle();
        }
        return null;
    }

    @Override
    public void dealDamageWithSource(Player player, float amount, Object nmsDamageSource) {
        if (nmsDamageSource instanceof DamageSource damageSource) {
            getServerPlayer(player).hurt(damageSource, amount);
        } else {
            ServerPlayer sp = getServerPlayer(player);
            sp.hurt(sp.damageSources().generic(), amount);
        }
    }

    @Override
    public long getCurrentTick() {
        return net.minecraft.server.MinecraftServer.getServer().getTickCount();
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
        int patch = parseGroupOrDefault(matcher, 3, 0);
        return major == SUPPORTED_MAJOR && minor == SUPPORTED_MINOR && patch <= SUPPORTED_MAX_PATCH;
    }

    private static int parseGroup(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static int parseGroupOrDefault(Matcher matcher, int group, int fallback) {
        String value = matcher.group(group);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static boolean hasRequiredRuntimeSymbols() {
        try {
            Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            ServerPlayer.class.getMethod("getItemBySlot", EquipmentSlot.class);
            ServerPlayer.class.getMethod("setHealth", float.class);
            ServerPlayer.class.getMethod("removeAllEffects");
            ServerPlayer.class.getMethod("clearFire");
            ServerPlayer.class.getMethod("addEffect", MobEffectInstance.class);
            return true;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }
}