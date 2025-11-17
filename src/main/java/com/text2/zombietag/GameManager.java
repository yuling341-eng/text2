package com.text2.zombietag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class GameManager implements Listener {
    private final ZombieTagPlugin plugin;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<UUID, TimedMarker> stunnedPlayers = new HashMap<>();
    private final Map<UUID, TimedMarker> infections = new HashMap<>();
    private final Map<UUID, TimedMarker> pendingRespawns = new HashMap<>();
    private BossBar bossBar;
    private Scoreboard scoreboard;
    private Team survivorTeam;
    private Team zombieTeam;
    private Objective sidebar;
    private final List<String> sidebarEntries = new ArrayList<>();
    private BukkitTask pulseTask;
    private BukkitTask trackerTask;
    private BukkitTask growthTask;
    private BukkitTask hostReleaseTask;
    private boolean running;
    private long startTime;
    private int growthLevel;
    private Location zeroZero;
    private final Random random = new Random();

    public GameManager(ZombieTagPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public void startGame(CommandSender sender) {
        if (running) {
            sender.sendMessage(Component.text("게임이 이미 진행 중입니다.", NamedTextColor.RED));
            return;
        }
        List<Player> survivalPlayers = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toCollection(ArrayList::new));
        if (survivalPlayers.size() < 2) {
            sender.sendMessage(Component.text("서바이벌 모드 플레이어가 2명 이상 필요합니다.", NamedTextColor.RED));
            return;
        }

        resetState();
        running = true;
        startTime = System.currentTimeMillis();
        World world = survivalPlayers.get(0).getWorld();
        zeroZero = new Location(world, 0.5, world.getHighestBlockYAt(0, 0) + 1.0, 0.5);
        world.getChunkAt(zeroZero).load();

        initScoreboard();
        initBossbar();

        Collections.shuffle(survivalPlayers, random);
        int hostCount = Math.max(1, (int) Math.ceil(survivalPlayers.size() * 0.2));
        List<Player> hostList = survivalPlayers.subList(0, hostCount);
        List<Player> survivorList = survivalPlayers.subList(hostCount, survivalPlayers.size());

        hostList.forEach(p -> registerPlayer(p, Role.HOST_ZOMBIE));
        survivorList.forEach(p -> registerPlayer(p, Role.SURVIVOR));

        survivorList.forEach(this::scatterSurvivor);
        hostList.forEach(this::prepareDormantHost);

        Bukkit.broadcast(Component.text("좀비 술래잡기가 시작되었습니다! 숙주좀비는 5분 뒤 깨어납니다.", NamedTextColor.GOLD));
        playSoundToAll(Sound.EVENT_RAID_HORN, 1f, 1f);

        hostReleaseTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player host : hostList) {
                activateHost(host);
            }
            Bukkit.broadcast(Component.text("숙주좀비가 각성했습니다!", NamedTextColor.DARK_RED));
            playSoundToAll(Sound.ENTITY_WITHER_SPAWN, 1f, 0.5f);
        }, 20L * 300);

        pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, 0L, 20L);
        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTracking, 0L, 40L);
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::increaseGrowth, 20L * 180, 20L * 180);
    }

    public void stopGame(String reason) {
        if (!running) {
            return;
        }
        Bukkit.broadcast(Component.text(reason, NamedTextColor.YELLOW));
        resetState();
    }

    private void resetState() {
        running = false;
        growthLevel = 0;
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
        if (trackerTask != null) {
            trackerTask.cancel();
            trackerTask = null;
        }
        if (growthTask != null) {
            growthTask.cancel();
            growthTask = null;
        }
        if (hostReleaseTask != null) {
            hostReleaseTask.cancel();
            hostReleaseTask = null;
        }
        if (bossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                bossBar.removePlayer(player);
            }
            bossBar = null;
        }
        if (scoreboard != null) {
            for (PlayerProfile profile : profiles.values()) {
                Player player = profile.getPlayer();
                if (player.isOnline()) {
                    player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setWalkSpeed(0.2f);
                    player.setFoodLevel(20);
                    player.setSaturation(20);
                    player.getInventory().remove(Material.COMPASS);
                }
            }
            scoreboard = null;
        }
        profiles.clear();
        stunnedPlayers.clear();
        infections.clear();
        pendingRespawns.clear();
        zeroZero = null;
        sidebarEntries.clear();
    }

    private void registerPlayer(Player player, Role role) {
        PlayerProfile profile = new PlayerProfile(player, role);
        profiles.put(player.getUniqueId(), profile);
        profile.applyAttributes();
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setWalkSpeed(0.2f);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().remove(Material.COMPASS);
        player.getInventory().addItem(new ItemStack(Material.COMPASS));
        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
            if (role == Role.SURVIVOR) {
                survivorTeam.addEntry(player.getName());
            } else {
                zombieTeam.addEntry(player.getName());
            }
        }
    }

    private void scatterSurvivor(Player player) {
        if (zeroZero == null) {
            return;
        }
        World world = zeroZero.getWorld();
        Location target = findRandomLocation(world, zeroZero, 1000);
        player.teleport(target);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 10, 3, true, false));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
    }

    private void prepareDormantHost(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(zeroZero.clone().add(0, 200, 0));
        player.showTitle(Title.title(Component.text("대기 중", NamedTextColor.GRAY), Component.text("곧 각성합니다", NamedTextColor.DARK_GRAY)));
    }

    private void activateHost(Player player) {
        if (!player.isOnline() || !running) {
            return;
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(zeroZero);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 20 * 120, 1, true, false));
        player.showTitle(Title.title(Component.text("사냥 시작!", NamedTextColor.DARK_RED), Component.text("생존자를 추적하세요", NamedTextColor.RED)));
    }

    private void initBossbar() {
        bossBar = Bukkit.createBossBar("감염 진행도", BarColor.RED, BarStyle.SOLID);
        bossBar.setVisible(true);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    private void initScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = scoreboard.registerNewObjective("zt", "dummy", ChatColor.DARK_GREEN + "Zombie Tag", RenderType.INTEGER);
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        survivorTeam = scoreboard.registerNewTeam("survivors");
        survivorTeam.setAllowFriendlyFire(false);
        survivorTeam.color(org.bukkit.ChatColor.GREEN);
        zombieTeam = scoreboard.registerNewTeam("zombies");
        zombieTeam.setAllowFriendlyFire(false);
        zombieTeam.color(org.bukkit.ChatColor.RED);
    }

    private Location findRandomLocation(World world, Location center, int radius) {
        int attempts = 0;
        while (attempts++ < 32) {
            int x = center.getBlockX() + random.nextInt(radius * 2) - radius;
            int z = center.getBlockZ() + random.nextInt(radius * 2) - radius;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            Material type = world.getBlockAt(loc).getType();
            if (type != Material.WATER && type != Material.LAVA) {
                return loc;
            }
        }
        return center;
    }

    private void pulse() {
        if (!running) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        updateSidebar(elapsed);
        updateBossbar();
        sendActionbar();
        checkTimedEffects();
        checkWinConditions();
    }

    private void updateSidebar(long elapsed) {
        if (sidebar == null) {
            return;
        }
        for (String entry : sidebarEntries) {
            scoreboard.resetScores(entry);
        }
        sidebarEntries.clear();
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GREEN + "생존자: " + ChatColor.WHITE + countRole(Role.SURVIVOR));
        lines.add(ChatColor.RED + "좀비: " + ChatColor.WHITE + countZombies());
        lines.add(ChatColor.YELLOW + "경과: " + ChatColor.WHITE + formatDuration(elapsed));
        lines.add(ChatColor.GOLD + "증강: " + ChatColor.WHITE + growthLevel);
        for (int i = 0; i < lines.size(); i++) {
            String entry = lines.get(i) + ChatColor.values()[i];
            sidebar.getScore(entry).setScore(lines.size() - i);
            sidebarEntries.add(entry);
        }
    }

    private void updateBossbar() {
        if (bossBar == null) {
            return;
        }
        int total = profiles.size();
        int infected = countZombies();
        double progress = total == 0 ? 0.0 : infected / (double) total;
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        bossBar.setTitle("감염 진행도 " + (int) Math.round(progress * 100) + "%");
    }

    private void sendActionbar() {
        int survivorCount = countRole(Role.SURVIVOR);
        int zombieCount = countZombies();
        Component component = Component.text("생존자 " + survivorCount + "명 | 좀비 " + zombieCount + "명", NamedTextColor.AQUA);
        for (PlayerProfile profile : profiles.values()) {
            Player player = profile.getPlayer();
            if (player.isOnline()) {
                player.sendActionBar(component);
            }
        }
    }

    private void checkTimedEffects() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, TimedMarker>> iterator = stunnedPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedMarker> entry = iterator.next();
            if (entry.getValue().releaseTime() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
                    player.removePotionEffect(PotionEffectType.WATER_BREATHING);
                    player.setWalkSpeed(0.2f);
                    player.sendMessage(ChatColor.GREEN + "기절에서 회복되었습니다!");
                }
                iterator.remove();
            }
        }
        iterator = infections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedMarker> entry = iterator.next();
            if (entry.getValue().releaseTime() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                    convertToZombie(player, entry.getValue().location());
                }
                iterator.remove();
            }
        }
        iterator = pendingRespawns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedMarker> entry = iterator.next();
            if (entry.getValue().releaseTime() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.teleport(entry.getValue().location());
                    player.setGameMode(GameMode.SURVIVAL);
                    scatterZombiesOnRespawn(player);
                }
                iterator.remove();
            }
        }
    }

    private void increaseGrowth() {
        if (!running) {
            return;
        }
        growthLevel++;
        for (PlayerProfile profile : profiles.values()) {
            if (profile.getRole().isZombie()) {
                Player player = profile.getPlayer();
                if (!player.isOnline()) {
                    continue;
                }
                double baseSpeed = profile.getRole() == Role.HOST_ZOMBIE ? 0.125 : 0.11;
                double boosted = baseSpeed * (1 + growthLevel * 0.05);
                AttributeInstance movement = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                if (movement != null) {
                    movement.setBaseValue(boosted);
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 20 * 120, Math.max(0, growthLevel / 2), true, false));
                player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.7f + 0.1f * growthLevel);
            }
        }
        Bukkit.broadcast(Component.text("좀비가 더욱 강해졌습니다! (단계 " + growthLevel + ")", NamedTextColor.DARK_RED));
    }

    private void scatterZombiesOnRespawn(Player player) {
        if (zeroZero != null) {
            player.teleport(zeroZero);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 5, 2, true, false));
    }

    private int countRole(Role role) {
        int count = 0;
        for (PlayerProfile profile : profiles.values()) {
            if (profile.getRole() == role) {
                count++;
            }
        }
        return count;
    }

    private int countZombies() {
        int count = 0;
        for (PlayerProfile profile : profiles.values()) {
            if (profile.getRole().isZombie()) {
                count++;
            }
        }
        return count;
    }

    private void playSoundToAll(Sound sound, float volume, float pitch) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private String formatDuration(long elapsed) {
        Duration duration = Duration.ofMillis(elapsed);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        return String.format(Locale.KOREA, "%02d:%02d", minutes, seconds);
    }

    private void updateTracking() {
        if (!running) {
            return;
        }
        List<Player> survivors = profiles.values().stream()
                .filter(p -> p.getRole() == Role.SURVIVOR)
                .map(PlayerProfile::getPlayer)
                .filter(Player::isOnline)
                .toList();
        if (survivors.isEmpty()) {
            return;
        }
        for (PlayerProfile profile : profiles.values()) {
            if (!profile.getRole().isZombie()) {
                continue;
            }
            Player zombie = profile.getPlayer();
            if (!zombie.isOnline()) {
                continue;
            }
            Player nearest = survivors.stream()
                    .filter(survivor -> survivor.getWorld().equals(zombie.getWorld()))
                    .min((a, b) -> Double.compare(a.getLocation().distanceSquared(zombie.getLocation()),
                            b.getLocation().distanceSquared(zombie.getLocation())))
                    .orElse(null);
            if (nearest != null) {
                zombie.setCompassTarget(nearest.getLocation());
                zombie.sendActionBar(Component.text("추적 대상: " + nearest.getName() + " (" + (int) zombie.getLocation().distance(nearest.getLocation()) + "m)", NamedTextColor.RED));
            }
        }
    }

    private void convertToZombie(Player player, Location spawnLocation) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        profile.setRole(Role.ZOMBIE);
        profile.applyAttributes();
        zombieTeam.addEntry(player.getName());
        survivorTeam.removeEntry(player.getName());
        Location target = spawnLocation != null ? spawnLocation : zeroZero;
        if (target == null) {
            target = player.getWorld().getSpawnLocation();
        }
        player.teleport(target);
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 3, 1, true, false));
        player.setGameMode(GameMode.SURVIVAL);
        player.showTitle(Title.title(Component.text("감염!", NamedTextColor.RED), Component.text("좀비 진영으로 합류", NamedTextColor.DARK_RED)));
        Bukkit.broadcast(Component.text(player.getName() + " 님이 감염되었습니다!", NamedTextColor.DARK_RED));
        checkWinConditions();
    }

    private void checkWinConditions() {
        if (!running) {
            return;
        }
        if (countRole(Role.SURVIVOR) == 0) {
            stopGame("모든 생존자가 감염되었습니다. 좀비의 승리!");
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Monster) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        PlayerProfile victimProfile = profiles.get(victim.getUniqueId());
        PlayerProfile attackerProfile = profiles.get(attacker.getUniqueId());
        if (victimProfile == null || attackerProfile == null) {
            return;
        }
        if ((victimProfile.getRole() == Role.SURVIVOR && attackerProfile.getRole() == Role.SURVIVOR) ||
                (victimProfile.getRole().isZombie() && attackerProfile.getRole().isZombie())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDeathMessage(null);
        if (profile.getRole() == Role.SURVIVOR) {
            Player killer = player.getKiller();
            if (killer != null) {
                PlayerProfile killerProfile = profiles.get(killer.getUniqueId());
                if (killerProfile != null && killerProfile.getRole().isZombie()) {
                    infections.put(player.getUniqueId(), new TimedMarker(player.getLocation(), System.currentTimeMillis() + 60_000));
                    player.sendMessage(ChatColor.RED + "곧 감염됩니다. 60초 후 좀비로 부활합니다.");
                    player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.6f);
                } else {
                    applyStun(player);
                }
            } else {
                applyStun(player);
            }
        } else {
            pendingRespawns.put(player.getUniqueId(), new TimedMarker(zeroZero, System.currentTimeMillis() + 15_000));
        }
    }

    private void applyStun(Player player) {
        stunnedPlayers.put(player.getUniqueId(), new TimedMarker(player.getLocation(), System.currentTimeMillis() + 60_000));
        player.sendMessage(ChatColor.YELLOW + "자연사로 인해 1분간 기절합니다.");
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        TimedMarker infection = infections.get(player.getUniqueId());
        TimedMarker stun = stunnedPlayers.get(player.getUniqueId());
        TimedMarker respawn = pendingRespawns.get(player.getUniqueId());
        if (infection != null) {
            event.setRespawnLocation(infection.location());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 60, 0, true, false));
            });
        } else if (stun != null) {
            event.setRespawnLocation(stun.location());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setWalkSpeed(0f);
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 75, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 20 * 75, 0, true, false));
            });
        } else if (respawn != null) {
            event.setRespawnLocation(respawn.location());
            Bukkit.getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
        } else if (zeroZero != null) {
            event.setRespawnLocation(zeroZero);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running || !event.hasChangedPosition()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        TimedMarker stun = stunnedPlayers.get(uuid);
        if (stun != null && stun.releaseTime() > System.currentTimeMillis()) {
            event.setTo(event.getFrom());
            return;
        }
        TimedMarker infection = infections.get(uuid);
        if (infection != null && infection.releaseTime() > System.currentTimeMillis()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!running) {
            return;
        }
        profiles.remove(event.getPlayer().getUniqueId());
        checkWinConditions();
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof EnderDragon && running) {
            stopGame("엔더드래곤이 처치되었습니다! 생존자의 승리!");
        }
    }

    private record TimedMarker(Location location, long releaseTime) {
    }
}
