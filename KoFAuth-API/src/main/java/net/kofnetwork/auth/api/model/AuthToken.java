package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Токен из таблицы {@code tokens}: refresh, код подтверждения, ссылка привязки,
 * резервный код TOTP или ключ API.
 *
 * <p><b>Здесь нет самого токена.</b> Только {@link #tokenHash()} — SHA-256 от значения.
 * Сырое значение существует ровно один раз: в момент выпуска оно возвращается вызывающему
 * коду и уходит игроку. После этого предъявленный токен можно только захэшировать и
 * сравнить. Утечка дампа базы не даёт возможности предъявить ни один токен.
 *
 * <p><b>Ротация refresh.</b> {@link #parentTokenId()} связывает звенья цепочки. Если
 * предъявлен токен, у которого {@code used == true}, значит либо клиент повторил
 * запрос, либо токен утёк и им воспользовались дважды. Различить эти случаи нельзя,
 * поэтому вся цепочка отзывается — стандартное поведение при detection утечки
 * (см. OAuth 2.0 Security BCP, «refresh token replay detection»).
 */
public record AuthToken(
        long id,
        @Nullable Long accountId,
        @Nullable Long sessionId,
        @NotNull String tokenHash,
        @NotNull TokenType type,
        @Nullable Long parentTokenId,
        @Nullable IpAddress issuedIp,
        @NotNull Instant issuedAt,
        @NotNull Instant expiresAt,
        boolean used,
        @Nullable Instant usedAt,
        @Nullable IpAddress usedIp,
        boolean revoked,
        @Nullable Instant revokedAt,
        @NotNull Map<String, Object> metadata
) {

    public AuthToken {
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (tokenHash.length() != 64) {
            throw new IllegalArgumentException(
                    "tokenHash должен быть SHA-256 в hex (64 символа), получено " + tokenHash.length());
        }
        metadata = metadata == null || metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Создаёт токен со сроком жизни по умолчанию для его типа.
     *
     * @param tokenHash SHA-256 от сырого значения; само значение сюда не передаётся
     */
    public static @NotNull AuthToken issue(@Nullable Long accountId,
                                           @NotNull TokenType type,
                                           @NotNull String tokenHash,
                                           @Nullable IpAddress issuedIp) {
        Instant now = Instant.now();
        return new AuthToken(0L, accountId, null, tokenHash, type, null, issuedIp,
                now, now.plus(type.defaultLifetime()), false, null, null,
                false, null, Map.of());
    }

    /** Отмечает токен использованным. */
    public @NotNull AuthToken markUsed(@NotNull Instant at, @Nullable IpAddress ip) {
        return new AuthToken(id, accountId, sessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, expiresAt, true, at, ip, revoked, revokedAt, metadata);
    }

    /** Отзывает токен. */
    public @NotNull AuthToken revoke(@NotNull Instant at) {
        if (revoked) {
            return this;
        }
        return new AuthToken(id, accountId, sessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, expiresAt, used, usedAt, usedIp, true, at, metadata);
    }

    public @NotNull AuthToken withId(long newId) {
        return new AuthToken(newId, accountId, sessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, expiresAt, used, usedAt, usedIp, revoked, revokedAt, metadata);
    }

    public @NotNull AuthToken withSessionId(@Nullable Long newSessionId) {
        return new AuthToken(id, accountId, newSessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, expiresAt, used, usedAt, usedIp, revoked, revokedAt, metadata);
    }

    public @NotNull AuthToken withParent(@Nullable Long newParentTokenId) {
        return new AuthToken(id, accountId, sessionId, tokenHash, type, newParentTokenId, issuedIp,
                issuedAt, expiresAt, used, usedAt, usedIp, revoked, revokedAt, metadata);
    }

    public @NotNull AuthToken withMetadata(@NotNull Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(extra);
        return new AuthToken(id, accountId, sessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, expiresAt, used, usedAt, usedIp, revoked, revokedAt, merged);
    }

    public @NotNull AuthToken withExpiry(@NotNull Instant newExpiresAt) {
        return new AuthToken(id, accountId, sessionId, tokenHash, type, parentTokenId, issuedIp,
                issuedAt, newExpiresAt, used, usedAt, usedIp, revoked, revokedAt, metadata);
    }

    /** Можно ли предъявить токен прямо сейчас. */
    public boolean isUsable(@NotNull Instant at) {
        if (revoked || !expiresAt.isAfter(at)) {
            return false;
        }
        return !type.isSingleUse() || !used;
    }

    public boolean isExpired(@NotNull Instant at) {
        return !expiresAt.isAfter(at);
    }

    /**
     * Признак повторного использования одноразового токена — сигнал об утечке.
     *
     * @see TokenType#isRotating()
     */
    public boolean isReplay() {
        return type.isSingleUse() && used;
    }

    /** Хэш укорочен: полное значение в логе позволило бы сопоставить его с базой. */
    @Override
    public String toString() {
        return "AuthToken{id=" + id
                + ", accountId=" + accountId
                + ", type=" + type
                + ", used=" + used
                + ", revoked=" + revoked
                + ", expiresAt=" + expiresAt
                + ", tokenHash=" + tokenHash.substring(0, 8) + "...}";
    }
}
