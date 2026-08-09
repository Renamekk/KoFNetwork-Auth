package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
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
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Команда {@code /email} — привязка и подтверждение почты.
 *
 * <p>Доступна только аутентифицированному игроку: привязка почты к чужому аккаунту
 * из Limbo была бы способом перехватить восстановление пароля.
 *
 * <p>Подкоманды: без аргументов — состояние, {@code <адрес>} — привязать,
 * {@code verify <код>} — подтвердить, {@code resend} — выслать код заново,
 * {@code unlink} — отвязать.
 */
public final class EmailCommand implements SimpleCommand {

    private final KoFAuthCore core;
    private final MessageService messages;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public EmailCommand(@NotNull KoFAuthCore core,
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
            source.sendMessage(miniMessage.deserialize("<red>Команда доступна только в игре."));
            return;
        }
        if (!core.email().isConfigured()) {
            player.sendMessage(prefixed("<yellow>Отправка почты на сервере не настроена."));
            return;
        }

        String[] args = invocation.arguments();

        withAuthenticated(player, account -> {
            if (args.length == 0) {
                status(player, account);
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "verify", "подтвердить" -> verify(player, args);
                case "resend", "повторить" -> resend(player, account);
                case "unlink", "отвязать" -> unlink(player, account);
                default -> link(player, account, args[0]);
            }
        });
    }

    /**
     * Выполняет действие только для вошедшего игрока.
     *
     * <p>Состояние проверяется в Redis, а не по факту нахождения на лобби: игрок
     * мог оказаться там из-за ошибки маршрутизации, и полагаться на его текущий
     * сервер как на признак аутентификации нельзя.
     */
    private void withAuthenticated(Player player, java.util.function.Consumer<Account> action) {
        core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
            if (!state.isAuthenticated()) {
                player.sendMessage(prefixed("<red>Сначала войдите в аккаунт."));
                return;
            }
            core.authentication().findAccount(player.getUsername()).thenAccept(found -> {
                if (found.isEmpty()) {
                    player.sendMessage(prefixed("<red>Аккаунт не найден."));
                    return;
                }
                action.accept(found.get());
            });
        }).exceptionally(e -> {
            logger.error("Ошибка команды /email для {}", player.getUsername(), e);
            player.sendMessage(prefixed("<red>Внутренняя ошибка. Попробуйте позже."));
            return null;
        });
    }

    private void status(Player player, Account account) {
        core.email().findPrimary(account.id()).thenAccept(binding -> {
            if (binding.isEmpty()) {
                player.sendMessage(prefixed("<yellow>Почта не привязана."));
                player.sendMessage(miniMessage.deserialize(
                        "  <gray>Привязать: <white>/email ваш@адрес.ru"));
                return;
            }
            var value = binding.get();
            player.sendMessage(prefixed("<white>Почта: <gray>"
                    + net.kofnetwork.auth.core.mail.SmtpMailSender.mask(value.email())));
            player.sendMessage(miniMessage.deserialize(value.verified()
                    ? "  <green>Подтверждена"
                    : "  <yellow>Не подтверждена <gray>— /email verify <код>"));
            if (!value.verified()) {
                player.sendMessage(miniMessage.deserialize(
                        "  <gray>Выслать код заново: <white>/email resend"));
            }
        });
    }

    private void link(Player player, Account account, String address) {
        core.email().linkEmail(account.id(), address, contextOf(player)).thenAccept(result -> {
            if (result.isSuccess()) {
                player.sendMessage(prefixed("<green>Код подтверждения отправлен на "
                        + net.kofnetwork.auth.core.mail.SmtpMailSender.mask(address)));
                player.sendMessage(miniMessage.deserialize(
                        "  <gray>Подтвердите: <white>/email verify <код>"));
                return;
            }
            player.sendMessage(prefixed("<red>" + describe(result.errorCode())));
        });
    }

    private void verify(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(prefixed("<yellow>Использование: <white>/email verify <код>"));
            return;
        }
        core.email().verifyEmail(args[1].trim(), contextOf(player)).thenAccept(result ->
                player.sendMessage(result.isSuccess()
                        ? prefixed("<green>Почта подтверждена. Теперь доступно восстановление пароля.")
                        : prefixed("<red>" + describe(result.errorCode()))));
    }

    private void resend(Player player, Account account) {
        core.email().resendVerification(account.id(), contextOf(player)).thenAccept(result ->
                player.sendMessage(result.isSuccess()
                        ? prefixed("<green>Код отправлен повторно.")
                        : prefixed("<red>" + describe(result.errorCode()))));
    }

    private void unlink(Player player, Account account) {
        core.email().unlinkEmail(account.id(), contextOf(player)).thenAccept(result ->
                player.sendMessage(result.isSuccess()
                        ? prefixed("<yellow>Почта отвязана. Восстановление пароля больше недоступно.")
                        : prefixed("<red>" + describe(result.errorCode()))));
    }

    /** Человекочитаемое описание кода ошибки. */
    private static String describe(String code) {
        if (code == null) {
            return "Не удалось выполнить операцию.";
        }
        return switch (code) {
            case "EMAIL_INVALID_FORMAT" -> "Адрес указан неверно.";
            case "EMAIL_BLOCKED_DOMAIN" -> "Одноразовые почтовые сервисы не поддерживаются.";
            case "EMAIL_TOO_LONG" -> "Адрес слишком длинный.";
            case "EMAIL_LIMIT_REACHED" -> "К этому адресу привязано слишком много аккаунтов.";
            case "EMAIL_NOT_FOUND" -> "Почта не привязана.";
            case "EMAIL_ALREADY_VERIFIED" -> "Почта уже подтверждена.";
            case "TOKEN_INVALID", "TOKEN_NOT_FOUND", "TOKEN_EXPIRED" ->
                    "Код недействителен или истёк.";
            case "RATE_LIMITED" -> "Слишком часто. Попробуйте позже.";
            case "MAIL_DISABLED" -> "Отправка почты не настроена.";
            case "MAIL_SEND_FAILED" -> "Не удалось отправить письмо. Сообщите администрации.";
            default -> "Не удалось выполнить операцию.";
        };
    }

    private AuthContext contextOf(Player player) {
        return AuthContext.minecraft(
                IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());
    }

    /** Строка, собранная на месте, с общим префиксом сети. */
    private Component prefixed(String text) {
        return messages.prefixedRaw(text);
    }

    /**
     * Подсказки только для подкоманд.
     *
     * <p>Адрес и код не дополняются: код — секрет, а адрес подставлять из базы
     * значило бы показывать чужую почту тому, кто набрал команду в чужой сессии.
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (Suggestions.depth(args) != 1) {
            return List.of();
        }
        return Suggestions.matching(
                List.of("verify", "resend", "unlink"), Suggestions.partial(args));
    }
}
