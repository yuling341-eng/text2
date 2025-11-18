package com.text2.zombietag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ZombieTagCommand implements CommandExecutor, TabCompleter {
    private final ZombieTagPlugin plugin;
    private final GameManager gameManager;

    public ZombieTagCommand(ZombieTagPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("사용법: /" + label + " <start|stop|status|reload>", NamedTextColor.YELLOW));
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "start" -> {
                if (!sender.hasPermission("zombietag.admin")) {
                    sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
                    return true;
                }
                gameManager.startGame(sender);
            }
            case "stop" -> {
                if (!sender.hasPermission("zombietag.admin")) {
                    sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
                    return true;
                }
                gameManager.stopGame("관리자가 게임을 중지했습니다.");
            }
            case "status" -> sender.sendMessage(Component.text(gameManager.isRunning() ? "진행 중" : "대기 중", NamedTextColor.AQUA));
            case "reload" -> {
                if (!sender.hasPermission("zombietag.admin")) {
                    sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
                    return true;
                }
                plugin.reloadPluginSettings();
                sender.sendMessage(Component.text("구성을 다시 불러왔습니다.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("알 수 없는 하위 명령입니다.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = Arrays.asList("start", "stop", "status", "reload");
            List<String> result = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(args[0].toLowerCase())) {
                    result.add(option);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
