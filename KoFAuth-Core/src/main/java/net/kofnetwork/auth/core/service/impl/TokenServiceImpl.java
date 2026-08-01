package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.SessionRepository;
import net.kofnetwork.auth.api.repository.TokenRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.core.security.JwtProvider;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link TokenService}.
 *
 * <p><b>Ротация refresh с обнаружением повтора.</b> При каждом обновлении старый
 * токен гасится, а новый ссылается на него через {@code parent_token_id}. Если
 * предъявлен уже погашенный токен, значит либо клиент повторил запрос, либо токен
 * утёк и им воспользовались дважды. Различить эти случаи невозможно, поэтому
 * отзывается вся цепочка — стандартное поведение из OAuth 2.0 Security BCP.
 */
public final class TokenServiceImpl implements TokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenServiceImpl.class);

    private final TokenRepository tokens;
    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final JwtProvider jwt;
    private final ConfigurationService config;
    private final EventBus events;

    public TokenServiceImpl(@NotNull TokenRepository tokens,
                            @NotNull SessionRepository sessions,
                            @NotNull AccountRepository accounts,
                            @NotNull JwtProvider jwt,
                            @NotNull ConfigurationService config,
                            @NotNull EventBus events) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.accounts = accounts;
        this.jwt = jwt;
        this.config = config;
        this.events = events;
    }

    @Override
    public @NotNull CompletableFuture<IssuedToken> issue(@Nullable Long accountId,
                                                          @NotNull TokenType type,
                                                          @Nullable IpAddress ip,
                                                          @Nullable Duration customLifetime) {
        // Сырое значение существует только здесь и в ответе вызывающему.
        // В базу уходит его SHA-256.
        String raw = switch (type) {
            case TOTP_RECOVERY -> TokenGenerator.recoveryCode();
            case TELEGRAM_LINK, DISCORD_LINK -> TokenGenerator.humanReadableCode(
                    config.getInt(ConfigFile.TELEGRAM, "link.code-length", 8));
            case EMAIL_VERIFY, PASSWORD_RESET -> TokenGenerator.numericCode(
                    config.getInt(ConfigFile.MAIL, "verification.code-length", 6));
            default -> TokenGenerator.randomToken();
        };

        AuthToken token = AuthToken.issue(accountId, type, TokenGenerator.hash(raw), ip);
        if (customLifetime != null) {
            token = token.withExpiry(token.issuedAt().plus(customLifetime));
        }
        return tokens.insert(token).thenApply(saved -> new IssuedToken(raw, saved));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<AuthToken>> consume(@NotNull String rawValue,
                                                                           @NotNull TokenType expectedType,
                                                                           @Nullable IpAddress ip) {
        return tokens.findByHash(TokenGenerator.hash(rawValue)).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.fail("TOKEN_NOT_FOUND", "Токен не найден"));
            }
            AuthToken token = found.get();
            if (token.type() != expectedType) {
                // Токен одного назначения не должен работать в другом: код привязки
                // Telegram не может сбросить пароль.
                return completed(OperationResult.fail("TOKEN_WRONG_TYPE",
                        "Токен предназначен для другой операции"));
            }
            if (token.revoked()) {
                return completed(OperationResult.fail("TOKEN_REVOKED", "Токен отозван"));
            }
            if (token.isExpired(Instant.now())) {
                return completed(OperationResult.fail("TOKEN_EXPIRED", "Срок действия истёк"));
            }
            if (token.isReplay()) {
                return handleReplay(token);
            }
            // Пометка атомарна: два параллельных запроса с одним кодом не пройдут оба.
            return tokens.markUsed(token.id(), Instant.now(), ip).thenApply(marked -> marked
                    ? OperationResult.ok(token)
                    : OperationResult.fail("TOKEN_ALREADY_USED", "Токен уже использован"));
        });
    }

    /** Повторное использование одноразового токена — сигнал об утечке. */
    private CompletableFuture<OperationResult<AuthToken>> handleReplay(AuthToken token) {
        LOGGER.warn("Повторное использование токена типа {} (аккаунт {})",
                token.type(), token.accountId());

        CompletableFuture<?> reaction = token.type().isRotating()
                ? tokens.revokeChain(token.id(), Instant.now())
                : CompletableFuture.completedFuture(0);

        return reaction
                .thenCompose(ignored -> events.publish(SuspiciousActivityEvent.of(
                        token.accountId(), SecurityEventType.TOKEN_REPLAY_DETECTED,
                        AuthContext.system(),
                        "Повторное предъявление токена " + token.type())))
                .thenApply(ignored -> OperationResult.fail("TOKEN_REPLAY",
                        "Токен уже был использован"));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<AuthToken>> peek(@NotNull String rawValue,
                                                                        @NotNull TokenType expectedType) {
        return tokens.findByHash(TokenGenerator.hash(rawValue)).thenApply(found -> {
            if (found.isEmpty() || found.get().type() != expectedType) {
                return OperationResult.fail("TOKEN_NOT_FOUND", "Токен не найден");
            }
            AuthToken token = found.get();
            return token.isUsable(Instant.now())
                    ? OperationResult.ok(token)
                    : OperationResult.fail("TOKEN_UNUSABLE", "Токен недействителен");
        });
    }

    // ================================================================ JWT

    @Override
    public @NotNull CompletableFuture<TokenPair> issueTokenPair(long accountId,
                                                                 long sessionId,
                                                                 @Nullable IpAddress ip) {
        return sessions.findById(sessionId).thenCompose(found -> {
            String publicId = found.map(Session::publicId).orElse(String.valueOf(sessionId));
            return accounts.findById(accountId).thenCompose(account -> {
                String username = account.map(a -> a.username()).orElse("unknown");
                String access = jwt.issueAccessToken(accountId, username, publicId);

                return issue(accountId, TokenType.REFRESH, ip, refreshLifetime())
                        .thenCompose(issued -> tokens
                                .insert(issued.token().withSessionId(sessionId))
                                .thenApply(ignored -> new TokenPair(
                                        access,
                                        issued.value(),
                                        jwt.accessLifetime(),
                                        refreshLifetime())));
            });
        });
    }

    private Duration refreshLifetime() {
        return config.getDuration(ConfigFile.SECURITY, "jwt.refresh-lifetime", Duration.ofDays(30));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<TokenPair>> refresh(@NotNull String refreshToken,
                                                                           @Nullable IpAddress ip) {
        return tokens.findByHash(TokenGenerator.hash(refreshToken)).thenCompose(found -> {
            if (found.isEmpty() || found.get().type() != TokenType.REFRESH) {
                return completed(OperationResult.fail("TOKEN_NOT_FOUND", "Refresh-токен не найден"));
            }
            AuthToken token = found.get();

            if (token.isReplay()) {
                return handleReplay(token).thenApply(OperationResult::propagateFailure);
            }
            if (!token.isUsable(Instant.now())) {
                return completed(OperationResult.fail("TOKEN_UNUSABLE",
                        "Refresh-токен недействителен"));
            }
            Long accountId = token.accountId();
            if (accountId == null) {
                return completed(OperationResult.fail("TOKEN_INVALID",
                        "Токен не связан с аккаунтом"));
            }

            return tokens.markUsed(token.id(), Instant.now(), ip).thenCompose(marked -> {
                if (!marked) {
                    return completed(OperationResult.<TokenPair>fail("TOKEN_ALREADY_USED",
                            "Refresh-токен уже использован"));
                }
                return rotate(token, accountId, ip);
            });
        });
    }

    /** Выпускает новую пару, связывая refresh с предыдущим звеном цепочки. */
    private CompletableFuture<OperationResult<TokenPair>> rotate(AuthToken previous,
                                                                  long accountId,
                                                                  @Nullable IpAddress ip) {
        String raw = TokenGenerator.randomToken();
        AuthToken next = AuthToken.issue(accountId, TokenType.REFRESH, TokenGenerator.hash(raw), ip)
                .withParent(previous.id())
                .withSessionId(previous.sessionId())
                .withExpiry(Instant.now().plus(refreshLifetime()));

        return tokens.insert(next).thenCompose(saved -> accounts.findById(accountId)
                .thenCompose(account -> {
                    if (account.isEmpty()) {
                        return completed(OperationResult.<TokenPair>fail("ACCOUNT_NOT_FOUND",
                                "Аккаунт не найден"));
                    }
                    Long sessionId = previous.sessionId();
                    CompletableFuture<String> publicId = sessionId == null
                            ? CompletableFuture.completedFuture(String.valueOf(accountId))
                            : sessions.findById(sessionId)
                                    .thenApply(s -> s.map(Session::publicId)
                                            .orElse(String.valueOf(sessionId)));

                    return publicId.thenApply(id -> OperationResult.ok(new TokenPair(
                            jwt.issueAccessToken(accountId, account.get().username(), id),
                            raw,
                            jwt.accessLifetime(),
                            refreshLifetime())));
                }));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<AccessClaims>> verifyAccessToken(
            @NotNull String accessToken) {
        // Проверка подписи не требует обращения к хранилищу и отсекает подделки
        // до того, как они создадут нагрузку. Живость сессии сверяет вызывающий
        // по sessionPublicId из полезной нагрузки.
        return CompletableFuture.completedFuture(jwt.verify(accessToken));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> revokeRefreshToken(
            @NotNull String refreshToken) {
        return tokens.findByHash(TokenGenerator.hash(refreshToken)).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("TOKEN_NOT_FOUND", "Токен не найден"));
            }
            AuthToken token = found.get();
            return tokens.revokeChain(token.id(), Instant.now())
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeRefreshTokensOf(long accountId) {
        return revokeAllByType(accountId, TokenType.REFRESH);
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeAllByType(long accountId,
                                                                @NotNull TokenType type) {
        return tokens.revokeAllByAccountAndType(accountId, type, Instant.now());
    }

    private static <T> CompletableFuture<OperationResult<T>> completed(OperationResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
