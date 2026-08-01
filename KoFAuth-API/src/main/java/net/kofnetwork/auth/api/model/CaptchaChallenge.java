package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Выданный CAPTCHA-челлендж. Соответствует строке {@code captcha}.
 *
 * <p>Ожидаемый ответ хранится только как SHA-256 ({@link #expectedAnswerHash()}).
 * Держать ответ в открытом виде незачем: проверка сводится к сравнению хэшей, а
 * дамп базы в этом случае не даёт готовых ответов на активные челленджи.
 *
 * <p>{@link #accountId()} равен {@code null}, когда капча выдана до регистрации —
 * тогда игрок опознаётся по {@link #playerUuid()}.
 */
public record CaptchaChallenge(
        long id,
        @Nullable Long accountId,
        @Nullable UUID playerUuid,
        @NotNull String challengeId,
        @NotNull CaptchaType type,
        @NotNull String expectedAnswerHash,
        int attempts,
        int maxAttempts,
        @NotNull IpAddress ip,
        @NotNull CaptchaStatus status,
        @NotNull Instant issuedAt,
        @NotNull Instant expiresAt,
        @Nullable Instant resolvedAt
) {

    public CaptchaChallenge {
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expectedAnswerHash, "expectedAnswerHash");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts должен быть не меньше 1, получено " + maxAttempts);
        }
    }

    /** Выдаёт новый челлендж. */
    public static @NotNull CaptchaChallenge issue(@Nullable Long accountId,
                                                  @Nullable UUID playerUuid,
                                                  @NotNull CaptchaType type,
                                                  @NotNull String expectedAnswerHash,
                                                  @NotNull IpAddress ip,
                                                  int maxAttempts,
                                                  @NotNull Duration ttl) {
        Instant now = Instant.now();
        return new CaptchaChallenge(0L, accountId, playerUuid, UUID.randomUUID().toString(),
                type, expectedAnswerHash, 0, maxAttempts, ip, CaptchaStatus.PENDING,
                now, now.plus(ttl), null);
    }

    /** Учитывает неверную попытку; при исчерпании лимита переводит челлендж в {@code FAILED}. */
    public @NotNull CaptchaChallenge failAttempt(@NotNull Instant at) {
        int next = attempts + 1;
        boolean exhausted = next >= maxAttempts;
        return new CaptchaChallenge(id, accountId, playerUuid, challengeId, type,
                expectedAnswerHash, next, maxAttempts, ip,
                exhausted ? CaptchaStatus.FAILED : CaptchaStatus.PENDING,
                issuedAt, expiresAt, exhausted ? at : null);
    }

    /** Отмечает челлендж пройденным. */
    public @NotNull CaptchaChallenge pass(@NotNull Instant at) {
        return new CaptchaChallenge(id, accountId, playerUuid, challengeId, type,
                expectedAnswerHash, attempts, maxAttempts, ip, CaptchaStatus.PASSED,
                issuedAt, expiresAt, at);
    }

    /** Отмечает челлендж просроченным. */
    public @NotNull CaptchaChallenge expire(@NotNull Instant at) {
        return new CaptchaChallenge(id, accountId, playerUuid, challengeId, type,
                expectedAnswerHash, attempts, maxAttempts, ip, CaptchaStatus.EXPIRED,
                issuedAt, expiresAt, at);
    }

    public @NotNull CaptchaChallenge withId(long newId) {
        return new CaptchaChallenge(newId, accountId, playerUuid, challengeId, type,
                expectedAnswerHash, attempts, maxAttempts, ip, status, issuedAt, expiresAt, resolvedAt);
    }

    /** Принимает ли челлендж ответы прямо сейчас. */
    public boolean isAnswerable(@NotNull Instant at) {
        return status == CaptchaStatus.PENDING
                && expiresAt.isAfter(at)
                && attempts < maxAttempts;
    }

    /** Сколько попыток осталось. */
    public int remainingAttempts() {
        return Math.max(0, maxAttempts - attempts);
    }
}
