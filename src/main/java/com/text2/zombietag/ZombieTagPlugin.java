package com.text2.zombietag;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ZombieTagPlugin extends JavaPlugin {
    private GameManager gameManager;
    private ConfigSettings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new ConfigSettings(getConfig());
        this.gameManager = new GameManager(this, settings);
        getServer().getPluginManager().registerEvents(gameManager, this);
        PluginCommand command = getCommand("zombietag");
        if (command != null) {
            ZombieTagCommand executor = new ZombieTagCommand(this, gameManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand teamChat = getCommand("c");
        if (teamChat != null) {
            teamChat.setExecutor(new TeamChatCommand(gameManager));
        }
        getLogger().info("ZombieTag plugin enabled");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stopGame("게임이 종료되었습니다.");
        }
        getLogger().info("ZombieTag plugin disabled");
    }

    public void reloadPluginSettings() {
        reloadConfig();
        settings = new ConfigSettings(getConfig());
        if (gameManager != null) {
            gameManager.updateSettings(settings);
        }
    }

    public ConfigSettings getSettings() {
        return settings;
    }
}
