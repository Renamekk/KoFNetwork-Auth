package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
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
 * которые неизбежно разойдутся. Консоль имеет все права безусловно.
 *
 * <p>Каждое действие над чужим аккаунтом пишется в аудит с указанием исполнителя:
 * по журналу должно быть видно не только что произошло, но и кто это сделал.
 */
public final class AuthAdminCommand implements SimpleCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private static final List<String> SUBCOMMANDS = List.of(
            "reload", "info", "player", "lock", "unlock", "resetpassword",
            "forceverify", "sessions", "devices", "logs", "migrate", "export", "import");

    private final KoFAuthCore core;
    private final MessageService messages;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthAdminCommand(@NotNull KoFAuthCore core,
                            @NotNull MessageService messages,
                            @NotNull Logger logger) {
        this.core = core;
        this.messages = messages;
        this.logger = logger;
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
        requirePermission(source, "kofauth.admin." + permissionFor(sub)).thenAccept(allowed -> {
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
        line(source, "Пул БД", "активных " + pools.databaseActive()
                + ", в очереди " + pools.databaseQueued());
        line(source, "Пул I/O", "активных " + pools.ioActive()
                + ", в очереди " + pools.ioQueued());
    }

    private void showPlayer(CommandSource source, Account account) {
        Instant now = Instant.now();
        source.sendMessage(prefixed("<gray>─── <white>" + account.username() + " <gray>───"));
        line(source, "UUID", account.uuid().toString());
        line(source, "Статус", statusColor(account) + account.status());
        line(source, "Лицензионный", account.premium() ? "да" : "нет");
        line(source, "Регистрация", TIME.format(account.registrationDate())
                + " <gray>с " + account.registrationIp().asMasked());
        line(source, "Последний вход", account.lastLoginAt() == null
                ? "<gray>никогда"
                : TIME.format(account.lastLoginAt()) + " <gray>с "
                        + (account.lastLoginIp() == null ? "?" : account.lastLoginIp().asMasked()));
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

    private void showSessions(CommandSource source, Account account) {
        core.sessions().listSessions(account.id(), null).thenAccept(sessions -> {
            if (sessions.isEmpty()) {
                source.sendMessage(prefixed("<gray>Активных сессий нет."));
                return;
            }
            source.sendMessage(prefixed("<white>Сессии " + account.username()
                    + " <gray>(" + sessions.size() + ")"));
            sessions.forEach(session -> source.sendMessage(miniMessage.deserialize(
                    "  <gray>— <white>" + session.type() + " <gray>| " + session.ipMasked()
                            + " | " + TIME.format(session.lastSeenAt())
                            + (session.server() == null ? "" : " | " + session.server()))));
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
                            + " <gray>| " + device.lastSeenIp().asMasked()
                            + " | " + TIME.format(device.lastSeenAt())
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

    /**
     * Проверяет право через RBAC.
     *
     * <p>Консоль получает всё безусловно: у неё нет аккаунта, а требовать
     * заводить его для администратора сервера бессмысленно.
     */
    private CompletableFuture<Boolean> requirePermission(CommandSource source, String node) {
        if (!(source instanceof Player player)) {
            return CompletableFuture.completedFuture(true);
        }
        return core.authentication().findAccount(player.getUsername())
                .thenCompose(account -> account
                        .map(value -> core.adminOperations().hasPermission(value.id(), node))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)))
                .exceptionally(e -> false);
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
        source.sendMessage(prefixed("<white>Команды <gray>/auth"));
        source.sendMessage(miniMessage.deserialize("""
                  <gray>reload <white>— перезагрузить конфигурацию
                  <gray>info <white>— состояние системы
                  <gray>player <ник> <white>— сведения об аккаунте
                  <gray>lock|unlock <ник> <white>— блокировка аккаунта
                  <gray>resetpassword <ник> <пароль> <white>— сброс пароля
                  <gray>forceverify <ник> <white>— подтвердить почту
                  <gray>sessions|devices|logs <ник> <white>— сессии, устройства, журнал
                  <gray>migrate <white>— применить миграции схемы
                  <gray>export <файл> <white>— выгрузить аккаунты (содержит хэши паролей)
                  <gray>import <файл> <white>— загрузить аккаунты из выгрузки"""));
    }

    private Component prefixed(String text) {
        return messages.parse(core.config().getString(
                net.kofnetwork.auth.api.config.ConfigFile.CONFIG, "messages.prefix", "") + text);
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

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Точная проверка асинхронна и выполняется в execute; здесь только грубая
        // отсечка, чтобы команда не светилась в подсказках у обычных игроков.
        return !(invocation.source() instanceof Player)
                || invocation.source().hasPermission("kofauth.admin");
    }
}
