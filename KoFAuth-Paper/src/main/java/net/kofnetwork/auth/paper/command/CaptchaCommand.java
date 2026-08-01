package net.kofnetwork.auth.paper.command;

import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.paper.captcha.CaptchaGuiManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Команда {@code /captcha <код>} для текстовой CAPTCHA.
 *
 * <p>Нужна только типу {@link net.kofnetwork.auth.api.model.CaptchaType#TEXT_INPUT}:
 * в остальных ответ даётся кликом по ячейке, и вводить ничего не требуется.
 */
public final class CaptchaCommand implements CommandExecutor, TabCompleter {

    private final KoFAuthCore core;
    private final CaptchaGuiManager manager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public CaptchaCommand(@NotNull KoFAuthCore core, @NotNull CaptchaGuiManager manager) {
        this.core = core;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>Команда доступна только в игре."));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(miniMessage.deserialize(
                    "<yellow>Использование: <white>/captcha <код>"));
            return true;
        }

        core.captcha().findPending(player.getUniqueId()).thenAccept(found -> {
            if (found.isEmpty()) {
                player.sendMessage(miniMessage.deserialize(
                        "<yellow>У вас нет активной проверки."));
                return;
            }
            manager.submit(player, found.get().challengeId(), args[0]);
        });
        return true;
    }

    /** Подсказок нет: код известен только игроку. */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        return List.of();
    }
}
