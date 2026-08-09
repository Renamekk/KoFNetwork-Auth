package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.RegistrationRequest;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountRegisteredEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.RoleRepository;
import net.kofnetwork.auth.api.repository.SettingsRepository;
import net.kofnetwork.auth.api.result.RegistrationResult;
import net.kofnetwork.auth.api.result.RegistrationResultType;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.RegistrationService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.SessionService;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.security.PasswordHasher;
import net.kofnetwork.auth.core.security.PasswordPolicy;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Реализация {@link RegistrationService}.
 *
 * <p>Порядок проверок задан ценой каждой: сначала настройка «включена ли регистрация»
 * (чтение из кэша), затем формат ника (чистая функция), затем ограничение скорости
 * (одна операция Redis), и только в конце обращение к базе и BCrypt.
 */
public final class RegistrationServiceImpl implements RegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationServiceImpl.class);

    /** Код проблемы с ником. */
    public static final String USERNAME_TOO_SHORT = "USERNAME_TOO_SHORT";
    public static final String USERNAME_TOO_LONG = "USERNAME_TOO_LONG";
    public static final String USERNAME_INVALID_CHARS = "USERNAME_INVALID_CHARS";
    public static final String USERNAME_BLACKLISTED = "USERNAME_BLACKLISTED";

    /**
     * Ники, которые нельзя занимать.
     *
     * <p>Не вопрос вкуса: ник {@code Console} или {@code Server} в чате неотличим от
     * системного сообщения, и на этом строится социальная инженерия
     * («Server: введите пароль в чат для проверки»).
     */
    private static final Set<String> BLACKLIST = Set.of(
            "console", "server", "admin", "administrator", "system", "kofauth",
            "kof", "staff", "moderator", "owner", "root", "null", "undefined");

    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final SettingsRepository settings;
    private final SessionService sessionService;
    private final SecurityService security;
    private final AuditService audit;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final ConfigurationService config;
    private final EventBus events;

    public RegistrationServiceImpl(@NotNull AccountRepository accounts,
                                   @NotNull RoleRepository roles,
                                   @NotNull SettingsRepository settings,
                                   @NotNull SessionService sessionService,
                                   @NotNull SecurityService security,
                                   @NotNull AuditService audit,
                                   @NotNull PasswordHasher hasher,
                                   @NotNull PasswordPolicy policy,
                                   @NotNull ConfigurationService config,
                                   @NotNull EventBus events) {
        this.accounts = accounts;
        this.roles = roles;
        this.settings = settings;
        this.sessionService = sessionService;
        this.security = security;
        this.audit = audit;
        this.hasher = hasher;
        this.policy = policy;
        this.config = config;
        this.events = events;
    }

    @Override
    public @NotNull CompletableFuture<RegistrationResult> register(
            @NotNull RegistrationRequest request) {

        return isRegistrationEnabled().thenCompose(enabled -> {
            if (!enabled) {
                return completed(RegistrationResult.rejected(
                        RegistrationResultType.REGISTRATION_DISABLED, null));
            }

            Optional<String> usernameIssue = validateUsername(request.username());
            if (usernameIssue.isPresent()) {
                return completed(RegistrationResult.rejected(
                        RegistrationResultType.INVALID_USERNAME, usernameIssue.get()));
            }

            if (!request.passwordsMatch()) {
                return completed(RegistrationResult.rejected(
                        RegistrationResultType.PASSWORDS_DO_NOT_MATCH, null));
            }

            List<String> passwordIssues = validatePassword(request.password(), request.username());
            if (!passwordIssues.isEmpty()) {
                return completed(RegistrationResult.weakPassword(passwordIssues));
            }

            return checkLimits(request).thenCompose(limitFailure -> limitFailure
                    .map(RegistrationServiceImpl::completed)
                    .orElseGet(() -> createAccount(request)));
        });
    }

    /** Ограничения скорости, лимит на IP и AntiBot. */
    private CompletableFuture<Optional<RegistrationResult>> checkLimits(RegistrationRequest request) {
        if (!request.context().isSubjectToRateLimit()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String ip = request.context().ip().asString();

        return security.checkAndConsume("register.per-ip", ip).thenCompose(verdict -> {
            if (!verdict.allowed()) {
                return CompletableFuture.completedFuture(Optional.of(
                        RegistrationResult.rateLimited(
                                verdict.retryAfter() == null ? Duration.ofMinutes(10)
                                        : verdict.retryAfter())));
            }
            return security.isBotSuspected(request.context()).thenCompose(bot -> {
                if (bot) {
                    return CompletableFuture.completedFuture(Optional.of(
                            RegistrationResult.rejected(RegistrationResultType.BOT_DETECTED, null)));
                }
                return checkIpAccountLimit(request);
            });
        });
    }

    private CompletableFuture<Optional<RegistrationResult>> checkIpAccountLimit(
            RegistrationRequest request) {
        int max = config.getInt(ConfigFile.CONFIG, "auth.registration.max-accounts-per-ip", 3);
        if (max <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Duration window = config.getDuration(ConfigFile.CONFIG,
                "auth.registration.ip-limit-window", Duration.ofDays(7));
        return accounts.countRegistrationsFromIp(request.context().ip(), Instant.now().minus(window))
                .thenApply(count -> count >= max
                        ? Optional.of(RegistrationResult.rejected(
                                RegistrationResultType.IP_LIMIT_REACHED, null))
                        : Optional.empty());
    }

    /** Создаёт аккаунт, выдаёт роль по умолчанию и сессию. */
    private CompletableFuture<RegistrationResult> createAccount(RegistrationRequest request) {
        UUID uuid = request.playerUuid() != null ? request.playerUuid() : offlineUuid(request.username());
        String hash = hasher.hash(request.password());

        Account account = Account
                .newAccount(uuid, request.username(), hash, request.context().ip())
                .build();

        return accounts.insert(account)
                .thenCompose(saved -> grantDefaultRole(saved).thenApply(ignored -> saved))
                .thenCompose(saved -> sessionService
                        .create(saved.id(), sessionTypeFor(request), request.context(), null)
                        .thenCompose(session -> cacheSessionForPlayer(request, session)
                                .thenApply(ignored -> finish(saved, session, request))))
                .exceptionally(e -> translateFailure(e, request));
    }

    /**
     * Привязывает UUID игрока к выданной сессии.
     *
     * <p>Привязку ставит Core, а не вызывающая команда: иначе между «регистрация
     * завершена» и «привязка записана» существует окно, в котором игрок уже
     * считается вошедшим, но его сессию по UUID никто найти не может. Для входов
     * не из игры UUID отсутствует, и привязывать нечего.
     */
    private CompletableFuture<Void> cacheSessionForPlayer(RegistrationRequest request,
                                                          Session session) {
        UUID playerUuid = request.playerUuid();
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        return sessionService.cacheForPlayer(playerUuid, session)
                .exceptionally(e -> {
                    // Кэш недоступен: аккаунт создан, но переподключение потребует
                    // пароля. Отменять из-за этого регистрацию нельзя.
                    LOGGER.warn("Не удалось привязать сессию к UUID {}", playerUuid, e);
                    return null;
                });
    }

    private CompletableFuture<Void> grantDefaultRole(Account account) {
        return roles.findDefaultRole()
                .thenCompose(role -> role
                        .map(value -> roles.grantRole(account.id(), value.id(), null, null)
                                .<Void>thenApply(ignored -> null))
                        .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .exceptionally(e -> {
                    // Отсутствие роли не повод отменять регистрацию: аккаунт создан,
                    // права можно выдать позже.
                    LOGGER.warn("Не удалось выдать роль по умолчанию аккаунту {}",
                            account.username(), e);
                    return null;
                });
    }

    private RegistrationResult finish(Account account, Session session, RegistrationRequest request) {
        events.publish(AccountRegisteredEvent.of(account, request.context()));
        audit.log(account.id(), SecurityEventType.ACCOUNT_REGISTERED, request.context(),
                "Аккаунт создан");
        return RegistrationResult.success(account, session);
    }

    /**
     * Превращает исключение в исход регистрации.
     *
     * <p>Нарушение уникального ключа — не отказ базы, а проигранная гонка с
     * параллельной регистрацией того же ника. Проверка {@code existsByUsername}
     * гарантии не даёт: между ней и вставкой всегда есть окно. Настоящую гарантию
     * даёт индекс, и обработать его нарушение обязаны здесь.
     */
    private RegistrationResult translateFailure(Throwable e, RegistrationRequest request) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof SqlExecutor.DuplicateKeyException) {
            return RegistrationResult.rejected(RegistrationResultType.USERNAME_TAKEN, null);
        }
        LOGGER.error("Не удалось зарегистрировать аккаунт '{}'", request.username(), cause);
        return RegistrationResult.error(cause.getMessage());
    }

    private static SessionType sessionTypeFor(RegistrationRequest request) {
        return switch (request.context().source()) {
            case WEB -> SessionType.WEB;
            case TELEGRAM -> SessionType.TELEGRAM;
            case DISCORD -> SessionType.DISCORD;
            case API -> SessionType.API;
            default -> SessionType.GAME;
        };
    }

    /**
     * UUID для нелицензионного аккаунта.
     *
     * <p>Формула совпадает с той, что использует сам Minecraft в offline-режиме
     * ({@code OfflinePlayer:<ник>}, UUID версии 3). Собственная схема сделала бы
     * инвентарь и статистику игрока невидимыми для остальных плагинов.
     */
    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @NotNull List<String> validatePassword(@NotNull String password,
                                                   @NotNull String username) {
        return policy.validate(password, username);
    }

    @Override
    public @NotNull Optional<String> validateUsername(@NotNull String username) {
        if (username.length() < 3) {
            return Optional.of(USERNAME_TOO_SHORT);
        }
        if (username.length() > 16) {
            return Optional.of(USERNAME_TOO_LONG);
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                return Optional.of(USERNAME_INVALID_CHARS);
            }
        }
        if (BLACKLIST.contains(username.toLowerCase(Locale.ROOT))) {
            return Optional.of(USERNAME_BLACKLISTED);
        }
        return Optional.empty();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isUsernameAvailable(@NotNull String username) {
        return accounts.existsByUsername(username).thenApply(exists -> !exists);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isRegistrationEnabled() {
        // Настройка из базы применяется на всех узлах сразу и имеет приоритет
        // над YAML: администратору не нужно править файл на десяти серверах.
        return settings.get("auth.registration.enabled")
                .thenApply(value -> value
                        .map(Boolean::parseBoolean)
                        .orElseGet(() -> config.getBoolean(ConfigFile.CONFIG,
                                "auth.registration.enabled", true)))
                .exceptionally(e -> config.getBoolean(ConfigFile.CONFIG,
                        "auth.registration.enabled", true));
    }

    private static CompletableFuture<RegistrationResult> completed(RegistrationResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
