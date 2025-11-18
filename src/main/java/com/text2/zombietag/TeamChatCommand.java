package com.text2.zombietag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TeamChatCommand implements CommandExecutor {
    private final GameManager gameManager;

    public TeamChatCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("플레이어만 사용할 수 있는 명령어입니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("사용법: /" + label + " <메시지>", NamedTextColor.YELLOW));
            return true;
        }
        if (!gameManager.isRunning()) {
            player.sendMessage(Component.text("게임이 진행 중이 아닙니다.", NamedTextColor.RED));
            return true;
        }
        PlayerProfile profile = gameManager.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(Component.text("관전자는 팀 채팅을 보낼 수 없습니다.", NamedTextColor.RED));
            return true;
        }
        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            player.sendMessage(Component.text("보낼 메시지를 입력해주세요.", NamedTextColor.RED));
            return true;
        }
        gameManager.sendTeamChat(player, profile.getRole(), message);
        return true;
    }
}
