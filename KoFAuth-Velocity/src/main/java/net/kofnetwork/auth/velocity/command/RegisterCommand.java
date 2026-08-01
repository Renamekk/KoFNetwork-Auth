package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.RegistrationRequest;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.result.RegistrationResult;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.limbo.LimboRouter;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Команда {@code /register <пароль> <пароль>}. */
public final class RegisterCommand implements SimpleCommand {

    private final KoFAuthCore core;
    private final LimboRouter router;
    private final MessageService messages;
    private final Logger logger;

    public RegisterCommand(KoFAuthCore core, LimboRouter router,
                           MessageService messages, Logger logger) {
        this.core = core;
        this.router = router;
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
        if (args.length != 2) {
            player.sendMessage(messages.prefixed("register-usage",
                    "<yellow>Использование: <white>/register <пароль> <пароль>"));
            return;
        }

        core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
            if (state.isAuthenticated()) {
                player.sendMessage(messages.prefixed("already-logged-in", "<yellow>Вы уже вошли."));
                return;
            }
            if (state == AuthState.AWAITING_LOGIN) {
                player.sendMessage(messages.prefixed("already-registered",
                        "<yellow>Аккаунт уже существует. Войдите: <white>/login <пароль>"));
                return;
            }
            performRegistration(player, args[0], args[1]);
        });
    }

    private void performRegistration(Player player, String password, String confirmation) {
        AuthContext context = AuthContext.minecraft(
                IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());

        RegistrationRequest request = RegistrationRequest.ofPlayer(player.getUniqueId(),
                player.getUsername(), password, confirmation, context);

        core.registration().register(request)
                .thenAccept(result -> handleResult(player, result))
                .exceptionally(e -> {
                    logger.error("Ошибка регистрации игрока {}", player.getUsername(), e);
                    player.sendMessage(messages.prefixed("error",
                            "<red>Внутренняя ошибка. Попробуйте позже."));
                    return null;
                });
    }

    private void handleResult(Player player, RegistrationResult result) {
        switch (result.type()) {
            case SUCCESS -> onSuccess(player, result);

            case USERNAME_TAKEN -> player.sendMessage(messages.prefixed("username-taken",
                    "<red>Этот ник уже занят."));

            case INVALID_USERNAME -> player.sendMessage(messages.prefixed("invalid-username",
                    "<red>Ник содержит недопустимые символы или зарезервирован."));

            case PASSWORDS_DO_NOT_MATCH -> player.sendMessage(
                    messages.prefixed("passwords-mismatch", "<red>Пароли не совпадают."));

            // Список причин показывается целиком: сообщать о требованиях по одному
            // за попытку — верный способ довести человека до пароля на бумажке.
            case PASSWORD_TOO_WEAK -> {
                player.sendMessage(messages.prefixed("password-too-weak",
                        "<red>Пароль слишком простой:"));
                result.passwordIssues().forEach(issue ->
                        player.sendMessage(messages.parse(" <gray>— <white>" + describe(issue))));
            }

            case REGISTRATION_DISABLED -> player.sendMessage(
                    messages.prefixed("registration-disabled",
                            "<red>Регистрация временно закрыта."));

            case IP_LIMIT_REACHED -> player.sendMessage(messages.prefixed("ip-limit",
                    "<red>С вашего адреса зарегистрировано слишком много аккаунтов."));

            case RATE_LIMITED -> player.sendMessage(messages.prefixed("rate-limited",
                    "<red>Слишком много попыток. Подождите <white><minutes> мин.",
                    Map.of("minutes", String.valueOf(result.retryAfter() == null ? 10
                            : Math.max(1, result.retryAfter().toMinutes())))));

            case CAPTCHA_REQUIRED -> {
                core.sessions().setState(player.getUniqueId(), AuthState.CAPTCHA_REQUIRED);
                player.sendMessage(messages.prefixed("captcha-required",
                        "<yellow>Подтвердите, что вы не бот."));
            }

            case BOT_DETECTED -> player.disconnect(messages.kick("antibot",
                    "<red>Слишком много подключений с вашего адреса."));

            case PROXY_DETECTED -> player.disconnect(messages.kick("proxy-detected",
                    "<red>Вход через VPN или прокси запрещён."));

            case ERROR -> player.sendMessage(messages.prefixed("error",
                    "<red>Внутренняя ошибка. Попробуйте позже."));
        }
    }

    /** Человекочитаемое описание требования к паролю. */
    private static String describe(String issueCode) {
        return switch (issueCode) {
            case "PASSWORD_TOO_SHORT" -> "слишком короткий";
            case "PASSWORD_TOO_LONG" -> "слишком длинный";
            case "PASSWORD_NO_UPPERCASE" -> "нужна заглавная буква";
            case "PASSWORD_NO_LOWERCASE" -> "нужна строчная буква";
            case "PASSWORD_NO_DIGIT" -> "нужна цифра";
            case "PASSWORD_NO_SPECIAL" -> "нужен спецсимвол";
            case "PASSWORD_CONTAINS_USERNAME" -> "не должен содержать ваш ник";
            case "PASSWORD_TOO_COMMON" -> "слишком распространённый";
            case "PASSWORD_REPEATED_CHARACTERS" -> "не должен содержать aaaa";
            case "PASSWORD_SEQUENTIAL_CHARACTERS" -> "не должен содержать 12345";
            default -> issueCode;
        };
    }

    private void onSuccess(Player player, RegistrationResult result) {
        core.sessions().setState(player.getUniqueId(), AuthState.AUTHENTICATED)
                .thenCompose(ignored -> core.sessions()
                        .cacheForPlayer(player.getUniqueId(), result.session()))
                .thenRun(() -> {
                    player.sendMessage(messages.prefixed("register-success",
                            "<green>Аккаунт создан. Не забудьте привязать почту."));
                    Optional<RegisteredServer> lobby = router.selectLobby();
                    if (lobby.isPresent()) {
                        player.createConnectionRequest(lobby.get()).fireAndForget();
                    } else {
                        logger.error("Нет доступного лобби для игрока {}", player.getUsername());
                    }
                });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
