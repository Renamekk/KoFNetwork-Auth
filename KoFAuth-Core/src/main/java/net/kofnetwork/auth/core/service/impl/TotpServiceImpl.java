package net.kofnetwork.auth.core.service.impl;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.TotpSetupDto;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.model.TotpSecret;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.TokenRepository;
import net.kofnetwork.auth.api.repository.TotpRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.TotpService;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link TotpService}.
 *
 * <p><b>Двухшаговое подключение.</b> {@link #beginSetup} выдаёт секрет и QR, но
 * второй фактор ещё не работает; {@link #confirmSetup} включает его после ввода
 * верного кода. Без второго шага игрок, ошибившийся при сканировании QR, оказался
 * бы заперт снаружи собственного аккаунта.
 *
 * <p><b>Окно допуска — одно деление в обе стороны.</b> Часы на телефоне редко
 * идеально синхронны, и жёсткая проверка отвергала бы верные коды. Более широкое
 * окно уже заметно расширяет пространство перебора: при трёх делениях у атакующего
 * втрое больше принимаемых значений.
 */
public final class TotpServiceImpl implements TotpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TotpServiceImpl.class);

    /** Сколько делений времени допускается в каждую сторону. */
    private static final int ALLOWED_DRIFT_PERIODS = 1;

    /** Сколько резервных кодов выдаётся за раз. */
    private static final int RECOVERY_CODE_COUNT = 10;

    private final TotpRepository totp;
    private final TokenRepository tokens;
    private final AccountRepository accounts;
    private final AuditService audit;
    private final EventBus events;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

    private final String issuer;

    public TotpServiceImpl(@NotNull TotpRepository totp,
                           @NotNull TokenRepository tokens,
                           @NotNull AccountRepository accounts,
                           @NotNull AuditService audit,
                           @NotNull EventBus events,
                           @NotNull String issuer) {
        this.totp = totp;
        this.tokens = tokens;
        this.accounts = accounts;
        this.audit = audit;
        this.events = events;
        this.issuer = issuer;
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<TotpSetupDto>> beginSetup(
            long accountId, @NotNull String username, @NotNull AuthContext context) {

        return totp.findByAccount(accountId).thenCompose(existing -> {
            if (existing.isPresent() && existing.get().isActive()) {
                return completed(OperationResult.fail("TOTP_ALREADY_ENABLED",
                        "Второй фактор уже включён. Сначала отключите его."));
            }

            String secret = secretGenerator.generate();
            TotpSecret pending = TotpSecret.pending(accountId, secret);

            return totp.insert(pending)
                    .thenCompose(saved -> regenerateCodes(accountId)
                            .thenApply(codes -> buildSetup(saved, username, codes)));
        });
    }

    private OperationResult<TotpSetupDto> buildSetup(TotpSecret secret,
                                                     String username,
                                                     List<String> recoveryCodes) {
        String uri = secret.toUri(issuer, username);
        try {
            QrData data = new QrData.Builder()
                    .label(username)
                    .secret(secret.secret())
                    .issuer(issuer)
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(secret.digits())
                    .period(secret.periodSeconds())
                    .build();
            String png = Base64.getEncoder().encodeToString(qrGenerator.generate(data));
            return OperationResult.ok(new TotpSetupDto(secret.secret(), uri, png, recoveryCodes));
        } catch (Exception e) {
            // QR — удобство, а не необходимость: секрет можно ввести руками.
            // Отказывать в подключении второго фактора из-за картинки неправильно.
            LOGGER.warn("Не удалось построить QR-код для аккаунта {}", secret.accountId(), e);
            return OperationResult.ok(new TotpSetupDto(secret.secret(), uri, "", recoveryCodes));
        }
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> confirmSetup(long accountId,
                                                                          @NotNull String code,
                                                                          @NotNull AuthContext context) {
        return totp.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("TOTP_NOT_STARTED",
                        "Подключение не начато"));
            }
            TotpSecret secret = found.get();
            if (secret.isActive()) {
                return completed(OperationResult.<Void>fail("TOTP_ALREADY_ENABLED",
                        "Второй фактор уже включён"));
            }

            long counter = matchingCounter(secret, code);
            if (counter < 0) {
                return completed(OperationResult.<Void>fail("TOTP_INVALID_CODE", "Неверный код"));
            }

            return totp.enable(accountId, Instant.now(), counter)
                    .thenCompose(enabled -> enabled
                            ? addTwoFactorMethod(accountId, context)
                            : completed(OperationResult.<Void>fail("TOTP_ALREADY_ENABLED",
                                    "Второй фактор уже включён")));
        });
    }

    private CompletableFuture<OperationResult<Void>> addTwoFactorMethod(long accountId,
                                                                        AuthContext context) {
        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND", "Аккаунт не найден"));
            }
            Set<TwoFactorMethod> methods = EnumSet.noneOf(TwoFactorMethod.class);
            methods.addAll(found.get().twoFactorMethods());
            methods.add(TwoFactorMethod.TOTP);

            return accounts.updateTwoFactorMethods(accountId, methods)
                    .thenCompose(ignored -> events.publish(BindingChangedEvent.of(accountId,
                            BindingChangedEvent.BindingKind.TOTP,
                            BindingChangedEvent.Action.ENABLED, null, context)))
                    .thenCompose(ignored -> audit.log(accountId, SecurityEventType.TOTP_ENABLED,
                            context, "Включён второй фактор TOTP"))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> verify(long accountId, @NotNull String code) {
        String normalized = code.trim().replace(" ", "");

        return totp.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty() || !found.get().isActive()) {
                return CompletableFuture.completedFuture(false);
            }
            TotpSecret secret = found.get();

            // Резервный код содержит дефисы и длиннее обычного — по этому признаку
            // и различаем, не заставляя игрока выбирать режим ввода.
            if (normalized.length() > secret.digits()) {
                return consumeRecoveryCode(accountId, normalized);
            }

            long counter = matchingCounter(secret, normalized);
            if (counter < 0) {
                return CompletableFuture.completedFuture(false);
            }
            // Фиксация окна атомарна: тот же код повторно не пройдёт.
            return totp.compareAndSetCounter(accountId, counter);
        });
    }

    /**
     * Ищет деление времени, для которого код совпадает.
     *
     * @return номер деления или {@code -1}, если совпадения нет
     */
    private long matchingCounter(TotpSecret secret, String code) {
        long current = Instant.now().getEpochSecond() / secret.periodSeconds();
        for (long offset = -ALLOWED_DRIFT_PERIODS; offset <= ALLOWED_DRIFT_PERIODS; offset++) {
            long counter = current + offset;
            try {
                String expected = codeGenerator.generate(secret.secret(), counter);
                // Сравнение в постоянном времени: код короткий, и посимвольное
                // сравнение с ранним выходом теоретически измеримо.
                if (TokenGenerator.constantTimeEquals(expected, code)) {
                    return counter;
                }
            } catch (CodeGenerationException e) {
                LOGGER.error("Не удалось вычислить код TOTP для аккаунта {}", secret.accountId(), e);
                return -1;
            }
        }
        return -1;
    }

    /** Гасит резервный код. */
    private CompletableFuture<Boolean> consumeRecoveryCode(long accountId, String code) {
        return tokens.findByHash(TokenGenerator.hash(code)).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            AuthToken token = found.get();
            if (token.type() != TokenType.TOTP_RECOVERY
                    || !java.util.Objects.equals(token.accountId(), accountId)
                    || !token.isUsable(Instant.now())) {
                return CompletableFuture.completedFuture(false);
            }
            return tokens.markUsed(token.id(), Instant.now(), null).thenCompose(marked -> {
                if (!marked) {
                    return CompletableFuture.completedFuture(false);
                }
                // Использование резервного кода — всегда повод насторожиться:
                // обычно оно означает потерю телефона либо чужой доступ к листку.
                return audit.log(accountId, SecurityEventType.RECOVERY_CODE_USED,
                                AuthContext.system(), "Использован резервный код TOTP")
                        .thenApply(ignored -> true);
            });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> disable(long accountId,
                                                                      @NotNull String confirmation,
                                                                      @NotNull AuthContext context) {
        // Отключение 2FA — самая ценная для злоумышленника операция, поэтому
        // требует подтверждения кодом, а не только действующей сессии.
        return verify(accountId, confirmation).thenCompose(valid -> {
            if (!valid) {
                return completed(OperationResult.<Void>fail("TOTP_INVALID_CODE",
                        "Неверный код подтверждения"));
            }
            return totp.deleteByAccount(accountId)
                    .thenCompose(ignored -> tokens.revokeAllByAccountAndType(accountId,
                            TokenType.TOTP_RECOVERY, Instant.now()))
                    .thenCompose(ignored -> accounts.findById(accountId))
                    .thenCompose(found -> {
                        if (found.isEmpty()) {
                            return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND",
                                    "Аккаунт не найден"));
                        }
                        Set<TwoFactorMethod> methods = EnumSet.noneOf(TwoFactorMethod.class);
                        methods.addAll(found.get().twoFactorMethods());
                        methods.remove(TwoFactorMethod.TOTP);
                        return accounts.updateTwoFactorMethods(accountId, methods)
                                .thenCompose(x -> events.publish(BindingChangedEvent.of(accountId,
                                        BindingChangedEvent.BindingKind.TOTP,
                                        BindingChangedEvent.Action.DISABLED, null, context)))
                                .thenCompose(x -> audit.log(accountId,
                                        SecurityEventType.TOTP_DISABLED, context,
                                        "Второй фактор TOTP отключён"))
                                .thenApply(x -> OperationResult.<Void>ok());
                    });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<List<String>>> regenerateRecoveryCodes(
            long accountId, @NotNull AuthContext context) {
        return regenerateCodes(accountId)
                .thenCompose(codes -> audit.log(accountId,
                                SecurityEventType.RECOVERY_CODES_REGENERATED, context,
                                "Перевыпущены резервные коды")
                        .thenApply(ignored -> OperationResult.ok(codes)));
    }

    /** Гасит старые коды и выпускает новые. */
    private CompletableFuture<List<String>> regenerateCodes(long accountId) {
        return tokens.revokeAllByAccountAndType(accountId, TokenType.TOTP_RECOVERY, Instant.now())
                .thenCompose(ignored -> {
                    List<String> raw = new ArrayList<>(RECOVERY_CODE_COUNT);
                    List<CompletableFuture<?>> inserts = new ArrayList<>(RECOVERY_CODE_COUNT);
                    for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
                        String code = TokenGenerator.recoveryCode();
                        raw.add(code);
                        inserts.add(tokens.insert(AuthToken.issue(accountId,
                                TokenType.TOTP_RECOVERY, TokenGenerator.hash(code), null)));
                    }
                    return CompletableFuture.allOf(inserts.toArray(CompletableFuture[]::new))
                            .thenApply(x -> raw);
                });
    }

    @Override
    public @NotNull CompletableFuture<Integer> countRemainingRecoveryCodes(long accountId) {
        return tokens.countUsable(accountId, TokenType.TOTP_RECOVERY, Instant.now());
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isEnabled(long accountId) {
        return totp.findByAccount(accountId).thenApply(found -> found
                .map(TotpSecret::isActive)
                .orElse(false));
    }

    private static <T> CompletableFuture<OperationResult<T>> completed(OperationResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
