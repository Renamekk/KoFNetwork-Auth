package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginApproval;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.limbo.PlayerTransfer;
import net.kofnetwork.auth.velocity.listener.PendingLogins;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Команда {@code /login <пароль>}.
 *
 * <p><b>Автодополнение отключено.</b> {@link #suggest} возвращает пустой список:
 * подсказка к аргументу-паролю не нужна, а любой возвращённый вариант клиент
 * покажет на экране.
 */
public final class LoginCommand implements SimpleCommand {

    private final KoFAuthCore core;
    private final PlayerTransfer transfer;
    private final MessageService messages;
    private final PendingLogins pendingLogins;
    private final Logger logger;

    public LoginCommand(KoFAuthCore core, PlayerTransfer transfer, MessageService messages,
                        PendingLogins pendingLogins, Logger logger) {
        this.core = core;
        this.transfer = transfer;
        this.messages = messages;
        this.pendingLogins = pendingLogins;
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
        // Второй аргумент — код приложения-аутентификатора (TOTP), и только он.
        // Кодов подтверждения через мессенджер больше нет: Telegram и Discord
        // присылают кнопки, а вводить в игре что-либо не требуется.
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(messages.prefixed("login-usage",
                    "<yellow>Использование: <white>/login <пароль> [код TOTP]"));
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
        }).exceptionally(e -> {
            // Состояние не прочитано — хранилище недоступно. Пускать «на всякий
            // случай» нельзя: именно так и выглядит вход без проверки.
            logger.error("Состояние игрока {} недоступно, вход отклонён",
                    player.getUsername(), e);
            player.sendMessage(messages.prefixed("unavailable",
                    "<red>Сервер авторизации недоступен. Попробуйте позже."));
            return null;
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

            case TWO_FACTOR_REQUIRED -> onTwoFactorRequired(player, result);

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
     * Пароль принят, но нужен второй фактор.
     *
     * <p>Для мессенджеров игрок ничего не вводит — он нажимает кнопку в своём чате.
     * Метка попытки запоминается здесь: по ней решение владельца будет сопоставлено
     * именно с этим входом, а не с предыдущим, от которого игрок уже отказался.
     * Заодно эта отметка освобождает игрока от отключения по общему таймауту:
     * ждать кнопку дольше, чем набирать пароль, — нормально.
     */
    private void onTwoFactorRequired(Player player, AuthResult result) {
        TwoFactorMethod method = result.requiredTwoFactor();
        String attemptId = result.attemptId();

        if (attemptId != null) {
            Duration window = core.config().getDuration(ConfigFile.SECURITY,
                    "two-factor.approval-lifetime", LoginApproval.DEFAULT_LIFETIME);
            pendingLogins.awaitingApproval(player.getUniqueId(), attemptId, window);
        }

        core.sessions().setState(player.getUniqueId(), AuthState.TWO_FACTOR_REQUIRED)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logger.error("Не удалось записать состояние ожидания у {}",
                                player.getUsername(), failure);
                        player.sendMessage(messages.prefixed("unavailable",
                                "<red>Сервер авторизации недоступен. Попробуйте позже."));
                        return;
                    }
                    player.sendMessage(attemptId == null
                            ? messages.prefixed("two-factor-required",
                                    "<yellow>Введите код приложения: <white>/login <пароль> <код>")
                            : messages.prefixed("approval-required",
                                    "<yellow>Подтвердите вход кнопкой в <white><method></white>.",
                                    Map.of("method", String.valueOf(method))));
                });
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
     *
     * <p><b>Отметка о завершении ставится до перевода.</b> Перевод на хаб занимает
     * время, и раньше именно в нём игрок успевал попасть под отключение по таймауту —
     * получалось «Вы не успели войти» сразу после верного пароля.
     */
    private void onSuccess(Player player, AuthResult result) {
        pendingLogins.completed(player.getUniqueId());

        core.sessions().resetState(player.getUniqueId(), AuthState.AUTHENTICATED)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logger.error("Вход игрока {} состоялся, но состояние не записано",
                                player.getUsername(), failure);
                        player.disconnect(messages.kick("unavailable",
                                "<red>Сервер авторизации недоступен. Подключитесь заново."));
                        return;
                    }
                    player.sendMessage(messages.prefixed("login-success",
                            "<green>Вход выполнен. Приятной игры!"));
                    sendToHub(player);
                });
    }

    /**
     * Переводит игрока на хаб.
     *
     * <p>Результат разбирается, а не отбрасывается. Прежний {@code fireAndForget()}
     * не сообщал ни об отказе хаба, ни об отсутствии свободного: игрок с полноценной
     * сессией просто оставался стоять в Limbo, не понимая, что произошло.
     */
    private void sendToHub(Player player) {
        transfer.toHub(player).thenAccept(outcome -> {
            if (outcome.success()) {
                return;
            }
            logger.error("Игрока {} не удалось перевести на хаб: {}",
                    player.getUsername(), outcome.detail());
            player.sendMessage(messages.prefixed("hub-unavailable",
                    "<red>Хаб недоступен. Сообщите администрации."));
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
