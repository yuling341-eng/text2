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

    public void applyAttributes() {
        if (player == null || !player.isOnline()) {
            return;
        }
        switch (role) {
            case SURVIVOR -> apply(20.0, 0.1);
            case HOST_ZOMBIE -> apply(40.0, 0.125);
            case ZOMBIE -> apply(10.0, 0.11);
        }
    }

    private void apply(double health, double speed) {
        if (player == null) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
        }
        player.setHealth(health);
        AttributeInstance movement = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(speed);
        }
    }
}
