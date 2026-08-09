package net.kofnetwork.auth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.event.events.RemoteEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.command.AuthAdminCommand;
import net.kofnetwork.auth.velocity.command.EmailCommand;
import net.kofnetwork.auth.velocity.command.LinkCommand;
import net.kofnetwork.auth.velocity.command.LoginCommand;
import net.kofnetwork.auth.velocity.command.RegisterCommand;
import net.kofnetwork.auth.velocity.command.WebLinkCommand;
import net.kofnetwork.auth.velocity.limbo.LimboRouter;
import net.kofnetwork.auth.velocity.listener.AuthenticationListener;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Плагин Velocity: гейт аутентификации сети.
 *
 * <p>Владеет экземпляром {@link KoFAuthCore} — прокси стартует раньше бэкендов,
 * поэтому именно он поднимает базу и применяет миграции. Paper-серверы
 * подключаются к уже готовой схеме.
 */
@Plugin(
        id = "kofauth",
        name = "KoFAuth",
        version = "1.0.0-SNAPSHOT",
        description = "Система авторизации сети KoF Network",
        authors = {"KoF Network"}
)
public final class KoFAuthVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private KoFAuthCore core;
    private LimboRouter router;
    private MessageService messages;
    private AuthAdminCommand adminCommand;

    @Inject
    public KoFAuthVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            core = KoFAuthCore.start(dataDirectory);
        } catch (RuntimeException e) {
            // Останавливаться на старте правильнее, чем работать без аутентификации:
            // прокси, пропускающий всех подряд, хуже прокси, который не поднялся.
            logger.error("KoFAuth не запустился. Сеть работает БЕЗ аутентификации — "
                    + "остановите прокси и исправьте конфигурацию.", e);
            return;
        }

        this.router = new LimboRouter(proxy, core.config(), logger);
        this.messages = new MessageService(core.config());

        registerCommands();
        proxy.getEventManager().register(this,
                new AuthenticationListener(core, router, messages, logger));

        subscribeToRemoteEvents();
        scheduleLoginTimeout();
        scheduleSessionKeepAlive();

        logger.info("KoFAuth запущен на прокси");
    }

    private void registerCommands() {
        CommandManager commands = proxy.getCommandManager();

        CommandMeta login = commands.metaBuilder("login")
                .aliases("l", "войти")
                .plugin(this)
                .build();
        commands.register(login, new LoginCommand(core, router, messages, logger));

        CommandMeta register = commands.metaBuilder("register")
                .aliases("reg", "регистрация")
                .plugin(this)
                .build();
        commands.register(register, new RegisterCommand(core, router, messages, logger));

        CommandMeta auth = commands.metaBuilder("auth")
                .aliases("kofauth")
                .plugin(this)
                .build();
        this.adminCommand = new AuthAdminCommand(core, proxy, messages, logger);
        commands.register(auth, adminCommand);

        CommandMeta email = commands.metaBuilder("email")
                .aliases("почта")
                .plugin(this)
                .build();
        commands.register(email, new EmailCommand(core, messages, logger));

        // Подтверждение привязки сайта. Направление то же, что у мессенджеров:
        // код показывает сайт, а решение принимает тот, кто уже вошёл в игре.
        CommandMeta link = commands.metaBuilder("link")
                .aliases("привязать")
                .plugin(this)
                .build();
        commands.register(link, new WebLinkCommand(core, messages, logger));

        // Код привязки выдаётся в игре и вводится в мессенджере. Обратное
        // направление позволило бы привязать свой Telegram к чужому нику:
        // достаточно было бы знать ник.
        CommandMeta telegram = commands.metaBuilder("telegram")
                .aliases("tg", "телеграм")
                .plugin(this)
                .build();
        commands.register(telegram,
                new LinkCommand(core, LinkCommand.Kind.TELEGRAM, messages, logger));

        CommandMeta discord = commands.metaBuilder("discord")
                .aliases("ds", "дискорд")
                .plugin(this)
                .build();
        commands.register(discord,
                new LinkCommand(core, LinkCommand.Kind.DISCORD, messages, logger));
    }

    /**
     * Реакция на события с других узлов сети.
     *
     * <p>Смена пароля на сайте происходит в другом процессе. Без этой подписки
     * игрок с угнанной сессией продолжал бы играть, несмотря на смену пароля
     * владельцем, — то есть смена пароля не давала бы главного, ради чего её делают.
     */
    private void subscribeToRemoteEvents() {
        core.events().subscribe(RemoteEvent.class, event -> {
            if (!event.isType(SessionInvalidatedEvent.class) || event.accountId() == null) {
                return;
            }
            disconnectAffectedPlayers(event);
        });

        // Локальные события того же типа — от этого же узла.
        core.events().subscribe(SessionInvalidatedEvent.class, event -> {
            if (event.accountId() != null) {
                kickByAccount(event.accountId(), event.reason(), event::affects);
            }
        });
    }

    private void disconnectAffectedPlayers(RemoteEvent event) {
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        kickByAccount(accountId, event.attribute("reason", "SESSION_REVOKED"),
                revokedSessions(event.attribute("sessions", ""),
                        event.booleanAttribute("affectsAll")));
    }

    /**
     * Разбирает перечень отозванных сессий из удалённого события.
     *
     * <p>По сети список едет строкой через запятую — в плоском {@code RemoteEvent}
     * массива нет. Пустой список вместе с {@code affectsAll = false} означает
     * «не затронута ни одна»: трактовать его как «все» значило бы выбрасывать
     * игроков по событию, которое их не касается.
     */
    static Predicate<String> revokedSessions(String sessionsAttribute, boolean affectsAll) {
        if (affectsAll) {
            return publicId -> true;
        }
        Set<String> revoked = Arrays.stream(sessionsAttribute.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());
        return revoked::contains;
    }

    /**
     * Выкидывает игроков, чья сессия отозвана, обратно в Limbo.
     *
     * <p>Сопоставление идёт через привязку UUID к сессии: держать на прокси карту
     * «accountId → игрок» значило бы дублировать состояние, которое уже есть
     * в Redis, и рассинхронизироваться с ним при каждом переподключении.
     *
     * <p><b>Сверка с конкретной сессией обязательна.</b> Раньше отключались все
     * игроки аккаунта — и это ломало обычный вход. При {@code max-concurrent: 1}
     * успешный {@code /login} отзывает прежнюю сессию, событие об этом приходило
     * сюда и выбрасывало того самого игрока, который только что ввёл пароль,
     * с сообщением «Ваша сессия завершена. Войдите заново». Событие при этом
     * честно перечисляло только старые сессии; их просто никто не читал.
     *
     * <p><b>Читается привязка, а не результат проверки сессии.</b>
     * {@code validate} для этой задачи не годится по двум причинам. Во-первых, он
     * возвращает пустое значение и для отозванной, и для просто истёкшей сессии,
     * и для игрока, который сейчас в Limbo, — а «сессии нет» это не то же самое,
     * что «названа этим отзывом»; на пустом значении прежний код отключал игрока,
     * хотя событие его не касалось. Во-вторых, {@code validate} не только читает:
     * при несовпадении адреса он отзывает сессию и публикует событие — то есть
     * обход всех игроков сети на каждый отзыв мог порождать новые отзывы.
     *
     * @param affects принимает публичный идентификатор сессии и отвечает,
     *                затронута ли она отзывом
     */
    private void kickByAccount(long accountId, String reason, Predicate<String> affects) {
        for (Player player : proxy.getAllPlayers()) {
            core.authentication().findAccount(player.getUsername()).thenAccept(account -> {
                if (account.isEmpty() || account.get().id() != accountId) {
                    return;
                }
                core.sessions().currentPublicId(player.getUniqueId()).thenAccept(publicId -> {
                    // Привязки нет — игрок не вошёл и находится в Limbo.
                    // Отключать его незачем: пароль он всё равно ещё не вводил.
                    if (publicId.isEmpty() || !affects.test(publicId.get())) {
                        return;
                    }
                    core.sessions().setState(player.getUniqueId(), AuthState.BLOCKED);
                    player.disconnect(messages.kick(
                            "PASSWORD_CHANGED".equals(reason) ? "password-changed" : "session-revoked",
                            "<yellow>Ваша сессия завершена. Войдите заново."));
                });
            });
        }
    }

    /**
     * Отключает тех, кто не успел войти.
     *
     * <p>Без этого соединения в Limbo копятся: боту достаточно подключиться и
     * молчать, чтобы занять слот. Проверка идёт раз в секунду по всем игрокам —
     * при 5000 CCU это тысячи дешёвых обращений к Redis, поэтому состояние
     * читается только у тех, кто находится на Limbo.
     */
    private void scheduleLoginTimeout() {
        Duration timeout = core.config().getDuration(ConfigFile.CONFIG,
                "auth.login.timeout", Duration.ofSeconds(60));

        proxy.getScheduler().buildTask(this, () -> {
            long deadline = System.currentTimeMillis() - timeout.toMillis();
            for (Player player : proxy.getAllPlayers()) {
                boolean onLimbo = player.getCurrentServer()
                        .map(connection -> router.isLimbo(connection.getServerInfo().getName()))
                        .orElse(false);
                if (!onLimbo) {
                    continue;
                }
                if (connectedAt.getOrDefault(player.getUniqueId(), Long.MAX_VALUE) > deadline) {
                    continue;
                }
                core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
                    if (state.requiresLimbo()) {
                        player.disconnect(messages.kick("timeout",
                                "<red>Вы не успели войти за отведённое время."));
                    }
                });
            }
        }).repeat(1, TimeUnit.SECONDS).schedule();
    }

    /**
     * Продлевает сессии играющих людей.
     *
     * <p><b>Без этого скользящий срок не скользил.</b> {@code auth.session.ttl}
     * описан как срок, продлеваемый активностью, но продлевать его было некому:
     * {@code touch} не вызывался нигде, кроме тестов. Сессия умирала ровно через
     * час после входа независимо от того, играл человек всё это время или нет.
     * Игрок, зашедший «через некоторое время», обнаруживал, что пароль нужно
     * вводить заново, — и попадал ровно в тот сценарий, где ломались состояние
     * и маршрутизация.
     *
     * <p>Период равен интервалу, с которым {@code touch} вообще доходит до MySQL
     * (там стоит собственное ограничение частоты записи). Чаще — значит гонять
     * Redis впустую, реже — значит рисковать тем, что сессия истечёт между
     * двумя обходами.
     *
     * <p>Состояние читается только у игроков вне Limbo: у остальных сессии нет
     * по определению.
     */
    private void scheduleSessionKeepAlive() {
        proxy.getScheduler().buildTask(this, () -> {
            for (Player player : proxy.getAllPlayers()) {
                boolean onLimbo = player.getCurrentServer()
                        .map(connection -> router.isLimbo(connection.getServerInfo().getName()))
                        .orElse(true);
                if (onLimbo) {
                    continue;
                }
                touchSessionOf(player);
            }
        }).repeat(1, TimeUnit.MINUTES).schedule();
    }

    private void touchSessionOf(Player player) {
        var context = net.kofnetwork.auth.api.dto.AuthContext.minecraft(
                net.kofnetwork.auth.api.model.IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());

        core.sessions().touchPlayer(player.getUniqueId(), context).exceptionally(e -> {
            logger.warn("Не удалось продлить сессию игрока {}", player.getUsername(), e);
            return null;
        });
    }

    /** Момент подключения игрока — основа для таймаута входа. */
    private final java.util.Map<java.util.UUID, Long> connectedAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Subscribe
    public void onPostLogin(com.velocitypowered.api.event.connection.PostLoginEvent event) {
        connectedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        connectedAt.remove(uuid);
        if (adminCommand != null) {
            adminCommand.forget(uuid);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.close();
        }
    }
}
