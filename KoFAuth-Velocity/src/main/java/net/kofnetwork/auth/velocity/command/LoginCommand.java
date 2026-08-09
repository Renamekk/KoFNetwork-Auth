package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.limbo.LimboRouter;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Команда {@code /login <пароль>}.
 *
 * <p><b>Автодополнение отключено.</b> {@link #suggest} возвращает пустой список:
 * подсказка к аргументу-паролю не нужна, а любой возвращённый вариант клиент
 * покажет на экране.
 */
public final class LoginCommand implements SimpleCommand {

    private final KoFAuthCore core;
    private final LimboRouter router;
    private final MessageService messages;
    private final Logger logger;

    public LoginCommand(KoFAuthCore core, LimboRouter router, MessageService messages, Logger logger) {
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
        // Второй аргумент — код второго фактора: TOTP либо код из бота, взятый
        // командой /sendcode. Он необязателен: при двухшаговом входе игрок сначала
        // получает запрос подтверждения и вводит код отдельно.
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(messages.prefixed("login-usage",
                    "<yellow>Использование: <white>/login <пароль> [код]"));
            return;
        }

        core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
            if (state.isAuthenticated()) {
                player.sendMessage(messages.prefixed("already-logged-in",
                        "<yellow>Вы уже вошли."));
                return;
            }
            if (state == AuthState.AWAITING_REGISTER) {
                player.sendMessage(messages.prefixed("register-prompt",
                        "<yellow>Зарегистрируйтесь: <white>/register <пароль> <пароль>"));
                return;
            }
            performLogin(player, args[0], args.length > 1 ? args[1] : null);
        });
    }

    private void performLogin(Player player, String password, String twoFactorCode) {
        AuthContext context = AuthContext.minecraft(
                IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());

        LoginRequest request = LoginRequest.ofPlayer(player.getUniqueId(),
                player.getUsername(), password, context)
                .withTwoFactorCode(twoFactorCode);

        core.authentication().login(request)
                .thenAccept(result -> handleResult(player, result))
                .exceptionally(e -> {
                    logger.error("Ошибка входа игрока {}", player.getUsername(), e);
                    player.sendMessage(messages.prefixed("error",
                            "<red>Внутренняя ошибка. Попробуйте позже."));
                    return null;
                });
    }

    private void handleResult(Player player, AuthResult result) {
        switch (result.type()) {
            case SUCCESS -> onSuccess(player, result);

            case TWO_FACTOR_REQUIRED -> {
                core.sessions().setState(player.getUniqueId(), AuthState.TWO_FACTOR_REQUIRED);
                player.sendMessage(messages.prefixed("two-factor-required",
                        "<yellow>Требуется подтверждение: <white><method>",
                        Map.of("method", String.valueOf(result.requiredTwoFactor()))));
            }

            case CAPTCHA_REQUIRED -> {
                core.sessions().setState(player.getUniqueId(), AuthState.CAPTCHA_REQUIRED);
                player.sendMessage(messages.prefixed("captcha-required",
                        "<yellow>Подтвердите, что вы не бот."));
            }

            case BAD_PASSWORD -> player.sendMessage(result.remainingAttempts() > 0
                    ? messages.prefixed("wrong-password-attempts",
                            "<red>Неверный логин или пароль. Осталось попыток: <white><attempts>",
                            Map.of("attempts", String.valueOf(result.remainingAttempts())))
                    : messages.prefixed("wrong-password", "<red>Неверный логин или пароль."));

            // Наружу выглядит так же, как неверный пароль: иначе форма входа
            // превращается в средство проверки существования ников.
            case UNKNOWN_ACCOUNT -> player.sendMessage(
                    messages.prefixed("wrong-password", "<red>Неверный логин или пароль."));

            case TEMPORARILY_LOCKED, RATE_LIMITED -> player.sendMessage(
                    messages.prefixed("rate-limited",
                            "<red>Слишком много попыток. Подождите <white><minutes> мин.",
                            Map.of("minutes", String.valueOf(
                                    result.retryAfter() == null ? 15
                                            : Math.max(1, result.retryAfter().toMinutes())))));

            case ACCOUNT_LOCKED -> player.disconnect(messages.kick("account-locked",
                    "<red>Аккаунт заблокирован администрацией."));

            case ACCOUNT_BANNED -> player.disconnect(messages.kick("account-banned",
                    "<red>Аккаунт заблокирован."));

            case BOT_DETECTED -> player.disconnect(messages.kick("antibot",
                    "<red>Слишком много подключений с вашего адреса."));

            case PROXY_DETECTED -> player.disconnect(messages.kick("proxy-detected",
                    "<red>Вход через VPN или прокси запрещён."));

            case TWO_FACTOR_FAILED -> player.sendMessage(messages.prefixed("two-factor-failed",
                    "<red>Неверный код подтверждения."));

            case TIMEOUT -> player.disconnect(messages.kick("timeout",
                    "<red>Вы не успели войти за отведённое время."));

            case ERROR -> player.sendMessage(messages.prefixed("error",
                    "<red>Внутренняя ошибка. Попробуйте позже."));
        }
    }

    /**
     * Завершение успешного входа.
     *
     * <p>Привязку UUID к сессии здесь не ставим — её записывает Core внутри
     * {@code login()}, до рассылки событий об отзыве прежних сессий. Делать это
     * тут значило бы опоздать: событие уже ушло бы с устаревшей привязкой.
     *
     * <p>Состояние задаётся через {@code resetState}: пароль принят, и остаток
     * прошлого состояния (например {@code BLOCKED} после отзыва сессии) не должен
     * отменять только что состоявшийся вход.
     */
    private void onSuccess(Player player, AuthResult result) {
        core.sessions().resetState(player.getUniqueId(), AuthState.AUTHENTICATED)
                .thenRun(() -> {
                    player.sendMessage(messages.prefixed("login-success",
                            "<green>Вход выполнен. Приятной игры!"));
                    sendToLobby(player);
                });
    }

    private void sendToLobby(Player player) {
        Optional<RegisteredServer> lobby = router.selectLobby();
        if (lobby.isEmpty()) {
            logger.error("Нет доступного лобби для игрока {}", player.getUsername());
            player.sendMessage(messages.prefixed("lobby-unavailable",
                    "<red>Лобби недоступно. Сообщите администрации."));
            return;
        }
        player.createConnectionRequest(lobby.get()).fireAndForget();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
