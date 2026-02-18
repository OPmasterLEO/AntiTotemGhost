package net.opmasterleo.masterantighost.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

public interface NmsAccessor {

    boolean hasTotemInOffhand(Player player);

    boolean hasTotemInEitherHand(Player player);

    boolean consumeOffhandTotemIfPresent(Player player);

    boolean consumeTotemFromEitherHandIfPresent(Player player);

    void consumeOneTotemFromOffhand(Player player);

    void setHealthNms(Player player, float health);

    void removeAllEffectsNms(Player player);

    void applyTotemEffectsNms(Player player);

    void extinguishFireNms(Player player);

    void broadcastTotemPopAnimation(Player player);

    Object captureDamageSource(EntityDamageEvent event);

    void dealDamageWithSource(Player player, float amount, Object nmsDamageSource);

    long getCurrentTick();

    boolean isAvailable();

    String getVersionTag();
}
