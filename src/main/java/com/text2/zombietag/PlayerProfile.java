package com.text2.zombietag;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.UUID;

public class PlayerProfile {
    private Player player;
    private final UUID uniqueId;
    private String lastKnownName;
    private Role role;

    public PlayerProfile(Player player, Role role) {
        this.player = player;
        this.uniqueId = player.getUniqueId();
        this.lastKnownName = player.getName();
        this.role = role;
    }

    public Player getPlayer() {
        return player;
    }

    public void refreshPlayer(Player player) {
        this.player = player;
        this.lastKnownName = player.getName();
    }

    public void detachPlayer() {
        this.player = null;
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void applyAttributes(ConfigSettings settings) {
        if (player == null || !player.isOnline()) {
            return;
        }
        ConfigSettings.RoleStats stats = settings.getRoleStats(role);
        if (stats == null) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(stats.maxHealth());
        }
        player.setHealth(stats.maxHealth());
        AttributeInstance movement = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(stats.movementSpeed());
        }
        AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(stats.attackDamage());
        }
    }
}
