package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.dto.PasswordChangeRequest;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.AccountLoginFailedEvent;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.api.repository.LoginHistoryRepository;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.AuthenticationService;
import net.kofnetwork.auth.api.service.EmailService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.SessionService;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.api.service.TotpService;
import net.kofnetwork.auth.core.security.PasswordHasher;
import net.kofnetwork.auth.core.security.PasswordPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link AuthenticationService}.
 *
 * <p><b>Порядок проверок.</b> Ограничение скорости → AntiBot → поиск аккаунта →
 * статус и блокировка → пароль → второй фактор → сессия. Дорогая проверка BCrypt
 * стоит после дешёвых отсечек: иначе перебор паролей нагружает процессор сервера
 * сильнее, чем машину атакующего.
 *
 * <p><b>Постоянное время ответа.</b> Для несуществующего аккаунта вызывается
 * {@link PasswordHasher#wasteTime()}. Без этого несуществующий ник отвечает за
 * миллисекунду, а существующий — за сотню, и разница выдаёт наличие аккаунта даже
 * при одинаковом тексте ошибки.
 */
public final class AuthenticationServiceImpl implements AuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationServiceImpl.class);

    private final AccountRepository accounts;
    private final DeviceRepository devices;
    private final LoginHistoryRepository loginHistory;
    private final SessionService sessions;
    private final SecurityService security;
    private final TotpService totp;
    private final TokenService tokens;
    private final EmailService email;
    private final AuditService audit;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final ConfigurationService config;
    private final EventBus events;

    public AuthenticationServiceImpl(@NotNull AccountRepository accounts,
                                     @NotNull DeviceRepository devices,
                                     @NotNull LoginHistoryRepository loginHistory,
                                     @NotNull SessionService sessions,
                                     @NotNull SecurityService security,
                                     @NotNull TotpService totp,
                                     @NotNull TokenService tokens,
                                     @NotNull EmailService email,
                                     @NotNull AuditService audit,
                                     @NotNull PasswordHasher hasher,
                                     @NotNull PasswordPolicy policy,
                                     @NotNull ConfigurationService config,
                                     @NotNull EventBus events) {
        this.accounts = accounts;
        this.devices = devices;
        this.loginHistory = loginHistory;
        this.sessions = sessions;
        this.security = security;
        this.totp = totp;
        this.tokens = tokens;
        this.email = email;
        this.audit = audit;
        this.hasher = hasher;
        this.policy = policy;
        this.config = config;
        this.events = events;
    }

    // ================================================================ вход

    @Override
    public @NotNull CompletableFuture<AuthResult> login(@NotNull LoginRequest request) {
        return checkRateLimits(request)
                .thenCompose(denied -> denied.isPresent()
                        ? CompletableFuture.completedFuture(denied.get())
                        : findAndAuthenticate(request))
                .exceptionally(e -> {
                    LOGGER.error("Ошибка при входе '{}'", request.username(), e);
                    return AuthResult.error(e.getMessage());
                });
    }

    private CompletableFuture<Optional<AuthResult>> checkRateLimits(LoginRequest request) {
        if (!request.context().isSubjectToRateLimit()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String ip = request.context().ip().asString();

        return security.checkAndConsume("login.per-ip", ip).thenCompose(ipVerdict -> {
            if (!ipVerdict.allowed()) {
                return CompletableFuture.completedFuture(Optional.of(
                        AuthResult.rateLimited(orDefault(ipVerdict.retryAfter()))));
            }
            return security.checkAndConsume("login.per-account",
                    request.username().toLowerCase(java.util.Locale.ROOT)).thenCompose(accountVerdict -> {
                if (!accountVerdict.allowed()) {
                    return CompletableFuture.completedFuture(Optional.of(
                            AuthResult.rateLimited(orDefault(accountVerdict.retryAfter()))));
                }
                return security.isBotSuspected(request.context()).thenApply(bot -> bot
                        ? Optional.of(AuthResult.rejected(LoginResultType.BOT_DETECTED, null))
                        : Optional.empty());
            });
        });
    }

    private static Duration orDefault(@Nullable Duration retryAfter) {
        return retryAfter == null ? Duration.ofMinutes(1) : retryAfter;
    }

    private CompletableFuture<AuthResult> findAndAuthenticate(LoginRequest request) {
        return accounts.findByUsername(request.username()).thenCompose(found -> {
            if (found.isEmpty()) {
                // Тратим столько же времени, сколько заняла бы настоящая проверка.
                hasher.wasteTime();
                return recordFailure(null, request, LoginResultType.UNKNOWN_ACCOUNT)
                        .thenApply(ignored -> AuthResult.unknownAccount());
            }
            return authenticate(found.get(), request);
        });
    }

    private CompletableFuture<AuthResult> authenticate(Account account, LoginRequest request) {
        Instant now = Instant.now();

        if (account.status() == AccountStatus.BANNED) {
            return recordFailure(account.id(), request, LoginResultType.ACCOUNT_BANNED)
                    .thenApply(ignored -> AuthResult.rejected(LoginResultType.ACCOUNT_BANNED, null));
        }
        if (account.status() == AccountStatus.LOCKED) {
            return recordFailure(account.id(), request, LoginResultType.ACCOUNT_LOCKED)
                    .thenApply(ignored -> AuthResult.rejected(LoginResultType.ACCOUNT_LOCKED, null));
        }
        if (account.isTemporarilyLocked(now)) {
            Duration retryAfter = Duration.between(now, account.lockedUntil());
            return recordFailure(account.id(), request, LoginResultType.TEMPORARILY_LOCKED)
                    .thenApply(ignored -> AuthResult.temporarilyLocked(retryAfter));
        }

        if (!hasher.verify(request.password(), account.passwordHash())) {
            return handleBadPassword(account, request);
        }

        // Пароль верен: счётчик неудач сбрасывается и снимается ограничение по аккаунту.
        return accounts.resetFailedAttempts(account.id())
                .thenCompose(ignored -> security.resetRateLimit("login.per-account",
                        account.lowerUsername()))
                .thenCompose(ignored -> maybeRehash(account, request.password()))
                .thenCompose(ignored -> resolveDevice(account, request))
                .thenCompose(device -> continueAfterPassword(account, request, device));
    }

    /**
     * Перехэширует пароль, если стоимость BCrypt была повышена.
     *
     * <p>Единственный момент, когда пароль известен в открытом виде, — успешный вход.
     * Это позволяет поднимать cost по мере роста мощности процессоров, не заставляя
     * игроков менять пароли.
     */
    private CompletableFuture<Void> maybeRehash(Account account, String password) {
        if (!hasher.needsRehash(account.passwordHash())) {
            return CompletableFuture.completedFuture(null);
        }
        return accounts.updatePassword(account.id(), hasher.hash(password),
                        PasswordHasher.ALGORITHM, Instant.now())
                .exceptionally(e -> {
                    // Неудача не должна отменять успешный вход.
                    LOGGER.warn("Не удалось перехэшировать пароль аккаунта {}", account.id(), e);
                    return null;
                });
    }

    private CompletableFuture<AuthResult> handleBadPassword(Account account, LoginRequest request) {
        int maxAttempts = config.getInt(ConfigFile.CONFIG, "auth.login.max-attempts", 5);
        Duration lockout = config.getDuration(ConfigFile.CONFIG, "auth.login.lockout-duration",
                Duration.ofMinutes(15));

        return accounts.incrementFailedAttempts(account.id()).thenCompose(failures -> {
            CompletableFuture<Void> lock = failures >= maxAttempts
                    ? accounts.lockUntil(account.id(), Instant.now().plus(lockout))
                    : CompletableFuture.completedFuture(null);

            return lock.thenCompose(ignored ->
                            recordFailure(account.id(), request, LoginResultType.BAD_PASSWORD))
                    .thenCompose(ignored -> {
                        if (failures >= maxAttempts) {
                            return events.publish(SuspiciousActivityEvent.of(account.id(),
                                            SecurityEventType.BRUTE_FORCE_DETECTED,
                                            request.context(),
                                            "Превышен лимит попыток входа: " + failures))
                                    .thenApply(x -> AuthResult.temporarilyLocked(lockout));
                        }
                        boolean showRemaining = config.getBoolean(ConfigFile.CONFIG,
                                "auth.login.show-remaining-attempts", true);
                        return CompletableFuture.completedFuture(AuthResult.badPassword(
                                showRemaining ? Math.max(0, maxAttempts - failures) : 0));
                    });
        });
    }

    /** Находит или создаёт устройство, с которого выполняется вход. */
    private CompletableFuture<Optional<DeviceRepository.UpsertResult>> resolveDevice(
            Account account, LoginRequest request) {
        String fingerprint = request.context().deviceFingerprint();
        if (fingerprint == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return devices.findOrCreate(account.id(), fingerprint, request.context().platform(),
                        request.context().ip(), Instant.now())
                .thenApply(Optional::of)
                .exceptionally(e -> {
                    LOGGER.warn("Не удалось определить устройство для аккаунта {}", account.id(), e);
                    return Optional.empty();
                });
    }

    /** Решает, нужен ли второй фактор, и завершает вход либо переводит в ожидание. */
    private CompletableFuture<AuthResult> continueAfterPassword(
            Account account, LoginRequest request,
            Optional<DeviceRepository.UpsertResult> device) {

        boolean newDevice = device.map(DeviceRepository.UpsertResult::created).orElse(false);
        boolean trustedDevice = device
                .map(result -> result.device().skipsTwoFactor())
                .orElse(false);

        return needsTwoFactor(account, request, trustedDevice).thenCompose(method -> {
            if (method.isEmpty()) {
                return completeLogin(account, request, device, null, newDevice);
            }

            TwoFactorMethod required = method.get();

            // Код уже введён вместе с паролем — проверяем сразу.
            if (request.twoFactorCode() != null && required == TwoFactorMethod.TOTP) {
                return totp.verify(account.id(), request.twoFactorCode()).thenCompose(valid -> {
                    if (!valid) {
                        return recordFailure(account.id(), request, LoginResultType.TWO_FACTOR_FAILED)
                                .thenApply(ignored -> AuthResult.rejected(
                                        LoginResultType.TWO_FACTOR_FAILED, null));
                    }
                    return completeLogin(account, request, device, required, newDevice);
                });
            }

            if (required == TwoFactorMethod.TOTP) {
                return CompletableFuture.completedFuture(
                        AuthResult.twoFactorRequired(account, required, null));
            }

            // Telegram и Discord: выпускаем токен подтверждения, по которому бот
            // найдёт ожидающий запрос, когда игрок нажмёт кнопку.
            return tokens.issue(account.id(), TokenType.LOGIN_APPROVAL, request.context().ip(), null)
                    .thenApply(issued -> AuthResult.twoFactorRequired(
                            account, required, issued.value()));
        });
    }

    /**
     * Определяет требуемый второй фактор.
     *
     * @return пустое значение, если второй фактор не нужен
     */
    private CompletableFuture<Optional<TwoFactorMethod>> needsTwoFactor(Account account,
                                                                        LoginRequest request,
                                                                        boolean trustedDevice) {
        if (!account.hasTwoFactor()) {
            // 2FA выключена, но с незнакомого устройства её можно потребовать
            // принудительно — если она вообще настроена. Здесь не настроена.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (trustedDevice) {
            // Доверенное устройство снимает требование второго фактора: игрок уже
            // подтверждал с него личность.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(
                Optional.ofNullable(account.preferredTwoFactor()));
    }

    /** Создаёт сессию и публикует событие входа. */
    private CompletableFuture<AuthResult> completeLogin(
            Account account, LoginRequest request,
            Optional<DeviceRepository.UpsertResult> device,
            @Nullable TwoFactorMethod usedMethod,
            boolean newDevice) {

        Long deviceId = device.map(result -> result.device().id()).orElse(null);
        Instant now = Instant.now();

        return enforceSessionLimit(account, request)
                .thenCompose(ignored -> sessions.create(account.id(),
                        sessionTypeFor(request), request.context(), deviceId))
                .thenCompose(session -> accounts.updateLastLogin(account.id(), request.context().ip(),
                                now, request.context().server(), request.context().country(),
                                request.context().city(), request.context().userAgent())
                        .thenApply(ignored -> session))
                .thenCompose(session -> isNewCountry(account, request).thenCompose(newCountry ->
                        audit.logLoginAttempt(LoginAttempt
                                        .success(account.id(), deviceId, request.username(),
                                                request.context().ip(), request.context().source(),
                                                usedMethod)
                                        .withGeo(request.context().country(),
                                                request.context().city(), request.context().isp())
                                        .withClient(request.context().userAgent(),
                                                request.context().server(),
                                                request.context().protocolVersion()))
                                .thenCompose(x -> events.publish(AccountLoginEvent.of(account, session,
                                        request.context(), usedMethod, newDevice, newCountry)))
                                .thenApply(x -> AuthResult.success(account, session))));
    }

    /**
     * Соблюдает лимит одновременных игровых сессий.
     *
     * <p>Старые сессии отзываются, а не отвергается новая: игрок, у которого
     * оборвалось соединение, иначе не смог бы зайти до истечения срока старой сессии.
     */
    private CompletableFuture<Void> enforceSessionLimit(Account account, LoginRequest request) {
        if (sessionTypeFor(request) != SessionType.GAME) {
            return CompletableFuture.completedFuture(null);
        }
        int max = config.getInt(ConfigFile.CONFIG, "auth.session.max-concurrent", 1);
        if (max <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return sessions.revokeAll(account.id(), null, Session.REASON_LOGOUT_ALL)
                .thenApply(ignored -> null);
    }

    private CompletableFuture<Boolean> isNewCountry(Account account, LoginRequest request) {
        String country = request.context().country();
        if (country == null) {
            return CompletableFuture.completedFuture(false);
        }
        return loginHistory.hasSuccessfulLoginFromCountry(account.id(), country)
                .thenApply(known -> !known)
                .exceptionally(e -> false);
    }

    private CompletableFuture<Void> recordFailure(@Nullable Long accountId,
                                                  LoginRequest request,
                                                  LoginResultType result) {
        LoginAttempt attempt = LoginAttempt
                .failure(accountId, request.username(), result, request.context().ip(),
                        request.context().source())
                .withGeo(request.context().country(), request.context().city(),
                        request.context().isp())
                .withClient(request.context().userAgent(), request.context().server(),
                        request.context().protocolVersion());

        return audit.logLoginAttempt(attempt)
                .thenCompose(ignored -> events.publish(AccountLoginFailedEvent.of(
                        accountId, request.username(), result, request.context(), 0)))
                .thenApply(ignored -> null);
    }

    private static SessionType sessionTypeFor(LoginRequest request) {
        return switch (request.context().source()) {
            case WEB -> SessionType.WEB;
            case TELEGRAM -> SessionType.TELEGRAM;
            case DISCORD -> SessionType.DISCORD;
            case API -> SessionType.API;
            default -> SessionType.GAME;
        };
    }

    // ================================================================ второй фактор

    @Override
    public @NotNull CompletableFuture<AuthResult> completeTwoFactor(@NotNull UUID playerUuid,
                                                                     @NotNull String code,
                                                                     @NotNull AuthContext context) {
        return accounts.findByUuid(playerUuid).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(AuthResult.unknownAccount());
            }
            Account account = found.get();

            return security.checkAndConsume("totp.per-account", String.valueOf(account.id()))
                    .thenCompose(verdict -> {
                        if (!verdict.allowed()) {
                            return CompletableFuture.completedFuture(
                                    AuthResult.rateLimited(orDefault(verdict.retryAfter())));
                        }
                        return totp.verify(account.id(), code).thenCompose(valid -> {
                            if (!valid) {
                                return audit.log(account.id(), SecurityEventType.TOTP_FAILED,
                                                context, "Неверный код второго фактора")
                                        .thenApply(ignored -> AuthResult.rejected(
                                                LoginResultType.TWO_FACTOR_FAILED, null));
                            }
                            LoginRequest synthetic = new LoginRequest(account.username(), "",
                                    playerUuid, code, context);
                            return completeLogin(account, synthetic, Optional.empty(),
                                    TwoFactorMethod.TOTP, false);
                        });
                    });
        });
    }

    @Override
    public @NotNull CompletableFuture<AuthResult> completeApproval(@NotNull String approvalToken,
                                                                    boolean approved,
                                                                    @NotNull AuthContext context) {
        return tokens.consume(approvalToken, TokenType.LOGIN_APPROVAL, context.ip())
                .thenCompose(result -> {
                    if (result.isFailure()) {
                        return CompletableFuture.completedFuture(
                                AuthResult.rejected(LoginResultType.TWO_FACTOR_FAILED,
                                        result.errorMessage()));
                    }
                    Long accountId = result.value().accountId();
                    if (accountId == null) {
                        return CompletableFuture.completedFuture(AuthResult.unknownAccount());
                    }
                    if (!approved) {
                        // Владелец нажал «Это не я»: сам факт важнее отказа во входе.
                        return events.publish(SuspiciousActivityEvent.of(accountId,
                                        SecurityEventType.LOGIN_FAILED, context,
                                        "Владелец отклонил вход"))
                                .thenApply(ignored -> AuthResult.rejected(
                                        LoginResultType.TWO_FACTOR_FAILED, "Вход отклонён владельцем"));
                    }
                    return accounts.findById(accountId).thenCompose(found -> found
                            .map(account -> completeLogin(account,
                                    new LoginRequest(account.username(), "", account.uuid(),
                                            null, context),
                                    Optional.empty(),
                                    approvalMethod(account), false))
                            .orElseGet(() -> CompletableFuture.completedFuture(
                                    AuthResult.unknownAccount())));
                });
    }

    private static @Nullable TwoFactorMethod approvalMethod(Account account) {
        if (account.hasTwoFactor(TwoFactorMethod.TELEGRAM)) {
            return TwoFactorMethod.TELEGRAM;
        }
        return account.hasTwoFactor(TwoFactorMethod.DISCORD) ? TwoFactorMethod.DISCORD : null;
    }

    // ================================================================ пароль

    @Override
    public @NotNull CompletableFuture<Boolean> verifyPassword(long accountId,
                                                               @NotNull String password) {
        return accounts.findById(accountId)
                .thenApply(found -> found
                        .map(account -> hasher.verify(password, account.passwordHash()))
                        .orElseGet(() -> {
                            hasher.wasteTime();
                            return false;
                        }));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> changePassword(
            long accountId, @NotNull PasswordChangeRequest request) {

        if (!request.passwordsMatch()) {
            return completed(OperationResult.fail("PASSWORDS_DO_NOT_MATCH",
                    "Новые пароли не совпадают"));
        }
        if (request.isSameAsCurrent()) {
            return completed(OperationResult.fail("PASSWORD_UNCHANGED",
                    "Новый пароль совпадает с текущим"));
        }

        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND", "Аккаунт не найден"));
            }
            Account account = found.get();

            // Текущий пароль обязателен даже при действующей сессии: сессия могла
            // быть угнана, и без повторного подтверждения угон стал бы захватом.
            if (!hasher.verify(request.currentPassword(), account.passwordHash())) {
                return audit.log(accountId, SecurityEventType.LOGIN_FAILED, request.context(),
                                "Неверный текущий пароль при смене")
                        .thenApply(ignored -> OperationResult.<Void>fail("BAD_PASSWORD",
                                "Текущий пароль неверен"));
            }

            List<String> issues = policy.validate(request.newPassword(), account.username());
            if (!issues.isEmpty()) {
                return completed(OperationResult.<Void>fail("PASSWORD_TOO_WEAK",
                        String.join(", ", issues)));
            }

            return applyNewPassword(account, request.newPassword(), request.context(),
                    request.revokeOtherSessions(), false);
        });
    }

    /** Общий путь смены пароля: хэш, отзыв сессий, событие, аудит. */
    private CompletableFuture<OperationResult<Void>> applyNewPassword(Account account,
                                                                       String newPassword,
                                                                       AuthContext context,
                                                                       boolean revokeSessions,
                                                                       boolean viaReset) {
        return accounts.updatePassword(account.id(), hasher.hash(newPassword),
                        PasswordHasher.ALGORITHM, Instant.now())
                .thenCompose(ignored -> revokeSessions
                        ? sessions.revokeAll(account.id(), null, Session.REASON_PASSWORD_CHANGED)
                                .thenApply(count -> null)
                        : CompletableFuture.completedFuture(null))
                .thenCompose(ignored -> tokens.revokeRefreshTokensOf(account.id()))
                .thenCompose(ignored -> events.publish(PasswordChangedEvent.of(
                        account.id(), context, viaReset, revokeSessions)))
                .thenCompose(ignored -> audit.log(account.id(),
                        viaReset ? SecurityEventType.PASSWORD_RESET_COMPLETED
                                : SecurityEventType.PASSWORD_CHANGED,
                        context, viaReset ? "Пароль сброшен по коду" : "Пароль изменён"))
                .thenApply(ignored -> OperationResult.<Void>ok());
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> requestPasswordReset(
            @NotNull String username, @NotNull AuthContext context) {
        // Ответ всегда одинаковый. Различимый исход превратил бы форму
        // восстановления в способ проверять, зарегистрирован ли ник и есть ли
        // у него подтверждённая почта.
        return security.checkAndConsume("password-reset.per-account",
                        username.toLowerCase(java.util.Locale.ROOT))
                .thenCompose(verdict -> {
                    if (!verdict.allowed()) {
                        // Даже отказ по лимиту наружу выглядит успехом: иначе по
                        // разнице ответов видно, что ник существует.
                        return CompletableFuture.completedFuture(OperationResult.<Void>ok());
                    }
                    return accounts.findByUsername(username)
                            .thenCompose(found -> found.isEmpty()
                                    ? CompletableFuture.completedFuture(OperationResult.<Void>ok())
                                    : startReset(found.get(), context));
                })
                .exceptionally(e -> {
                    LOGGER.error("Ошибка при запросе сброса пароля для '{}'", username, e);
                    return OperationResult.ok();
                });
    }

    /** Выпускает код сброса и отправляет его на подтверждённую почту. */
    private CompletableFuture<OperationResult<Void>> startReset(Account account,
                                                                 AuthContext context) {
        return email.findPrimary(account.id()).thenCompose(binding -> {
            if (binding.isEmpty() || !binding.get().isUsable()) {
                // Почты нет либо она не подтверждена. Отправлять код на
                // неподтверждённый адрес нельзя: привязка чужой почты стала бы
                // способом угона аккаунта.
                return audit.log(account.id(), SecurityEventType.PASSWORD_RESET_REQUESTED,
                                context, "Сброс запрошен, но подтверждённой почты нет")
                        .thenApply(ignored -> OperationResult.<Void>ok());
            }

            // Ранее выпущенные коды гасим: иначе действующими окажутся сразу
            // несколько, и каждый расширяет окно для атаки на почтовый ящик.
            return tokens.revokeAllByType(account.id(), TokenType.PASSWORD_RESET)
                    .thenCompose(ignored -> tokens.issue(account.id(),
                            TokenType.PASSWORD_RESET, context.ip(), null))
                    .thenCompose(issued -> email.sendTemplated(binding.get().email(),
                            "password-reset",
                            java.util.Map.of(
                                    "username", account.username(),
                                    "code", issued.value(),
                                    "expires", "30 минут",
                                    "ip", context.ip().asMasked())))
                    .thenCompose(sent -> audit.log(account.id(),
                            SecurityEventType.PASSWORD_RESET_REQUESTED, context,
                            sent.isSuccess()
                                    ? "Код сброса отправлен на почту"
                                    : "Не удалось отправить код: " + sent.errorCode()))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> completePasswordReset(
            @NotNull String resetToken, @NotNull String newPassword, @NotNull AuthContext context) {

        return tokens.consume(resetToken, TokenType.PASSWORD_RESET, context.ip())
                .thenCompose(result -> {
                    if (result.isFailure()) {
                        return completed(OperationResult.<Void>fail("TOKEN_INVALID",
                                "Код недействителен или истёк"));
                    }
                    Long accountId = result.value().accountId();
                    if (accountId == null) {
                        return completed(OperationResult.<Void>fail("TOKEN_INVALID",
                                "Код не связан с аккаунтом"));
                    }
                    return accounts.findById(accountId).thenCompose(found -> {
                        if (found.isEmpty()) {
                            return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND",
                                    "Аккаунт не найден"));
                        }
                        Account account = found.get();
                        List<String> issues = policy.validate(newPassword, account.username());
                        if (!issues.isEmpty()) {
                            return completed(OperationResult.<Void>fail("PASSWORD_TOO_WEAK",
                                    String.join(", ", issues)));
                        }
                        // Сброс всегда отзывает все сессии: он обычно означает,
                        // что доступ пытались перехватить.
                        return applyNewPassword(account, newPassword, context, true, true);
                    });
                });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> adminResetPassword(
            long accountId, @NotNull String newPassword, long adminAccountId,
            @NotNull AuthContext context) {

        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND", "Аккаунт не найден"));
            }
            Account account = found.get();
            List<String> issues = policy.validate(newPassword, account.username());
            if (!issues.isEmpty()) {
                return completed(OperationResult.<Void>fail("PASSWORD_TOO_WEAK",
                        String.join(", ", issues)));
            }
            return applyNewPassword(account, newPassword, context, true, false)
                    .thenCompose(result -> audit.logAdminAction(accountId, adminAccountId,
                                    SecurityEventType.PASSWORD_CHANGED, context,
                                    "Пароль сброшен администратором")
                            .thenApply(ignored -> result));
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<Account>> findAccount(@NotNull String username) {
        return accounts.findByUsername(username);
    }

    private static <T> CompletableFuture<OperationResult<T>> completed(OperationResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
