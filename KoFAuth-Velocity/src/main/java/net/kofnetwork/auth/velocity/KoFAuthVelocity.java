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
import net.kofnetwork.auth.velocity.command.LoginCommand;
import net.kofnetwork.auth.velocity.command.RegisterCommand;
import net.kofnetwork.auth.velocity.limbo.LimboRouter;
import net.kofnetwork.auth.velocity.listener.AuthenticationListener;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
        commands.register(auth, new AuthAdminCommand(core, messages, logger));

        CommandMeta email = commands.metaBuilder("email")
                .aliases("почта")
                .plugin(this)
                .build();
        commands.register(email, new EmailCommand(core, messages, logger));
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
                kickByAccount(event.accountId(), event.reason());
            }
        });
    }

    private void disconnectAffectedPlayers(RemoteEvent event) {
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        kickByAccount(accountId, event.attribute("reason", "SESSION_REVOKED"));
    }

    /**
     * Выкидывает игроков отозванного аккаунта обратно в Limbo.
     *
     * <p>Сопоставление идёт через проверку сессии: держать на прокси карту
     * «accountId → игрок» значило бы дублировать состояние, которое уже есть
     * в Redis, и рассинхронизироваться с ним при каждом переподключении.
     */
    private void kickByAccount(long accountId, String reason) {
        for (Player player : proxy.getAllPlayers()) {
            core.authentication().findAccount(player.getUsername()).thenAccept(account -> {
                if (account.isEmpty() || account.get().id() != accountId) {
                    return;
                }
                core.sessions().setState(player.getUniqueId(), AuthState.BLOCKED);
                player.disconnect(messages.kick(
                        "PASSWORD_CHANGED".equals(reason) ? "password-changed" : "session-revoked",
                        "<yellow>Ваша сессия завершена. Войдите заново."));
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

    /** Момент подключения игрока — основа для таймаута входа. */
    private final java.util.Map<java.util.UUID, Long> connectedAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Subscribe
    public void onPostLogin(com.velocitypowered.api.event.connection.PostLoginEvent event) {
        connectedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        connectedAt.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.close();
        }
    }
}
