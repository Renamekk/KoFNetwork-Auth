package net.kofnetwork.auth.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.limbo.PlayerTransfer;
import net.kofnetwork.auth.velocity.limbo.ServerPool;
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
 *
 * <p><b>Отказ хранилища состояния не открывает проход.</b> Состояние машины входа
 * живёт в общем хранилище, и раньше его недоступность возвращала значение по
 * умолчанию — {@code CONNECTING}, — а дальше ветвление шло так, будто это обычное
 * начало сессии. Теперь отказ отличим от «записи нет», и игрок при отказе
 * отключается с понятным сообщением, а не проходит на угад.
 */
public final class AuthenticationListener {

    private final KoFAuthCore core;
    private final ServerPool pool;
    private final PlayerTransfer transfer;
    private final MessageService messages;
    private final PendingLogins pendingLogins;
    private final Logger logger;

    public AuthenticationListener(KoFAuthCore core,
                                  ServerPool pool,
                                  PlayerTransfer transfer,
                                  MessageService messages,
                                  PendingLogins pendingLogins,
                                  Logger logger) {
        this.core = core;
        this.pool = pool;
        this.transfer = transfer;
        this.messages = messages;
        this.pendingLogins = pendingLogins;
        this.logger = logger;
    }

    /**
     * Отсечка до создания игрового профиля.
     *
     * <p>Самая дешёвая точка отказа: соединение ещё не стало игроком, ресурсы
     * на него не потрачены. Именно здесь отрабатывает AntiBot — пропустить
     * ботнет дальше означает создать тысячи объектов Player.
     *
     * <p><b>Отказ проверки закрывает вход, а не открывает.</b> Прежняя версия ловила
     * исключение, писала его в лог и пропускала соединение: при недоступном хранилище
     * AntiBot и ограничение скорости выключались целиком — ровно в тот момент, когда
     * они нужнее всего.
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
            logger.error("Проверка подключения с {} не выполнена: соединение отклонено",
                    ip.asMasked(), e);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    messages.kick("unavailable",
                            "<red>Сервер авторизации недоступен. Попробуйте позже.")));
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
     * означало отправку в Limbo вместо игрового сервера при каждом переподключении.
     *
     * <p><b>Состояние задаётся, а не меняется.</b> Используется
     * {@link net.kofnetwork.auth.api.service.SessionService#resetState} — новое
     * соединение начинает машину входа с нуля. Прежний вариант вызывал
     * {@code setState}, который отвергает переход из терминальных состояний
     * {@code AUTHENTICATED} и {@code BLOCKED}. Запись о прошлом входе переживает
     * соединение, поэтому у вернувшегося игрока с истёкшей сессией переход
     * «нужен пароль» отвергался, состояние оставалось {@code AUTHENTICATED} —
     * и маршрутизация отправляла его мимо Limbo прямо на игровой сервер, где
     * бэкенд, не найдя сессии, отключал его с ошибкой.
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
                            pendingLogins.completed(uuid);
                            return core.sessions().resetState(uuid, AuthState.AUTHENTICATED);
                        }
                        return core.authentication().findAccount(player.getUsername())
                                .thenCompose(account -> core.sessions().resetState(uuid,
                                        account.isPresent()
                                                ? AuthState.AWAITING_LOGIN
                                                : AuthState.AWAITING_REGISTER));
                    })
                    .join();
        } catch (RuntimeException e) {
            // Состояние не определилось — хранилище недоступно. Пропускать игрока
            // дальше нельзя: без состояния его не отличить от вошедшего.
            logger.error("Не удалось определить состояние игрока {}: отключаю",
                    player.getUsername(), e);
            player.disconnect(messages.kick("unavailable",
                    "<red>Сервер авторизации недоступен. Попробуйте позже."));
        }
    }

    /**
     * Маршрутизация: неаутентифицированный игрок попадает только в Limbo.
     *
     * <p>Событие синхронное по контракту Velocity, поэтому состояние читается
     * блокирующе. Оно берётся из общего хранилища — операция на доли миллисекунды.
     *
     * <p><b>Первичное подключение тоже проходит через выбор.</b> Раньше игрок,
     * которого {@code velocity.toml} отправлял на {@code limbo-1}, попадал туда без
     * всякой проверки: ветка «цель уже Limbo» возвращала управление, ничего не решив.
     * Из-за этого весь заход приходился на первый инстанс независимо от его состояния
     * и загрузки — а если он был выключен, игрок отправлялся на выключенный сервер.
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String target = event.getOriginalServer().getServerInfo().getName();

        AuthState state;
        try {
            state = core.sessions().getState(player.getUniqueId()).join();
        } catch (RuntimeException e) {
            logger.error("Состояние игрока {} недоступно: подключение отклонено",
                    player.getUsername(), e);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(messages.kick("unavailable",
                    "<red>Сервер авторизации недоступен. Попробуйте позже."));
            return;
        }

        if (!state.requiresLimbo()) {
            routeAuthenticated(event, player, target);
            return;
        }
        routeToLimbo(event, player, target);
    }

    /** Вошедшего игрока не держим в Limbo: переводим на хаб. */
    private void routeAuthenticated(ServerPreConnectEvent event, Player player, String target) {
        if (!pool.isLimbo(target)) {
            return;
        }
        // Сам он в Limbo и не просится: в velocity.toml `try` обязан указывать на
        // Limbo, поэтому первое подключение любого игрока идёт именно туда. Без
        // этой ветки вернувшийся игрок оставался в Limbo навсегда — перевести его
        // дальше было некому, потому что /login он не выполнял.
        Optional<ServerPool.Reservation> hub = pool.reserveHub();
        if (hub.isPresent()) {
            // Бронь держится до подключения: отпустить её здесь значило бы снова
            // показать следующему игроку место свободным, пока этот ещё в пути.
            pool.trackRouting(player.getUniqueId(), hub.get());
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(hub.get().server()));
            return;
        }
        // Хаб недоступен — пусть подождёт в Limbo. Он аутентифицирован,
        // и оставить его там безопаснее, чем отключить.
        logger.warn("Хаб недоступен, вошедший игрок {} остаётся в Limbo", player.getUsername());
    }

    /** Неаутентифицированный игрок допускается только в Limbo, выбранный по здоровью и ёмкости. */
    private void routeToLimbo(ServerPreConnectEvent event, Player player, String target) {
        Optional<ServerPool.Reservation> limbo = pool.reserveLimbo();
        if (limbo.isEmpty()) {
            // Все Limbo недоступны или заполнены. Пропускать неаутентифицированного
            // игрока куда-либо ещё нельзя ни при каких настройках.
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(messages.kick("limbo-unavailable",
                    "<red>Сервер авторизации недоступен. Попробуйте позже."));
            return;
        }
        ServerPool.Reservation reservation = limbo.get();
        pool.trackRouting(player.getUniqueId(), reservation);
        if (reservation.name().equals(target)) {
            // Выбор совпал с исходной целью — подменять результат не нужно,
            // но место всё равно занято до конца подключения.
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(reservation.server()));
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
        AuthState state = stateOrBlocked(player);
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
        if (stateOrBlocked(player).isAuthenticated()) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        player.sendMessage(promptFor(stateOrBlocked(player)));
    }

    /**
     * Состояние игрока; при отказе хранилища — {@link AuthState#BLOCKED}.
     *
     * <p>Значение по умолчанию выбрано так, чтобы отказ закрывал, а не открывал:
     * недоступное хранилище не должно превращать неаутентифицированного игрока
     * в аутентифицированного.
     */
    private AuthState stateOrBlocked(Player player) {
        try {
            return core.sessions().getState(player.getUniqueId()).join();
        } catch (RuntimeException e) {
            logger.warn("Состояние игрока {} недоступно, считаю его невошедшим",
                    player.getUsername(), e);
            return AuthState.BLOCKED;
        }
    }

    /**
     * Фиксирует переход игрока на другой сервер сети.
     *
     * <p>Помимо записи текущего сервера это единственное место, где сессия
     * продлевается событием, а не таймером, — переключение сервера гарантированно
     * означает живого игрока.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();

        // Игрок доехал — бронь больше не нужна: теперь он считается подключённым.
        pool.routingCompleted(player.getUniqueId());

        core.sessions().currentPublicId(player.getUniqueId())
                .thenCompose(publicId -> publicId
                        .map(id -> core.sessions().updateServer(id, server))
                        .orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(null)))
                .exceptionally(e -> {
                    logger.warn("Не удалось записать сервер игрока {}", player.getUsername(), e);
                    return null;
                });
    }

    /**
     * Очистка при отключении.
     *
     * <p><b>Состояние машины входа намеренно не удаляется.</b> Раньше здесь
     * вызывался {@code clearState}, и это была гонка: обработчик отключения
     * асинхронный, а игрок может переподключиться раньше, чем цепочка дойдёт до
     * хранилища. Тогда удаление приходило уже после того, как {@link #onPostLogin}
     * записал состояние нового соединения, и стирало именно его — вернувшийся
     * игрок с действующей сессией оказывался в Limbo и был вынужден вводить
     * пароль заново.
     *
     * <p>Удалять и незачем: у записи есть срок жизни, а каждое новое подключение
     * всё равно перезаписывает её через {@code resetState}. Капча — другое дело:
     * незакрытая задача переживает соединение, и ответ на неё игрок уже не увидит.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // Игрок не доехал — место освобождается, иначе бронь осталась бы навсегда
        // и постепенно вывела бы сервер из выбора.
        pool.routingCompleted(uuid);

        core.sessions().getState(uuid)
                .thenCompose(state -> state.isAuthenticated()
                        ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                        : core.captcha().cancel(uuid).thenApply(ignored -> null))
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
