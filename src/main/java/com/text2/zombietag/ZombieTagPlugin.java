package com.text2.zombietag;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ZombieTagPlugin extends JavaPlugin {
    private GameManager gameManager;

    @Override
    public void onEnable() {
        this.gameManager = new GameManager(this);
        getServer().getPluginManager().registerEvents(gameManager, this);
        PluginCommand command = getCommand("zombietag");
        if (command != null) {
            ZombieTagCommand executor = new ZombieTagCommand(gameManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("ZombieTag plugin enabled");
    }

    @Override
    public void onDisable() {
        gameManager.stopGame("게임이 종료되었습니다.");
        getLogger().info("ZombieTag plugin disabled");
    }
}
