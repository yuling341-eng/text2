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
import org.bukkit.entity.Blaze;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class GameManager implements Listener {
    private final ZombieTagPlugin plugin;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<UUID, TimedMarker> stunnedPlayers = new HashMap<>();
    private final Map<UUID, TimedMarker> infections = new HashMap<>();
    private final Map<UUID, TimedMarker> zombieRecoveries = new HashMap<>();
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
    private final Map<UUID, TimedMarker> dormantHosts = new HashMap<>();
    private final Map<UUID, TimedMarker> rejoinGrace = new HashMap<>();
    private final Map<UUID, UUID> lastTrackedTargets = new HashMap<>();
    private long nextGrowthTimestamp;
    private static final long FIRST_GROWTH_DELAY = Duration.ofMinutes(30).toMillis();
    private static final long GROWTH_INTERVAL = Duration.ofMinutes(15).toMillis();
    private static final long[] GROWTH_REMINDER_WINDOWS = {
            Duration.ofMinutes(10).toMillis(),
            Duration.ofMinutes(5).toMillis(),
            Duration.ofMinutes(1).toMillis(),
            Duration.ofSeconds(30).toMillis(),
            Duration.ofSeconds(10).toMillis()
    };
    private final Set<Long> firedGrowthReminders = new HashSet<>();

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
        nextGrowthTimestamp = startTime + FIRST_GROWTH_DELAY;
        firedGrowthReminders.clear();
        rejoinGrace.clear();
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

        Bukkit.broadcast(Component.text("⚔️ 좀비 사냥전 개시! 숙주 좀비는 5분 뒤 각성하니 신속히 거점을 잡으세요.", NamedTextColor.GOLD));
        playSoundToAll(Sound.EVENT_RAID_HORN, 1f, 1f);

        hostReleaseTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player host : hostList) {
                activateHost(host);
            }
            Bukkit.broadcast(Component.text("👑 숙주 좀비 군단이 눈을 떴습니다! 생존자 위치 추적이 시작됩니다.", NamedTextColor.DARK_RED));
            playSoundToAll(Sound.ENTITY_WITHER_SPAWN, 1f, 0.5f);
        }, 20L * 300);

        pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, 0L, 20L);
        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTracking, 0L, 10L);
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::increaseGrowth, 20L * 60 * 30, 20L * 60 * 15);
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
        nextGrowthTimestamp = 0L;
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
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (bossBar != null) {
                bossBar.removePlayer(online);
            }
            online.setScoreboard(mainBoard);
            online.setGameMode(GameMode.SURVIVAL);
            unlockMovement(online);
            online.setInvulnerable(false);
            online.setFoodLevel(20);
            online.setSaturation(20);
            online.getInventory().remove(Material.COMPASS);
        }
        bossBar = null;
        scoreboard = null;
        profiles.clear();
        stunnedPlayers.clear();
        infections.clear();
        zombieRecoveries.clear();
        dormantHosts.clear();
        rejoinGrace.clear();
        firedGrowthReminders.clear();
        lastTrackedTargets.clear();
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
        attachHud(player);
        syncTeams(profile);
        if (role.isZombie()) {
            player.getInventory().addItem(new ItemStack(Material.COMPASS));
        }
    }

    private void scatterSurvivor(Player player) {
        if (zeroZero == null) {
            return;
        }
        World world = zeroZero.getWorld();
        Location target = findRandomLocation(world, zeroZero, 1000);
        player.teleport(target);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 10, 3, true, false));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
    }

    private void prepareDormantHost(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(zeroZero.clone().add(0, 200, 0));
        dormantHosts.put(player.getUniqueId(), new TimedMarker(zeroZero, System.currentTimeMillis() + 300_000));
        lockMovement(player, 20 * 60 * 5);
        player.showTitle(Title.title(Component.text("대기 중", NamedTextColor.GRAY), Component.text("곧 각성합니다", NamedTextColor.DARK_GRAY)));
        player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.6f);
    }

    private void activateHost(Player player) {
        if (!running || player == null) {
            return;
        }
        dormantHosts.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(zeroZero);
        unlockMovement(player);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 120, 1, true, false));
        player.showTitle(Title.title(Component.text("사냥 시작!", NamedTextColor.DARK_RED), Component.text("생존자를 추적하세요", NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.5f);
    }

    private void initBossbar() {
        bossBar = Bukkit.createBossBar("감염률(숙주 제외)", BarColor.GREEN, BarStyle.SEGMENTED_12);
        bossBar.setVisible(true);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    private void initScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = scoreboard.registerNewObjective(
                "zt",
                "dummy",
                Component.text("Zombie Tag", NamedTextColor.DARK_GREEN),
                RenderType.INTEGER
        );
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        survivorTeam = scoreboard.registerNewTeam("survivors");
        survivorTeam.setAllowFriendlyFire(false);
        survivorTeam.color(NamedTextColor.GREEN);
        zombieTeam = scoreboard.registerNewTeam("zombies");
        zombieTeam.setAllowFriendlyFire(false);
        zombieTeam.color(NamedTextColor.RED);
    }

    private void attachHud(Player player) {
        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
        }
        if (bossBar != null && !bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
    }

    private void syncTeams(PlayerProfile profile) {
        if (scoreboard == null) {
            return;
        }
        String name = profile.getLastKnownName();
        survivorTeam.removeEntry(name);
        zombieTeam.removeEntry(name);
        if (profile.getRole() == Role.SURVIVOR) {
            survivorTeam.addEntry(name);
        } else {
            zombieTeam.addEntry(name);
        }
    }

    private void removeFromTeams(PlayerProfile profile) {
        if (scoreboard == null) {
            return;
        }
        String name = profile.getLastKnownName();
        survivorTeam.removeEntry(name);
        zombieTeam.removeEntry(name);
    }

    private void enableSpectator(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        player.getInventory().remove(Material.COMPASS);
        attachHud(player);
        player.showTitle(Title.title(Component.text("관전 모드", NamedTextColor.GRAY), Component.text("게임이 진행 중입니다", NamedTextColor.DARK_GRAY)));
        player.sendMessage(ChatColor.GRAY + "현재 라운드가 이미 진행 중이므로 관전자로 입장했습니다.");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
    }

    private void restoreReturningPlayer(Player player, PlayerProfile profile, Location returnPoint) {
        profile.refreshPlayer(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setWalkSpeed(0.2f);
        attachHud(player);
        syncTeams(profile);
        profile.applyAttributes();
        player.getInventory().remove(Material.COMPASS);
        if (profile.getRole().isZombie()) {
            player.getInventory().addItem(new ItemStack(Material.COMPASS));
        }
        UUID uuid = player.getUniqueId();
        Location fallback = returnPoint;
        if (infections.containsKey(uuid)) {
            fallback = infections.get(uuid).location();
        } else if (stunnedPlayers.containsKey(uuid)) {
            fallback = stunnedPlayers.get(uuid).location();
        } else if (zombieRecoveries.containsKey(uuid)) {
            fallback = zombieRecoveries.get(uuid).location();
        } else if (dormantHosts.containsKey(uuid)) {
            fallback = dormantHosts.get(uuid).location();
        }
        if (profile.getRole() == Role.HOST_ZOMBIE && !dormantHosts.containsKey(uuid)) {
            fallback = zeroZero;
        }
        if (fallback == null) {
            fallback = zeroZero;
        }
        if (fallback == null) {
            fallback = player.getWorld().getSpawnLocation();
        }
        player.teleport(fallback);
        reapplyPendingState(player);
        player.sendMessage(ChatColor.GREEN + "복귀 유예 내에 돌아왔습니다. 전장으로 복귀합니다!");
        player.showTitle(Title.title(Component.text("복귀 완료", NamedTextColor.GREEN), Component.text("행운을 빕니다", NamedTextColor.WHITE)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
    }

    private void reapplyPendingState(Player player) {
        UUID uuid = player.getUniqueId();
        TimedMarker dormancy = dormantHosts.get(uuid);
        if (dormancy != null && dormancy.releaseTime() > System.currentTimeMillis()) {
            lockMovement(player, ticksUntilRelease(dormancy));
            player.showTitle(Title.title(Component.text("각성 대기", NamedTextColor.GRAY), Component.text("숙주좀비 준비 중", NamedTextColor.DARK_GRAY)));
            return;
        }
        TimedMarker infection = infections.get(uuid);
        if (infection != null && infection.releaseTime() > System.currentTimeMillis()) {
            int ticks = ticksUntilRelease(infection);
            lockMovement(player, ticks);
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 1, true, false));
            player.showTitle(Title.title(Component.text("감염 중...", NamedTextColor.DARK_RED), Component.text("곧 좀비가 됩니다", NamedTextColor.RED)));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f);
            return;
        }
        TimedMarker stun = stunnedPlayers.get(uuid);
        if (stun != null && stun.releaseTime() > System.currentTimeMillis()) {
            int ticks = ticksUntilRelease(stun);
            lockMovement(player, ticks);
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 75, 1, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 20 * 75, 0, true, false));
            player.showTitle(Title.title(Component.text("기절 상태", NamedTextColor.GOLD), Component.text("1분 후 복귀", NamedTextColor.YELLOW)));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.8f);
            return;
        }
        TimedMarker zombieDown = zombieRecoveries.get(uuid);
        if (zombieDown != null && zombieDown.releaseTime() > System.currentTimeMillis()) {
            int ticks = ticksUntilRelease(zombieDown);
            lockMovement(player, ticks);
            setZombieShield(player, true);
            player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, ticks, 3, true, false));
            player.showTitle(Title.title(Component.text("재정비 중", NamedTextColor.DARK_RED), Component.text("1분 뒤 다시 일어납니다", NamedTextColor.RED)));
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 0.7f);
            return;
        }
        unlockMovement(player);
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
        sendActionbar(elapsed);
        checkTimedEffects();
        checkGrowthReminders();
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
        int survivors = countRole(Role.SURVIVOR);
        int hosts = countRole(Role.HOST_ZOMBIE);
        int infected = countRole(Role.ZOMBIE);
        int totalInfecting = survivors + infected;
        double progress = totalInfecting == 0 ? 0.0 : infected / (double) totalInfecting;
        String ratioText = String.format(Locale.KOREA, "%02d%%", (int) Math.round(progress * 100));
        long nextGrowth = getNextGrowthCountdown();
        long activeGrace = rejoinGrace.values().stream().filter(marker -> marker.releaseTime() > System.currentTimeMillis()).count();
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.DARK_GREEN + "┏━ 전황 브리핑 ━┓");
        lines.add(ChatColor.YELLOW + " ⏱ 경과 " + ChatColor.WHITE + formatDuration(elapsed));
        lines.add(ChatColor.GOLD + " ⚡ 증강 Lv." + growthLevel);
        lines.add(ChatColor.LIGHT_PURPLE + " ➜ 다음 증강 " + ChatColor.WHITE + formatDuration(nextGrowth));
        lines.add(ChatColor.BLACK + " ");
        lines.add(ChatColor.GREEN + " 🛡 생존자 " + ChatColor.WHITE + survivors + "명");
        lines.add(ChatColor.RED + " 🧟 감염자 " + ChatColor.WHITE + infected + "명");
        lines.add(ChatColor.DARK_RED + " 👑 숙주 " + ChatColor.WHITE + hosts + "명");
        lines.add(ChatColor.LIGHT_PURPLE + " ☣ 감염률 " + ChatColor.WHITE + ratioText + ChatColor.GRAY + " (숙주 제외)");
        lines.add(ChatColor.BLUE + " 💉 감염 대기 " + ChatColor.WHITE + infections.size() + "명");
        lines.add(ChatColor.DARK_AQUA + " 💤 기절/재정비 " + ChatColor.WHITE + (stunnedPlayers.size() + zombieRecoveries.size()) + "명");
        lines.add(ChatColor.AQUA + " 🔁 복귀 유예 " + ChatColor.WHITE + activeGrace + "명");
        lines.add(ChatColor.GRAY + " 🌙 숙주 대기 " + ChatColor.WHITE + dormantHosts.size() + "명");
        lines.add(ChatColor.DARK_GREEN + "┗━━━━━━━━━━━━┛");
        for (int i = 0; i < lines.size(); i++) {
            ChatColor suffix = ChatColor.values()[i % ChatColor.values().length];
            String entry = lines.get(i) + suffix;
            sidebar.getScore(entry).setScore(lines.size() - i);
            sidebarEntries.add(entry);
        }
    }

    private void updateBossbar() {
        if (bossBar == null) {
            return;
        }
        int survivors = countRole(Role.SURVIVOR);
        int converted = countRole(Role.ZOMBIE);
        int denominator = survivors + converted;
        double progress = denominator == 0 ? 0.0 : converted / (double) denominator;
        double clamped = Math.min(1.0, Math.max(0.0, progress));
        bossBar.setProgress(clamped);
        BarColor color = clamped < 0.34 ? BarColor.GREEN : clamped < 0.67 ? BarColor.YELLOW : BarColor.RED;
        if (bossBar.getColor() != color) {
            bossBar.setColor(color);
        }
        bossBar.setTitle(String.format(Locale.KOREA, "감염률(숙주 제외) %d%% | 생존 %d명 / 감염 %d명", (int) Math.round(clamped * 100), survivors, converted));
    }

    private void sendActionbar(long elapsed) {
        int survivorCount = countRole(Role.SURVIVOR);
        int zombieCount = countZombies();
        String timer = formatDuration(getNextGrowthCountdown());
        String roundTimer = formatDuration(elapsed);
        Component component = Component.text(
                "🛡 생존 " + survivorCount + "명  |  🧟 위협 " + zombieCount + "명  |  ⚡ 증강 " + timer + " 후  |  ⏱ " + roundTimer,
                NamedTextColor.AQUA
        );
        for (PlayerProfile profile : profiles.values()) {
            Player player = profile.getPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            if (profile.getRole() == Role.SURVIVOR || dormantHosts.containsKey(uuid)) {
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
                    unlockMovement(player);
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
                    unlockMovement(player);
                    player.setGameMode(GameMode.SURVIVAL);
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                    player.removePotionEffect(PotionEffectType.WEAKNESS);
                }
                convertToZombie(entry.getKey(), entry.getValue().location());
                iterator.remove();
            }
        }
        iterator = zombieRecoveries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedMarker> entry = iterator.next();
            if (entry.getValue().releaseTime() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    unlockMovement(player);
                    setZombieShield(player, false);
                    player.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
                    player.sendMessage(ChatColor.DARK_RED + "재정비를 마치고 다시 움직일 수 있습니다!");
                    player.showTitle(Title.title(Component.text("재가동", NamedTextColor.DARK_RED), Component.text("본진으로 귀환하였습니다", NamedTextColor.GRAY)));
                    player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 1f, 0.9f);
                }
                iterator.remove();
            }
        }
        iterator = rejoinGrace.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedMarker> entry = iterator.next();
            if (entry.getValue().releaseTime() <= now) {
                UUID uuid = entry.getKey();
                PlayerProfile profile = profiles.remove(uuid);
                if (profile != null) {
                    removeFromTeams(profile);
                    Bukkit.broadcast(Component.text(profile.getLastKnownName() + " 님이 복귀 유예를 넘겨 탈락했습니다.", NamedTextColor.GRAY));
                }
                stunnedPlayers.remove(uuid);
                infections.remove(uuid);
                zombieRecoveries.remove(uuid);
                dormantHosts.remove(uuid);
                lastTrackedTargets.remove(uuid);
                iterator.remove();
                checkWinConditions();
            }
        }
    }

    private void increaseGrowth() {
        if (!running) {
            return;
        }
        growthLevel++;
        Bukkit.broadcast(Component.text("⚡ 좀비 증강 단계 " + growthLevel + " 발동! 이동 속도와 공격력이 폭발적으로 증가합니다.", NamedTextColor.DARK_PURPLE));
        for (PlayerProfile profile : profiles.values()) {
            Player player = profile.getPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (profile.getRole().isZombie()) {
                double baseSpeed = profile.getRole() == Role.HOST_ZOMBIE ? 0.125 : 0.11;
                double boosted = baseSpeed * (1 + growthLevel * 0.05);
                AttributeInstance movement = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                if (movement != null) {
                    movement.setBaseValue(boosted);
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 120, Math.max(0, growthLevel / 2), true, false));
                player.showTitle(
                        Title.title(Component.text("좀비 증강!", NamedTextColor.DARK_RED),
                                Component.text("단계 " + growthLevel + " 공격 속도 상승", NamedTextColor.RED))
                );
                player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.7f + 0.1f * growthLevel);
            } else {
                player.showTitle(
                        Title.title(Component.text("경고!", NamedTextColor.GOLD),
                                Component.text("좀비가 더 빠르고 강해집니다", NamedTextColor.YELLOW))
                );
                player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1f, 1.2f);
            }
        }
        nextGrowthTimestamp = startTime + FIRST_GROWTH_DELAY + (long) growthLevel * GROWTH_INTERVAL;
        firedGrowthReminders.clear();
    }

    private void checkGrowthReminders() {
        if (!running || nextGrowthTimestamp <= 0) {
            return;
        }
        long remaining = nextGrowthTimestamp - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        int upcomingLevel = growthLevel + 1;
        for (long window : GROWTH_REMINDER_WINDOWS) {
            if (remaining <= window && firedGrowthReminders.add(window)) {
                String readable = formatDuration(Math.max(remaining, 0));
                if (window >= Duration.ofMinutes(1).toMillis()) {
                    Bukkit.broadcast(Component.text("⏰ 다음 증강까지 " + readable + " 남았습니다. (예정 단계 " + upcomingLevel + ")", NamedTextColor.LIGHT_PURPLE));
                    playSoundToAll(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
                } else {
                    for (PlayerProfile profile : profiles.values()) {
                        Player player = profile.getPlayer();
                        if (player == null || !player.isOnline()) {
                            continue;
                        }
                        Component subtitle = profile.getRole().isZombie()
                                ? Component.text(readable + " 후 폭주!", NamedTextColor.RED)
                                : Component.text(readable + " 후 위기!", NamedTextColor.GOLD);
                        player.showTitle(Title.title(Component.text("증강 임박", NamedTextColor.DARK_PURPLE), subtitle));
                        Sound cue = profile.getRole().isZombie() ? Sound.ENTITY_ZOMBIE_AMBIENT : Sound.BLOCK_NOTE_BLOCK_CHIME;
                        player.playSound(player.getLocation(), cue, 1f, profile.getRole().isZombie() ? 0.7f : 1.4f);
                    }
                }
            }
        }
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

    private void setZombieShield(Player player, boolean shielded) {
        if (player != null) {
            player.setInvulnerable(shielded);
        }
    }

    private void lockMovement(Player player, int durationTicks) {
        player.setWalkSpeed(0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, 10, true, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationTicks, 250, true, false, false));
    }

    private void unlockMovement(Player player) {
        player.setWalkSpeed(0.2f);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    private int ticksUntilRelease(TimedMarker marker) {
        long diff = marker.releaseTime() - System.currentTimeMillis();
        if (diff <= 0) {
            return 20;
        }
        long ticks = diff / 50L;
        return (int) Math.max(20L, ticks);
    }

    private String formatDuration(long elapsed) {
        Duration duration = Duration.ofMillis(elapsed);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        return String.format(Locale.KOREA, "%02d:%02d", minutes, seconds);
    }

    private long getNextGrowthCountdown() {
        if (!running) {
            return 0L;
        }
        long next = startTime + FIRST_GROWTH_DELAY + (long) growthLevel * GROWTH_INTERVAL;
        long now = System.currentTimeMillis();
        return Math.max(0L, next - now);
    }

    private void updateTracking() {
        if (!running) {
            return;
        }
        List<Player> survivors = profiles.values().stream()
                .filter(p -> p.getRole() == Role.SURVIVOR)
                .map(PlayerProfile::getPlayer)
                .filter(Objects::nonNull)
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
            if (zombie == null || !zombie.isOnline()) {
                continue;
            }
            UUID uuid = zombie.getUniqueId();
            if (dormantHosts.containsKey(uuid) || zombieRecoveries.containsKey(uuid)) {
                continue;
            }
            Location zombieLocation = zombie.getLocation();
            Player nearest = survivors.stream()
                    .filter(survivor -> survivor.getWorld().equals(zombie.getWorld()))
                    .min((a, b) -> Double.compare(a.getLocation().distanceSquared(zombieLocation),
                            b.getLocation().distanceSquared(zombieLocation)))
                    .orElse(null);
            if (nearest != null) {
                UUID targetId = nearest.getUniqueId();
                Location targetLocation = nearest.getLocation();
                if (!Objects.equals(lastTrackedTargets.get(uuid), targetId)) {
                    lastTrackedTargets.put(uuid, targetId);
                    zombie.playSound(zombieLocation, Sound.UI_LOOM_TAKE_RESULT, 0.6f, 1.6f);
                    zombie.sendMessage(ChatColor.DARK_RED + "새로운 추적 대상: " + ChatColor.RED + nearest.getName());
                }
                zombie.setCompassTarget(targetLocation);
                int distance = (int) Math.round(zombieLocation.distance(targetLocation));
                zombie.sendActionBar(Component.text("📡 " + nearest.getName() + " 까지 " + distance + "m", NamedTextColor.RED));
            }
        }
    }

    private void convertToZombie(UUID uuid, Location spawnLocation) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null) {
            return;
        }
        profile.setRole(Role.ZOMBIE);
        syncTeams(profile);
        Player player = profile.getPlayer();
        Location target = spawnLocation != null ? spawnLocation : zeroZero;
        if (target == null && player != null) {
            target = player.getWorld().getSpawnLocation();
        }
        if (player != null && player.isOnline()) {
            profile.applyAttributes();
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().remove(Material.COMPASS);
            player.getInventory().addItem(new ItemStack(Material.COMPASS));
            if (target != null) {
                player.teleport(target);
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 3, 1, true, false));
            player.showTitle(Title.title(Component.text("감염!", NamedTextColor.RED), Component.text("좀비 진영으로 합류", NamedTextColor.DARK_RED)));
            player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.6f);
        }
        Bukkit.broadcast(Component.text("☣ " + profile.getLastKnownName() + " 님이 감염되어 좀비 진영에 합류했습니다!", NamedTextColor.DARK_RED));
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
        Entity entity = event.getEntity();
        if (entity instanceof Monster && !(entity instanceof Enderman) && !(entity instanceof Blaze)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        TimedMarker recovery = zombieRecoveries.get(victim.getUniqueId());
        if (recovery != null && recovery.releaseTime() > System.currentTimeMillis()) {
            event.setCancelled(true);
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
    public void onProtectedDamage(EntityDamageEvent event) {
        if (!running || !(event.getEntity() instanceof Player player)) {
            return;
        }
        TimedMarker recovery = zombieRecoveries.get(player.getUniqueId());
        if (recovery != null && recovery.releaseTime() > System.currentTimeMillis()) {
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
                    queueZombieInfection(player, profile);
                } else {
                    applyStun(player);
                }
            } else {
                applyStun(player);
            }
        } else {
            applyZombieDown(player);
        }
    }

    private void applyStun(Player player) {
        stunnedPlayers.put(player.getUniqueId(), new TimedMarker(player.getLocation(), System.currentTimeMillis() + 60_000));
        player.sendMessage(ChatColor.YELLOW + "자연사로 인해 1분간 기절합니다. 자리를 지키고 부여된 버프로 생존하세요.");
        player.showTitle(Title.title(Component.text("기절!", NamedTextColor.GOLD), Component.text("1분 동안 움직일 수 없습니다", NamedTextColor.YELLOW)));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.8f);
    }

    private void queueZombieInfection(Player player, PlayerProfile profile) {
        UUID uuid = player.getUniqueId();
        infections.put(uuid, new TimedMarker(player.getLocation(), System.currentTimeMillis() + 60_000));
        if (profile != null && profile.getRole() != Role.ZOMBIE) {
            profile.setRole(Role.ZOMBIE);
            syncTeams(profile);
            profile.applyAttributes();
        }
        player.sendMessage(ChatColor.RED + "곧 감염됩니다. 60초 후 좀비로 부활합니다.");
        player.showTitle(Title.title(Component.text("감염 진행", NamedTextColor.DARK_RED), Component.text("1분 뒤 좀비가 됩니다", NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 0.6f);
    }

    private void applyZombieDown(Player player) {
        zombieRecoveries.put(player.getUniqueId(), new TimedMarker(player.getLocation(), System.currentTimeMillis() + 60_000));
        player.sendMessage(ChatColor.DARK_RED + "좀비가 쓰러졌습니다. 1분 동안 재정비하며 피해를 받지 않습니다.");
        player.showTitle(Title.title(Component.text("재정비 시작", NamedTextColor.DARK_RED), Component.text("60초 뒤 복귀", NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 0.6f);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!running) {
            return;
        }
        Player player = event.getPlayer();
        attachHud(player);
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.refreshPlayer(player);
        }
        TimedMarker grace = rejoinGrace.remove(uuid);
        long now = System.currentTimeMillis();
        if (profile != null && grace != null && grace.releaseTime() > now) {
            restoreReturningPlayer(player, profile, grace.location());
            return;
        }
        if (profile != null) {
            removeFromTeams(profile);
            profiles.remove(uuid);
            Bukkit.broadcast(Component.text(profile.getLastKnownName() + " 님이 복귀 시간을 넘겨 관전자 전환되었습니다.", NamedTextColor.GRAY));
            stunnedPlayers.remove(uuid);
            infections.remove(uuid);
            zombieRecoveries.remove(uuid);
            dormantHosts.remove(uuid);
            lastTrackedTargets.remove(uuid);
            checkWinConditions();
        }
        enableSpectator(player);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!running) {
            return;
        }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            enableSpectator(player);
            return;
        }
        profile.refreshPlayer(player);
        attachHud(player);
        TimedMarker infection = infections.get(player.getUniqueId());
        TimedMarker stun = stunnedPlayers.get(player.getUniqueId());
        TimedMarker zombieDown = zombieRecoveries.get(player.getUniqueId());
        if (infection != null) {
            event.setRespawnLocation(infection.location());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(GameMode.SURVIVAL);
                int ticks = ticksUntilRelease(infection);
                lockMovement(player, ticks);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 1, true, false));
                player.showTitle(Title.title(Component.text("감염 중...", NamedTextColor.DARK_RED), Component.text("몸이 굳어갑니다", NamedTextColor.RED)));
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f);
            });
        } else if (stun != null) {
            event.setRespawnLocation(stun.location());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(GameMode.SURVIVAL);
                lockMovement(player, ticksUntilRelease(stun));
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 75, 1, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 20 * 75, 0, true, false));
            });
        } else if (zombieDown != null) {
            event.setRespawnLocation(zombieDown.location());
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(GameMode.SURVIVAL);
                int ticks = ticksUntilRelease(zombieDown);
                lockMovement(player, ticks);
                setZombieShield(player, true);
                player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, ticks, 3, true, false));
                player.showTitle(Title.title(Component.text("재정비 진행", NamedTextColor.DARK_RED), Component.text("곧 다시 일어납니다", NamedTextColor.RED)));
                player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 0.7f);
            });
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
            return;
        }
        TimedMarker respawn = zombieRecoveries.get(uuid);
        if (respawn != null && respawn.releaseTime() > System.currentTimeMillis()) {
            event.setTo(event.getFrom());
            return;
        }
        if (dormantHosts.containsKey(uuid)) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (bossBar != null) {
            bossBar.removePlayer(event.getPlayer());
        }
        if (!running) {
            return;
        }
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.detachPlayer();
            Location returnPoint = event.getPlayer().getLocation();
            rejoinGrace.put(uuid, new TimedMarker(returnPoint, System.currentTimeMillis() + 60_000));
            Bukkit.broadcast(Component.text(profile.getLastKnownName() + " 님이 잠시 퇴장했습니다. 60초 안에 복귀하지 않으면 탈락합니다.", NamedTextColor.GRAY));
        }
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
