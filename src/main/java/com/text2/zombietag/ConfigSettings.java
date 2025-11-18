package com.text2.zombietag;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConfigSettings {
    public record RoleStats(double maxHealth, double movementSpeed, double attackDamage) {
    }

    public record GrowthSettings(long firstDelayMillis,
                                 long intervalMillis,
                                 double speedPercentPerLevel,
                                 double attackDamagePerLevel,
                                 int strengthDurationSeconds,
                                 int strengthAmplifierStep,
                                 List<Long> reminderWindowsMillis) {
        public double speedMultiplier(int level) {
            return 1.0 + (speedPercentPerLevel / 100.0) * level;
        }

        public double attackBonus(int level) {
            return attackDamagePerLevel * level;
        }
    }

    public record BuffSettings(int scatterResistanceSeconds,
                               int scatterResistanceAmplifier,
                               int stunFireResistanceSeconds,
                               int stunWaterBreathingSeconds,
                               int zombieRecoveryResistanceAmplifier,
                               int spawnFireResistanceSeconds,
                               int spawnWaterBreathingSeconds) {
    }

    public record Timers(long stunMillis, long infectionMillis, long zombieRecoveryMillis, long rejoinGraceMillis) {
    }

    public record TrackingSettings(int intervalTicks) {
    }

    private final double hostRatio;
    private final int scatterRadius;
    private final long hostReleaseDelayMillis;
    private final Timers timers;
    private final BuffSettings buffSettings;
    private final TrackingSettings trackingSettings;
    private final GrowthSettings growthSettings;
    private final Map<Role, RoleStats> roleStats = new EnumMap<>(Role.class);

    public ConfigSettings(FileConfiguration config) {
        this.hostRatio = Math.max(0.05, Math.min(1.0, config.getDouble("gameplay.host-ratio", 0.2)));
        this.scatterRadius = Math.max(1, config.getInt("gameplay.scatter-radius", 1000));
        this.hostReleaseDelayMillis = Math.max(1L, config.getLong("gameplay.host-release-delay-seconds", 300L) * 1000L);
        this.timers = new Timers(
                config.getLong("timers.stun-seconds", 60L) * 1000L,
                config.getLong("timers.infection-seconds", 60L) * 1000L,
                config.getLong("timers.zombie-recovery-seconds", 60L) * 1000L,
                config.getLong("timers.rejoin-grace-seconds", 60L) * 1000L
        );
        this.buffSettings = new BuffSettings(
                config.getInt("protection.scatter-resistance-seconds", 10),
                config.getInt("protection.scatter-resistance-amplifier", 3),
                config.getInt("protection.stun-fire-resistance-seconds", 75),
                config.getInt("protection.stun-water-breathing-seconds", 75),
                config.getInt("protection.zombie-recovery-resistance-amplifier", 3),
                config.getInt("protection.spawn-fire-resistance-seconds", 30),
                config.getInt("protection.spawn-water-breathing-seconds", 30)
        );
        this.trackingSettings = new TrackingSettings(Math.max(1, config.getInt("tracking.interval-ticks", 10)));
        this.growthSettings = new GrowthSettings(
                Math.max(1000L, config.getLong("growth.first-delay-minutes", 30L) * 60_000L),
                Math.max(1000L, config.getLong("growth.interval-minutes", 15L) * 60_000L),
                config.getDouble("growth.speed-percent-per-level", 5.0),
                config.getDouble("growth.attack-damage-per-level", 0.5),
                config.getInt("growth.strength-duration-seconds", 120),
                Math.max(1, config.getInt("growth.strength-amplifier-step", 2)),
                readReminderWindows(config)
        );
        roleStats.put(Role.SURVIVOR, readRole(config, "roles.survivor", 20.0, 0.1, 4.0));
        roleStats.put(Role.HOST_ZOMBIE, readRole(config, "roles.host-zombie", 40.0, 0.125, 4.0));
        roleStats.put(Role.ZOMBIE, readRole(config, "roles.zombie", 10.0, 0.11, 2.0));
    }

    private RoleStats readRole(FileConfiguration config, String path, double defaultHealth, double defaultSpeed, double defaultAttack) {
        double health = config.getDouble(path + ".max-health", defaultHealth);
        double speed = config.getDouble(path + ".movement-speed", defaultSpeed);
        double attack = config.getDouble(path + ".attack-damage", defaultAttack);
        return new RoleStats(health, speed, attack);
    }

    private List<Long> readReminderWindows(FileConfiguration config) {
        List<Long> seconds = config.getLongList("growth.reminder-seconds");
        if (seconds.isEmpty()) {
            seconds = List.of(600L, 300L, 60L, 30L, 10L);
        }
        List<Long> millis = new ArrayList<>();
        for (Long second : seconds) {
            long value = Math.max(1L, second) * 1000L;
            millis.add(value);
        }
        return millis;
    }

    public double getHostRatio() {
        return hostRatio;
    }

    public int getScatterRadius() {
        return scatterRadius;
    }

    public long getHostReleaseDelayMillis() {
        return hostReleaseDelayMillis;
    }

    public Timers getTimers() {
        return timers;
    }

    public BuffSettings getBuffSettings() {
        return buffSettings;
    }

    public TrackingSettings getTrackingSettings() {
        return trackingSettings;
    }

    public GrowthSettings getGrowthSettings() {
        return growthSettings;
    }

    public RoleStats getRoleStats(Role role) {
        return roleStats.get(role);
    }
}
