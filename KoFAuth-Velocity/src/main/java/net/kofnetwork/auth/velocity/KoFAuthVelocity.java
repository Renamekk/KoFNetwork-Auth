package net.kofnetwork.auth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.event.events.LoginApprovalDecidedEvent;
import net.kofnetwork.auth.api.event.events.RemoteEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.command.AuthAdminCommand;
import net.kofnetwork.auth.velocity.command.EmailCommand;
import net.kofnetwork.auth.velocity.command.LinkCommand;
import net.kofnetwork.auth.velocity.command.LoginCommand;
import net.kofnetwork.auth.velocity.command.RegisterCommand;
import net.kofnetwork.auth.velocity.command.WebLinkCommand;
import net.kofnetwork.auth.velocity.limbo.HttpLimboControlPlane;
import net.kofnetwork.auth.velocity.limbo.LimboControlPlane;
import net.kofnetwork.auth.velocity.limbo.LimboLifecycleController;
import net.kofnetwork.auth.velocity.limbo.PlayerTransfer;
import net.kofnetwork.auth.velocity.limbo.ServerHealth;
import net.kofnetwork.auth.velocity.limbo.ServerPool;
import net.kofnetwork.auth.velocity.listener.AuthenticationListener;
import net.kofnetwork.auth.velocity.listener.PendingLogins;
import net.kofnetwork.auth.velocity.message.MessageService;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Плагин Velocity: гейт аутентификации сети.
 *
 * <p>Владеет экземпляром {@link KoFAuthCore} — прокси стартует раньше бэкендов,
 * поэтому именно он поднимает базу и применяет миграции. Paper-серверы подключаются
 * к уже готовой схеме.
 *
 * <p><b>Прокси без аутентификации не работает.</b> Прежняя версия при отказе запуска
 * писала в лог «сеть работает БЕЗ аутентификации» и продолжала работу: обработчики не
 * регистрировались, команды не появлялись, и любой желающий заходил под любым ником.
 * Предупреждение в логе — не защита. Теперь отказ запуска останавливает прокси.
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
    private ServerPool pool;
    private ServerHealth health;
    private PlayerTransfer transfer;
    private LimboLifecycleController lifecycle;
    private MessageService messages;
    private AuthAdminCommand adminCommand;

    private final PendingLogins pendingLogins = new PendingLogins();

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
            // Прокси, пропускающий всех подряд, хуже прокси, который не поднялся:
            // первый молча отдаёт чужие аккаунты, второй виден сразу.
            logger.error("KoFAuth не запустился. Прокси останавливается: работать без "
                    + "аутентификации нельзя — любой игрок зашёл бы под любым ником.", e);
            proxy.shutdown();
            return;
        }

        this.health = new ServerHealth(proxy, logger);
        this.pool = new ServerPool(proxy, core.config(), health, logger);
        this.transfer = new PlayerTransfer(pool, logger);
        this.messages = new MessageService(core.config());

        LimboControlPlane controlPlane =
                HttpLimboControlPlane.fromConfig(core.config(), pool.limboNames(), logger);
        this.lifecycle = new LimboLifecycleController(pool, health, controlPlane,
                core.config(), logger);

        registerCommands();
        proxy.getEventManager().register(this, new AuthenticationListener(
                core, pool, transfer, messages, pendingLogins, logger));

        subscribeToRemoteEvents();
        scheduleHealthProbe();
        scheduleLifecycle();
        scheduleLoginTimeout();
        scheduleSessionKeepAlive();
        scheduleSessionReconciliation();

        reportServerPools();
        logger.info("KoFAuth запущен на прокси");
    }

    /**
     * Сообщает, какие серверы из конфигурации прокси действительно знает.
     *
     * <p>Один раз при запуске, а не при каждом опросе. Перечень в
     * {@code velocity.yml} описывает целевую сеть — пять Limbo и десять хабов, —
     * а поднято обычно меньше; повторять это предупреждение каждые десять секунд
     * значит превратить лог в шум, в котором потеряется настоящая опечатка.
     */
    private void reportServerPools() {
        reportPool("Limbo", pool.limboNames());
        reportPool("Хабы", pool.hubNames());

        if (pool.limboNames().stream().noneMatch(name -> proxy.getServer(name).isPresent())) {
            logger.error("Ни один Limbo из velocity.yml не описан в velocity.toml — "
                    + "игроки не смогут пройти аутентификацию");
        }
        if (pool.hubNames().stream().noneMatch(name -> proxy.getServer(name).isPresent())) {
            logger.error("Ни один хаб из velocity.yml не описан в velocity.toml — "
                    + "вошедшего игрока будет некуда перевести");
        }
    }

    private void reportPool(String kind, List<String> names) {
        List<String> present = names.stream()
                .filter(name -> proxy.getServer(name).isPresent())
                .toList();
        List<String> missing = names.stream()
                .filter(name -> proxy.getServer(name).isEmpty())
                .toList();

        logger.info("{}: подключены {} из {} — {}", kind, present.size(), names.size(), present);
        if (!missing.isEmpty()) {
            logger.info("{}: не описаны в velocity.toml и в маршрутизации не участвуют — {}",
                    kind, missing);
        }
    }

    private void registerCommands() {
        CommandManager commands = proxy.getCommandManager();

        CommandMeta login = commands.metaBuilder("login")
                .aliases("l", "войти")
                .plugin(this)
                .build();
        commands.register(login, new LoginCommand(core, transfer, messages, pendingLogins, logger));

        CommandMeta register = commands.metaBuilder("register")
                .aliases("reg", "регистрация")
                .plugin(this)
                .build();
        commands.register(register,
                new RegisterCommand(core, transfer, messages, pendingLogins, logger));

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

    // ================================================================ события сети

    /**
     * Реакция на события с других узлов сети.
     *
     * <p>Смена пароля на сайте происходит в другом процессе. Без этой подписки
     * игрок с угнанной сессией продолжал бы играть, несмотря на смену пароля
     * владельцем, — то есть смена пароля не давала бы главного, ради чего её делают.
     * Подтверждение входа кнопкой приходит оттуда же: решение принимается в процессе
     * WebAPI, а ждёт его игрок, подключённый сюда.
     */
    private void subscribeToRemoteEvents() {
        core.events().subscribe(RemoteEvent.class, event -> {
            if (event.accountId() == null) {
                return;
            }
            if (event.isType(SessionInvalidatedEvent.class)) {
                disconnectAffectedPlayers(event);
            } else if (event.isType(LoginApprovalDecidedEvent.class)) {
                applyRemoteApproval(event);
            }
        });

        // Локальные события того же типа — от этого же узла.
        core.events().subscribe(SessionInvalidatedEvent.class, event -> {
            if (event.isNoop() || event.accountId() == null) {
                // Отзыв, не затронувший ни одной сессии, не повод обходить всех игроков.
                return;
            }
            kickByAccount(event.accountId(), event.reason(), event::affects);
        });

        core.events().subscribe(LoginApprovalDecidedEvent.class, event ->
                applyApproval(event.playerUuid(), event.attemptId(), event.status(),
                        event.username()));
    }

    private void disconnectAffectedPlayers(RemoteEvent event) {
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        Predicate<String> affects = revokedSessions(
                event.attribute("scope", ""),
                event.attribute("sessions", ""),
                event.booleanAttribute("affectsAll"));

        kickByAccount(accountId, event.attribute("reason", "SESSION_REVOKED"), affects);
    }

    /**
     * Разбирает перечень отозванных сессий из удалённого события.
     *
     * <p>По сети список едет строкой через запятую — в плоском {@code RemoteEvent}
     * массива нет. Охват приезжает отдельным полем, и это существенно: пустая строка
     * при {@code NONE} и при {@code ALL} выглядит одинаково, а означает
     * противоположное. Раньше охват выводили из содержимого, и отзыв, которому нечего
     * было отзывать, выбрасывал из игры всех, кого не касался.
     *
     * @param scope       значение {@link SessionInvalidatedEvent.Scope}; пусто у узлов
     *                    прежней версии, для которых остаётся {@code affectsAll}
     * @param affectsAll  запасной признак для совместимости
     */
    static Predicate<String> revokedSessions(String scope, String sessionsAttribute,
                                             boolean affectsAll) {
        SessionInvalidatedEvent.Scope parsed = scope == null || scope.isBlank()
                ? (affectsAll ? SessionInvalidatedEvent.Scope.ALL : legacyScope(sessionsAttribute))
                : SessionInvalidatedEvent.Scope.parse(scope);

        return switch (parsed) {
            case ALL -> publicId -> true;
            case NONE -> publicId -> false;
            case SOME -> {
                Set<String> revoked = split(sessionsAttribute);
                yield revoked::contains;
            }
        };
    }

    /** Узел прежней версии не присылал охват: выводим его из содержимого. */
    private static SessionInvalidatedEvent.Scope legacyScope(String sessionsAttribute) {
        return split(sessionsAttribute).isEmpty()
                ? SessionInvalidatedEvent.Scope.NONE
                : SessionInvalidatedEvent.Scope.SOME;
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());
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
     * сюда и выбрасывало того самого игрока, который только что ввёл пароль.
     *
     * <p><b>Читается привязка, а не результат проверки сессии.</b> {@code validate}
     * для этой задачи не годится: он возвращает пустое значение и для отозванной, и для
     * просто истёкшей сессии, и для игрока в Limbo, — а «сессии нет» это не то же самое,
     * что «названа этим отзывом». Кроме того, он не только читает: при несовпадении
     * адреса он отзывает сессию и публикует событие, то есть обход всех игроков сети на
     * каждый отзыв мог порождать новые отзывы.
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
                    returnToAuthentication(player, reason);
                }).exceptionally(e -> {
                    logger.warn("Не удалось прочитать сессию игрока {} при отзыве",
                            player.getUsername(), e);
                    return null;
                });
            });
        }
    }

    /** Возвращает игрока к аутентификации: сессия его больше не пускает. */
    private void returnToAuthentication(Player player, String reason) {
        pendingLogins.reset(player.getUniqueId());
        core.sessions().resetState(player.getUniqueId(), AuthState.BLOCKED)
                .whenComplete((ignored, failure) -> player.disconnect(messages.kick(
                        "PASSWORD_CHANGED".equals(reason) ? "password-changed" : "session-revoked",
                        "<yellow>Ваша сессия завершена. Войдите заново.")));
    }

    // ================================================================ подтверждение входа

    private void applyRemoteApproval(RemoteEvent event) {
        String rawUuid = event.attribute("playerUuid", "");
        if (rawUuid.isBlank()) {
            return;
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException e) {
            logger.warn("Событие подтверждения принесло неразбираемый UUID: {}", rawUuid);
            return;
        }
        ApprovalStatus status;
        try {
            status = ApprovalStatus.valueOf(event.attribute("status", "DENIED"));
        } catch (IllegalArgumentException e) {
            status = ApprovalStatus.DENIED;
        }
        applyApproval(playerUuid, event.attribute("attemptId", ""), status,
                event.attribute("username", ""));
    }

    /**
     * Доводит решение владельца до игрока.
     *
     * <p>Именно этого шага не было раньше вовсе: погашение кода создавало отдельную
     * сессию мессенджера, а игрок оставался в {@code TWO_FACTOR_REQUIRED} и в Limbo до
     * самого таймаута — даже нажав «Это я».
     *
     * <p>Метка попытки сверяется обязательно. Игрок мог начать вход заново, и решение по
     * прежней попытке не должно ни пускать его, ни выбрасывать.
     */
    private void applyApproval(UUID playerUuid, String attemptId, ApprovalStatus status,
                               String username) {
        if (playerUuid == null) {
            return;
        }
        Player player = proxy.getPlayer(playerUuid).orElse(null);
        if (player == null) {
            // Игрок отключился, не дождавшись. Сессия при одобрении уже создана —
            // вернувшись, он войдёт без пароля, и это ровно то, чего он просил.
            return;
        }
        if (!attemptId.isEmpty() && !pendingLogins.matchesAttempt(playerUuid, attemptId)) {
            logger.debug("Решение по устаревшей попытке {} игрока {} отброшено",
                    attemptId, username);
            return;
        }

        if (status != ApprovalStatus.APPROVED) {
            pendingLogins.reset(playerUuid);
            core.sessions().resetState(playerUuid, AuthState.AWAITING_LOGIN)
                    .whenComplete((ignored, failure) -> player.sendMessage(messages.prefixed(
                            status == ApprovalStatus.EXPIRED ? "approval-expired" : "approval-denied",
                            status == ApprovalStatus.EXPIRED
                                    ? "<red>Время подтверждения истекло. Попробуйте войти заново."
                                    : "<red>Вход отклонён владельцем аккаунта.")));
            return;
        }

        // Отметка ставится до перевода: перевод занимает время, и без неё игрок успевал
        // попасть под отключение по таймауту уже после успешного подтверждения.
        pendingLogins.completed(playerUuid);
        core.sessions().resetState(playerUuid, AuthState.AUTHENTICATED)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logger.error("Подтверждение принято, но состояние игрока {} не записано",
                                username, failure);
                        player.disconnect(messages.kick("error",
                                "<red>Внутренняя ошибка. Подключитесь заново."));
                        return;
                    }
                    player.sendMessage(messages.prefixed("login-success",
                            "<green>Вход подтверждён. Приятной игры!"));
                    sendToHub(player);
                });
    }

    /** Переводит игрока на хаб и сообщает, если ни один не принял. */
    private void sendToHub(Player player) {
        transfer.toHub(player).thenAccept(outcome -> {
            if (!outcome.success()) {
                player.sendMessage(messages.prefixed("hub-unavailable",
                        "<red>Хаб недоступен. Сообщите администрации."));
            }
        });
    }

    // ================================================================ планировщики

    /**
     * Опрашивает бэкенды.
     *
     * <p>Без этого выключенный сервер остаётся кандидатом на подключение — и, будучи
     * пустым, самым привлекательным для стратегии «наименее загруженный».
     */
    private void scheduleHealthProbe() {
        Duration interval = core.config().getDuration(ConfigFile.VELOCITY,
                "health.probe-interval", Duration.ofSeconds(10));
        Duration timeout = core.config().getDuration(ConfigFile.VELOCITY,
                "health.probe-timeout", Duration.ofSeconds(3));

        proxy.getScheduler().buildTask(this, () -> {
            List<String> all = new ArrayList<>(pool.limboNames());
            all.addAll(pool.hubNames());
            health.probe(all, timeout);
        }).repeat(Math.max(1, interval.toSeconds()), TimeUnit.SECONDS).schedule();
    }

    /** Регулирует число включённых Limbo-инстансов. */
    private void scheduleLifecycle() {
        Duration interval = core.config().getDuration(ConfigFile.VELOCITY,
                "limbo.lifecycle.interval", Duration.ofSeconds(15));

        proxy.getScheduler().buildTask(this, () -> lifecycle.sweep())
                .repeat(Math.max(5, interval.toSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Отключает тех, кто не успел войти.
     *
     * <p>Без этого соединения в Limbo копятся: боту достаточно подключиться и молчать,
     * чтобы занять слот.
     *
     * <p><b>Гонка снята.</b> Раньше решение принималось по двум величинам — моменту
     * подключения и состоянию в Redis, — и между их чтением и вызовом
     * {@code disconnect()} помещался успешный вход: игрок вводил верный пароль и тут же
     * получал «Вы не успели войти». Теперь освобождение от таймаута отмечается
     * {@link PendingLogins} до начала перевода на хаб и проверяется здесь же, в одном
     * месте с решением.
     */
    private void scheduleLoginTimeout() {
        proxy.getScheduler().buildTask(this, () -> {
            Duration timeout = core.config().getDuration(ConfigFile.CONFIG,
                    "auth.login.timeout", Duration.ofSeconds(60));
            Instant now = Instant.now();
            Instant deadline = now.minus(timeout);

            for (Player player : proxy.getAllPlayers()) {
                UUID uuid = player.getUniqueId();
                boolean onLimbo = player.getCurrentServer()
                        .map(connection -> pool.isLimbo(connection.getServerInfo().getName()))
                        .orElse(false);
                if (!onLimbo) {
                    continue;
                }
                if (pendingLogins.connectedAt(uuid).isAfter(deadline)) {
                    continue;
                }
                if (pendingLogins.isExemptFromTimeout(uuid, now)) {
                    // Вход состоялся либо игрок ждёт кнопку в мессенджере — отдельный
                    // срок ожидания у этого случая свой.
                    continue;
                }
                core.sessions().getState(uuid).thenAccept(state -> {
                    // Повторная проверка отметки: между чтением состояния и отключением
                    // вход мог завершиться.
                    if (state.requiresLimbo()
                            && !pendingLogins.isExemptFromTimeout(uuid, Instant.now())) {
                        player.disconnect(messages.kick("timeout",
                                "<red>Вы не успели войти за отведённое время."));
                    }
                }).exceptionally(e -> {
                    // Состояние не прочиталось — хранилище недоступно. Отключать по
                    // таймауту в этот момент нельзя: причина не в игроке.
                    logger.warn("Состояние игрока {} не прочитано, таймаут не применён",
                            player.getUsername(), e);
                    return null;
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
     */
    private void scheduleSessionKeepAlive() {
        proxy.getScheduler().buildTask(this, () -> {
            for (Player player : proxy.getAllPlayers()) {
                boolean onLimbo = player.getCurrentServer()
                        .map(connection -> pool.isLimbo(connection.getServerInfo().getName()))
                        .orElse(true);
                if (onLimbo) {
                    continue;
                }
                touchSessionOf(player);
            }
        }).repeat(1, TimeUnit.MINUTES).schedule();
    }

    /**
     * Сверяет, что играющие игроки всё ещё держат действующую сессию.
     *
     * <p><b>Зачем это нужно поверх событий.</b> Redis Pub/Sub не хранит сообщений:
     * узел, потерявший соединение на несколько секунд, не узнает об отзывах, случившихся
     * за это время, — и игрок с отозванной сессией продолжит играть до истечения срока.
     * Событие остаётся быстрым путём, а этот обход — медленным, но не теряющимся:
     * он опирается на состояние в базе, а не на факт доставки.
     *
     * <p>Проверяются только те, кто вне Limbo: у остальных сессии нет по определению.
     */
    private void scheduleSessionReconciliation() {
        Duration interval = core.config().getDuration(ConfigFile.VELOCITY,
                "session.reconcile-interval", Duration.ofSeconds(30));

        proxy.getScheduler().buildTask(this, () -> {
            for (Player player : proxy.getAllPlayers()) {
                boolean onLimbo = player.getCurrentServer()
                        .map(connection -> pool.isLimbo(connection.getServerInfo().getName()))
                        .orElse(true);
                if (onLimbo) {
                    continue;
                }
                reconcile(player);
            }
        }).repeat(Math.max(5, interval.toSeconds()), TimeUnit.SECONDS).schedule();
    }

    private void reconcile(Player player) {
        UUID uuid = player.getUniqueId();
        core.sessions().hasValidSession(uuid).thenAccept(valid -> {
            if (valid) {
                return;
            }
            logger.info("Сессия игрока {} больше не действует: возвращаю к аутентификации",
                    player.getUsername());
            returnToAuthentication(player, "SESSION_REVOKED");
        }).exceptionally(e -> {
            // Хранилище не ответило. Выбрасывать игроков по недоступности базы нельзя:
            // это превратило бы кратковременный сбой в массовое отключение сети.
            logger.warn("Не удалось сверить сессию игрока {}", player.getUsername(), e);
            return null;
        });
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

    // ================================================================ соединения

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        pendingLogins.connected(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingLogins.disconnected(uuid);
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
