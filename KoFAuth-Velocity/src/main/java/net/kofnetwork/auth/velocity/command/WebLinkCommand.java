package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Команда {@code /link <код>} — подтверждение привязки игрового аккаунта к сайту.
 *
 * <p><b>Почему подтверждает игрок, а не сайт.</b> Сайт не может доказать владение
 * игровым аккаунтом: всё, что у него есть, — введённый посетителем ник. Доказать
 * владение можно только в игре, где игрок уже прошёл пароль. Поэтому сайт лишь
 * показывает код, а решение принимает тот, кто находится в аккаунте. Обратный
 * порядок («введите ник на сайте, подтвердите в игре») позволил бы кому угодно
 * начать привязку к чужому нику и завалить владельца запросами на подтверждение.
 *
 * <p>Схема — device authorization grant: короткий код для человека и отдельный
 * долгий секрет для опроса состояния браузером. Разделение принципиально: код
 * виден на экране и его диктуют вслух, поэтому выдавать по нему токены доступа
 * нельзя.
 *
 * <p><b>Привязка ведётся по UUID.</b> В подтверждение попадает
 * {@link Player#getUniqueId()}, а не ник: ник в сети меняется, UUID — нет.
 */
public final class WebLinkCommand implements SimpleCommand {

    private final KoFAuthCore core;
    private final MessageService messages;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public WebLinkCommand(@NotNull KoFAuthCore core,
                          @NotNull MessageService messages,
                          @NotNull Logger logger) {
        this.core = core;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.parse("<red>Команда доступна только в игре."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length != 1) {
            player.sendMessage(messages.prefixed("web-link-usage",
                    "<yellow>Использование: <white>/link <код с сайта>"));
            player.sendMessage(miniMessage.deserialize(
                    "  <gray>Код показывается на " + panelUrl()
                            + " после нажатия «Привязать аккаунт»."));
            return;
        }

        withAuthenticated(player, account -> confirm(player, account, args[0]));
    }

    /**
     * Выполняет действие только для вошедшего игрока.
     *
     * <p>Подтверждение выдаёт браузеру полный доступ к аккаунту, поэтому право на
     * него имеет лишь тот, кто уже доказал владение паролем. Проверяется состояние
     * в Redis, а не текущий сервер: игрок мог оказаться на лобби из-за ошибки
     * маршрутизации, и его местоположение — не признак пройденной аутентификации.
     */
    private void withAuthenticated(Player player, Consumer<Account> action) {
        core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
            if (!state.isAuthenticated()) {
                player.sendMessage(messages.prefixed("not-authenticated-command",
                        "<red>Сначала войдите в аккаунт."));
                return;
            }
            core.authentication().findAccount(player.getUsername()).thenAccept(found -> {
                if (found.isEmpty()) {
                    player.sendMessage(messages.prefixed("account-not-found",
                            "<red>Аккаунт не найден."));
                    return;
                }
                action.accept(found.get());
            });
        }).exceptionally(e -> {
            logger.error("Ошибка команды /link для {}", player.getUsername(), e);
            player.sendMessage(messages.prefixed("error",
                    "<red>Внутренняя ошибка. Попробуйте позже."));
            return null;
        });
    }

    private void confirm(Player player, Account account, String code) {
        AuthContext context = AuthContext.minecraft(
                IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());

        core.links().confirmWebLink(code, player.getUniqueId(), account.id(), context)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        player.sendMessage(messages.prefixed("web-link-success",
                                "<green>Сайт привязан к вашему аккаунту. Вкладка в браузере "
                                        + "войдёт сама."));
                        return;
                    }
                    player.sendMessage(messages.prefixed("web-link-failed",
                            "<red><reason>",
                            Map.of("reason", describe(result.errorCode()))));
                })
                .exceptionally(e -> {
                    logger.error("Не удалось подтвердить привязку сайта для {}",
                            player.getUsername(), e);
                    player.sendMessage(messages.prefixed("error",
                            "<red>Внутренняя ошибка. Попробуйте позже."));
                    return null;
                });
    }

    private static String describe(String code) {
        if (code == null) {
            return "Не удалось выполнить операцию.";
        }
        return switch (code) {
            case "CODE_INVALID" -> "Код недействителен или истёк. Обновите страницу и возьмите новый.";
            case "ACCOUNT_NOT_FOUND" -> "Аккаунт не найден.";
            case "CACHE_UNAVAILABLE" -> "Привязка временно недоступна. Сообщите администрации.";
            default -> "Не удалось выполнить операцию.";
        };
    }

    private String panelUrl() {
        return core.config().getString(ConfigFile.CONFIG, "web.panel-url", "сайте сети");
    }

    /** Строка, собранная на месте, с общим префиксом сети. */
    private Component prefixed(String text) {
        return messages.prefixedRaw(text);
    }

    /**
     * Автодополнение отключено намеренно.
     *
     * <p>Единственный аргумент — одноразовый код, дающий доступ к аккаунту.
     * Подставлять его в строку ввода по TAB означало бы показать его любому,
     * кто смотрит в экран.
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
