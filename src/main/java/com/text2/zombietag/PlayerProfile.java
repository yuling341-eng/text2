package com.text2.zombietag;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public class PlayerProfile {
    private final Player player;
    private Role role;

    public PlayerProfile(Player player, Role role) {
        this.player = player;
        this.role = role;
    }

    public Player getPlayer() {
        return player;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void applyAttributes() {
        if (!player.isOnline()) {
            return;
        }
        switch (role) {
            case SURVIVOR -> apply(20.0, 0.1);
            case HOST_ZOMBIE -> apply(40.0, 0.125);
            case ZOMBIE -> apply(10.0, 0.11);
        }
    }

    private void apply(double health, double speed) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
        }
        player.setHealth(Math.min(player.getHealth(), health));
        AttributeInstance movement = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(speed);
        }
    }
}
