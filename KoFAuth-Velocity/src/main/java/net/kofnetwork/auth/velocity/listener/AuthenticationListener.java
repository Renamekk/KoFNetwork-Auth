package net.kofnetwork.auth.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kofnetwork.auth.api.KoFAuth;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.limbo.LimboRouter;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Гейт аутентификации на уровне прокси.
 *
 * <p>Здесь и только здесь принимается решение, куда игрок может попасть. Все
 * ограничения продублированы на Paper (см. {@code KoFAuth-Paper}), но именно
 * прокси — единственная точка, которую нельзя обойти прямым подключением к
 * бэкенду при правильно настроенном {@code player-info-forwarding}.
 */
public final class AuthenticationListener {

    private final KoFAuthCore core;
    private final LimboRouter router;
    private final MessageService messages;
    private final Logger logger;

    public AuthenticationListener(KoFAuthCore core,
                                  LimboRouter router,
                                  MessageService messages,
                                  Logger logger) {
        this.core = core;
        this.router = router;
        this.messages = messages;
        this.logger = logger;
    }

    /**
     * Отсечка до создания игрового профиля.
     *
     * <p>Самая дешёвая точка отказа: соединение ещё не стало игроком, ресурсы
     * на него не потрачены. Именно здесь отрабатывает AntiBot — пропустить
     * ботнет дальше означает создать тысячи объектов Player.
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        IpAddress ip = IpAddress.of(event.getConnection().getRemoteAddress().getAddress());
        var context = net.kofnetwork.auth.api.dto.AuthContext.minecraft(ip, null, null, null);

        // Блокирующее ожидание в PreLogin допустимо и необходимо: событие
        // асинхронное (Velocity выполняет его вне сетевого потока), а решение
        // «пускать или нет» обязано быть принято до продолжения рукопожатия.
        try {
            if (core.security().isBotSuspected(context).join()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        messages.kick("antibot", "<red>Слишком много подключений с вашего адреса.")));
                return;
            }
            var reputation = core.security().checkIpReputation(ip).join();
            if (reputation.shouldBlock()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        messages.kick("proxy-detected",
                                "<red>Вход через VPN или прокси запрещён.")));
            }
        } catch (RuntimeException e) {
            // Отказ проверки не должен закрывать вход всей сети.
            logger.error("Ошибка проверки подключения с {}", ip.asMasked(), e);
        }
    }

    /**
     * Определяет исходное состояние игрока.
     *
     * <p><b>Ожидание здесь обязательно.</b> Velocity подключает игрока к серверу
     * только после того, как все обработчики этого события завершились, — но
     * «завершился» для асинхронной цепочки означает «вернул управление», а не
     * «дописал состояние». Раньше метод отдавал управление сразу, и следующий
     * за ним {@link ServerPreConnectEvent} успевал прочитать состояние по
     * умолчанию — {@code CONNECTING}. Для игрока с действующей сессией это
     * означало отправку в Limbo вместо игрового сервера при каждом
     * переподключении.
     *
     * <p>Событие выполняется вне сетевого потока, поэтому блокировка допустима:
     * тормозится вход одного игрока, а не работа прокси. Так же поступает
     * {@link #onPreLogin(PreLoginEvent)}.
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        IpAddress ip = IpAddress.of(player.getRemoteAddress().getAddress());

        try {
            core.sessions().validate(uuid, ip)
                    .thenCompose(session -> {
                        if (session.isPresent()) {
                            // Действующая сессия: игрок продолжает с того же места,
                            // повторный ввод пароля после разрыва соединения раздражает
                            // и ничего не добавляет к безопасности.
                            return core.sessions().setState(uuid, AuthState.AUTHENTICATED);
                        }
                        return core.authentication().findAccount(player.getUsername())
                                .thenCompose(account -> core.sessions().setState(uuid,
                                        account.isPresent()
                                                ? AuthState.AWAITING_LOGIN
                                                : AuthState.AWAITING_REGISTER));
                    })
                    .join();
        } catch (RuntimeException e) {
            // Состояние не определилось — игрок останется в CONNECTING и попадёт
            // в Limbo. Это безопасный исход: пустит его только пароль.
            logger.error("Не удалось определить состояние игрока {}",
                    player.getUsername(), e);
        }
    }

    /**
     * Маршрутизация: неаутентифицированный игрок попадает только в Limbo.
     *
     * <p>Событие синхронное по контракту Velocity, поэтому состояние читается
     * блокирующе. Оно берётся из Redis — операция на доли миллисекунды.
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        AuthState state = core.sessions().getState(player.getUniqueId()).join();

        String target = event.getOriginalServer().getServerInfo().getName();

        if (!state.requiresLimbo()) {
            // Игрок с действующей сессией не должен попадать в Limbo. Сам он туда
            // и не просится: в velocity.toml `try` обязан указывать на Limbo,
            // поэтому первое подключение любого игрока идёт именно туда. Без этой
            // ветки вернувшийся игрок оставался в Limbo навсегда — перевести его
            // дальше было некому, потому что /login он не выполнял.
            if (router.isLimbo(target)) {
                Optional<RegisteredServer> lobby = router.selectLobby();
                if (lobby.isPresent()) {
                    event.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby.get()));
                    return;
                }
                // Лобби недоступно — пусть подождёт в Limbo. Он аутентифицирован,
                // и оставить его там безопаснее, чем отключить.
                logger.warn("Лобби недоступно, вошедший игрок {} остаётся в Limbo",
                        player.getUsername());
            }
            return;
        }

        if (router.isLimbo(target)) {
            return;
        }

        Optional<RegisteredServer> limbo = router.selectLimbo();
        if (limbo.isEmpty()) {
            if (router.kickWhenLimboUnavailable()) {
                player.disconnect(messages.kick("limbo-unavailable",
                        "<red>Сервер авторизации недоступен. Попробуйте позже."));
            }
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo.get()));
    }

    /**
     * Блокировка команд до входа.
     *
     * <p>Белый список, а не чёрный: перечислить все опасные команды всех плагинов
     * невозможно, а забытая в чёрном списке команда — это дыра.
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        AuthState state = core.sessions().getState(player.getUniqueId()).join();
        if (state.isAuthenticated()) {
            return;
        }

        String root = event.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        boolean allowed = core.config()
                .getStringList(ConfigFile.VELOCITY, "restrictions.allowed-commands").stream()
                .anyMatch(name -> name.equalsIgnoreCase(root));

        if (allowed) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());

        if (core.config().getBoolean(ConfigFile.VELOCITY,
                "restrictions.kick-on-forbidden-command", false)) {
            player.disconnect(messages.kick("not-authenticated",
                    "<red>Вы не прошли аутентификацию."));
        } else {
            player.sendMessage(promptFor(state));
        }
    }

    /**
     * Блокировка чата до входа.
     *
     * <p>Отключать нельзя: игрок, перепутавший {@code /login} с обычным сообщением,
     * отправит пароль в общий чат. Здесь это сообщение не доходит ни до кого.
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!core.config().getBoolean(ConfigFile.VELOCITY, "restrictions.block-chat", true)) {
            return;
        }
        AuthState state = core.sessions().getState(player.getUniqueId()).join();
        if (state.isAuthenticated()) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        player.sendMessage(promptFor(state));
    }

    /** Очистка состояния при отключении. */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        core.sessions().getState(uuid)
                .thenCompose(state -> {
                    if (state.isAuthenticated()) {
                        // Сессия остаётся: игрок вернётся и продолжит без пароля.
                        // Снимается только состояние машины входа.
                        return core.sessions().clearState(uuid);
                    }
                    // Незавершённый вход: гасим ещё и незакрытую капчу, иначе
                    // при переподключении игрок получит задачу, ответ на которую
                    // потерялся вместе с прошлой сессией.
                    return core.captcha().cancel(uuid)
                            .thenCompose(ignored -> core.sessions().clearState(uuid));
                })
                .exceptionally(e -> {
                    logger.warn("Не удалось очистить состояние игрока {}", uuid, e);
                    return null;
                });
    }

    private net.kyori.adventure.text.Component promptFor(AuthState state) {
        return state == AuthState.AWAITING_REGISTER
                ? messages.prefixed("register-prompt",
                        "<yellow>Зарегистрируйтесь: <white>/register <пароль> <пароль>")
                : messages.prefixed("login-prompt",
                        "<yellow>Войдите: <white>/login <пароль>");
    }
}
