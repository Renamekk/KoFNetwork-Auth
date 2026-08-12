package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.Severity;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Административная команда {@code /auth}.
 *
 * <p><b>Права проверяются по RBAC из базы, а не по правам Velocity.</b> Роли
 * KoFAuth общие для всей сети и меняются из панели; дублировать их в
 * {@code permissions.json} каждого прокси означало бы держать два источника истины,
 * которые неизбежно разойдутся.
 *
 * <p><b>Поверх ролей стоит безусловный доступ.</b> Консоль, OP игрового сервера и
 * перечисленные в {@code velocity.yml → admin.operators} получают все подкоманды
 * без исключений и без разбора узлов: тот, кто может выдать себе OP, и так
 * распоряжается сервером, и отказывать ему в просмотре чужого адреса
 * бессмысленно. Разбор источников доступа — в {@link AdminAccess}.
 *
 * <p>Каждое действие над чужим аккаунтом пишется в аудит с указанием исполнителя:
 * по журналу должно быть видно не только что произошло, но и кто это сделал.
 */
public final class AuthAdminCommand implements SimpleCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    /**
     * Описание подкоманды: то же самое питает и справку, и автодополнение.
     *
     * <p>Один источник вместо двух списков — иначе добавленная подкоманда
     * неизбежно оказывается либо без подсказки, либо без описания в справке.
     *
     * @param name        имя подкоманды
     * @param syntax      аргументы в том виде, в каком их набирает администратор
     * @param summary     одна строка для общего списка
     * @param details     развёрнутое объяснение: что именно делает и чем чревато
     * @param examples    примеры вызова
     * @param takesPlayer нужен ли вторым аргументом ник — по нему строится
     *                    автодополнение
     */
    private record SubCommand(String name,
                              String syntax,
                              String summary,
                              List<String> details,
                              List<String> examples,
                              boolean takesPlayer) {

        String permission() {
            return "kofauth.admin." + permissionFor(name);
        }
    }

    private static final List<SubCommand> SUBCOMMANDS = List.of(
            new SubCommand("help", "[подкоманда]",
                    "справка по командам",
                    List.of("Без аргумента — список всех подкоманд.",
                            "С аргументом — подробное описание одной."),
                    List.of("/auth help", "/auth help resetpassword"), false),

            new SubCommand("reload", "",
                    "перечитать конфигурацию",
                    List.of("Перечитывает все YAML-файлы без перезапуска сети.",
                            "Соединения с базой и Redis не пересоздаются, игроки не отключаются.",
                            "Изменения размеров пулов и параметров подключения к базе",
                            "требуют полного перезапуска — они применяются только при старте."),
                    List.of("/auth reload"), false),

            new SubCommand("info", "",
                    "состояние системы",
                    List.of("Готовность, состояние пула базы, кэша и рабочих пулов.",
                            "Первое, что стоит посмотреть при жалобах на медленный вход."),
                    List.of("/auth info"), false),

            new SubCommand("player", "<ник>",
                    "сведения об аккаунте",
                    List.of("Статус, дата регистрации, второй фактор, CAPTCHA,",
                            "геолокация и число неудачных попыток.",
                            "<red>Адреса показываются полностью: адрес регистрации,",
                            "<red>первого и последнего входа. Это персональные данные —",
                            "<red>не выводите команду на стрим и в общий чат."),
                    List.of("/auth player Steve"), true),

            new SubCommand("lock", "<ник>",
                    "заблокировать аккаунт",
                    List.of("Вход закрывается, все сессии разрываются немедленно.",
                            "Игрок остаётся в базе; данные не удаляются.",
                            "Снимается командой /auth unlock."),
                    List.of("/auth lock Steve"), true),

            new SubCommand("unlock", "<ник>",
                    "разблокировать аккаунт",
                    List.of("Возвращает статус ACTIVE и снимает ограничение частоты",
                            "попыток входа по этому нику — иначе игрок упрётся в лимит,",
                            "накопленный до блокировки."),
                    List.of("/auth unlock Steve"), true),

            new SubCommand("resetpassword", "<ник> <новый пароль>",
                    "сменить пароль игроку",
                    List.of("Пароль проверяется политикой сложности, как при регистрации.",
                            "Все сессии аккаунта разрываются, refresh-токены отзываются.",
                            "Игрок получает уведомление на почту, если она подтверждена.",
                            "<red>Пароль виден в истории команд — выдавайте временный",
                            "<red>и требуйте сменить его самостоятельно."),
                    List.of("/auth resetpassword Steve Vremenny42Parol"), true),

            new SubCommand("forceverify", "<ник>",
                    "подтвердить почту вручную",
                    List.of("Помечает привязанную почту подтверждённой без кода.",
                            "Нужно, когда письмо не доходит, а восстановление пароля",
                            "игроку требуется. Почта должна быть уже привязана."),
                    List.of("/auth forceverify Steve"), true),

            new SubCommand("sessions", "<ник>",
                    "активные сессии игрока",
                    List.of("Тип, адрес, время последней активности и сервер.",
                            "Несколько игровых сессий при max-concurrent: 1 —",
                            "признак того, что аккаунтом пользуются не только владельцем."),
                    List.of("/auth sessions Steve"), true),

            new SubCommand("logout", "<ник>",
                    "сбросить сессии игрока",
                    List.of("Завершает все сессии аккаунта: игровые, веб и токены.",
                            "Игрока выбрасывает к аутентификации немедленно — он",
                            "проходит вход заново с вводом пароля и второго фактора.",
                            "Аккаунт при этом НЕ блокируется: войти он сможет сразу.",
                            "Нужна, когда пароль мог утечь, а блокировать аккаунт",
                            "не за что: /auth lock для этого слишком грубый инструмент."),
                    List.of("/auth logout Steve"), true),

            new SubCommand("devices", "<ник>",
                    "устройства игрока",
                    List.of("Список известных устройств с отметками доверия и блокировки.",
                            "Доверенное устройство не требует второго фактора."),
                    List.of("/auth devices Steve"), true),

            new SubCommand("logs", "<ник>",
                    "журнал безопасности игрока",
                    List.of("Последние 15 событий: входы, отказы, смены пароля и привязок.",
                            "Полная история — в личном кабинете и в таблице security_logs."),
                    List.of("/auth logs Steve"), true),

            new SubCommand("migrate", "",
                    "применить миграции схемы",
                    List.of("Нужна при migrate-on-startup: false.",
                            "Момент обновления схемы выбирает администратор, а не гонка",
                            "нескольких прокси за то, кто первым перезапустится."),
                    List.of("/auth migrate"), false),

            new SubCommand("export", "<файл>",
                    "выгрузить аккаунты",
                    List.of("<red>Файл содержит хэши паролей всей сети.",
                            "<red>Обращайтесь с ним как с дампом базы.",
                            "Событие пишется в журнал как критическое."),
                    List.of("/auth export backup/accounts.json"), false),

            new SubCommand("import", "<файл>",
                    "загрузить аккаунты из выгрузки",
                    List.of("Существующие ники пропускаются, а не перезаписываются.",
                            "В отчёте видно, сколько записей загружено и сколько пропущено."),
                    List.of("/auth import backup/accounts.json"), false));

    private static final List<String> SUBCOMMAND_NAMES =
            SUBCOMMANDS.stream().map(SubCommand::name).toList();

    private final KoFAuthCore core;
    private final ProxyServer proxy;
    private final MessageService messages;
    private final AdminAccess access;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthAdminCommand(@NotNull KoFAuthCore core,
                            @NotNull ProxyServer proxy,
                            @NotNull MessageService messages,
                            @NotNull AdminAccess access,
                            @NotNull Logger logger) {
        this.core = core;
        this.proxy = proxy;
        this.messages = messages;
        this.access = access;
        this.logger = logger;
    }

    private static Optional<SubCommand> describe(String name) {
        return SUBCOMMANDS.stream()
                .filter(sub -> sub.name().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            usage(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // Справка не требует прав: она не раскрывает данных и нужна прежде всего
        // тому, кто ещё не разобрался, какие права ему выданы.
        if ("help".equals(sub) || "?".equals(sub) || "справка".equals(sub)) {
            help(source, args.length > 1 ? args[1] : null);
            return;
        }

        access.allows(source, "kofauth.admin." + permissionFor(sub)).thenAccept(allowed -> {
            if (!allowed) {
                source.sendMessage(miniMessage.deserialize("<red>Недостаточно прав."));
                return;
            }
            dispatch(source, sub, args);
        });
    }

    private static String permissionFor(String sub) {
        return switch (sub) {
            case "unlock" -> "lock";
            case "resetpassword" -> "resetpassword";
            case "forceverify" -> "forceverify";
            default -> sub;
        };
    }

    private void dispatch(CommandSource source, String sub, String[] args) {
        switch (sub) {
            case "reload" -> reload(source);
            case "info" -> info(source);
            case "player" -> withAccount(source, args, this::showPlayer);
            case "lock" -> withAccount(source, args, (s, a) -> setStatus(s, a, AccountStatus.LOCKED));
            case "unlock" -> withAccount(source, args, (s, a) -> setStatus(s, a, AccountStatus.ACTIVE));
            case "resetpassword" -> resetPassword(source, args);
            case "forceverify" -> withAccount(source, args, this::forceVerify);
            case "sessions" -> withAccount(source, args, this::showSessions);
            case "logout" -> withAccount(source, args, this::dropSessions);
            case "devices" -> withAccount(source, args, this::showDevices);
            case "logs" -> withAccount(source, args, this::showLogs);
            case "migrate" -> migrate(source);
            case "export" -> export(source, args);
            case "import" -> importAccounts(source, args);
            default -> usage(source);
        }
    }

    // ------------------------------------------------------------------ подкоманды

    private void reload(CommandSource source) {
        // Заодно сбрасываем отражение прав: выданная только что роль должна
        // подхватываться перезагрузкой, а не переподключением игрока.
        access.invalidate();
        source.sendMessage(prefixed("<yellow>Перезагрузка конфигурации..."));
        core.config().reload().thenAccept(report -> {
            if (report.success()) {
                source.sendMessage(prefixed("<green>Конфигурация перезагружена за "
                        + report.durationMs() + " мс"));
                return;
            }
            source.sendMessage(prefixed("<red>Перезагрузка завершилась с ошибками:"));
            report.errors().forEach((component, error) ->
                    source.sendMessage(miniMessage.deserialize(
                            "  <gray>— <white>" + component + "<gray>: <red>" + escape(error))));
        });
    }

    private void info(CommandSource source) {
        var pool = core.database().snapshot();
        var pools = core.executors().stats();

        source.sendMessage(prefixed("<gray>─── <white>KoFAuth " + core.version() + " <gray>───"));
        line(source, "Состояние", core.isReady() ? "<green>готов" : "<red>не готов");
        line(source, "База", pool.up()
                ? "<green>подключена <gray>(активных " + pool.active() + " из " + pool.total()
                        + ", ожидают " + pool.awaiting() + ")"
                : "<red>недоступна");
        line(source, "Кэш", core.cache().isAvailable()
                ? "<green>" + core.cache().providerName()
                : "<yellow>отключён <gray>(сессии не общие между процессами)");
        // Подъём прав по OP работает только через общее хранилище. Молчать об
        // этом нельзя: неработающий признак выглядит как поломка плагина.
        line(source, "Права по OP", access.operatorsShared()
                ? "<green>работают <gray>(признак приходит с игровых серверов)"
                : "<yellow>не переносятся <gray>(нет общего Redis — "
                        + "администраторов задаёт velocity.yml → admin.operators)");
        line(source, "Пул БД", "активных " + pools.databaseActive()
                + ", в очереди " + pools.databaseQueued());
        line(source, "Пул I/O", "активных " + pools.ioActive()
                + ", в очереди " + pools.ioQueued());
    }

    /**
     * Карточка аккаунта для администратора — <b>без маскирования адресов</b>.
     *
     * <p>Разбор инцидента ведётся по адресам, и {@code 192.168.0.***} для него
     * бесполезен: по нему не сверить два входа между собой, не сопоставить
     * с логом обратного прокси и не отличить соседа по NAT от чужого человека.
     * Прежняя маскировка защищала не от администратора — она мешала именно ему,
     * оставляя единственным способом узнать адрес прямой запрос к базе.
     *
     * <p>Показываются три адреса, и они отвечают на разные вопросы:
     * <ul>
     *   <li><b>регистрация</b> — откуда аккаунт создан;</li>
     *   <li><b>первый вход</b> — откуда им начали пользоваться. При угоне сразу
     *       после регистрации эти два расходятся, и по одному лишь адресу
     *       регистрации подмену не увидеть;</li>
     *   <li><b>последний вход</b> — откуда им пользуются сейчас.</li>
     * </ul>
     *
     * <p>Строка первого входа читается из {@code login_history} и может быть
     * пуста: записи чистятся по сроку хранения. Пустота означает исчерпанную
     * ретенцию, а не отсутствие входов, и так и подписана.
     */
    private void showPlayer(CommandSource source, Account account) {
        // Первый вход читается ДО вывода, а не по ходу его. В аккаунте хранится
        // только последний вход, первый лежит в login_history, и печать из
        // колбэка развалила бы порядок карточки: строка приезжала бы после
        // всех остальных, то есть под «CAPTCHA пройдена», а не рядом
        // с регистрацией и последним входом, с которыми её и сравнивают.
        core.adminOperations().firstLogin(account.id())
                .exceptionally(e -> {
                    logger.warn("Не удалось прочитать первый вход аккаунта {}",
                            account.username(), e);
                    return Optional.empty();
                })
                .thenAccept(first -> renderPlayer(source, account, first));
    }

    private void renderPlayer(CommandSource source,
                              Account account,
                              Optional<net.kofnetwork.auth.api.model.LoginAttempt> firstLogin) {
        Instant now = Instant.now();
        source.sendMessage(prefixed("<gray>─── <white>" + account.username() + " <gray>───"));
        line(source, "UUID", account.uuid().toString());
        line(source, "Статус", statusColor(account) + account.status());
        line(source, "Лицензионный", account.premium() ? "да" : "нет");
        line(source, "Регистрация", TIME.format(account.registrationDate())
                + " <gray>с <aqua>" + account.registrationIp().asString());
        line(source, "Первый вход", firstLogin
                .map(attempt -> TIME.format(attempt.createdAt())
                        + " <gray>с <aqua>" + attempt.ip().asString()
                        + locationOf(attempt.country(), attempt.city()))
                .orElse("<gray>нет записей <dark_gray>(история очищена по сроку хранения)"));
        line(source, "Последний вход", account.lastLoginAt() == null
                ? "<gray>никогда"
                : TIME.format(account.lastLoginAt()) + " <gray>с <aqua>"
                        + (account.lastLoginIp() == null ? "?" : account.lastLoginIp().asString()));
        line(source, "Геолокация", account.lastCountry() == null
                ? "<gray>неизвестна"
                : account.lastCountry() + (account.lastCity() == null ? "" : ", " + account.lastCity()));
        line(source, "Неудачных попыток", String.valueOf(account.failedLoginAttempts()));
        if (account.isTemporarilyLocked(now)) {
            line(source, "Блокировка до", TIME.format(account.lockedUntil()));
        }
        line(source, "Второй фактор", account.hasTwoFactor()
                ? account.twoFactorMethods().toString()
                : "<gray>выключен");
        line(source, "CAPTCHA пройдена", account.captchaPassed() ? "да" : "нет");
    }

    /** Страна и город попытки в скобках; пусто, если геолокация неизвестна. */
    private static String locationOf(String country, String city) {
        if (country == null && city == null) {
            return "";
        }
        String place = country == null ? city : (city == null ? country : country + ", " + city);
        return " <dark_gray>(" + escape(place) + ")";
    }

    /**
     * Сбрасывает все сессии игрока, не блокируя аккаунт.
     *
     * <p>Отзыв публикует {@link net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent},
     * а на него уже подписан прокси: игрок с отозванной сессией возвращается
     * к аутентификации сам, без отдельного обхода списка подключённых. Поэтому
     * здесь только отзыв и запись в аудит — выбрасывать игрока вручную значило бы
     * продублировать путь, который и так работает для всех узлов сети, включая
     * соседние прокси.
     *
     * <p>Отличие от {@code /auth lock}: там вход закрывается совсем, здесь игрок
     * входит заново тем же паролем. Это разные инструменты для разных случаев —
     * «возможно, утёк пароль» и «этот аккаунт больше не должен заходить».
     */
    private void dropSessions(CommandSource source, Account account) {
        core.sessions().revokeAll(account.id(), null, Session.REASON_ADMIN)
                .thenCompose(revoked -> core.audit().logAdminAction(account.id(), actorId(source),
                                SecurityEventType.SESSION_REVOKED, contextOf(source),
                                "Сессии сброшены администратором")
                        .thenApply(ignored -> revoked))
                .thenAccept(revoked -> source.sendMessage(revoked == 0
                        ? prefixed("<yellow>У " + account.username()
                                + " не было активных сессий. Войти заново он всё равно обязан: "
                                + "привязка снята.")
                        : prefixed("<green>Сессии " + account.username()
                                + " сброшены <gray>(" + revoked + "). "
                                + "Игрок вернётся к вводу пароля.")))
                .exceptionally(e -> {
                    logger.error("Не удалось сбросить сессии аккаунта {}", account.username(), e);
                    source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
                    return null;
                });
    }

    private void setStatus(CommandSource source, Account account, AccountStatus status) {
        boolean locking = status != AccountStatus.ACTIVE;

        // Порядок важен: сначала статус, потом разрыв сессий. При обратном порядке
        // игрок успевает переподключиться в окно между отзывом и блокировкой.
        core.adminOperations().updateStatus(account.id(), status)
                .thenCompose(ignored -> locking
                        ? core.sessions().revokeAll(account.id(), null, Session.REASON_ADMIN)
                        : CompletableFuture.completedFuture(0))
                .thenCompose(ignored -> locking
                        ? CompletableFuture.<Void>completedFuture(null)
                        : core.security().resetRateLimit("login.per-account",
                                account.lowerUsername()))
                .thenCompose(ignored -> core.audit().logAdminAction(account.id(),
                        actorId(source), locking
                                ? SecurityEventType.ACCOUNT_LOCKED
                                : SecurityEventType.ACCOUNT_UNLOCKED,
                        contextOf(source),
                        locking ? "Аккаунт заблокирован" : "Аккаунт разблокирован"))
                .thenRun(() -> source.sendMessage(prefixed(locking
                        ? "<green>Аккаунт " + account.username() + " заблокирован, сессии разорваны"
                        : "<green>Аккаунт " + account.username() + " разблокирован")))
                .exceptionally(e -> {
                    logger.error("Не удалось изменить статус аккаунта {}", account.username(), e);
                    source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
                    return null;
                });
    }

    private void resetPassword(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(prefixed("<yellow>Использование: <white>/auth resetpassword <ник> <пароль>"));
            return;
        }
        withAccount(source, args, (s, account) ->
                core.authentication().adminResetPassword(account.id(), args[2],
                                actorId(source), contextOf(source))
                        .thenAccept(result -> s.sendMessage(result.isSuccess()
                                ? prefixed("<green>Пароль игрока " + account.username()
                                        + " изменён, сессии разорваны")
                                : prefixed("<red>Не удалось: " + escape(result.errorMessage())))));
    }

    private void forceVerify(CommandSource source, Account account) {
        core.email().findPrimary(account.id()).thenAccept(binding -> {
            if (binding.isEmpty()) {
                source.sendMessage(prefixed("<yellow>У игрока нет привязанной почты."));
                return;
            }
            core.audit().logAdminAction(account.id(), actorId(source),
                            SecurityEventType.EMAIL_VERIFIED, contextOf(source),
                            "Почта подтверждена администратором")
                    .thenRun(() -> source.sendMessage(
                            prefixed("<green>Почта игрока " + account.username() + " подтверждена")));
        });
    }

    /**
     * Сессии аккаунта с полными адресами.
     *
     * <p>Читаются доменные сессии, а не DTO личного кабинета: в DTO адрес
     * маскирован, потому что тот же список видит игрок на сайте. Здесь смотрит
     * администратор, и маска ему только мешает — по {@code 192.168.0.***} не
     * сверить две сессии между собой.
     */
    private void showSessions(CommandSource source, Account account) {
        core.adminOperations().listSessions(account.id()).thenAccept(sessions -> {
            if (sessions.isEmpty()) {
                source.sendMessage(prefixed("<gray>Активных сессий нет."));
                return;
            }
            source.sendMessage(prefixed("<white>Сессии " + account.username()
                    + " <gray>(" + sessions.size() + ")"));
            sessions.forEach(session -> source.sendMessage(miniMessage.deserialize(
                    "  <gray>— <white>" + session.type() + " <gray>| <aqua>"
                            + session.ip().asString()
                            + " <gray>| " + TIME.format(session.lastSeenAt())
                            + (session.server() == null ? "" : " | " + escape(session.server())))));
        }).exceptionally(e -> {
            logger.error("Не удалось прочитать сессии аккаунта {}", account.username(), e);
            source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
            return null;
        });
    }

    private void showDevices(CommandSource source, Account account) {
        source.sendMessage(prefixed("<white>Устройства " + account.username()));
        core.adminOperations().listDevices(account.id()).thenAccept(devices -> {
            if (devices.isEmpty()) {
                source.sendMessage(miniMessage.deserialize("  <gray>нет записей"));
                return;
            }
            devices.forEach(device -> source.sendMessage(miniMessage.deserialize(
                    "  <gray>— <white>" + escape(device.friendlyName())
                            + " <gray>| <aqua>" + device.lastSeenIp().asString()
                            + " <gray>| " + TIME.format(device.lastSeenAt())
                            + (device.trusted() ? " <green>[доверено]" : "")
                            + (device.blocked() ? " <red>[заблокировано]" : ""))));
        });
    }

    private void showLogs(CommandSource source, Account account) {
        core.audit().getSecurityLog(account.id(), Severity.INFO, 15, 0).thenAccept(entries -> {
            if (entries.isEmpty()) {
                source.sendMessage(prefixed("<gray>Журнал пуст."));
                return;
            }
            source.sendMessage(prefixed("<white>Журнал " + account.username()
                    + " <gray>(последние " + entries.size() + ")"));
            entries.forEach(entry -> source.sendMessage(miniMessage.deserialize(
                    "  " + severityColor(entry.severity()) + entry.eventType()
                            + " <gray>| " + TIME.format(entry.at())
                            + (entry.ipMasked() == null ? "" : " | " + entry.ipMasked())
                            + (entry.message() == null ? "" : " <white>" + escape(entry.message())))));
        });
    }

    /**
     * Применяет ожидающие миграции схемы.
     *
     * <p>Нужна при {@code migrate-on-startup: false}: обновление схемы сети из
     * нескольких прокси в момент старта — это гонка за то, кто первым перезапустится,
     * а здесь момент выбирает администратор.
     */
    private void migrate(CommandSource source) {
        source.sendMessage(prefixed("<yellow>Применение миграций..."));
        CompletableFuture
                .supplyAsync(() -> core.database().migrateNow())
                .thenAccept(outcome -> {
                    if (!outcome.success()) {
                        source.sendMessage(prefixed("<red>Миграции не применены: "
                                + escape(outcome.error())));
                        return;
                    }
                    source.sendMessage(prefixed(outcome.applied() == 0
                            ? "<green>Схема актуальна, применять нечего"
                            : "<green>Применено миграций: " + outcome.applied()
                                    + " <gray>(схема на версии " + outcome.version() + ")"));
                });
    }

    /**
     * Выгружает аккаунты в файл.
     *
     * <p><b>Событие критическое.</b> Файл содержит хэши паролей всей сети, то есть
     * равнозначен дампу таблицы {@code users}. Если выгрузку сделал не тот, кто
     * должен был, это обязано быть видно в журнале с первого взгляда, а не
     * восстанавливаться по косвенным признакам.
     */
    private void export(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(prefixed("<yellow>Использование: <white>/auth export <файл>"));
            return;
        }
        Path target = Path.of(args[1]);
        source.sendMessage(prefixed("<yellow>Выгрузка аккаунтов в " + escape(target.toString()) + "..."));

        // Событие без целевого аккаунта: выгрузка касается всей таблицы, и
        // привязывать её к чьей-то строке значило бы исказить журнал.
        core.audit().log(null, SecurityEventType.DATA_EXPORTED, contextOf(source),
                        "Выгрузка аккаунтов в " + target)
                .thenCompose(ignored -> core.transfer().exportTo(target))
                .thenAccept(result -> source.sendMessage(result.success()
                        ? prefixed("<green>Выгружено аккаунтов: " + result.processed()
                                + " <gray>(файл содержит хэши паролей — храните как дамп базы)")
                        : prefixed("<red>Не удалось выгрузить: " + escape(result.error()))))
                .exceptionally(e -> {
                    logger.error("Выгрузка аккаунтов не удалась", e);
                    source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
                    return null;
                });
    }

    /** Загружает аккаунты из файла выгрузки; существующие ники пропускаются. */
    private void importAccounts(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(prefixed("<yellow>Использование: <white>/auth import <файл>"));
            return;
        }
        Path from = Path.of(args[1]);
        source.sendMessage(prefixed("<yellow>Загрузка аккаунтов из " + escape(from.toString()) + "..."));

        core.audit().log(null, SecurityEventType.DATA_IMPORTED, contextOf(source),
                        "Загрузка аккаунтов из " + from)
                .thenCompose(ignored -> core.transfer().importFrom(from))
                .thenAccept(result -> source.sendMessage(result.success()
                        ? prefixed("<green>Загружено: " + result.processed()
                                + "<gray>, пропущено (уже существуют или битые): " + result.skipped())
                        : prefixed("<red>Не удалось загрузить: " + escape(result.error()))))
                .exceptionally(e -> {
                    logger.error("Загрузка аккаунтов не удалась", e);
                    source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
                    return null;
                });
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Находит аккаунт по нику из аргументов и передаёт действию. */
    private void withAccount(CommandSource source, String[] args,
                             java.util.function.BiConsumer<CommandSource, Account> action) {
        if (args.length < 2) {
            source.sendMessage(prefixed("<yellow>Укажите ник: <white>/auth " + args[0] + " <ник>"));
            return;
        }
        core.authentication().findAccount(args[1]).thenAccept(found -> {
            if (found.isEmpty()) {
                source.sendMessage(prefixed("<red>Аккаунт «" + escape(args[1]) + "» не найден."));
                return;
            }
            action.accept(source, found.get());
        }).exceptionally(e -> {
            logger.error("Ошибка команды /auth {}", args[0], e);
            source.sendMessage(prefixed("<red>Ошибка: " + escape(e.getMessage())));
            return null;
        });
    }

    private long actorId(CommandSource source) {
        // Консоль не имеет аккаунта: в аудите исполнитель останется пустым,
        // но источник события будет SYSTEM, что и отличает её от игрока.
        return 0L;
    }

    private AuthContext contextOf(CommandSource source) {
        if (source instanceof Player player) {
            return AuthContext.minecraft(
                    IpAddress.of(player.getRemoteAddress().getAddress()), null, null, null);
        }
        return AuthContext.system();
    }

    private void usage(CommandSource source) {
        help(source, null);
    }

    /**
     * Справка по {@code /auth}.
     *
     * <p>Без аргумента — обзор: имя, аргументы и одна строка назначения на каждую
     * подкоманду. С аргументом — всё, что нужно знать перед вызовом: развёрнутое
     * описание последствий, узел прав и примеры.
     *
     * <p>Имя подкоманды кликабельно и подставляется в строку ввода: набирать
     * {@code /auth help resetpassword} руками ради описания — ровно тот барьер,
     * из-за которого справку не читают.
     */
    private void help(CommandSource source, @Nullable String topic) {
        if (topic == null || topic.isBlank()) {
            helpOverview(source);
            return;
        }
        Optional<SubCommand> found = describe(topic);
        if (found.isEmpty()) {
            source.sendMessage(prefixed("<red>Подкоманды «" + escape(topic) + "» нет."));
            source.sendMessage(miniMessage.deserialize(
                    "  <gray>Список: <white><click:suggest_command:'/auth help'>/auth help</click>"));
            return;
        }
        helpTopic(source, found.get());
    }

    private void helpOverview(CommandSource source) {
        source.sendMessage(prefixed("<gray>─── <white>KoFAuth " + core.version()
                + " <gray>— команды <white>/auth <gray>───"));

        for (SubCommand sub : SUBCOMMANDS) {
            String syntax = sub.syntax().isEmpty() ? "" : " <dark_gray>" + escape(sub.syntax());
            source.sendMessage(miniMessage.deserialize(
                    "  <click:suggest_command:'/auth " + sub.name() + " '>"
                            + "<hover:show_text:'<gray>Подробно: <white>/auth help " + sub.name() + "'>"
                            + "<yellow>" + sub.name() + "</hover></click>"
                            + syntax + " <gray>— " + sub.summary()));
        }

        source.sendMessage(miniMessage.deserialize(
                "  <gray>Подробно о любой: <white><click:suggest_command:'/auth help '>"
                        + "/auth help <подкоманда></click>"));
        source.sendMessage(miniMessage.deserialize(
                "  <dark_gray>Права выдаются ролями KoFAuth, а не permissions.json прокси."));
        source.sendMessage(miniMessage.deserialize(access.hasFullAccessCached(source)
                ? "  <dark_gray>У вас полный доступ: OP игрового сервера открывает все подкоманды."
                : "  <dark_gray>OP игрового сервера открывает все подкоманды без исключений."));
    }

    private void helpTopic(CommandSource source, SubCommand sub) {
        source.sendMessage(prefixed("<gray>─── <white>/auth " + sub.name() + " <gray>───"));
        source.sendMessage(miniMessage.deserialize("  <gray>Назначение: <white>" + sub.summary()));
        source.sendMessage(miniMessage.deserialize("  <gray>Синтаксис: <white>/auth "
                + sub.name() + (sub.syntax().isEmpty() ? "" : " " + escape(sub.syntax()))));
        source.sendMessage(miniMessage.deserialize("  <gray>Право: <white>" + sub.permission()
                + " <dark_gray>(или OP игрового сервера)"));

        source.sendMessage(miniMessage.deserialize("  <gray>Описание:"));
        sub.details().forEach(linePart ->
                source.sendMessage(miniMessage.deserialize("    <white>" + linePart)));

        source.sendMessage(miniMessage.deserialize("  <gray>Примеры:"));
        sub.examples().forEach(example -> source.sendMessage(miniMessage.deserialize(
                "    <click:suggest_command:'" + example + "'><aqua>" + escape(example)
                        + "</click>")));
    }

    /** Строка, собранная на месте, с общим префиксом сети. */
    private Component prefixed(String text) {
        return messages.prefixedRaw(text);
    }

    private void line(CommandSource source, String label, String value) {
        source.sendMessage(miniMessage.deserialize(
                "  <gray>" + label + ": <white>" + value));
    }

    private static String statusColor(Account account) {
        return switch (account.status()) {
            case ACTIVE -> "<green>";
            case LOCKED, BANNED -> "<red>";
            case PENDING_DELETION -> "<yellow>";
        };
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case INFO -> "<gray>";
            case WARNING -> "<yellow>";
            case CRITICAL -> "<red>";
        };
    }

    /**
     * Экранирует теги MiniMessage в значениях из базы.
     *
     * <p>Без этого ник или сообщение аудита, содержащее {@code <red>}, изменило бы
     * оформление вывода, а {@code <click:run_command:...>} — вставило бы в чужой
     * чат кликабельную команду.
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }

    /**
     * Автодополнение.
     *
     * <p>Дополняется не только подкоманда, но и её аргументы. Прежняя версия
     * отвечала лишь на первое слово: на {@code /auth player <TAB>} подсказок не
     * было, потому что проверка {@code args.length <= 1} не учитывала пустой
     * элемент, который Velocity добавляет после пробела.
     *
     * <p>Пароль и пути к файлам не дополняются: первый секретен, второй ведёт
     * в файловую систему прокси, содержимое которой игроку знать незачем.
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String partial = Suggestions.partial(args);

        if (Suggestions.depth(args) == 1) {
            return Suggestions.matching(SUBCOMMAND_NAMES, partial);
        }

        String sub = Suggestions.subcommand(args);

        if (Suggestions.depth(args) == 2) {
            if ("help".equals(sub)) {
                return Suggestions.matching(SUBCOMMAND_NAMES, partial);
            }
            return describe(sub)
                    .filter(SubCommand::takesPlayer)
                    .map(ignored -> Suggestions.onlinePlayers(proxy, partial))
                    .orElseGet(List::of);
        }

        return List.of();
    }

    /**
     * Допускать ли источник к команде.
     *
     * <p><b>Почему нельзя полагаться на права Velocity.</b> Прокси не имеет
     * встроенной системы прав: без стороннего плагина
     * {@link com.velocitypowered.api.permission.PermissionSubject#hasPermission}
     * отвечает {@code UNDEFINED}, что приводится к {@code false} для любого
     * игрока. Команда отклонялась бы ещё до вызова {@link #execute}, и ни роль
     * KoFAuth, ни OP игрового сервера не проверялись бы никогда.
     *
     * <p>Решение принимает {@link AdminAccess}; здесь только синхронный ответ по
     * уже известному, потому что Velocity спрашивает этот метод в том числе на
     * каждое нажатие TAB.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return access.maySee(invocation.source());
    }
}
